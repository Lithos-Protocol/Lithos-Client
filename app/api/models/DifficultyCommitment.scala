package api.models

import lfsm.LFSMHelpers
import play.api.libs.json._
import transactions.rollups.{CommitmentSends, CommitmentState, CommitmentTransactions}

import scala.util.{Failure, Success, Try}

/**
 * One commitment and the heights it acts at. `inForceFromHeight` is the first height whose rollups
 * judge NISPs against it. `servedFromHeight`, the first block the stratum mines at it, is present
 * only on a commitment that has not bound yet.
 */
case class CommitmentEntry(score: String, diff: String, declaredHeight: Int, inForceFromHeight: Int,
                           servedFromHeight: Option[Int] = None)

/**
 * A registration or change that has been sent but is not yet in the confirmed commitment list.
 * `confirmedHeight` is present once it has confirmed and this client has not yet synced past it.
 */
case class CommitmentInFlight(txId: String, kind: String, confirmedHeight: Option[Int], commitment: CommitmentEntry)

/** Blocks from the tip a commitment is sent at until each thing happens. */
case class CommitmentTiming(servedAfterBlocks: Int, inForceAfterBlocks: Int, replaceableAfterBlocks: Int,
                            windowBlocks: Int, rollupLifetimeBlocks: Int)

/**
 * This miner's difficulty commitment as the chain and this client see it.
 *
 * `canCommit` is what a send control keys off and `blockedReason` says why not. Both are a snapshot:
 * `POST /mining/commitment` checks everything again.
 */
case class DifficultyCommitment(state: String,
                                reason: Option[String],
                                height: Option[Int],
                                inForce: Option[CommitmentEntry],
                                pending: Option[CommitmentEntry],
                                inFlight: Option[CommitmentInFlight],
                                replaceableFromHeight: Option[Int],
                                canCommit: Boolean,
                                blockedReason: Option[String],
                                autoCommit: Boolean,
                                configDiff: String,
                                timing: CommitmentTiming)

/** A registration or commitment change to send. `diff` is written the way `stratum.diff` is. */
case class CommitmentRequest(diff: String)

/** A sent registration or change and the heights its commitment acts at. */
case class CommitmentResult(txId: String,
                            kind: String,
                            outcome: String,
                            score: String,
                            diff: String,
                            sentAtHeight: Int,
                            declaredHeight: Int,
                            servedFromHeight: Int,
                            inForceFromHeight: Int,
                            replaceableFromHeight: Int)

/** Why a commitment cannot be sent now. */
object CommitmentBlock {
  /** `state.disableTransforms` switches off all rollup work, registering and committing included. */
  final val TransformsDisabled = "TRANSFORMS_DISABLED"
  /** `state.autoCommit` keeps the commitment at `stratum.diff`, so a change made here would be undone. */
  final val AutoCommit = "AUTO_COMMIT"
  /** The chain or this client's view of it could not be read. */
  final val Unavailable = "UNAVAILABLE"
  /** A registration or change is waiting to confirm, or to be synced. */
  final val InFlight = "IN_FLIGHT"
  /** The registration confirmed, and synchronization has not recorded this miner's data box yet. */
  final val Syncing = "SYNCING"
  /** The newest commitment cannot be replaced before `replaceableFromHeight`. */
  final val Locked = "LOCKED"
}

object DifficultyCommitment {
  implicit lazy val entryFormat: Format[CommitmentEntry] = Json.format[CommitmentEntry]
  implicit lazy val inFlightFormat: Format[CommitmentInFlight] = Json.format[CommitmentInFlight]
  implicit lazy val timingFormat: Format[CommitmentTiming] = Json.format[CommitmentTiming]
  implicit lazy val jsonFormat: Format[DifficultyCommitment] = Json.format[DifficultyCommitment]
  implicit lazy val requestFormat: Format[CommitmentRequest] = Json.format[CommitmentRequest]
  implicit lazy val resultFormat: Format[CommitmentResult] = Json.format[CommitmentResult]

  final val Unregistered = "unregistered"
  final val Registering = "registering"
  final val Waiting = "waiting"
  final val Active = "active"
  final val Unknown = "unknown"

  /** The longest diff string the transaction engine admits for a registration or change. */
  final val MaxDiffLength = 32

  val Timing: CommitmentTiming = {
    val declare = CommitmentTransactions.DeclareAfter
    CommitmentTiming(
      servedAfterBlocks = declare - CommitmentTransactions.ServeLead,
      inForceAfterBlocks = CommitmentTransactions.inForceFrom(declare),
      replaceableAfterBlocks = CommitmentTransactions.replaceableFrom(declare),
      windowBlocks = LFSMHelpers.NISP_WINDOW,
      rollupLifetimeBlocks = LFSMHelpers.ROLLUP_LIFETIME.toInt)
  }

  /**
   * The score a `stratum.diff`-style string commits to, by the same conversion the commitment
   * transaction uses. Fails on anything without a K, M, G, T or P suffix, outside a positive Long, or
   * longer than the transaction engine admits.
   */
  def scoreOf(diff: String): Try[Long] = Try {
    require(diff.trim.length <= MaxDiffLength, s"diff '$diff' is longer than $MaxDiffLength characters")
    val tau = BigInt(LFSMHelpers.parseDiffValueForStratum(diff.trim).get)
    require(tau > 0, s"diff '$diff' is not positive")
    val score = LFSMHelpers.convertTauOrScore(tau)
    require(score > 0 && score <= Long.MaxValue, s"diff '$diff' is outside the range a commitment can hold")
    score.toLong
  }

  def diffOf(score: Long): String = LFSMHelpers.formatTau(LFSMHelpers.convertTauOrScore(BigInt(score)))

  private def entry(declared: Int, score: Long, served: Option[Int] = None): CommitmentEntry =
    CommitmentEntry(score.toString, diffOf(score), declared, CommitmentTransactions.inForceFrom(declared), served)

  /** A commitment that has not bound yet, served over `previous`, the score in force before it. */
  private def upcoming(declared: Int, score: Long, previous: Option[Long]): CommitmentEntry =
    entry(declared, score, Some(CommitmentTransactions.servedFrom(declared, score, previous)))

  private def flight(sent: CommitmentSends.Outstanding, previous: Option[Long]): CommitmentInFlight =
    CommitmentInFlight(sent.sent.txId, sent.sent.kind, sent.includedAt,
      upcoming(sent.sent.declaredHeight, sent.sent.score, previous))

  /**
   * The status from what was read.
   *
   * @param read        the commitment lists at the tip
   * @param registered  whether the synced Miner Dictionary holds this miner, or why it cannot say
   * @param outstanding the last registration or change this client sent, while it still holds the next
   * @param autoCommit  whether the auto-commit loop is running and owns the commitment
   */
  def of(read: Try[CommitmentState],
         registered: Either[String, Boolean],
         outstanding: Option[CommitmentSends.Outstanding],
         autoCommit: Boolean,
         transformsDisabled: Boolean,
         configDiff: String): DifficultyCommitment = {
    val settingsBlock =
      if (transformsDisabled) Some(CommitmentBlock.TransformsDisabled)
      else if (autoCommit) Some(CommitmentBlock.AutoCommit)
      else None

    def status(state: String, reason: Option[String], height: Option[Int], inForce: Option[CommitmentEntry],
               pending: Option[CommitmentEntry], inFlight: Option[CommitmentInFlight],
               replaceable: Option[Int], block: Option[String]): DifficultyCommitment = {
      val blocked = settingsBlock.orElse(block)
      DifficultyCommitment(state, reason, height, inForce, pending, inFlight, replaceable,
        blocked.isEmpty, blocked, autoCommit, configDiff, Timing)
    }

    read match {
      case Failure(ex) =>
        status(Unknown, Some(s"could not read the commitment: ${ex.getMessage}"), None, None, None,
          outstanding.map(flight(_, None)), None, Some(CommitmentBlock.Unavailable))

      case Success(state) => state.confirmed match {
        case None =>
          outstanding.filter(_.sent.kind == CommitmentSends.Registration) match {
            case Some(registration) =>
              status(Registering, Some(s"waiting for ${registration.reason}"), Some(state.height), None, None,
                Some(flight(registration, None)),
                Some(CommitmentTransactions.replaceableFrom(registration.sent.declaredHeight)),
                Some(CommitmentBlock.InFlight))
            case None => registered match {
              case Right(false) => status(Unregistered, None, Some(state.height), None, None, None, None, None)
              // The dictionary is what says whether this miner is in it; the stored data box token is a
              // cache that synchronization fills in after the registration confirms.
              case Right(true) =>
                status(Registering, Some("registered; waiting for synchronization to record this miner's data box"),
                  Some(state.height), None, None, None, None, Some(CommitmentBlock.Syncing))
              case Left(why) =>
                status(Unknown, Some(s"the Miner Dictionary is not ready: $why"), Some(state.height), None, None,
                  None, None, Some(CommitmentBlock.Syncing))
            }
          }

        case Some(schedule) if schedule.entries.isEmpty =>
          status(Unknown, Some("the data box carries no commitments"), Some(state.height), None, None, None, None,
            Some(CommitmentBlock.Unavailable))

        case Some(schedule) =>
          val entries = schedule.entries
          val newest = entries.head
          val aged = state.height - newest._1 >= LFSMHelpers.NISP_WINDOW
          val inForce = (if (aged) entries.headOption else entries.lift(1)).map(e => entry(e._1, e._2))
          val pending = if (aged) None else Some(upcoming(newest._1, newest._2, entries.lift(1).map(_._2)))
          // A change in the mempool is seen whoever sent it; this client's own record also covers one
          // the node has confirmed that this read predates.
          val inFlight = state.unconfirmed.flatMap { next =>
            next.entries.headOption.map(e => CommitmentInFlight(next.txId, CommitmentSends.Change, None,
              upcoming(e._1, e._2, next.entries.lift(1).map(_._2))))
          }.orElse(outstanding
            .filter(o => o.sent.kind == CommitmentSends.Change && !entries.headOption.contains(o.sent.declaredHeight -> o.sent.score))
            .map(flight(_, Some(newest._2))))
          val replaceable = CommitmentTransactions.replaceableFrom(newest._1)
          val block =
            if (inFlight.isDefined) Some(CommitmentBlock.InFlight)
            else if (state.height < replaceable) Some(CommitmentBlock.Locked)
            else None
          status(if (inForce.isDefined) Active else Waiting, None, Some(state.height), inForce, pending, inFlight,
            Some(replaceable), block)
      }
    }
  }
}
