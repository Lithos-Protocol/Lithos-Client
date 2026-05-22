package tasks

import akka.actor.{ActorRef, ActorSystem, Cancellable, CoordinatedShutdown}
import akka.pattern.ask
import akka.util.Timeout
import configs.TasksConfig.TaskConfiguration
import configs._
import lfsm.LFSMHelpers
import lfsm.states.MinerTree
import org.ergoplatform.appkit.impl.NodeAndExplorerDataSourceImpl
import org.ergoplatform.restapi.client.FullBlock
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import play.api.cache.SyncCacheApi
import state.LFSMTransformer
import state.Subscribable.{Subscribe, SubscribeAck, SubscribeRejected, SubscribeResponse}
import state.messages.DictionaryMessages.InitialMDState
import state.messages.StateFrameMessages._
import state.messages.SyncMessages._
import state.messages.{BlockInfo, BlockMessage}
import utils.Globals

import javax.inject.{Inject, Named, Singleton}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

@Singleton
class MDSyncTask @Inject()(cache: SyncCacheApi, system: ActorSystem, config: Configuration,
                           @Named("md-synchronizer") mdSynchronizer: ActorRef,
                           @Named("sync-handler")    syncHandler:       ActorRef,
                           cs: CoordinatedShutdown) {

  val logger: Logger = LoggerFactory.getLogger("MDSyncTask")
  val taskConfig: TaskConfiguration = new TasksConfig(config).dictionarySyncTask

  val contexts: Contexts       = new Contexts(system)
  val syncConfig: SyncConfig   = new SyncConfig(config)
  val stratumConfig: StratumConfig = new StratumConfig(config)
  val stateConfig: StateConfig = new StateConfig(config)
  val nodeConfig: NodeConfig   = Globals.getNodeConfig

  if (taskConfig.enabled) {

    val dataNFT = Globals.mdDB.getDataBoxToken
    if (dataNFT.isEmpty) {
      logger.info("Starting Miner Dictionary synchronization")
      logger.info(s"Dictionary synchronization will start at height ${LFSMHelpers.MD_GENESIS_HEIGHT}")

      var currentHeight = LFSMHelpers.MD_GENESIS_HEIGHT
      val nodeDataSource = nodeConfig.getClient.getDataSource.asInstanceOf[NodeAndExplorerDataSourceImpl]
      mdSynchronizer ! InitialMDState(LFSMHelpers.MD_GENESIS_HEIGHT, MinerTree.initialState)

      Future {
        // Phase 1: catch up to chain head
        var synced = false
        while (!synced) {
          val tip = chainHeight
          if (currentHeight <= tip) {
            loadBlockSync(currentHeight, nodeDataSource) match {
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

          val mdToken = LFSMHelpers.getMDToken(nodeConfig.getClient).toString

          Await.result((syncHandler ? Subscribe(currentHeight, mdSynchronizer, Some(mdToken))).mapTo[SubscribeResponse], 10 seconds) match {
            case SubscribeAck =>
              logger.info(s"MDSynchronizer subscribed to SyncHandler at height $currentHeight")
              mdSynchronizer ! CompletedInitSync
              subscribed = true
            case SubscribeRejected(reason) =>
              logger.warn(s"SyncHandler rejected subscription: $reason — catching up one block")
              loadBlockSync(currentHeight + 1, nodeDataSource) match {
                case Success(_) => currentHeight += 1
                case Failure(ex) =>
                  logger.error(s"Failed to load block ${currentHeight + 1} during subscription handoff", ex)
                  Thread.sleep(5000)
              }
          }
        }

        // Phase 3: if data box still not created, run a separate addToMD poller
        if (Globals.mdDB.getDataBoxToken.isEmpty && !stateConfig.disableTransforms.getOrElse(false)) {
          logger.info("Data box token not yet created — starting addToMD polling")
          var addToMDTask: Option[Cancellable] = None
          addToMDTask = Some(system.scheduler.scheduleWithFixedDelay(
            initialDelay = 10 seconds,
            delay        = syncConfig.listeningInterval)({
            () =>
              if (Globals.mdDB.getDataBoxToken.isDefined) {
                logger.info(s"Data box token found (${Globals.mdDB.getDataBoxToken.get}), stopping addToMD poller")
                addToMDTask.foreach(_.cancel())
              } else {
                Try {
                  LFSMTransformer.addToMD(nodeConfig.getClient, cache, nodeConfig.getNodeWallet, stratumConfig.diff)
                }.failed.foreach { ex =>
                  logger.error(s"Error during addToMD: ${ex.getMessage}", ex)
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
    nodeConfig.getClient.execute(ctx => ctx.getHeight)

  private def loadBlockSync(height: Int, dataSource: NodeAndExplorerDataSourceImpl): Try[Unit] = {
    if (height % 10000 == 0)
      logger.info(s"Loading block at height $height")
    Try {
      val blockHeader = dataSource.getNodeBlocksApi.getFullBlockAt(height).execute().body().get(0)
      val fullBlock   = dataSource.getNodeBlocksApi.getFullBlockById(blockHeader).execute().body()
      mdSynchronizer ! BlockMessage(BlockInfo.fromSync(fullBlock))
    }
  }
}
