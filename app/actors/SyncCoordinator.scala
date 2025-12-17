package actors

import actors.SyncHandler.{Genesis, StopTracking, Transform}
import akka.actor.{Actor, ActorRef, Terminated}
import akka.util.Timeout
import configs.NodeConfig
import org.ergoplatform.appkit.{ErgoClient, ErgoProver}
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import play.api.cache.SyncCacheApi
import play.api.libs.concurrent.InjectedActorSupport
import utils.TreeCache

import javax.inject.Inject
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

class SyncCoordinator @Inject() (syncFactory: TreeSyncer.SyncFactory, config: Configuration, cacheApi: SyncCacheApi) extends Actor with InjectedActorSupport {
  implicit val timeout: Timeout = 10 seconds

  val logger: Logger          = LoggerFactory.getLogger("SyncCoordinator")
  val nodeConfig: NodeConfig  = new NodeConfig(config)
  val client: ErgoClient      = nodeConfig.getClient
  val prover: ErgoProver      = nodeConfig.prover
  val treeCache: TreeCache    = TreeCache(cacheApi)

  override def receive: Receive = {
    case gen: Genesis =>
      logger.info("Initialized SyncCoordinator")
      handleGenesis(gen)
      context.become(postInit(Map(gen.tree.utxoId -> gen.tree.blockId)))
      logger.info(s"Got 1 tree")
      treeCache.setTreeSet(Set(gen.tree.utxoId))
    case _ =>
      logger.info("Skipped non-genesis transaction before initialization")

  }
  // Maps utxoId -> blockId to keep track of children
  private def postInit(trees: Map[String, String]): Receive = {
    case gen: Genesis =>
      handleGenesis(gen)
      logger.info(s"Got ${trees.size + 1} trees")
      treeCache.addToTreeSet(gen.tree.utxoId)
      context.become(postInit(trees + (gen.tree.utxoId -> gen.tree.blockId)))
    case transform: Transform =>
      //logger.info(s"Got transform with id ${transform.tx.id}")
      val optBlockId = trees.get(transform.input.id)
      optBlockId match {
        case Some(treeBlockId) =>
          handleTransform(transform, treeBlockId)
          context.become(postInit(trees - transform.input.id + (transform.output.id -> treeBlockId)))
          logger.info(s"Pre-applied transform ${transform.tx.id} for NISPTree ${treeBlockId.slice(0, 12)} at ${transform.blockInfo.height}")
          treeCache.removeFromTreeSet(transform.input.id)
          treeCache.addToTreeSet(transform.output.id)
        case None =>
          logger.info(s"Skipping transform ${transform.tx.id} with no genesis history")
      }
    case stopTracking: StopTracking =>
      val removedTree = trees.find(_._2 == stopTracking.blockId)
      require(removedTree.isDefined, s"Map must contain actor name ${stopTracking.blockId} to remove tracking")
      context.become(postInit(trees - removedTree.get._1))
      logger.info(s"Removed NISPTree ${stopTracking.blockId}")
      treeCache.removeFromTreeSet(removedTree.get._1)
  }

  private def handleGenesis(genesis: Genesis): Unit = {
    client.execute{
      ctx =>
        //logger.info("Got generate tree message")
        val child: ActorRef = injectedChild(syncFactory(genesis.tree.blockId, ctx, prover), genesis.tree.blockId)
        //logger.info(s"Created child ${child.path.name}")
        child ! genesis
      //logger.info(s"Sent initial block message to child ${child.path.name}")
    }

  }

  private def handleTransform(transform: Transform, treeBlockId: String): Unit = {
    val optChild = context.child(treeBlockId)
    optChild match {
      case Some(child) =>
        child ! transform
      case None =>
        logger.warn(s"Could not apply transform ${transform.tx.id} to non-existent NISPTree ${transform.blockInfo.id}")
    }
  }

}

object SyncCoordinator {

  case class HandleGenesis(actorRef: ActorRef)
}
