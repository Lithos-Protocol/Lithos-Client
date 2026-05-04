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
import plasmadex.PDHelpers
import plasmadex.states.LiquidityState
import play.api.Configuration
import play.api.cache.SyncCacheApi
import state.LFSMTransformer
import state.messages.DictionaryMessages.{InitialLiqState, InitialMDState}
import state.messages.SyncMessages._
import state.messages.{BlockInfo, BlockMessage}
import utils.Globals

import javax.inject.{Inject, Named, Singleton}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

@Singleton
class PDSyncTask @Inject()(cache: SyncCacheApi, system: ActorSystem, config: Configuration,
                           @Named("pd-synchronizer") pdSynchronizer: ActorRef, cs: CoordinatedShutdown) {

  val logger: Logger = LoggerFactory.getLogger("PDSyncTask")
  val taskConfig: TaskConfiguration = new TasksConfig(config).dexSyncTask

  val contexts: Contexts = new Contexts(system)
  val syncConfig: SyncConfig = new SyncConfig(config)
  val stratumConfig: StratumConfig = new StratumConfig(config)
  val stateConfig: StateConfig = new StateConfig(config)

  val nodeConfig: NodeConfig = Globals.getNodeConfig
  if (taskConfig.enabled) {

    logger.info("Starting PlasmaDex Dictionary synchronization")
    logger.info(s"PlasmaDex Dictionary synchronization will start at height ${PDHelpers.LP_GENESIS_HEIGHT}")

    var currentHeight = PDHelpers.LP_GENESIS_HEIGHT
    var synced = false
    val nodeDataSource = nodeConfig.getClient.getDataSource.asInstanceOf[NodeAndExplorerDataSourceImpl]
    pdSynchronizer ! InitialLiqState(PDHelpers.LP_GENESIS_HEIGHT, LiquidityState.initialState)

    Future {
      while (currentHeight <= chainHeight && !synced) {
        loadBlockSync(currentHeight, nodeDataSource) match {
          case Success(_) =>
            if (currentHeight != chainHeight) {
              currentHeight = currentHeight + 1
            } else {
              // Start listening once synced
              logger.info(s"Finished syncing to height ${chainHeight}")
              pdSynchronizer ! GetSynced
              logger.info(s"Now listening every ${syncConfig.listeningInterval} for new blocks")
              Globals.setPDSynced()

                var syncTask: Option[Cancellable] = None
                syncTask = Some(system.scheduler.scheduleWithFixedDelay(initialDelay = 10 seconds,
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
                      }(contexts.pollingContext)
                    }
                  //
                })(contexts.pollingContext))
                synced = true
              }

          case Failure(exception) => logger.error(s"Failed to load block ${currentHeight} while syncing PlasmaDex", exception)
        }
      }

    }(contexts.pollingContext)
  } else {
    logger.info("PDSync was not enabled")
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
    pdSynchronizer ! BlockMessage(BlockInfo.fromSync(block))
  }

  private def awaitBlockSync(block: FullBlock) = {
    implicit val timeout: Timeout = Timeout(7 seconds)

    Await.result((pdSynchronizer ? BlockMessage(BlockInfo.fromSync(block))).mapTo[HandledBlock], 7 seconds)
  }
}
