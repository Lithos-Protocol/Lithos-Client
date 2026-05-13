package actors


import akka.actor.{Actor, ActorRef, Terminated}
import akka.util.Timeout
import cache.RollupCache
import configs.NodeConfig
import mutations.NodeWallet
import org.ergoplatform.appkit.{ErgoClient, ErgoProver}
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import play.api.cache.SyncCacheApi
import play.api.libs.concurrent.InjectedActorSupport
import state.messages.MempoolMessages.{BuildRollupChains, MempoolChain, MempoolTransform}
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
    case GetSynced =>
      if(trees.keys.nonEmpty) {
        val syncedTrees = (for (k: String <- trees.keys.toSet) yield rollupCache.get(k).map(t => k -> t).toSeq).flatten
        if (syncedTrees.size == trees.keys.size)
          sender() ! FullSync(syncedTrees.toSeq)
        else
          sender() ! PartialSync(syncedTrees.toSeq, (trees.keys.toSet -- syncedTrees.map(_._1)).toSeq)
        if(syncedTrees.nonEmpty){
          logger.info("Enabling mempool states for rollup coordinator")
          context.become(postSync(trees, Map.empty))
        }
      }else{
        sender() ! NoRollups()
      }
  }

  private def postSync(trees: Map[String, String],
                       chainMap: Map[String, MempoolChain],
                      ): Receive = {
    case gen: Genesis =>
      if(!trees.values.toSet.contains(gen.blockInfo.id)) {
        handleGenesis(gen)
        logger.info(s"Got ${trees.size + 1} trees")
        rollupCache.addToTreeSet(gen.tree.utxoId)
        context.become(postSync(trees + (gen.tree.utxoId -> gen.tree.blockId), chainMap))
      }else{
        logger.warn(s"Skipping genesis for ${gen.blockInfo.id} at height ${gen.blockInfo.height} due to duplicate trees")
      }
    case transform: RollupTransform =>
      val optBlockId = trees.get(transform.input.id)
      optBlockId match {
        case Some(treeBlockId) =>
          handleTransform(transform, treeBlockId)
          // Rollup transforms should reset mempool map, to avoid condition of new mempool txs
          // arriving before BuildRollupChains and changing chains which will be reset anyway.

          context.become(postSync(
            trees - transform.input.id + (transform.output.id -> treeBlockId),
            chainMap - transform.input.id,
          ))
          logger.info(s"Pre-applied transform ${transform.tx.id} for rollup ${treeBlockId.slice(0, 12)} at ${transform.blockInfo.height}")
          rollupCache.removeFromTreeSet(transform.input.id)
          rollupCache.addToTreeSet(transform.output.id)
          if(rollupCache.getMempoolState(treeBlockId).isDefined)
            rollupCache.removeMempoolState(treeBlockId)
        case None =>
          logger.info(s"Skipping transform ${transform.tx.id} with no genesis history")
          context.become(postSync(trees, chainMap))
      }
    case chain: MempoolChain =>
      if(trees.contains(chain.startId)) {
        if(!chainMap.contains(chain.startId) || (chainMap.contains(chain.startId) && chainMap(chain.startId) != chain )) {
          sendMempoolChain(chain, trees(chain.startId))
          val keysToRemove = chainMap.keySet -- trees.keySet

          context.become(postSync(trees, chainMap + (chain.startId -> chain) -- keysToRemove))
        }
      }
    case BuildRollupChains =>
      mempoolView ! BuildRollupChains
    // Stop Tracking should come only from children
    case stopTracking: StopTracking =>
      val removedTree = trees.find(_._2 == stopTracking.blockId)
      require(removedTree.isDefined, s"Map must contain actor name ${stopTracking.blockId} to remove tracking")
      context.become(postSync(trees - removedTree.get._1, chainMap))
      logger.info(s"Removed Rollup ${stopTracking.blockId}")
      rollupCache.removeFromTreeSet(removedTree.get._1)
      if(rollupCache.getMempoolState(stopTracking.blockId).isDefined)
        rollupCache.removeMempoolState(stopTracking.blockId)
    // Remove Rollup should come from syncHandler, which is interfacing with tasks and other external actors
    case removeRollup: RemoveRollup =>
      val tree = trees.find(_._2 == removeRollup.blockId)
      require(tree.isDefined, s"Map must contain actor name ${removeRollup.blockId} to remove tracking")
      context.become(postSync(trees - tree.get._1, chainMap))
      logger.info(s"Removed rollup ${removeRollup.blockId}")
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

  private def buildMempoolChains(utxoIds: Set[String], mempoolTxs: Set[MempoolTransform]): Map[String, MempoolChain] = {
    mempoolTxs.foldLeft(Map.empty[String, MempoolChain]){
      (z, t) =>
        if(utxoIds.contains(t.input.id)){
          if(z.contains(t.input.id))
            logger.warn(s"Replacing existing mempool sequence for utxo ${t.input.id}")

          z + (t.input.id -> MempoolChain(Seq(t)))
        }else {
          val chain = z.find(c => c._2.transforms.exists(_.output.id == t.input.id))
          if(chain.isDefined)
            z + (chain.get._1 -> MempoolChain(chain.get._2.transforms :+ t))
          else {
            logger.warn(s"Dropping $t with id ${t.tx.id} due to lack of viable tx chain")
            z
          }
        }
    }
  }

}

object RollupCoordinator {

}
