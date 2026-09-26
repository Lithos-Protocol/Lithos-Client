package transactions.rollups

import lfsm.LFSMHelpers
import org.bouncycastle.util.encoders.Hex
import org.slf4j.Logger
import transactions.rollups.TransactionMessages.RollupTxStub
import transactions.rollups.TransactionMessages.RollupTxType
import transactions.rollups.TransactionMessages.RollupTxType.{NISPEvaluation, NISPSubmission}

/**
 * Which queued rollup stubs the funded path leaves to this miner's own block for now.
 *
 * A stub the candidate builds fee-less for block H is held until a chain check past H still finds
 * it queued, so H passed without the work landing. A stub arriving while the candidate path runs is
 * held until a build tries it or its block ends. Submissions and fraud proofs within
 * [[CandidateFirst.NearDeadlineBlocks]] of the end of their window are never held.
 *
 * Belongs to one actor's thread.
 *
 * @param holdArrivals whether a build later in the block can bring a new stub into the served job.
 *                     Without one, holding an arrival only delays its funded copy.
 */
private[transactions] final class CandidateFirst(logger: Logger, holdArrivals: Boolean = true) {
  import CandidateFirst._

  /** The block the candidate path last asked about; 0 until it asks. */
  private var candidateHeight = 0
  /** The block the queue was last checked against chain state for. */
  private var checkedHeight = 0
  private var offers = Map.empty[StubKey, Offer]

  /** The block being mined, by whichever signal saw it last. */
  def height: Int = math.max(candidateHeight, checkedHeight)

  /**
   * A stub newly queued. Held for the candidate only while the candidate path asked about this block
   * or the one before; otherwise nothing is building candidates and the funded path takes it.
   */
  def arrived(stub: RollupTxStub): Unit =
    if (holdArrivals && candidateWork(stub) && candidateHeight > 0 && candidateHeight + 1 >= height &&
      !nearDeadline(stub, height))
      offers += StubKey.of(stub) -> Pending(height)

  /** The candidate path asked about `blockHeight`. A stub no build tried before it is released. */
  def candidateAsked(blockHeight: Int): Unit =
    if (blockHeight > candidateHeight) {
      candidateHeight = blockHeight
      settle()
    }

  /**
   * The queue was just checked against chain state at `blockHeight`, and `queued` says what is left.
   * A stub carried for an earlier block survived the check only because that block did not take it.
   */
  def checked(blockHeight: Int, queued: StubKey => Boolean): Unit = {
    offers = offers.filter(entry => queued(entry._1))
    checkedHeight = math.max(checkedHeight, blockHeight)
    settle()
  }

  /**
   * One candidate build for `blockHeight` finished. What it built is held through that block; a
   * waiting stub it did not build is released, since this block will not carry it.
   */
  def built(blockHeight: Int, tried: Seq[RollupTxStub], built: Seq[RollupTxStub],
            queued: StubKey => Boolean): Unit =
    if (blockHeight >= candidateHeight) {
      val builtKeys = built.iterator.map(StubKey.of).toSet
      var held = Vector.empty[StubKey]
      tried.foreach { stub =>
        val key = StubKey.of(stub)
        if (queued(key) && !nearDeadline(stub, blockHeight)) offers.get(key) match {
          case None | Some(Pending(_)) if builtKeys(key) =>
            offers += key -> Carried(blockHeight)
            held :+= key
          case Some(Pending(_)) => offers += key -> Released
          case _ => ()
        }
      }
      if (held.nonEmpty)
        logger.info(s"Holding ${held.size} rollup stub(s) back from the funded path while the candidate " +
          s"for block $blockHeight offers them fee-less: ${describe(held)}")
    }

  def forget(stub: RollupTxStub): Unit = offers -= StubKey.of(stub)

  def forgetRollup(rollupBlockId: String): Unit =
    offers = offers.filterNot(_._1.rollupBlockId == rollupBlockId)

  /** Whether the funded path may send this stub now. */
  def fundable(stub: RollupTxStub): Boolean =
    nearDeadline(stub, height) || offers.get(StubKey.of(stub)).forall(_ == Released)

  /**
   * Whether a candidate build for `blockHeight` may offer this stub. Not once the block it was
   * carried for has passed: the chain check decides whether it landed, and the funded copy is next.
   */
  def offerable(stub: RollupTxStub, blockHeight: Int): Boolean = offers.get(StubKey.of(stub)) match {
    case Some(Carried(carriedFor)) => carriedFor >= blockHeight
    // The node drops a pooled transaction its own candidate double-spends, so work the funded path
    // owns stays out of the candidate
    case Some(Released) => false
    case _ => true
  }

  private def settle(): Unit = {
    var released = Vector.empty[StubKey]
    offers = offers.map {
      case (key, Pending(waitingFor)) if waitingFor < height => key -> Released
      case (key, Carried(carriedFor)) if carriedFor < checkedHeight =>
        released :+= key
        key -> Released
      case entry => entry
    }
    if (released.nonEmpty)
      logger.info(s"Releasing ${released.size} rollup stub(s) to the funded path: the block their " +
        s"candidate offered them to passed, and the chain check at $checkedHeight still found them " +
        s"undone: ${describe(released)}")
  }

  /** `Type:rollup` for each key, the rollup id cut to 8 characters. */
  private def describe(keys: Seq[StubKey]): String =
    keys.map(key => s"${key.txType}:${key.rollupBlockId.take(8)}").mkString(", ")
}

private[transactions] object CandidateFirst {

  /** Blocks left in a submission's or fraud proof's window at which it goes to the funded path at once. */
  final val NearDeadlineBlocks = 10

  /**
   * Whether `stub`'s window closes within [[NearDeadlineBlocks]] blocks of `height`, counting
   * `height` itself. Only submissions and fraud proofs have a window that closes.
   */
  def nearDeadline(stub: RollupTxStub, height: Int): Boolean = {
    val window = stub.txType match {
      case NISPSubmission => Some(LFSMHelpers.HOLDING_PERIOD)
      case NISPEvaluation if stub.fpInfo.isDefined => Some(LFSMHelpers.EVAL_PERIOD)
      case _ => None
    }
    window.exists(length => stub.currentPeriod.exists(start => start + length - height <= NearDeadlineBlocks))
  }

  /** Evaluation stubs without a fraud proof go to the evaluator, never to a candidate. */
  private def candidateWork(stub: RollupTxStub): Boolean =
    stub.txType != NISPEvaluation || stub.fpInfo.isDefined

  /** A queued stub's identity: its rollup, its type and, for a fraud proof, the accused miner. */
  final case class StubKey(rollupBlockId: String, txType: RollupTxType, target: Option[String])

  object StubKey {
    def of(stub: RollupTxStub): StubKey =
      StubKey(stub.rollupBlockId, stub.txType, stub.fpInfo.map(fp => Hex.toHexString(fp._1)))
  }

  private sealed trait Offer
  /** Waiting for a candidate build during block `height` to try it. */
  private final case class Pending(height: Int) extends Offer
  /** Built fee-less for block `height`. */
  private final case class Carried(height: Int) extends Offer
  /** Left to the funded path. */
  private case object Released extends Offer
}
