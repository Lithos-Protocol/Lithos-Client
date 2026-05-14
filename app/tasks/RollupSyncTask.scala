package tasks

import akka.actor.{ActorRef, ActorSystem, CoordinatedShutdown}
import akka.pattern.ask
import akka.util.Timeout
import configs.TasksConfig.TaskConfiguration
import configs.{Contexts, NodeConfig, SyncConfig, TasksConfig}
import org.ergoplatform.appkit.impl.NodeAndExplorerDataSourceImpl
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import play.api.cache.SyncCacheApi
import state.messages.StateFrameMessages._
import state.messages.SyncMessages._
import state.messages.{BlockInfo, BlockMessage}
import utils.Globals

import javax.inject.{Inject, Named, Singleton}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

@Singleton
class RollupSyncTask @Inject()(cache: SyncCacheApi, system: ActorSystem, config: Configuration,
                               @Named("sync-handler") syncHandler: ActorRef,
                               @Named("state-frame") stateFrame:   ActorRef,
                               cs: CoordinatedShutdown) {

  val logger: Logger = LoggerFactory.getLogger("RollupSyncTask")
  val taskConfig: TaskConfiguration = new TasksConfig(config).rollupSyncTask

  val contexts: Contexts     = new Contexts(system)
  val syncConfig: SyncConfig = new SyncConfig(config)
  val nodeConfig: NodeConfig = Globals.getNodeConfig

  if (taskConfig.enabled) {
    logger.info("Starting rollup synchronization")
    logger.info(s"Rollup synchronization will start at height ${syncConfig.startHeight}")

    var currentHeight = syncConfig.startHeight
    val nodeDataSource = nodeConfig.getClient.getDataSource.asInstanceOf[NodeAndExplorerDataSourceImpl]

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
              logger.error(s"Failed to load block $currentHeight while syncing rollups", ex)
          }
        } else {
          synced = true
        }
      }

      logger.info(s"Finished initial rollup sync to height $currentHeight")
      syncHandler ! CompletedInitSync
      Globals.setSynced()

      // Phase 2: handoff to StateFrame — keep loading blocks and retrying until accepted
      logger.info("Attempting rollup subscription to StateFrame")
      var subscribed = false
      while (!subscribed) {
        implicit val timeout: Timeout = Timeout(10 seconds)
        Await.result((stateFrame ? Subscribe(currentHeight, syncHandler)).mapTo[SubscribeResponse], 10 seconds) match {
          case SubscribeAck =>
            logger.info(s"SyncHandler subscribed to StateFrame at height $currentHeight")
            subscribed = true
          case SubscribeRejected(reason) =>
            logger.warn(s"StateFrame rejected rollup subscription: $reason — catching up one block")
            loadBlockSync(currentHeight + 1, nodeDataSource) match {
              case Success(_) => currentHeight += 1
              case Failure(ex) =>
                logger.error(s"Failed to load block ${currentHeight + 1} during subscription handoff", ex)
                Thread.sleep(2000)
            }
        }
      }

    }(contexts.pollingContext)
  } else {
    logger.info("Block Polling was not enabled")
  }

  private def chainHeight: Int =
    nodeConfig.getClient.execute(ctx => ctx.getHeight)

  private def loadBlockSync(height: Int, dataSource: NodeAndExplorerDataSourceImpl): Try[Unit] = {
    if (height % 100 == 0)
      logger.info(s"Loading rollup block at height $height")
    Try {
      val blockHeader = dataSource.getNodeBlocksApi.getFullBlockAt(height).execute().body().get(0)
      val fullBlock   = dataSource.getNodeBlocksApi.getFullBlockById(blockHeader).execute().body()
      syncHandler ! BlockMessage(BlockInfo.fromSync(fullBlock))
    }
  }
}
