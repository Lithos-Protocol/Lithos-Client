package tasks

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import cache.MDCache
import configs._
import mutations.NotEnoughInputsException
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import play.api.cache.SyncCacheApi
import state.messages.SyncMessages.{CommittedState, GetCommittedState, Ready}
import transactions.rollups.{CommitmentTransactions, DataBoxSource}
import transactions.wallet.{ReservationExpiredException, WalletSelector}
import utils.Globals

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.{Inject, Named, Singleton}
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success, Try}

/** Creates the local Miner Dictionary entry only after canonical synchronization is ready. */
@Singleton
class MDSyncTask @Inject()(cache: SyncCacheApi,
                           system: ActorSystem,
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

  if (taskConfig.enabled) {
    system.scheduler.scheduleWithFixedDelay(taskConfig.startup, commitmentInterval) { () =>
      if (Globals.getDetailedSyncStatus.isInstanceOf[Ready] &&
        Globals.mdDB.getDataBoxToken.isEmpty &&
        !stateConfig.disableTransforms.getOrElse(false) &&
        stateConfig.autoCommit.getOrElse(true) &&
        dictionaryTrusted &&
        running.compareAndSet(false, true)) {
        Try {
          val walletSelector = WalletSelector(walletManager, 4.seconds, contexts.pollingContext)
          new CommitmentTransactions(nodeContext, DataBoxSource.Stored)
            .sendInitialCommitment(stratumConfig.diff, MDCache(cache), walletSelector)
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

  /** Refuses registration while the tracked dictionary cannot produce a current insertion proof. */
  private def dictionaryTrusted: Boolean = {
    implicit val timeout: Timeout = Timeout(4.seconds)
    Try(Await.result((syncHandler ? GetCommittedState).mapTo[Any], 4.seconds)) match {
      case Success(CommittedState(state)) =>
        state.minerDictionaryFault.foreach(reason =>
          logger.warn(s"Not registering in the Miner Dictionary: its state is not current ($reason)"))
        state.minerDictionaryFault.isEmpty
      case Success(_) => false
      case Failure(ex) =>
        logger.warn(s"Could not read synchronization state before registering: ${ex.getMessage}")
        false
    }
  }
}
