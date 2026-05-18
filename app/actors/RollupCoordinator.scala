package actors


import akka.actor.{Actor, ActorRef}
import akka.util.Timeout
import cache.{MempoolCache, RollupCache}
import configs.NodeConfig
import mutations.NodeWallet
import org.ergoplatform.appkit.ErgoClient
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import play.api.cache.SyncCacheApi
import play.api.libs.concurrent.InjectedActorSupport
import state.AutoSubscribable.AutoSubscribe
import state.messages.MempoolMessages.{MempoolChain, RebuildMempoolChains, ResetMempoolState, UpdatedMempoolChains}
import state.messages.RollupMessages._
import state.messages.SyncMessages._
import utils.Globals

import javax.inject.{Inject, Named}
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

class RollupCoordinator @Inject()(syncFactory: RollupSynchronizer.RollupSyncFactory, config: Configuration,
                                  cacheApi: SyncCacheApi, @Named("mempool-view") mempoolView: ActorRef) extends Actor with InjectedActorSupport {
  implicit val timeout: Timeout = 10 seconds

  val logger: Logger          = LoggerFactory.getLogger("RollupCoordinator")
  val nodeConfig: NodeConfig  = Globals.getNodeConfig
  val client: ErgoClient      = nodeConfig.getClient
  val prover: NodeWallet      = nodeConfig.getNodeWallet
  val rollupCache: RollupCache    = RollupCache(cacheApi)

  override def receive: Receive = {
    case gen: Genesis =>
      logger.info("Initialized RollupCoordinator")
      handleGenesis(gen)
      context.become(postInit(Map(gen.tree.utxoId -> gen.tree.blockId)))
      logger.info(s"Got 1 rollup")
      rollupCache.setTreeSet(Set(gen.tree.utxoId))
    case GetSynced =>
      sender() ! NoRollups()
    case _ =>
      logger.info("Skipped non-genesis transaction before initialization")

  }
  // Maps utxoId -> blockId to keep track of children
  private def postInit(trees: Map[String, String]): Receive = {
    case gen: Genesis =>
      if(!trees.values.toSet.contains(gen.blockInfo.id)) {
        handleGenesis(gen)
        logger.info(s"Got ${trees.size + 1} rollups")
        rollupCache.addToTreeSet(gen.tree.utxoId)
        context.become(postInit(trees + (gen.tree.utxoId -> gen.tree.blockId)))
      }else{
        logger.warn(s"Skipping genesis for ${gen.blockInfo.id} at height ${gen.blockInfo.height} due to duplicate trees")
      }
    case transform: RollupTransform =>
      val optBlockId = trees.get(transform.input.id)
      optBlockId match {
        case Some(treeBlockId) =>
          handleTransform(transform, treeBlockId)
          context.become(postInit(trees - transform.input.id + (transform.output.id -> treeBlockId)))
          // Any messages routed to the synchronizer after pre-application are guaranteed to have
          // access to the correct post-transform state
          logger.info(s"Pre-applied transform ${transform.tx.id} for rollup ${treeBlockId.slice(0, 12)} at ${transform.blockInfo.height}")
          rollupCache.removeFromTreeSet(transform.input.id)
          rollupCache.addToTreeSet(transform.output.id)
        case None =>
          logger.info(s"Skipping transform ${transform.tx.id} with no genesis history")
      }
      // Stop Tracking should come only from children
    case stopTracking: StopTracking =>
      val removedTree = trees.find(_._2 == stopTracking.blockId)
      require(removedTree.isDefined, s"Map must contain actor name ${stopTracking.blockId} to remove tracking")
      context.become(postInit(trees - removedTree.get._1))
      logger.info(s"Removed Rollup ${stopTracking.blockId}")
      rollupCache.removeFromTreeSet(removedTree.get._1)
      // Remove Rollup should come from syncHandler, which is interfacing with tasks and other external actors
    case removeTree: RemoveRollup =>
      val tree = trees.find(_._2 == removeTree.blockId)
      require(tree.isDefined, s"Map must contain actor name ${removeTree.blockId} to remove tracking")
      context.become(postInit(trees - tree.get._1))
      logger.info(s"Removed rollup ${removeTree.blockId}")
      rollupCache.removeFromTreeSet(tree.get._1)
      rollupCache.remove(tree.get._1)
    case CompletedInitSync =>
      logger.info("Enabling mempool states for rollup coordinator")
      mempoolView ! AutoSubscribe(self)
      context.become(postSync(trees))
  }

  private def postSync(trees: Map[String, String]): Receive = {
    case gen: Genesis =>
      if(!trees.values.toSet.contains(gen.blockInfo.id)) {
        handleGenesis(gen)
        logger.info(s"Got ${trees.size + 1} trees")
        rollupCache.addToTreeSet(gen.tree.utxoId)
        context.become(postSync(trees + (gen.tree.utxoId -> gen.tree.blockId)))
      }else{
        logger.warn(s"Skipping genesis for ${gen.blockInfo.id} at height ${gen.blockInfo.height} due to duplicate trees")
      }
    case transform: RollupTransform =>
      val optBlockId = trees.get(transform.input.id)
      optBlockId match {
        case Some(treeBlockId) =>
          handleTransform(transform, treeBlockId)
          context.become(postSync(
            trees - transform.input.id + (transform.output.id -> treeBlockId)
          ))
          logger.info(s"Pre-applied transform ${transform.tx.id} for rollup ${treeBlockId.slice(0, 12)} at ${transform.blockInfo.height}")
          rollupCache.removeFromTreeSet(transform.input.id)
          rollupCache.addToTreeSet(transform.output.id)
          // We can reset mempool state on transform pre-application, this will help avoid issues if tx which makes it onto the block
          // is not the one that was stored in mempool state. Mempool states will be forcefully updated anyway after
          // all transforms are pre-applied
          if(rollupCache.getMempoolState(treeBlockId).isDefined)
            rollupCache.removeMempoolState(treeBlockId)
        case None =>
          logger.info(s"Skipping transform ${transform.tx.id} with no genesis history")
          context.become(postSync(trees))
      }
    case UpdatedMempoolChains =>
      //logger.info("Sending updated mempool chains to rollup synchronizers")
      val mempoolCache = MempoolCache(cacheApi)
      val relevantChains = trees.map(t => t._2 -> mempoolCache.getMempoolChain(t._1))
      relevantChains.foreach{
        rc =>
          if(rc._2.isDefined)
            sendMempoolChain(rc._2.get, rc._1)
      }
    case RebuildMempoolChains =>
      mempoolView ! RebuildMempoolChains
    case ResetMempoolState(blockId) =>
      val resetTree = trees.find(_._2 == blockId)
      if(resetTree.isDefined) {
        logger.info(s"Manually resetting mempool state for rollup $blockId")
        rollupCache.removeMempoolState(blockId)

        context.become(postSync(trees))
      }

    // Stop Tracking should come only from children
    case stopTracking: StopTracking =>
      val removedTree = trees.find(_._2 == stopTracking.blockId)
      require(removedTree.isDefined, s"Map must contain actor name ${stopTracking.blockId} to remove tracking")
      context.become(postSync(trees - removedTree.get._1))
      logger.info(s"Removed Rollup ${stopTracking.blockId}")

      rollupCache.removeFromTreeSet(removedTree.get._1)
      if(rollupCache.getMempoolState(stopTracking.blockId).isDefined)
        rollupCache.removeMempoolState(stopTracking.blockId)
    // Remove Rollup should come from syncHandler, which is interfacing with tasks and other external actors
    case removeRollup: RemoveRollup =>
      val tree = trees.find(_._2 == removeRollup.blockId)
      require(tree.isDefined, s"Map must contain actor name ${removeRollup.blockId} to remove tracking")
      context.become(postSync(trees - tree.get._1))

      rollupCache.removeFromTreeSet(tree.get._1)
      rollupCache.remove(tree.get._1)
      if(rollupCache.getMempoolState(removeRollup.blockId).isDefined)
        rollupCache.removeMempoolState(removeRollup.blockId)
    case GetSynced =>
      if(trees.keys.nonEmpty) {
        val syncedTrees = (for (k: String <- trees.keys.toSet) yield rollupCache.get(k).map(t => k -> t).toSeq).flatten
        if (syncedTrees.size == trees.keys.size)
          sender() ! FullSync(syncedTrees.toSeq)
        else
          sender() ! PartialSync(syncedTrees.toSeq, (trees.keys.toSet -- syncedTrees.map(_._1)).toSeq)

      }else{
        sender() ! NoRollups()
      }
    case GetCurrentRollup(blockId) =>
      // TODO: Maybe forward to rollup synchronizer for slightly better guarantee that mempool state is accurate
      val rollupState = trees.find(_._2 == blockId)
      rollupState match {
        case Some(rollupKV) =>
          val mempoolRollupState = rollupCache.getMempoolState(blockId)
          sender() ! CurrentRollup(rollupKV._1, rollupCache.get(rollupKV._1).get, mempoolRollupState)
        case None =>
          sender() ! NoRollupFound()
      }
    case updateEvaluation: UpdateEvaluation =>
      sendEvalUpdate(updateEvaluation)
  }

  private def handleGenesis(genesis: Genesis): Unit = {
    client.execute{
      ctx =>
        val child: ActorRef = injectedChild(syncFactory(genesis.tree.blockId, ctx, prover), genesis.tree.blockId)
        child ! genesis
    }

  }

  private def handleTransform(transform: RollupTransform, treeBlockId: String): Unit = {
    val optChild = context.child(treeBlockId)
    optChild match {
      case Some(child) =>
        child ! transform
      case None =>
        logger.warn(s"Could not apply transform ${transform.tx.id} to non-existent rollup ${treeBlockId}")
    }
  }

  private def sendMempoolChain(chain: MempoolChain, treeBlockId: String): Unit = {
    val optChild = context.child(treeBlockId)
    optChild match {
      case Some(child) =>
        child ! chain
      case None =>
        logger.warn(s"Could not send mempool chain to non-existent rollup ${treeBlockId}")
    }
  }

  private def sendEvalUpdate(updateEvaluation: UpdateEvaluation): Unit = {
    val optChild = context.child(updateEvaluation.blockId)
    optChild match {
      case Some(child) =>
        child ! updateEvaluation
      case None =>
        logger.warn(s"Could not send evaluation update to non-existent rollup ${updateEvaluation.blockId}")
    }
  }

}

object RollupCoordinator {

}
