package transactions.engine.execution

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import configs.NodeContext
import mutations.NodeWallet
import node.MutationConversions._
import node.NodeApi
import node.model.NodeBox
import org.ergoplatform.appkit.{BlockchainContext, UnsignedTransaction}
import transactions.engine.EngineBroadcast
import transactions.engine.wallet.{EngineFunding, EngineWalletMessages, EngineWalletState, WalletInventory}
import work.lithos.mutations.{TxBuilder, UTXO}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

object ArbReserveExecution {

  /**
   * What one attempt concluded. `plainValue` is the confirmed plain ERG it could merge, `stake` the box size it
   * aimed for, and `outputs` the boxes the sent transaction creates besides its fee.
   */
  final case class Status(outcome: String, plainValue: BigInt, stake: Long, inputs: Int = 0,
                          outputs: Seq[Long] = Seq.empty, txId: Option[String] = None)

  /** The wallet can already hold a stake once its boxes confirm, so nothing is sent. */
  final val OutcomeHeld = "held"
  /** Confirmed plain boxes hold less than twice the stake. */
  final val OutcomeShort = "short"
  /** Enough value, but no merge within the input cap leaves a stake the wallet would take. */
  final val OutcomeUnreachable = "unreachable"

  /** Fee on the merge transaction. */
  final val Fee: Long = EngineWalletState.MaintenanceFee

  /** A box added beside the stake when the fraud-proof pins would otherwise leave the stake no second box. */
  final val SpareValue: Long = SeedExecution.MinBoxValue

  /** How much of the wallet must stay outside the stake: as much again as the stake itself. */
  final val ReserveMultiple: Int = 2

  final val RequestTimeout: FiniteDuration = 40.seconds

  /** A plain wallet box as the wallet's stake rule sees it, and whether it is in a block. */
  final case class Plain(id: String, value: Long, onchain: Boolean)

  sealed trait Plan
  case object Held extends Plan
  final case class Short(plainValue: BigInt) extends Plan
  final case class Unreachable(plainValue: BigInt) extends Plan
  final case class Merge(inputs: Vector[Plain], outputs: Vector[Long], plainValue: BigInt) extends Plan

  /**
   * The wallet's stake rule on box values: the pins take the `missingPins` smallest, and of the rest the stake
   * needs one of at least `stake` while another stays free.
   */
  def stakeable(values: Seq[Long], missingPins: Int, stake: Long): Boolean = {
    val free = values.sorted.drop(missingPins)
    free.size >= 2 && free.exists(_ >= stake)
  }

  /**
   * What to send so the wallet can hold a stake of `stake`. Held when the boxes, unconfirmed ones included, already
   * allow one. Otherwise, with the fewest spare boxes that satisfy the pins, the largest confirmed boxes, up to
   * `maxInputs`, are merged into one box of exactly `stake`, the spares and the change; change below a box's
   * minimum joins the stake box. Only when confirmed plain boxes hold at least twice the stake.
   */
  def plan(boxes: Seq[Plain], missingPins: Int, stake: Long, fee: Long, maxInputs: Int): Plan =
    if (stakeable(boxes.map(_.value), missingPins, stake)) Held
    else {
      val confirmed = boxes.filter(_.onchain)
      val total = confirmed.map(b => BigInt(b.value)).sum
      val largest = confirmed.sortBy(-_.value).take(maxInputs)
      def merge(spares: Int): Option[Merge] = {
        val needed = BigInt(stake) + fee + BigInt(spares) * SpareValue
        val inputs = largest.foldLeft(Vector.empty[Plain]) { (taken, box) =>
          if (taken.map(b => BigInt(b.value)).sum >= needed) taken else taken :+ box
        }
        val change = inputs.map(b => BigInt(b.value)).sum - needed
        val outputs =
          if (change >= UTXO.MIN_CHANGE) Vector(stake) ++ Vector.fill(spares)(SpareValue) :+ change.toLong
          else Vector(stake + change.toLong) ++ Vector.fill(spares)(SpareValue)
        val untouched = confirmed.filterNot(b => inputs.exists(_.id == b.id)).map(_.value)
        Some(Merge(inputs, outputs, total)).filter(_ => change >= 0 && stakeable(untouched ++ outputs, missingPins, stake))
      }
      if (total < BigInt(stake) * ReserveMultiple) Short(total)
      else (0 to missingPins + 2).iterator.map(merge).collectFirst { case Some(m) => m: Plan }
        .getOrElse(Unreachable(total))
    }

  /** The merge: `inputs` to `outputs` at the first address, and the fee. */
  def build(ctx: BlockchainContext, wallet: NodeWallet, inputs: Seq[NodeBox], outputs: Seq[Long],
            fee: Long): UnsignedTransaction = {
    val spent = inputs.map(_.toInputUTXO(ctx))
    require(spent.forall(_.tokens.isEmpty) && spent.map(_.value).sum == outputs.sum + fee,
      "an arb reserve must spend plain boxes to exactly its outputs and fee")
    TxBuilder(ctx).setInputs(spent: _*)
      .setOutputs((outputs.map(UTXO(wallet.contract, _)) :+ UTXO.feeBox(fee)): _*)
      .buildTx(0, wallet.p2pk)
  }
}

/**
 * Merges plain wallet boxes into one the arb stake can be held from, for a wallet that has the ERG but not a box
 * of the stake's size. The wallet takes the new box as the stake once it confirms. Every output stays at the
 * first address, so the only ERG that leaves the wallet is the fee.
 */
private[engine] class ArbReserveExecution(node: NodeContext, api: NodeApi, owner: ActorRef, stake: Long,
                                          limits: configs.WalletConfig = configs.WalletConfig.Default,
                                          requestTimeout: FiniteDuration = ArbReserveExecution.RequestTimeout)
                                         (implicit ec: ExecutionContext) {
  import ArbReserveExecution._

  private val logger: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger("ArbReserveExecution")
  private implicit val timeout: Timeout = Timeout(requestTimeout)

  /** @param missingPins the pins' missing share when the attempt started */
  def execute(missingPins: Int, alive: () => Boolean): Status = {
    val owned = Await.result((owner ? EngineWalletMessages.GetOwnedInputIds).mapTo[Set[String]], requestTimeout)
    val wallet = node.getNodeWallet
    node.getClient.execute { ctx =>
      var found = Vector.empty[(Plain, NodeBox)]
      WalletInventory.walk(api, wallet.signableTrees, wallet.rewardTrees.keySet, limits, rewardsOnly = false,
        p2pkOnly = true) { (box, reward, onchain) =>
        if (!reward && box.assets.isEmpty && !owned.contains(box.boxId) &&
          box.value >= EngineWalletState.PinnedInputMinValue && (!onchain || box.creationHeight <= ctx.getHeight))
          found :+= Plain(box.boxId, box.value, onchain) -> box
        false
      }
      plan(found.map(_._1), missingPins, stake, Fee, limits.maxInputs) match {
        case Held => Status(OutcomeHeld, found.filter(_._1.onchain).map(f => BigInt(f._1.value)).sum, stake)
        case Short(value) => Status(OutcomeShort, value, stake)
        case Unreachable(value) => Status(OutcomeUnreachable, value, stake)
        case Merge(inputs, outputs, value) =>
          require(alive(), "arb reserve attempt expired")
          val boxes = inputs.map(p => found.find(_._1.id == p.id).get._2)
          val spent = boxes.map(_.toInputUTXO(ctx))
          require(spent.map(_.id.toString) == boxes.map(_.boxId) && spent.map(_.bytes.length).sum <= limits.maxInputBytes,
            "arb reserve input identity or byte budget is invalid")
          val allocation = EngineFunding(owner, limits.reservationTimeoutMs.milliseconds, ec).reserveKnown(spent)
          // Released however the send ends; a broadcast input stays owned until reconciliation resolves it
          try {
            val unsigned = build(ctx, wallet, boxes, outputs, Fee)
            require(alive(), "arb reserve attempt expired")
            val result = new EngineBroadcast(owner, api, requestTimeout)
              .send(wallet.sign(unsigned), Seq(allocation), "arb-reserve", alive)
            logger.info(s"Sent transaction ${result.txId} merging ${inputs.size} wallet box(es) into a " +
              s"$stake nanoERG box for the arb stake: ${result.outcome}")
            Status(result.outcome, value, stake, inputs.size, outputs, Some(result.txId))
          } finally allocation.release()
      }
    }
  }
}
