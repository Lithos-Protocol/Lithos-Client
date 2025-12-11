package actors

import actors.SyncCoordinator.{GenerateTree, HandleGenesis}
import akka.actor.{Actor, ActorRef}
import akka.pattern.ask
import akka.util.Timeout
import configs.NodeConfig
import lfsm.{LFSMPhase, NISPTree}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{ErgoClient, ErgoProver, ErgoValue}
import org.ergoplatform.restapi.client.FullBlock
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import play.api.cache.SyncCacheApi
import play.api.libs.concurrent.InjectedActorSupport
import sigma.data.AvlTreeFlags
import state.BlockInfo
import state.LFSMSync.logger
import utils.Helpers.holdingContract
import utils.{Helpers, NISPTreeCache}
import work.lithos.plasma.PlasmaParameters
import work.lithos.plasma.collections.PlasmaMap

import javax.inject.Inject
import scala.collection.JavaConverters
import scala.concurrent.duration.{Duration, DurationInt}
import scala.language.postfixOps

class SyncCoordinator @Inject() (syncFactory: TreeSyncer.SyncFactory, config: Configuration, cache: SyncCacheApi) extends Actor with InjectedActorSupport {
  implicit val timeout: Timeout = 10 seconds
  val logger: Logger          = LoggerFactory.getLogger("SyncCoordinator")
  val nodeConfig: NodeConfig  = new NodeConfig(config)
  val client: ErgoClient      = nodeConfig.getClient
  val prover: ErgoProver      = nodeConfig.prover

  override def receive: Receive = {
    case GenerateTree(tree, msg) =>
      val child: ActorRef = injectedChild(syncFactory(tree), tree.blockId)
      logger.info(s"Created child with ${child.path.name}")
      child ! msg
      logger.info(s"Sent initial block message to child ${child.path.name}")
      sender() ! HandleGenesis(child)
    case HandleGenesis(child) =>
      logger.info(s"Handled genesis for child ${child.path.name}")
    case BlockMessage(blockInfo) =>
      val existingSyncTasks = for(syncer <- context.children) yield syncer ? BlockMessage(blockInfo)

      val genesisTxs = findGenesisTxs(blockInfo)
      val txs = genesisTxs.flatMap(g => g.toSeq)
      logger.info(s"Generating ${txs.size} new trees")
      txs.foreach(self ! GenerateTree(_, BlockMessage(blockInfo)))

  }

  private def findGenesisTxs(blockInfo: BlockInfo): Seq[Option[NISPTree]] = {
    client.execute{
      ctx =>

        val holdingTxs = blockInfo.txs.filter {
          x => x.outputs.exists(_.ergoTree == Hex.toHexString(holdingContract(ctx).propBytes))
        }
        holdingTxs.map {
          e =>
            val output = e.outputs.find {
              o => o.ergoTree == Hex.toHexString(holdingContract(ctx).propBytes)
            }.get
            val numMiners = output.registers.applyOrElse(1, "none")
            numMiners match {
              case "none" =>
                logger.warn("Found invalid holding contract, skipping it during sync process")
                None
              case ergoVal: String =>
                if (ergoVal == ErgoValue.of(0).toHex) {
                  logger.info(s"Found new holding contract in block ${blockInfo.height}")
                  val holdingBox = output.toInput(ctx)
                  val newTree    = PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.AllOperationsAllowed, PlasmaParameters.default)
                  Some(
                    NISPTree(newTree, 0, BigInt(0), Some(holdingBox.registers(3).getValue.asInstanceOf[Long]),
                    holdingBox.value, holdingBox.registers(3).getValue.asInstanceOf[Long].toInt, hasMiner = false, LFSMPhase.HOLDING,
                    blockId = blockInfo.id)
                  )

                } else {
                  logger.warn("Skipping non genesis transaction")
                  None
                }
            }
        }
    }
  }
}

object SyncCoordinator {
  case class GenerateTree(nispTree: NISPTree, msg: BlockMessage)
  case class HandleGenesis(actorRef: ActorRef)
}
