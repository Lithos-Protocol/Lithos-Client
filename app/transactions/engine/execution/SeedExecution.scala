package transactions.engine.execution

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import configs.NodeContext
import lfsm.RollupProtocol
import mutations.NodeWallet
import node.NodeApi
import node.MutationConversions._
import node.model.NodeBox
import org.ergoplatform.appkit.{BlockchainContext, Parameters, UnsignedTransaction}
import state.synchronization.CompleteMempool
import transactions.engine.EngineBroadcast
import transactions.engine.wallet.{EngineFunding, EngineWalletMessages, EngineWalletState, WalletInventory}
import transactions.rollups.{CommitmentSchedule, CommitmentTransactions, DataBoxSource}
import work.lithos.mutations.{InputUTXO, TxBuilder, UTXO}

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object SeedExecution {

  /**
   * What one seed attempt concluded. `usable` counts plain tokenless boxes that can pay a fee and a
   * change box; `created` is how many boxes of `boxValue` the sent transaction adds. `plainValue` is
   * what the wallet held in plain boxes before the seed, and `fullSeedValue` what reaching `target` needs.
   */
  final case class Status(outcome: String, usable: Int, target: Int, created: Int, boxValue: Long,
                          txId: Option[String] = None, plainValue: BigInt = 0, fullSeedValue: BigInt = 0)

  /** No confirmed data box yet: the registration is unconfirmed, or synchronization has not recorded it. */
  final val OutcomeWaiting = "waiting"
  /** The data box was not made by a registration inside the window, so this miner is not new. */
  final val OutcomeNotInitial = "not-initial"
  /** At the target already, or no box can be split without breaking the half-value bound. */
  final val OutcomeNoWork = "no-work"

  /** Whether an outcome ends seeding for this process. A wait or a refused send is tried again. */
  def settles(outcome: String): Boolean = outcome != OutcomeWaiting && outcome != EngineBroadcast.Rejected

  /** Blocks past the registration's declared commitment height in which a seed may still start, a day. */
  final val InitialWindow: Int = 720

  /** Least value one seed box holds, whatever the bond. */
  final val MinBoxValue: Long = 50000000L

  /** A box below this cannot pay a fee and leave a change box, so it is not counted as usable. */
  final val UsableValue: Long = Parameters.MinFee + UTXO.MIN_CHANGE

  /** Fee on the seed transaction. */
  final val Fee: Long = EngineWalletState.MaintenanceFee

  final val RequestTimeout: FiniteDuration = 40.seconds

  /** Seed box value for a miner committed at `score`: two NISP submissions' bond, fee and change. */
  def boxValue(score: Long): Long =
    math.max(MinBoxValue, Math.multiplyExact(2L, Math.addExact(RollupProtocol.bondForScore(score), UsableValue)))

  /** A data box made by a registration: one commitment, declared no more than the window ago. */
  def initial(schedule: CommitmentSchedule): Boolean =
    schedule.entries.size == 1 && schedule.height.toLong - schedule.entries.head._1 <= InitialWindow

  /**
   * The plain tokenless wallet as one seed sees it. `small` is the value in boxes no larger than a seed
   * box, and `largest` the biggest box this attempt may split: in a block, unowned and unclaimed.
   */
  final case class Scan(usable: Int, total: BigInt, small: BigInt, largest: Option[NodeBox])

  /** Walk the whole wallet once. Rewards and token boxes are neither counted nor split. */
  def scan(api: NodeApi, trees: Set[String], rewardTrees: Set[String], excluded: Set[String],
           height: Int, boxValue: Long, limits: configs.WalletConfig): Scan = {
    var usable = 0
    var total, small = BigInt(0)
    var largest = Option.empty[NodeBox]
    WalletInventory.walk(api, trees, rewardTrees, limits, rewardsOnly = false, p2pkOnly = false) {
      (box, _, onchain) =>
        if (box.assets.isEmpty) {
          total += box.value
          if (box.value <= boxValue) small += box.value
          if (box.value >= UsableValue) usable += 1
          if (onchain && !excluded.contains(box.boxId) && box.creationHeight <= height &&
            largest.forall(_.value < box.value)) largest = Some(box)
        }
        false
    }
    Scan(usable, total, small, largest)
  }

  /**
   * Seed boxes to split from the scan's largest box: enough to reach `target`, while boxes no larger
   * than a seed box hold at most half the plain value and the split box keeps a seed box's worth.
   * The half-value bound is what leaves a second attempt nothing to do after a partial seed.
   */
  def plan(scan: Scan, target: Int, boxValue: Long, fee: Long): Int = scan.largest match {
    case None => 0
    case Some(box) =>
      val wanted = BigInt(target - scan.usable)
      val budget = (scan.total / 2 - scan.small) / boxValue
      val fromBox = (BigInt(box.value) - fee) / boxValue - 1
      Seq(wanted, budget, fromBox).min.max(BigInt(0)).toInt
  }

  /** Plain value a seed to `target` needs under the half-value bound, when the extra arrives as one box. */
  def fullSeedValue(scan: Scan, target: Int, boxValue: Long): BigInt =
    (scan.small + BigInt(math.max(0, target - scan.usable)) * boxValue) * 2

  /**
   * The warning for an attempt that ran and left the wallet short of its target, naming the plain ERG a
   * full seed needs. None when it reached the target, is still due, or never looked at the wallet.
   */
  def shortfall(status: Status): Option[String] = {
    val reached = status.usable + status.created
    val ran = status.outcome == OutcomeNoWork || (status.txId.nonEmpty && settles(status.outcome))
    if (!ran || reached >= status.target) None
    else Some(s"Wallet seed ended at $reached of ${status.target} plain boxes. A full seed needs about " +
      s"${erg(status.fullSeedValue)} ERG in plain boxes and this wallet held ${erg(status.plainValue)} ERG" +
      (if (status.plainValue >= status.fullSeedValue) ", but no confirmed, unreserved box was large enough to split"
      else "") + ". ERG added now is seeded only if the client restarts within about a day of registering")
  }

  /** nanoERG as ERG to three places, rounded up so a stated requirement is never short. */
  private def erg(nanoErgs: BigInt): String =
    (BigDecimal(nanoErgs) / BigDecimal(Parameters.OneErg)).setScale(3, BigDecimal.RoundingMode.UP).toString

  /** `count` boxes of `boxValue`, then the split box's remainder, all to the first address, and the fee. */
  def build(ctx: BlockchainContext, wallet: NodeWallet, input: InputUTXO, count: Int,
            boxValue: Long, fee: Long): UnsignedTransaction = {
    val remainder = input.value - fee - Math.multiplyExact(count.toLong, boxValue)
    require(count > 0 && input.tokens.isEmpty && remainder >= boxValue,
      s"a seed of $count box(es) of $boxValue cannot be split from ${input.value} nanoERG")
    val outputs = Seq.fill(count)(UTXO(wallet.contract, boxValue)) :+ UTXO(wallet.contract, remainder) :+
      UTXO.feeBox(fee)
    TxBuilder(ctx).setInputs(input).setOutputs(outputs: _*).buildTx(0, wallet.p2pk)
  }
}

/**
 * Splits the wallet's largest plain box into seed boxes once this miner's registration confirms, so
 * NISP submissions, fraud proofs and optional work each have a box rather than chaining off one. It
 * runs only for a data box a registration made inside [[SeedExecution.InitialWindow]], which leaves
 * miners registered before then alone.
 */
private[engine] class SeedExecution(node: NodeContext, api: NodeApi, owner: ActorRef, dataBoxes: DataBoxSource,
                                    limits: configs.WalletConfig = configs.WalletConfig.Default,
                                    requestTimeout: FiniteDuration = SeedExecution.RequestTimeout)
                                   (implicit ec: ExecutionContext) {
  import SeedExecution._

  private val logger: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger("SeedExecution")
  private implicit val timeout: Timeout = Timeout(requestTimeout)

  /** This miner's confirmed commitment list. Overridable so a test can supply one. */
  protected def schedule(): Try[CommitmentSchedule] =
    new CommitmentTransactions(node, dataBoxes) {
      override protected def executionNode: NodeApi = api
    }.commitmentSchedule

  def execute(target: Int, alive: () => Boolean): Status = {
    // A local read, so an unregistered miner costs no node call per attempt
    if (dataBoxes.getDataBoxToken.isEmpty) Status(OutcomeWaiting, 0, target, 0, 0L)
    else schedule() match {
      case Failure(ex) =>
        logger.debug(s"Seed waiting for a confirmed data box: ${ex.getMessage}")
        Status(OutcomeWaiting, 0, target, 0, 0L)
      case Success(commitments) if !initial(commitments) => Status(OutcomeNotInitial, 0, target, 0, 0L)
      case Success(commitments) => seed(target, boxValue(commitments.entries.head._2), alive)
    }
  }

  private def seed(target: Int, value: Long, alive: () => Boolean): Status = {
    val observed = CompleteMempool.observe(owner, api, requestTimeout, "seed")
    val owned = Await.result((owner ? EngineWalletMessages.GetOwnedInputIds).mapTo[Set[String]], requestTimeout)
    val wallet = node.getNodeWallet
    node.getClient.execute { ctx =>
      val seen = scan(api, wallet.signableTrees, wallet.rewardTrees.keySet, owned ++ observed.snapshot.get.spent,
        ctx.getHeight, value, limits)
      val count = plan(seen, target, value, Fee)
      val needed = fullSeedValue(seen, target, value)
      if (count == 0) Status(OutcomeNoWork, seen.usable, target, 0, value, None, seen.total, needed)
      else {
        require(alive(), "seed attempt expired")
        val box = seen.largest.get
        val input = box.toInputUTXO(ctx)
        require(input.id.toString == box.boxId && input.bytes.length <= limits.maxInputBytes,
          "seed input identity or byte budget is invalid")
        val allocation = EngineFunding(owner, limits.reservationTimeoutMs.milliseconds, ec).reserveKnown(Seq(input))
        // Released however the send ends; a broadcast input stays owned until reconciliation resolves it
        try {
          val unsigned = build(ctx, wallet, input, count, value, Fee)
          require(alive(), "seed attempt expired")
          val signed = wallet.sign(unsigned)
          val result = new EngineBroadcast(owner, api, requestTimeout).send(signed, Seq(allocation), "seed", alive)
          logger.info(s"Sent transaction ${result.txId} splitting $count box(es) of $value nanoERG from ${box.boxId}: " +
            s"${result.outcome}")
          Status(result.outcome, seen.usable, target, count, value, Some(result.txId), seen.total, needed)
        } finally allocation.release()
      }
    }
  }
}
