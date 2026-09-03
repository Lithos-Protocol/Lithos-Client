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

  if (taskConfig.enabled) {
    system.scheduler.scheduleWithFixedDelay(taskConfig.startup, commitmentInterval) { () =>
      val view = Globals.syncView
      if (view.canonical.available &&
        dictionaryTrusted(view) &&
        Globals.mdDB.getDataBoxToken.isEmpty &&
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
   * Refuses registration while the tracked dictionary cannot produce a current insertion proof.
   */
  private def dictionaryTrusted(view: SyncView): Boolean = {
    view.minerDictionary.reason.foreach(reason =>
      logger.warn(s"Not registering in the Miner Dictionary: $reason"))
    view.minerDictionary.available && view.minerDictionaryMetadata.isDefined
  }
}
