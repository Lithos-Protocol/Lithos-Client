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
import transactions.rollups.{CommitmentTransactions, DataBoxSource}
import transactions.wallet.{ReservationExpiredException, WalletSelector}
import lfsm.LFSMHelpers
import scorex.utils.Longs
import utils.Globals

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.{Inject, Named, Singleton}
import scala.concurrent.duration.DurationInt
import scala.util.Try

/** Creates the local Miner Dictionary entry only after canonical synchronization is ready. */
@Singleton
class MDSyncTask @Inject()(system: ActorSystem,
                           config: Configuration,
                           nodeContext: NodeContext,
                           @Named("sync-handler") syncHandler: ActorRef,
                           @Named("wallet-manager") walletManager: ActorRef) {

  private val logger: Logger = LoggerFactory.getLogger("MDSyncTask")
  private val taskConfig = new TasksConfig(config).dictionarySyncTask
  private val contexts = new Contexts(system)
  private val stratumConfig = new StratumConfig(config)
  private val stateConfig = new StateConfig(config)
  private val running = new AtomicBoolean(false)
  private val commitmentInterval = taskConfig.interval.max(1.minute)
  // A cold Miner Dictionary request may reconstruct one large prover from snapshot + journal.
  private implicit val timeout: Timeout = Timeout(30.seconds)

  private val minerHash: Array[Byte] = nodeContext.getNodeWallet.contract.hashedPropBytes
  /** Zero until this miner's entry has been read; it never moves without a re-registration. */
  private val expiry = new java.util.concurrent.atomic.AtomicLong(0L)
  /**
   * Attempts spent reading the expiry, capped at [[MDSyncTask.MaxExpiryReads]]. Only incremented
   * while the expiry is still unknown, so a successful read costs nothing further.
   */
  private val expiryReads = new java.util.concurrent.atomic.AtomicInteger(0)

  if (taskConfig.enabled) {
    system.scheduler.scheduleWithFixedDelay(taskConfig.startup, commitmentInterval) { () =>
      val view = Globals.syncView
      // Read once: it logs its reason as a side effect, so asking twice reports twice.
      val trusted = view.canonical.available && dictionaryTrusted(view)
      if (trusted) warnIfExpiring(view)
      if (trusted &&
        Globals.mdDB.getDataBoxToken.isEmpty &&
        // The dictionary is what says whether this miner is in it. The local token is a cache, and a
        // cleared store would otherwise read as never having registered.
        !view.minerDictionaryMetadata.exists(_.hasMiner) &&
        !stateConfig.disableTransforms.getOrElse(false) &&
        stateConfig.autoCommit.getOrElse(false) &&
        running.compareAndSet(false, true)) {
        Try {
          val walletSelector = WalletSelector(walletManager, 4.seconds, contexts.pollingContext)
          val dictionary = scala.concurrent.Await.result(syncHandler ? GetMinerDictionary,
            timeout.duration) match {
            case CurrentMinerDictionary(value) => value
            case other => throw new IllegalStateException(s"Miner Dictionary became unavailable: $other")
          }
          new CommitmentTransactions(nodeContext, DataBoxSource.Stored)
            .sendInitialCommitment(stratumConfig.diff, dictionary, walletSelector)
        }.failed.foreach {
          case noInputs: NotEnoughInputsException =>
            logger.error(s"Could not create the Miner Dictionary entry: ${noInputs.getMessage}")
          case badReservation: ReservationExpiredException =>
            logger.error(s"Miner Dictionary wallet reservation expired: ${badReservation.getMessage}")
          case ex => logger.error("Unexpected Miner Dictionary commitment failure", ex)
        }
        running.set(false)
      }
    }(contexts.pollingContext)
  } else logger.info("Miner Dictionary commitment task is disabled")

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

  /**
   * Refuses registration while the tracked dictionary cannot produce a current insertion proof.
   */
  private def dictionaryTrusted(view: SyncView): Boolean = {
    view.minerDictionary.reason.foreach(reason =>
      logger.warn(s"Not registering in the Miner Dictionary: $reason"))
    view.minerDictionary.available && view.minerDictionaryMetadata.isDefined
  }
}

object MDSyncTask {

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
