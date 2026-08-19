package mining

import akka.actor.Actor
import evaluation.NTable
import lfsm.LFSMHelpers
import mining.MiningMessages._
import org.bouncycastle.util.encoders.Hex
import org.slf4j.{Logger, LoggerFactory}
import sigma.pow.Autolykos2PowValidation
import stratum.BlockTemplate
import stratum.counter.{ExtraNonceCounter, JobCounter}
import stratum.data.{MiningCandidate, Options}

import scala.collection.mutable
import scala.util.Try

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

  /** What the current job is FOR, as opposed to what its header happens to contain. See [[jobIdentity]]. */
  private var currentIdentity: (Long, Boolean, String) = (-1L, false, "")

  /** Templates ignored since the last real job, logged so the suppression is visible. */
  private var suppressed: Int = 0

  // ─── receive ──────────────────────────────────────────────────────────────

  override def receive: Receive = {

    // ------------------------------------------------------------------
    // Process a new block template fetched from the node.
    // Mirrors JobManager.processTemplate exactly.
    // ------------------------------------------------------------------
    // A solo job must never carry collateral data. Its `pk` decides where the coinbase goes, and a
    // candidate requested for a lender's key keeps that key even when the genesis transaction is not
    // in it — mining that pays the lender for a block that spends no collateral, and costs this
    // miner the reward. Refused outright rather than mined, because there is no safe way to use it.
    case ProcessTemplate(candidate, _, usesCollateral, _, _)
      if !usesCollateral && candidate.collateralData != null =>
      logger.error(s"Refusing a solo template carrying collateral data for pk ${candidate.pk} at " +
        s"height ${candidate.height} — this would pay a lender for a block with no genesis " +
        "transaction. Nothing is mined on it")
      sender() ! false

    case ProcessTemplate(candidate, tau, usesCollateral, reducedShareMessages, mustPublish) =>
      val identity = jobIdentity(candidate, usesCollateral)
      val isNew = currentJob.isEmpty || identity != currentIdentity

      // Never go backwards, whatever the caller says: a template for a height already passed is a
      // dead block no matter how it was obtained.
      val notBehind = currentJob.forall(candidate.height >= _.candidate.height)

      // `mustPublish` overrides the identity test because it assumes dropping the template is free,
      // and once it has been fetched from the node it is not — see the message's own documentation.
      // Pacing is applied by whoever fetches, before fetching.
      if (notBehind && (mustPublish || isNew)) {
        val jobId    = jobCounter.next()
        val template = new BlockTemplate(jobId, candidate, tau, usesCollateral, reducedShareMessages)
        currentJob   = Some(template)
        currentIdentity = identity
        validJobs.put(jobId, template)
        pruneOldJobs(jobId)
        // The header is logged because it is the only way to tell a freshly assembled candidate from
        // the one the node had cached — two jobs with the same header mean the node did no work.
        logger.info(s"New job $jobId at height ${candidate.height} usesCollateral=$usesCollateral " +
          s"msg=${Hex.toHexString(candidate.msg).take(16)}" +
          (if (suppressed > 0) s" (ignored $suppressed mempool refresh(es) since the last job)" else ""))
        suppressed = 0
        context.parent ! NewJobAvailable(template)
        sender() ! true
      } else {
        suppressed += 1
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
    // Validated on the actor thread, not in a Future. It reads `validJobs` and mutates the job's
    // submission set, neither of which is thread-safe, so running it concurrently raced with
    // ProcessTemplate and with other shares — losing duplicate detection at best. One Autolykos
    // verification is cheap enough that serialising it is not the bottleneck; the mailbox hop that
    // used to go through LithosPool was.
    //
    // Guarded because it now runs where a throw is fatal: an exception here restarts the actor and
    // empties validJobs, so every connected miner is told "old block" until the next poll builds a
    // job. The inputs are miner-supplied, so this must never be reachable.
    case msg: ProcessShare =>
      sender() ! Try(validateShare(msg)).recover { case ex =>
        logger.error(s"Share validation threw for job ${msg.jobId} from ${msg.ipAddress}", ex)
        ShareRejected(20, "malformed share", msg.extraNonce1)
      }.get
  }

  // ─── private helpers ──────────────────────────────────────────────────────

  /**
   * What makes a template a genuinely different job, as opposed to the same block with a different
   * transaction set.
   *
   * Three things oblige a miner to switch: a new height, collateral appearing or disappearing, and a
   * different genesis transaction. A newer mempool does not — the old header still mines a valid
   * block, just without the newest fees — so it is not part of the identity.
   *
   * That does not mean mempool refreshes are dropped. LithosPool paces them and marks them
   * `mustPublish`, because by the time one arrives it has already been fetched and holding it back
   * would strand the job miners are on. This identity is what decides the rest.
   */
  private def jobIdentity(candidate: MiningCandidate, usesCollateral: Boolean): (Long, Boolean, String) =
    (candidate.height,
      usesCollateral,
      Option(candidate.collateralData).map(_.txId).getOrElse(""))

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

    // Both derived once when the job was built. They only depend on the job's tau, and recomputing
    // them meant three BigInteger divisions on every share for an answer that never changes.
    val realTau = job.realTau
    val superShareThreshold = job.superShareThreshold

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
        return ShareRejected(32, s"Low difficulty share with " +
          s"\n nonce = ${Hex.toHexString(nonce)}" +
          s"\n strat-diff: ${job.tau} | ${LFSMHelpers.formatTau(job.tau)}" +
          s"\n share-diff: ${fH} | ${LFSMHelpers.formatTau(fH)}", extraNonce1)

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
      // Only a block or a super share is ever read downstream, and those are one in tens of
      // thousands. The candidate's fields are assigned in its constructors and nowhere else, so
      // sharing the job's own is safe; the copy existed to guard a mutability that does not exist.
      candidate       = if (isBlock || isSuperShare) MiningCandidate.copy(job.candidate) else job.candidate,
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
