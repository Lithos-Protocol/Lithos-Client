package tasks

import akka.actor.{ActorRef, ActorSystem, Cancellable, CoordinatedShutdown}
import akka.pattern.ask
import akka.util.Timeout
import configs.TasksConfig.TaskConfiguration
import configs._
import lfsm.LFSMHelpers
import lfsm.contracts.DictionaryContracts
import lfsm.states.MinerTree
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{ErgoId, ErgoToken, JavaHelpers, Parameters}
import org.ergoplatform.appkit.impl.NodeAndExplorerDataSourceImpl
import org.ergoplatform.restapi.client.FullBlock
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import play.api.cache.SyncCacheApi
import state.LFSMTransformer
import state.LFSMTransformer.logger
import state.messages.DictionaryMessages.InitialState
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
                           @Named("md-synchronizer") mdSynchronizer: ActorRef, cs: CoordinatedShutdown) {

  val logger: Logger = LoggerFactory.getLogger("MDSyncTask")
  val taskConfig: TaskConfiguration = new TasksConfig(config).dictionarySyncTask

  val contexts: Contexts = new Contexts(system)
  val syncConfig: SyncConfig = new SyncConfig(config)
  val stratumConfig: StratumConfig = new StratumConfig(config)
  val stateConfig: StateConfig = new StateConfig(config)

  val nodeConfig: NodeConfig = Globals.getNodeConfig
  if (taskConfig.enabled) {

    val dataNFT = Globals.mdDB.getDataBoxToken
    if (dataNFT.isEmpty) {
      logger.info("Starting Miner Dictionary synchronization")
      logger.info(s"Dictionary synchronization will start at height ${LFSMHelpers.MD_GENESIS_HEIGHT}")

      var currentHeight = LFSMHelpers.MD_GENESIS_HEIGHT
      var synced = false
      val nodeDataSource = nodeConfig.getClient.getDataSource.asInstanceOf[NodeAndExplorerDataSourceImpl]
      mdSynchronizer ! InitialState(LFSMHelpers.MD_GENESIS_HEIGHT, MinerTree.initialState)

      Future {
        while (currentHeight <= chainHeight && !synced) {
          loadBlockSync(currentHeight, nodeDataSource) match {
            case Success(_) =>
              if (currentHeight != chainHeight) {
                currentHeight = currentHeight + 1
              } else {
                // Start listening once synced
                logger.info(s"Finished syncing to height ${chainHeight}")
                mdSynchronizer ! GetSynced
                logger.info(s"Now listening every ${syncConfig.listeningInterval} for new blocks")
                Globals.setSynced()
                if (Globals.mdDB.getDataBoxToken.isEmpty) {
                  logger.info(s"Found no data box token during sync, will continue polling" +
                    s" blocks every ${syncConfig.listeningInterval} until data box is created")
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
                            if (Globals.mdDB.getDataBoxToken.isDefined) {
                              // Cancel sync task if data box token is found
                              logger.info(s"Ending sync task due to finding data box token ${Globals.mdDB.getDataBoxToken.get}")
                              syncTask.get.cancel()
                            }else if (currentHeight > chainHeight) {
                              if (!stateConfig.disableTransforms.getOrElse(false)) {
                                Try {
                                  implicit val timeout = Timeout(5 seconds)
                                  LFSMTransformer.addToMD(nodeConfig.getClient, cache, nodeConfig.getNodeWallet, stratumConfig.diff)
                                }.recoverWith {
                                  case t: Throwable =>
                                    logger.error(s"Got error during sync ${t.getMessage}", t)
                                    Failure(t)
                                }
                              }
                            }
                        }(contexts.pollingContext)
                      }
                    //
                  })(contexts.pollingContext))
                } else {
                  logger.info(s"Got data box token ${Globals.mdDB.getDataBoxToken.get} during synchronization")
                }
                synced = true
              }
            case Failure(exception) => logger.error(s"Failed to load block ${currentHeight} while syncing", exception)
          }
        }

      }(contexts.pollingContext)
    } else {
      logger.info(s"Found saved DataBox token ${dataNFT.get}, not syncing MD")
    }
  } else {
    logger.info("MDSync was not enabled")
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
    mdSynchronizer ! BlockMessage(BlockInfo.fromSync(block))
  }

  private def awaitBlockSync(block: FullBlock) = {
    implicit val timeout: Timeout = Timeout(7 seconds)

    Await.result((mdSynchronizer ? BlockMessage(BlockInfo.fromSync(block))).mapTo[HandledBlock], 7 seconds)
  }
}
