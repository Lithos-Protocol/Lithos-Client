package transactions.engine.execution

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import configs.NodeContext
import mutations.NodeWallet
import node.MutationConversions._
import node.NodeApi
import node.model.{MempoolOptions, NodeBox}
import org.ergoplatform.appkit.{BlockchainContext, NetworkType, UnsignedTransaction}
import transactions.batching.arb.ArbStake
import transactions.engine.EngineBroadcast
import transactions.engine.wallet.{EngineFunding, EngineWalletMessages, EngineWalletState}
import work.lithos.mutations.{Contract, Eip27Adjustment, InputUTXO, MainnetEip27Constants, Token, TxBuilder, UTXO}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

object ArbReserveExecution {

  /**
   * What one reserve attempt concluded. `walletValue` is the wallet's spendable nanoERG when it was read, and
   * `stake` the box size the attempt aimed for.
   */
  final case class Status(outcome: String, walletValue: Long, stake: Long, txId: Option[String] = None)

  /** A confirmed box of at least the stake is at the arb address, so nothing is sent and nothing more is due. */
  final val OutcomeHeld = "held"
  /** The wallet's spendable ERG is under twice the stake. */
  final val OutcomeShort = "short"
  /** A box of at least the stake is in the mempool on its way there, so nothing is sent until it is seen again. */
  final val OutcomePending = "pending"
  /** The stake was reclaimed in this process, so none is sent until a restart. */
  final val OutcomePaused = "paused"

  /** What a reclaim sent: `boxes` holding `nanoErgs` back to the first address, with `left` boxes still there. */
  final case class Reclaimed(txId: String, boxes: Int, nanoErgs: Long, outcome: String, reason: Option[String],
                             left: Int)

  /** Fee on the reserve and on the reclaim. */
  final val Fee: Long = EngineWalletState.MaintenanceFee

  /** How much of the wallet must stay outside the stake: as much again as the stake itself. */
  final val ReserveMultiple: Int = 2

  /** ERG selected beyond the stake and fee, so the reserve's change always clears a box's minimum. */
  final val ChangeFloor: Long = 10L * UTXO.MIN_CHANGE

  final val RequestTimeout: FiniteDuration = 40.seconds

  /** Whether `boxes` hold a stake of full size: ERG only, at `tree`, of at least `stake`. */
  def funded(boxes: Seq[NodeBox], tree: String, stake: Long): Boolean =
    boxes.exists(box => ArbStake.usable(box, tree) && box.value >= stake)

  /** The reserve: `inputs` to one box of exactly `stake` at `stakeTo`, the fee, and the change to the first address. */
  def reserveTx(ctx: BlockchainContext, wallet: NodeWallet, inputs: Seq[InputUTXO], stakeTo: Contract, stake: Long,
                fee: Long): UnsignedTransaction = {
    val total = inputs.foldLeft(0L)((sum, box) => Math.addExact(sum, box.value))
    require(stake > 0L && total - stake - fee >= UTXO.MIN_CHANGE,
      s"an arb reserve of $stake nanoERG needs inputs covering it, the fee and a change box, got $total")
    TxBuilder(ctx).setInputs(inputs: _*).setOutputs(UTXO(stakeTo, stake)).buildTx(fee, wallet.p2pk)
  }

  /**
   * The reclaim: every one of `inputs`, tokens included, to one box at the first address, less the fee. On mainnet
   * re-emission tokens are left out of that box and their nanoERG withheld, since the builder burns them and
   * pays that amount to the re-emission contract.
   */
  def reclaimTx(ctx: BlockchainContext, wallet: NodeWallet, inputs: Seq[InputUTXO], fee: Long,
                network: NetworkType): UnsignedTransaction = {
    val gross = inputs.foldLeft(0L)((sum, box) => Math.addExact(sum, box.value))
    val obligation = Eip27Adjustment.obligation(inputs, network)
    val payout = Math.subtractExact(Math.subtractExact(gross, obligation), fee)
    require(inputs.nonEmpty && payout >= UTXO.MIN_CHANGE, s"$gross nanoERG at the arb address cannot pay the $fee fee")
    val tokens = inputs.flatMap(_.tokens)
      .filterNot(token => obligation > 0L && token.id.toString == MainnetEip27Constants.TokenId)
      .groupBy(_.id).toSeq.map { case (id, held) =>
        Token(id, held.foldLeft(0L)((sum, token) => Math.addExact(sum, token.amount)))
      }
    TxBuilder(ctx).setInputs(inputs: _*)
      .setOutputs(UTXO(wallet.contract, payout, tokens), UTXO.feeBox(fee))
      .buildTx(0, wallet.p2pk)
  }
}

/**
 * Moves the arb stake between the wallet and the arb address. The reserve sends one box of the stake's size
 * there, funded by an ordinary wallet selection, when the address holds none; the reclaim sends everything at
 * the address back to the first address. Both keep the ERG under this wallet's own key: only the fees leave.
 */
private[engine] class ArbReserveExecution(node: NodeContext, api: NodeApi, owner: ActorRef, stakes: ArbStake,
                          limits: configs.WalletConfig = configs.WalletConfig.Default,
                          requestTimeout: FiniteDuration = ArbReserveExecution.RequestTimeout)
                         (implicit ec: ExecutionContext) {
  import ArbReserveExecution._

  private val logger: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger("ArbReserveExecution")
  private implicit val timeout: Timeout = Timeout(requestTimeout)

  /**
   * Sends the stake to the arb address unless one of full size is there, known by id or on its way, the stake
   * was reclaimed, or the wallet holds under twice the stake. A failed read throws, so nothing is sent on a
   * guess.
   */
  def execute(alive: () => Boolean): Status = {
    val stake = stakes.target
    require(stake > 0L, "arb is not active")
    if (stakes.paused) Status(OutcomePaused, 0L, stake)
    else if (stakes.known().get.exists(_.value >= stake) || funded(stakes.boxes().get, stakes.tree, stake))
      Status(OutcomeHeld, 0L, stake)
    else if (funded(stakes.boxes(MempoolOptions.WithMempool).get, stakes.tree, stake)) Status(OutcomePending, 0L, stake)
    else {
      val spendable = Await.result((owner ? EngineWalletMessages.GetSpendableBalance)
        .mapTo[EngineWalletMessages.SpendableBalance], requestTimeout).nanoErgs
      if (BigInt(spendable) < BigInt(stake) * ReserveMultiple) Status(OutcomeShort, spendable, stake)
      else {
        require(alive(), "arb reserve attempt expired")
        val wallet = node.getNodeWallet
        val allocation = EngineFunding(owner, limits.reservationTimeoutMs.milliseconds, ec)
          .reserve(Math.addExact(Math.addExact(stake, Fee), ChangeFloor), Seq.empty)
        // Released however the send ends; a broadcast input stays owned until reconciliation resolves it
        try node.getClient.execute { ctx =>
          val unsigned = reserveTx(ctx, wallet, allocation.inputs, stakes.contract, stake, Fee)
          require(alive() && !stakes.paused, "arb reserve attempt expired")
          val result = new EngineBroadcast(owner, api, requestTimeout)
            .send(wallet.sign(unsigned), Seq(allocation), "arb-reserve", alive)
          logger.info(s"Sent transaction ${result.txId} moving $stake nanoERG from ${allocation.inputs.size} wallet " +
            s"box(es) to the arb address ${stakes.address}: ${result.outcome}")
          Status(result.outcome, spendable, stake, Some(result.txId))
        } finally allocation.release()
      }
    }
  }

  /**
   * Stops arb for the rest of this process and sends the confirmed boxes at the arb address, largest first up
   * to `wallet.max-inputs`, back to the first address. None when the address holds nothing confirmed, which
   * leaves arb running. A block of this miner's found meanwhile can spend the stake first; the node then refuses
   * this transaction, and the stake's successor is still at the address for another reclaim.
   */
  def reclaim(alive: () => Boolean): Option[Reclaimed] = {
    val wasPaused = stakes.paused
    stakes.pause()
    val wallet = node.getNodeWallet
    val sent = try node.getClient.execute { ctx =>
      val there = stakes.boxes().get.sortBy(-_.value)
      val taken = there.take(math.min(limits.maxInputs, EngineBroadcast.MaxSignedInputs))
      if (taken.isEmpty) None
      else {
        val inputs = taken.map(_.toInputUTXO(ctx))
        require(inputs.map(_.id.toString) == taken.map(_.boxId) &&
          inputs.forall(_.contract.ergoTreeHex == stakes.tree), "a box at the arb address did not read back as itself")
        val unsigned = reclaimTx(ctx, wallet, inputs, Fee, node.getNetwork)
        require(alive(), "arb reclaim expired")
        val result = new EngineBroadcast(owner, api, requestTimeout)
          .send(wallet.sign(unsigned), Seq.empty, "arb-reclaim", alive)
        val value = inputs.foldLeft(0L)((sum, box) => Math.addExact(sum, box.value))
        logger.info(s"Sent transaction ${result.txId} returning ${inputs.size} box(es) holding $value nanoERG from " +
          s"the arb address ${stakes.address} to the wallet: ${result.outcome}. Arb is off until this client restarts")
        Some(Reclaimed(result.txId, inputs.size, value, result.outcome, result.reason, there.size - taken.size))
      }
    } catch {
      // Nothing was sent, so a stake that was running keeps running
      case scala.util.control.NonFatal(ex) =>
        if (!wasPaused) stakes.resume()
        throw ex
    }
    if (sent.isEmpty && !wasPaused) stakes.resume()
    sent
  }
}
