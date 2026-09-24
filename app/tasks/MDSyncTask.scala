package tasks

import akka.actor.{ActorRef, ActorSystem}
import configs._
import mutations.NotEnoughInputsException
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import state.messages.SyncView
import state.messages.SyncMessages.{CurrentMinerDictionary, GetMinerDictionary}
import akka.pattern.ask
import akka.util.Timeout
import transactions.engine.{EngineBroadcast, TransactionEngine}
import transactions.engine.wallet.FundingExpiredException
import transactions.rollups.CommitmentProgress
import lfsm.LFSMHelpers
import scorex.utils.Longs
import utils.Globals

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.{Inject, Named, Singleton}
import scala.concurrent.duration.DurationInt
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

/**
 * Keeps this miner's Miner Dictionary entry and difficulty commitment in step with `stratum.diff`, and
 * warns before the registration expires.
 *
 * With auto-commit on, each pass registers this miner if it is not registered, and otherwise asks the
 * engine to move the commitment. A transaction it sent holds the loop until it confirms and this client
 * has synced past it, and the loop stops once the confirmed commitment is the configured one.
 */
@Singleton
class MDSyncTask @Inject()(system: ActorSystem,
                           config: Configuration,
                           nodeContext: NodeContext,
                           @Named("sync-handler") syncHandler: ActorRef,
                           @Named("transaction-engine") walletManager: ActorRef) {

  private val logger: Logger = LoggerFactory.getLogger("MDSyncTask")
  private val taskConfig = new TasksConfig(config).dictionarySyncTask
  private val contexts = new Contexts(system)
  private val stratumConfig = new StratumConfig(config)
  private val stateConfig = new StateConfig(config)
  private val running = new AtomicBoolean(false)
  private val commitmentInterval = taskConfig.interval.max(1.minute)
  // A cold Miner Dictionary request may reconstruct one large prover from snapshot + journal.
  private implicit val timeout: Timeout = Timeout(30.seconds)
  private val nodeApi = nodeContext.getNodeApi

  /** Rollup work can be switched off as a whole, and registering or committing is rollup work. */
  private val autoCommit = stateConfig.autoCommit && !stateConfig.disableTransforms.getOrElse(false)

  // Touched only inside a pass, and `running` keeps passes from overlapping.
  /** The registration or commitment last sent, until it has confirmed and been synced. */
  private var inFlight: Option[String] = None
  /** Set once the confirmed commitment is the configured one; nothing is left to do after that. */
  private var settled = false
  /** The last reason logged for waiting, so an unchanged wait is reported once rather than each pass. */
  private var lastWait: Option[String] = None

  private val minerHash: Array[Byte] = nodeContext.getNodeWallet.contract.hashedPropBytes
  /** Zero until this miner's entry has been read; it never moves without a re-registration. */
  private val expiry = new java.util.concurrent.atomic.AtomicLong(0L)
  /**
   * Attempts spent reading the expiry, capped at [[MDSyncTask.MaxExpiryReads]]. Only incremented
   * while the expiry is still unknown, so a successful read costs nothing further.
   */
  private val expiryReads = new java.util.concurrent.atomic.AtomicInteger(0)

  if (taskConfig.enabled) {
    logAutoCommit()
    system.scheduler.scheduleWithFixedDelay(taskConfig.startup, commitmentInterval) { () =>
      // A throw out of this closure would cancel the schedule for the life of the process.
      if (running.compareAndSet(false, true)) {
        try pass()
        catch { case NonFatal(ex) => logger.error("Miner Dictionary pass failed", ex) }
        finally running.set(false)
      }
    }(contexts.pollingContext)
  } else logger.info("Miner Dictionary commitment task is disabled" +
    (if (autoCommit) ", so state.autoCommit has no effect" else ""))

  private def pass(): Unit = {
    val view = Globals.syncView
    val trusted = view.canonical.available && view.minerDictionary.available &&
      view.minerDictionaryMetadata.isDefined
    if (trusted) warnIfExpiring(view)
    if (autoCommit && !settled) autoCommitPass(view, trusted)
  }

  /** Says once, at startup, whether this client registers and commits on its own and what that means. */
  private def logAutoCommit(): Unit =
    if (!stateConfig.autoCommit)
      logger.warn("Auto-commit is off (state.autoCommit): this client will not register this miner or change " +
        "its difficulty commitment. An unregistered miner cannot submit NISPs, so it is not paid from rollups")
    else if (!autoCommit)
      logger.warn("Auto-commit is on, but state.disableTransforms is true, so nothing will be registered or committed")
    else
      logger.info(s"Auto-commit is on: registering this miner if needed and committing diff ${stratumConfig.diff}, " +
        s"checked every ${commitmentInterval.toSeconds}s from ${taskConfig.startup.toSeconds}s after startup")

  /**
   * One step towards the configured commitment. Registration needs a trusted dictionary to insert into;
   * moving the commitment needs only the data box the registration created.
   */
  private def autoCommitPass(view: SyncView, trusted: Boolean): Unit =
    stillInFlight(view) match {
      case Some(reason) => waiting(reason)
      case None =>
        if (Globals.mdDB.getDataBoxToken.nonEmpty) request(TransactionEngine.CommitDifficulty, "difficulty commitment")
        else if (!view.canonical.available)
          waiting(s"synchronization: ${view.canonical.reason.getOrElse("not ready")}")
        else if (!trusted)
          waiting(s"the Miner Dictionary: ${view.minerDictionary.reason.getOrElse("not loaded")}")
        // The dictionary is what says whether this miner is in it. The local token is a cache, and a
        // cleared store would otherwise read as never having registered.
        else if (view.minerDictionaryMetadata.exists(_.hasMiner))
          waiting("synchronization to record this miner's data box")
        else request(TransactionEngine.RegisterMiner, "Miner Dictionary registration")
    }

  private def request(message: Any, what: String): Unit =
    Try(scala.concurrent.Await.result(walletManager ? message, timeout.duration)) match {
      case Success(txId: String) => sent(txId, what)
      case Success(CommitmentProgress.Sent(txId)) => sent(txId, what)
      case Success(CommitmentProgress.Settled) =>
        settled = true
        logger.info(s"Difficulty commitment ${stratumConfig.diff} is on chain; auto-commit has nothing left to do")
      case Success(CommitmentProgress.Waiting(reason)) => waiting(reason)
      case Success(TransactionEngine.Deferred(_, reason)) => waiting(s"the transaction engine: $reason")
      case Success(other) => logger.warn(s"Unexpected answer to the $what request: $other")
      // The node may hold it, so it is watched exactly like one that was accepted.
      case Failure(lost: EngineBroadcast.SubmissionOutcomeException) if lost.outcome == EngineBroadcast.Uncertain =>
        logger.warn(s"The node's answer to $what ${lost.txId} was lost; waiting to see whether it confirms")
        inFlight = Some(lost.txId)
      // Still queued behind other engine work. The next pass joins the same request rather than adding one.
      case Failure(_: akka.pattern.AskTimeoutException) => waiting(s"the transaction engine to run the $what")
      case Failure(noInputs: NotEnoughInputsException) =>
        logger.error(s"Could not fund the $what: ${noInputs.getMessage}")
      case Failure(badReservation: FundingExpiredException) =>
        logger.error(s"Wallet reservation for the $what expired: ${badReservation.getMessage}")
      case Failure(ex) => logger.error(s"Unexpected $what failure", ex)
    }

  private def sent(txId: String, what: String): Unit = {
    logger.info(s"Sent $what $txId; waiting for it to confirm")
    inFlight = Some(txId)
    lastWait = None
  }

  private def waiting(reason: String): Unit =
    if (!lastWait.contains(reason)) {
      lastWait = Some(reason)
      logger.info(s"Auto-commit waiting on $reason")
    }

  /**
   * What the transaction last sent is still waiting for, or None once it confirmed and was synced, or
   * left the mempool without confirming. Until then the dictionary and the data box read as they did
   * before it, and acting on them would build a double spend of it.
   */
  private def stillInFlight(view: SyncView): Option[String] = inFlight.flatMap { txId =>
    val status = for {
      pending <- nodeApi.unconfirmedTransactionById(txId).map(_.isDefined)
      included <- if (pending) Success(None) else nodeApi.indexedTransactionById(txId).map(_.map(_.inclusionHeight))
    } yield (pending, included)
    status match {
      case Success((true, _)) => Some(s"transaction $txId to confirm")
      case Success((false, included)) if MDSyncTask.awaitingSync(included, view.cursor.map(_.height)) =>
        Some(s"synchronization to reach height ${included.get}, where $txId confirmed")
      case Success(_) =>
        inFlight = None
        None
      case Failure(ex) => Some(s"the node to report on transaction $txId: ${ex.getMessage}")
    }
  }

  /**
   * Warns while removing this registration would strike one of this miner's own honest NISPs.
   *
   * A rollup opened just before the entry expires stays slashable for its whole lifetime afterwards,
   * and the commitment proof reads the dictionary as it stands rather than as it stood then.
   */
  private def warnIfExpiring(view: SyncView): Unit =
    for {
      height <- view.cursor.map(_.height.toLong)
      validUntil <- registrationExpiry(view)
      if MDSyncTask.shouldWarn(height, validUntil)
    } logger.warn(s"This miner's Miner Dictionary registration expires at height $validUntil " +
      s"(now $height). Submissions after that are worthless, but do NOT remove or re-register " +
      s"before height ${MDSyncTask.removalSafeAt(validUntil)}: rollups opened up to expiry stay " +
      "slashable until then, and removing lets anyone claim this miner's bond on an honest NISP")

  /**
   * This miner's expiry, read from its own dictionary entry and then kept.
   *
   * The entry is `[credentialId: 32][validUntil: 8]` and the value is fixed for the life of a
   * registration, so one read answers every later tick without materializing a prover again.
   */
  private def registrationExpiry(view: SyncView): Option[Long] = {
    if (expiry.get() == 0L && view.minerDictionaryMetadata.exists(_.hasMiner) &&
      expiryReads.getAndIncrement() < MDSyncTask.MaxExpiryReads) {
      val attempt = Try {
        scala.concurrent.Await.result(syncHandler ? GetMinerDictionary, timeout.duration) match {
          case CurrentMinerDictionary(md) =>
            md.dictionary.copy().lookUp(minerHash).response.headOption
              .flatMap(_.tryOp.toOption).flatten
              .filter(_.length == MDSyncTask.EntrySize)
              .foreach(entry => expiry.set(Longs.fromByteArray(entry.slice(32, MDSyncTask.EntrySize))))
          case other => throw new IllegalStateException(s"Miner Dictionary unavailable: $other")
        }
      }
      // Keyed on the expiry still being unset rather than on the Try failing. An ask that answers
      // while the entry is missing throws nothing and would otherwise go unreported.
      if (expiry.get() == 0L) {
        val reason = attempt.failed.map(_.getMessage)
          .getOrElse("the dictionary reports this miner but holds no well-formed entry for it")
        if (expiryReads.get() >= MDSyncTask.MaxExpiryReads)
          logger.warn(s"Could not read this miner's registration expiry: $reason. Giving up; this " +
            "miner will not be warned before its registration expires")
        else logger.info(s"Could not read this miner's registration expiry: $reason")
      }
    }
    Some(expiry.get()).filter(_ > 0L)
  }

}

object MDSyncTask {

  /**
   * Whether a transaction confirmed at `includedAt` is still ahead of this client's synced height, so
   * its effect is not yet visible locally. A transaction the node never confirmed holds nothing back.
   */
  private[tasks] def awaitingSync(includedAt: Option[Int], syncedTo: Option[Int]): Boolean =
    includedAt.exists(height => syncedTo.forall(_ < height))

  /**
   * Where the removal danger window opens. A rollup opened this close to expiry is still live at it,
   * and removing the entry while it is makes the miner's own honest submission look fraudulent.
   */
  private[tasks] def removalUnsafeFrom(validUntil: Long): Long = validUntil - LFSMHelpers.ROLLUP_LIFETIME

  /** Where it closes: the last rollup that could have been opened under this entry has finished. */
  private[tasks] def removalSafeAt(validUntil: Long): Long = validUntil + LFSMHelpers.ROLLUP_LIFETIME

  /**
   * Whether this height is inside the window where removal is unsafe, which is what decides the
   * warning. Named rather than inlined so the boundary is testable without an actor.
   */
  private[tasks] def shouldWarn(height: Long, validUntil: Long): Boolean =
    height >= removalUnsafeFrom(validUntil)

  /** A dictionary entry is `[credentialId: 32][validUntil: 8]`. */
  private[tasks] final val EntrySize: Int = 40

  /** Attempts at reading this miner's expiry before the warning is given up on. */
  private[tasks] final val MaxExpiryReads: Int = 3
}
