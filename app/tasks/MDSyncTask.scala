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
import state.messages.StateFrameMessages.Attach
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
                           @Named("state-frame") stateFrame: ActorRef,
                           cs: CoordinatedShutdown) {

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
                // Initial sync complete — hand block delivery over to StateFrame.
                logger.info(s"Finished syncing to height ${chainHeight}")
                mdSynchronizer ! GetSynced
                stateFrame ! Attach(mdSynchronizer)
                logger.info(s"MDSynchronizer attached to StateFrame, listening every ${syncConfig.listeningInterval}")
                Globals.setSynced()
                if (Globals.mdDB.getDataBoxToken.isEmpty) {
                  logger.info(s"Found no data box token during sync, will continue polling" +
                    s" until data box is created")
                  // StateFrame now delivers BlockMessages to MDSynchronizer.
                  // This schedule only handles addToMD transforms until the data box token appears.
                  var syncTask: Option[Cancellable] = None
                  syncTask = Some(system.scheduler.scheduleWithFixedDelay(initialDelay = 10 seconds,
                    delay = syncConfig.listeningInterval)({
                    () =>
                      if (Globals.mdDB.getDataBoxToken.isDefined) {
                        logger.info(s"Ending sync task due to finding data box token ${Globals.mdDB.getDataBoxToken.get}")
                        syncTask.get.cancel()
                      } else if (!stateConfig.disableTransforms.getOrElse(false)) {
                        Try {
                          LFSMTransformer.addToMD(nodeConfig.getClient, cache, nodeConfig.getNodeWallet, stratumConfig.diff)
                        }.recoverWith {
                          case t: Throwable =>
                            logger.error(s"Got error during sync ${t.getMessage}", t)
                            Failure(t)
                        }
                      }
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

  private def checkBlockTransactions(block: FullBlock): Unit = {
    mdSynchronizer ! BlockMessage(BlockInfo.fromSync(block))
  }
}
