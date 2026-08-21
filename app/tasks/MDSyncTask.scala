package tasks

import akka.actor.{ActorRef, ActorSystem, Cancellable, CoordinatedShutdown}
import akka.pattern.ask
import akka.util.Timeout
import cache.MDCache
import configs.TasksConfig.TaskConfiguration
import configs._
import lfsm.LFSMHelpers
import lfsm.states.MinerTree
import mutations.NotEnoughInputsException
import node.NodeApi
import org.ergoplatform.restapi.client.FullBlock
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import play.api.cache.SyncCacheApi
import state.LFSMTransformer
import state.synchronization.Subscribable.{Subscribe, SubscribeAck, SubscribeRejected, SubscribeResponse}
import state.messages.DictionaryMessages.InitialMDState
import state.messages.StateFrameMessages._
import state.messages.SyncMessages._
import state.messages.{BlockInfo, BlockMessage, NodeSync}
import transactions.rollups.{CommitmentTransactions, DataBoxSource}
import transactions.wallet.{ReservationExpiredException, WalletSelector}
import utils.Globals

import javax.inject.{Inject, Named, Singleton}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

@Singleton
class MDSyncTask @Inject()(cache: SyncCacheApi, system: ActorSystem, config: Configuration,
                           nodeContext: NodeContext,
                           @Named("md-synchronizer") mdSynchronizer: ActorRef,
                           @Named("sync-handler")    syncHandler:       ActorRef,
                           @Named("wallet-manager")  walletManager: ActorRef,
                           cs: CoordinatedShutdown) {

  private val logger: Logger = LoggerFactory.getLogger("MDSyncTask")
  private val taskConfig: TaskConfiguration = new TasksConfig(config).dictionarySyncTask

  private val contexts: Contexts       = new Contexts(system)
  private val syncConfig: SyncConfig   = new SyncConfig(config)
  private val stratumConfig: StratumConfig = new StratumConfig(config)
  private val stateConfig: StateConfig = new StateConfig(config)


  if (taskConfig.enabled) {

    val dataNFT = Globals.mdDB.getDataBoxToken
    if (dataNFT.isEmpty) {
      logger.info("Starting Miner Dictionary synchronization")
      logger.info(s"Dictionary synchronization will start at height ${LFSMHelpers.MD_GENESIS_HEIGHT}")

      var currentHeight = LFSMHelpers.MD_GENESIS_HEIGHT
      val nodeApi = nodeContext.getNodeApi
      mdSynchronizer ! InitialMDState(LFSMHelpers.MD_GENESIS_HEIGHT, MinerTree.initialState)

      Future {
        // Phase 1: catch up to chain head
        var synced = false
        while (!synced) {
          val tip = chainHeight
          if (currentHeight <= tip) {
            loadBlockSync(currentHeight, nodeApi) match {
              case Success(_) =>
                if (currentHeight < tip) {
                  currentHeight += 1
                } else {
                  synced = true
                }
              case Failure(ex) =>
                logger.error(s"Failed to load block $currentHeight while syncing", ex)
            }
          } else {
            synced = true
          }
        }

        logger.info(s"Finished catch up sync to height $currentHeight")


        // Phase 2: handoff to SyncHandler — keep loading blocks and retrying until accepted
        logger.info("Attempting subscription to SyncHandler")
        var subscribed = false
        while (!subscribed) {
          implicit val timeout: Timeout = Timeout(10 seconds)

          val mdToken = LFSMHelpers.getMDToken(nodeContext.getClient).toString

          Await.result((syncHandler ? Subscribe(currentHeight, mdSynchronizer, Some(mdToken))).mapTo[SubscribeResponse], 10 seconds) match {
            case SubscribeAck =>
              logger.info(s"MDSynchronizer subscribed to SyncHandler at height $currentHeight")
              mdSynchronizer ! CompletedInitSync
              subscribed = true
              Globals.setMDSynced()
            case SubscribeRejected(reason) =>
              logger.warn(s"SyncHandler rejected subscription: $reason — catching up one block")
              loadBlockSync(currentHeight + 1, nodeApi) match {
                case Success(_) => currentHeight += 1
                case Failure(ex) =>
                  logger.error(s"Failed to load block ${currentHeight + 1} during subscription handoff", ex)
                  Thread.sleep(5000)
              }
          }
        }

        // Phase 3: if data box still not created, run a separate addToMD poller
        if (Globals.mdDB.getDataBoxToken.isEmpty && !stateConfig.disableTransforms.getOrElse(false)) {
          logger.info("Data box token not yet created: starting addToMD polling")
          var addToMDTask: Option[Cancellable] = None
          addToMDTask = Some(system.scheduler.scheduleWithFixedDelay(
            initialDelay = 10 seconds,
            delay        = 1 minutes)({
            () =>
              if (Globals.mdDB.getDataBoxToken.isDefined) {
                logger.info(s"Data box token found (${Globals.mdDB.getDataBoxToken.get}), stopping addToMD poller")
                addToMDTask.foreach(_.cancel())
              } else {
                if (stateConfig.autoCommit.getOrElse(true)) {
                  Try {
                    val walletSelector = WalletSelector(walletManager, 4 seconds, contexts.pollingContext)
                    new CommitmentTransactions(nodeContext, DataBoxSource.Stored)
                      .sendInitialCommitment(stratumConfig.diff, MDCache(cache), walletSelector)
                  }.failed.foreach {
                    case noInputs: NotEnoughInputsException =>
                      logger.error("Could not send initial MinerDictionary commitment transaction due" +
                        s" to not having enough ERG in wallet: ${noInputs.getMessage}")
                    case badReservation: ReservationExpiredException =>
                      logger.error("Could not send initial MinerDictionary commitment transaction due" +
                        s" to a bad wallet reservation: ${badReservation.getMessage}")
                    case ex =>
                      logger.error(s"Got unexpected error while" +
                        s" creating initial MinerDictionary commitment transaction: ${ex.getMessage}", ex)
                  }
                }else {
                  logger.error("No data box exists, and auto-commit is set to false. You will not be able to gain" +
                    "pooled rewards until auto-commit is enabled and a data box is made.")
                }
              }
          })(contexts.pollingContext))
        } else if (Globals.mdDB.getDataBoxToken.isDefined) {
          logger.info(s"Data box token already present (${Globals.mdDB.getDataBoxToken.get}), skipping addToMD poller")
        }

      }(contexts.pollingContext)
    } else {
      logger.info(s"Found saved DataBox token ${dataNFT.get}, not syncing MD")
    }
  } else {
    logger.info("MDSync was not enabled")
  }

  private def chainHeight: Int =
    nodeContext.getClient.execute(ctx => ctx.getHeight)

  private def loadBlockSync(height: Int, nodeApi: NodeApi): Try[Unit] = {
    if (height % 10000 == 0)
      logger.info(s"Loading block at height $height")
    Try {
      val block = nodeApi.blockAt(height).map(_.head).get
      mdSynchronizer ! BlockMessage(NodeSync.blockInfo(block))
    }
  }
}
