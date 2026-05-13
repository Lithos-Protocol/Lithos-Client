package mining

import akka.actor.Actor
import akka.pattern.pipe
import evaluation.NTable
import lfsm.LFSMHelpers
import mining.MiningMessages._
import org.bouncycastle.util.encoders.Hex
import org.slf4j.{Logger, LoggerFactory}
import sigma.pow.Autolykos2PowValidation
import stratum.BlockTemplate
import stratum.counter.{ExtraNonceCounter, JobCounter}
import stratum.data.{MiningCandidate, Options}

import java.math.BigInteger
import java.util.Arrays
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
 * Actor that owns all job-management and share-validation state.
 *
 * This is a direct Scala translation of stratum.JobManager, with all
 * mutable state confined inside the actor so that no external synchronisation
 * is required.
 *
 * Parent: LithosPool (receives NewJobAvailable / JobUpdated events).
 *
 * Accepted messages
 * ─────────────────
 *  ProcessTemplate   → Boolean (true = new block produced)
 *  RequestSubscription → SubscriptionData
 *  ProcessShare      → ShareResult
 */
class LithosJobManager(options: Options) extends Actor {

  import LithosJobManager._

  private val logger: Logger = LoggerFactory.getLogger("LithosJobManager")

  // ─── state ────────────────────────────────────────────────────────────────

  private val jobCounter: JobCounter               = new JobCounter()
  val extraNonceCounter: ExtraNonceCounter         = new ExtraNonceCounter(options.extraNonce1Size)
  val extraNonce2Size: Int                         = ExtraNoncePlaceholder.length - options.extraNonce1Size

  private var currentJob: Option[BlockTemplate]   = None
  private val validJobs: mutable.HashMap[String, BlockTemplate] = mutable.HashMap.empty

  // ─── receive ──────────────────────────────────────────────────────────────

  override def receive: Receive = {

    // ------------------------------------------------------------------
    // Process a new block template fetched from the node.
    // Mirrors JobManager.processTemplate exactly.
    // ------------------------------------------------------------------
    case ProcessTemplate(candidate, tau, usesCollateral, reducedShareMessages) =>
      val isNew = currentJob.isEmpty || {
        !Arrays.equals(currentJob.get.candidate.msg, candidate.msg) &&
          candidate.height >= currentJob.get.candidate.height
      }

      if (isNew) {
        val jobId    = jobCounter.next()
        val template = new BlockTemplate(jobId, candidate, tau, usesCollateral, reducedShareMessages)
        currentJob   = Some(template)
        validJobs.put(jobId, template)
        pruneOldJobs(jobId)
        logger.info(s"New job $jobId at height ${candidate.height} usesCollateral=$usesCollateral")
        context.parent ! NewJobAvailable(template)
        sender() ! true
      } else {
        sender() ! false
      }

    // ------------------------------------------------------------------
    // Subscription request: issue a fresh extraNonce1 and return the
    // current job so StratumConnection can complete the subscribe handshake.
    // ------------------------------------------------------------------
    case RequestSubscription =>
      sender() ! SubscriptionData(extraNonceCounter.next(), extraNonce2Size, currentJob)

    // ------------------------------------------------------------------
    // Share submission: validate POW and record the submission to prevent
    // duplicates.  Mirrors JobManager.processShare exactly.
    // ------------------------------------------------------------------
    case msg: ProcessShare =>
      Future(validateShare(msg))(context.dispatcher).pipeTo(sender())
  }

  // ─── private helpers ──────────────────────────────────────────────────────

  /**
   * Remove jobs more than 5 IDs behind the current one, matching the Java
   * JobManager's rolling-window pruning logic.
   */
  private def pruneOldJobs(currentJobId: String): Unit = {
    val jobNum    = Integer.decode("0x" + currentJobId)
    val toRemove  = if (jobNum >= 5) jobNum - 5 else 65535 - (5 - jobNum)
    validJobs.remove(Integer.toHexString(toRemove))
  }

  /**
   * Full Autolykos2 share validation.  Preserves every check and
   * Lithos-specific super-share threshold from the original Java implementation.
   */
  private def validateShare(msg: ProcessShare): ShareResult = {
    import msg._

    // Size checks
    if (extraNonce2.length != extraNonce2Size)
      return ShareRejected(20, "incorrect size of extraNonce2", extraNonce1)

    val job = validJobs.getOrElse(jobId, null)
    if (job == null)
      return ShareRejected(21, "solution was for old block", extraNonce1)

    val nonce = concat(extraNonce1, extraNonce2)
    if (nonce.length != 8)
      return ShareRejected(20, "incorrect size of nonce", extraNonce1)

    // Duplicate check
    if (!job.registerSubmit(extraNonce1, extraNonce2, nTime, nonce))
      return ShareRejected(22, "duplicate share", extraNonce1)

    // Autolykos2 POW hit calculation
    val h  = intBytes(job.candidate.height.toInt)
    val fH = Autolykos2PowValidation
      .hitForVersion2ForMessageWithChecks(32, job.msg, nonce, h, NTable.lookUp(job.candidate.height.toInt))
      .bigInteger

    val isBlock = job.candidate.b.compareTo(fH) >= 0

    // Lithos super-share threshold: realTau / NISP_COEFFICIENT
    val realTau = LFSMHelpers
      .convertTauOrScore(LFSMHelpers.convertTauOrScore(scala.math.BigInt(job.tau)))
      .bigInteger
    val superShareThreshold = realTau.divide(BigInteger.valueOf(LFSMHelpers.NISP_COEFFICIENT))

    val (blockHash, isSuperShare) =
      if (isBlock) {
        // Share meets the network's full block target
        logger.info(s"Block candidate from $ipAddress at height ${job.candidate.height}, nonce=${Hex.toHexString(nonce)}")
        val superShare = superShareThreshold.compareTo(fH) >= 0 && job.usedCollateral
        (fH.toByteArray, superShare)

      } else if (superShareThreshold.compareTo(fH) >= 0) {
        // Share meets the super-share threshold but not the full-block target
        if (job.usedCollateral) {
          logger.info(s"Super share from $ipAddress at coefficient ${realTau.divide(fH)} (height ${job.candidate.height})")
          (fH.toByteArray, true)
        } else {
          logger.info(s"Super share from $ipAddress but miner was solo-mining (no collateral)")
          (fH.toByteArray, false)
        }

      } else if (job.tau.compareTo(fH) < 0) {
        // Share did not even reach the miner's assigned difficulty
        return ShareRejected(32, "Low difficulty share", extraNonce1)

      } else {
        // Normal valid share — not a block, not a super-share
        (Array.emptyByteArray, false)
      }

    ShareAccepted(
      jobId           = jobId,
      ipAddress       = ipAddress,
      workerName      = workerName,
      difficulty      = realTau,
      height          = job.candidate.height,
      msg             = job.candidate.msg,
      shareDiff       = fH,
      isBlock         = isBlock,
      blockDiffActual = job.candidate.b,
      blockHash       = blockHash,
      isSuperShare    = isSuperShare,
      candidate       = MiningCandidate.copy(job.candidate),
      nonce           = nonce
    )
  }

  // ─── byte utilities (Scala equivalents of stratum.Utils) ──────────────────

  private def concat(arrays: Array[Byte]*): Array[Byte] = {
    val out = new Array[Byte](arrays.map(_.length).sum)
    var pos = 0
    arrays.foreach { a => System.arraycopy(a, 0, out, pos, a.length); pos += a.length }
    out
  }

  private def intBytes(value: Int): Array[Byte] =
    Array((value >> 24).toByte, (value >> 16).toByte, (value >> 8).toByte, value.toByte)
}

object LithosJobManager {
  /** Mirrors stratum.JobManager.extraNoncePlaceholder — length determines extraNonce2Size. */
  private val ExtraNoncePlaceholder: Array[Byte] =
    Array(0xf0.toByte, 0x00, 0x00, 0x0f, 0xf1.toByte, 0x11, 0x11, 0x1f)
}
