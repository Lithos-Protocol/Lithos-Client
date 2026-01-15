package tasks

import actors.SyncHandler
import akka.Done
import akka.actor.{ActorRef, ActorSystem, Cancellable, CoordinatedShutdown}
import akka.pattern.{AskTimeoutException, ask}
import akka.util.Timeout
import configs.TasksConfig.TaskConfiguration
import configs.{Contexts, NodeConfig, StateConfig, StratumConfig, SyncConfig, TasksConfig}
import lfsm.LFSMHelpers
import lfsm.states.NISPTree
import org.ergoplatform.appkit.impl.NodeAndExplorerDataSourceImpl
import org.ergoplatform.restapi.client.FullBlock
import org.ergoplatform.sdk.BlockchainContext
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import play.api.cache.{AsyncCacheApi, SyncCacheApi}
import state.messages.{BlockInfo, BlockMessage}
import state.messages.SyncMessages.{FullSync, GetSynced, HandledBlock, NoRollups, PartialSync, SyncMessage}
import state.LFSMTransformer
import stratum.ErgoStratumServer
import stratum.data.{Data, Options}
import utils.Globals

import javax.inject.{Inject, Named, Singleton}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future, TimeoutException}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

@Singleton
class RollupSyncTask @Inject()(cache: SyncCacheApi, system: ActorSystem, config: Configuration,
                               @Named("sync-handler") syncHandler: ActorRef, cs: CoordinatedShutdown) {

  val logger: Logger = LoggerFactory.getLogger("RollupSyncTask")
  val taskConfig: TaskConfiguration = new TasksConfig(config).rollupSyncTask

  val contexts: Contexts = new Contexts(system)
  val syncConfig: SyncConfig = new SyncConfig(config)
  val stratumConfig: StratumConfig = new StratumConfig(config)
  val stateConfig: StateConfig     = new StateConfig(config)

  val nodeConfig: NodeConfig = Globals.getNodeConfig
  if(taskConfig.enabled) {
    logger.info("Starting rollup synchronization")
    logger.info(s"Rollup synchronization will start at height ${syncConfig.startHeight}")

    var polling: Option[Cancellable] = None
    var currentHeight = syncConfig.startHeight
    var synced = false
    val nodeDataSource = nodeConfig.getClient.getDataSource.asInstanceOf[NodeAndExplorerDataSourceImpl]


    //logger.info(s"Polling block at height ${currentHeight} for synchronization")
    // Blocking code until synced
    Future{
      while(currentHeight <= chainHeight && !synced) {
        loadBlockSync(currentHeight, nodeDataSource) match {
          case Success(value) =>
            if (currentHeight != chainHeight) {
              currentHeight = currentHeight + 1
            } else {
              // Start listening once synced
              logger.info(s"Finished syncing to height ${chainHeight}")
              syncHandler ! GetSynced
              logger.info(s"Now listening every ${syncConfig.listeningInterval} for new blocks")
              Globals.setSynced()
              system.scheduler.scheduleWithFixedDelay(initialDelay = 10 seconds,
                delay = syncConfig.listeningInterval)({
                () =>
                  // Blocking code on synchronization
                  if (currentHeight <= chainHeight) {
                    logger.info(s"Found new block ${currentHeight} on listen")
                    loadBlockAsync(currentHeight, nodeDataSource).onComplete {
                      case Failure(exception) =>
                        logger.error(s"Failed to load block ${currentHeight} while listening", exception)
                      case Success(block) =>
                        logger.info(s"Successfully pre-applied block ${block.blockInfo.height}")
                        currentHeight = currentHeight + 1
                        if(currentHeight > chainHeight) {
                          if(!stateConfig.disableTransforms.getOrElse(false)) {
                            Try {
                              implicit val timeout = Timeout(5 seconds)
                              val msg = Await.result((syncHandler ? GetSynced).mapTo[SyncMessage], 5 seconds)
                              msg match {
                                case FullSync(nispTrees) =>
                                  logger.info(s"Got FullSync() state with ${nispTrees.size} rollups")

                                  LFSMTransformer.onSync(nodeConfig.getClient, cache,
                                    nodeConfig.getNodeWallet, stratumConfig.diff, nispTrees, syncHandler, stateConfig.autoCommit.getOrElse(true))
                                case PartialSync(syncedTrees, unsyncedIds) =>
                                  logger.info(s"Got PartialSync() state with ${syncedTrees.size} synced rollups" +
                                    s" and ${unsyncedIds.size} unsynced rollups")

                                  LFSMTransformer.onSync(nodeConfig.getClient, cache,
                                    nodeConfig.getNodeWallet, stratumConfig.diff, syncedTrees, syncHandler, stateConfig.autoCommit.getOrElse(true))
                                case NoRollups() =>
                                  logger.info(s"Got NoRollups() state with 0 rollups")
                              }

                            }.recoverWith {
                              case timeout: TimeoutException =>
                                logger.warn("Skipping transforms due to no GetSynced response")
                                Failure(timeout)
                              case t: Throwable =>
                                logger.error(s"Got error during transformations: ${t.getMessage} \n", t)
                                Failure(t)
                            }
                          }
                        }
                    }(contexts.pollingContext)
                  }
//
              })(contexts.pollingContext)
              synced = true
            }
          case Failure(exception) => logger.error(s"Failed to load block ${currentHeight} while syncing", exception)
        }
      }
      //Thread.sleep(taskConfig.interval.toMillis)
    }(contexts.pollingContext)

  }else{
    logger.info("Block Polling was not enabled")
  }
  private def chainHeight: Int = {
    nodeConfig.getClient.execute{
      ctx => ctx.getHeight
    }
  }
  private def loadBlockSync(height: Int, dataSource: NodeAndExplorerDataSourceImpl): Try[Unit] = {
    if(height % 100 == 0)
      logger.info(s"Loading block at height ${height}")
    Try {

      val blockHeader = dataSource
        .getNodeBlocksApi.getFullBlockAt(height)
        .execute()
        .body().get(0)

      val fullBlock = dataSource
        .getNodeBlocksApi.getFullBlockById(blockHeader)
        .execute()
        .body()
      checkBlockTransactions(fullBlock)
    }

  }

  private def loadBlockAsync(height: Int, dataSource: NodeAndExplorerDataSourceImpl) = {
    implicit val context: ExecutionContext = contexts.syncContext
    Future {
      if (height % 100 == 0)
        logger.info(s"Loading block at height ${height}")
      Try {

        val blockHeader = dataSource
          .getNodeBlocksApi.getFullBlockAt(height)
          .execute()
          .body().get(0)

        val fullBlock = dataSource
          .getNodeBlocksApi.getFullBlockById(blockHeader)
          .execute()
          .body()
        awaitBlockSync(fullBlock)
      }
    }.map(_.get)

  }

  private def checkBlockTransactions(block: FullBlock): Unit = {
    syncHandler ! BlockMessage(BlockInfo.fromSync(block))
  }

  private def awaitBlockSync(block: FullBlock) = {
    implicit val timeout: Timeout = Timeout(7 seconds)

    Await.result((syncHandler ? BlockMessage(BlockInfo.fromSync(block))).mapTo[HandledBlock], 7 seconds)
  }
}
