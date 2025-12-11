package tasks

import akka.Done
import akka.actor.{ActorSystem, Cancellable, CoordinatedShutdown}
import configs.TasksConfig.TaskConfiguration
import configs.{Contexts, NodeConfig, StateConfig, StratumConfig, SyncConfig, TasksConfig}
import lfsm.LFSMHelpers
import org.ergoplatform.appkit.impl.NodeAndExplorerDataSourceImpl
import org.ergoplatform.restapi.client.FullBlock
import org.ergoplatform.sdk.BlockchainContext
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import play.api.cache.{AsyncCacheApi, SyncCacheApi}
import state.{LFSMSync, LFSMTransformer}
import stratum.ErgoStratumServer
import stratum.data.{Data, Options}
import utils.NISPTreeCache

import javax.inject.{Inject, Singleton}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

@Singleton
class BlockPolling @Inject()(cache: SyncCacheApi, system: ActorSystem, config: Configuration, cs: CoordinatedShutdown) {

  val logger: Logger = LoggerFactory.getLogger("BlockPolling")
  val taskConfig: TaskConfiguration = new TasksConfig(config).blockPolling

  val contexts: Contexts = new Contexts(system)
  val syncConfig: SyncConfig = new SyncConfig(config)
  val nodeConfig: NodeConfig = new NodeConfig(config)
  val stratumConfig: StratumConfig = new StratumConfig(config)
  val stateConfig: StateConfig     = new StateConfig(config)
  if(taskConfig.enabled) {
    logger.info("Starting synchronization via block polling")
    logger.info(s"Synchronization will start at height ${syncConfig.startHeight}")

    var polling: Option[Cancellable] = None
    var currentHeight = syncConfig.startHeight
    var synced = false
    val nodeDataSource = nodeConfig.getClient.getDataSource.asInstanceOf[NodeAndExplorerDataSourceImpl]

    cache.set(NISPTreeCache.TREE_SET, Seq.empty[String])
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
              logger.info(s"Now listening every ${syncConfig.listeningInterval} for new blocks")
              LFSMSync.synced = true
              system.scheduler.scheduleWithFixedDelay(initialDelay = 10 seconds,
                delay = syncConfig.listeningInterval)({
                () =>
                  if (currentHeight <= chainHeight) {
                    logger.info(s"Found new block ${currentHeight} on listen")
                    loadBlockAsync(currentHeight, nodeDataSource).onComplete {
                      case Failure(exception) =>
                        logger.error(s"Failed to load block ${currentHeight} while listening", exception)
                      case Success(value) =>
                        currentHeight = currentHeight + 1
                        if(currentHeight > chainHeight)
                          LFSMTransformer.onSync(nodeConfig.getClient, cache, nodeConfig.prover, stratumConfig.diff)
                    }(contexts.pollingContext)
                  }

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
    implicit val context: ExecutionContext = contexts.pollingContext
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
        checkBlockTransactions(fullBlock)
      }
    }.map(_.get)

  }

  private def checkBlockTransactions(block: FullBlock): Unit = {
    LFSMSync.searchHoldingContracts(block, nodeConfig.getClient, cache, nodeConfig.prover)
    LFSMSync.searchEvalContracts(block, nodeConfig.getClient, cache, nodeConfig.prover)
    LFSMSync.searchPayoutContracts(block, nodeConfig.getClient, cache, nodeConfig.prover)
  }
}
