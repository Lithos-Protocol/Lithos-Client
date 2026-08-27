package api

import utils.Globals
import lfsm.LFSMPhase
import lfsm.states.NISPTree
import models.ApiError
import models.BlockMiners
import models.PoolBlock
import play.api.cache.SyncCacheApi

/**
  * Provides a default implementation for [[BlocksApi]].
  */
class BlocksApiImpl extends BlocksApi {
  /**
    * @inheritdoc
    */
  override def getBlockById(utxoId: String, cache: SyncCacheApi): Option[PoolBlock] = {
    val optNISPTree = Globals.syncView.rollups.collectFirst { case (id, tree) if id == utxoId => tree }
    optNISPTree match {
      case Some(nispTree) =>
        val phase = nispTree.phase match {
          case LFSMPhase.HOLDING => "HOLDING"
          case LFSMPhase.EVAL => "EVAL"
          case LFSMPhase.PAYOUT => "PAYOUT"
        }
        Some(PoolBlock(utxoId, nispTree.blockId, nispTree.startHeight, nispTree.numMiners, phase))
      case None => None
    }
  }

  /**
    * @inheritdoc
    */
  override def getBlockMinersById(utxoId: String, cache: SyncCacheApi): Option[List[BlockMiners]] = {
    val optNISPTree = Globals.syncView.rollups.collectFirst { case (id, tree) if id == utxoId => tree }
    optNISPTree.map(n => n.minerSet.map(BlockMiners(_)).toList)
  }

  /**
    * @inheritdoc
    */
  override def getBlocksByHeight(fromHeight: Option[Int], toHeight: Option[Int], cache: SyncCacheApi): List[String] = {
    // TODO: Implement better logic
    val blocks = Globals.syncView.rollups
    val startHeight = fromHeight.getOrElse(0)
    val endHeight = toHeight.flatMap{h => if(h < 1) None else Some(h)}.getOrElse(10000000)
    blocks.filter(b => b._2.startHeight >= startHeight && b._2.startHeight < endHeight).map(_._1).toList
  }

  /**
    * @inheritdoc
    */
  override def getContractIds(limit: Option[Int], offset: Option[Int], cache: SyncCacheApi): List[String] = {
    val set = Globals.syncView.rollups.map(_._1)
    val page = ApiHelper.handlePagination(offset, limit)
    set.slice(page._1, page._1 + page._2).toList
  }

  /**
    * @inheritdoc
    */
  override def getPoolBlocksByIds(requestBody: List[String], cache: SyncCacheApi): List[Option[PoolBlock]] = {
    for (r <- requestBody) yield getBlockById(r, cache)
  }

  override def getAllPoolBlocks(limit: Option[Int], offset: Option[Int], cache: SyncCacheApi): List[PoolBlock] = {
    val set = getContractIds(limit, offset, cache)
    // TODO: Dont use .get, because trees are individually synced now and treeSet not guaranteed to map to NISPTree
    (for(id <- set) yield getBlockById(id, cache).toSeq).flatten.sortBy(_.blockHeight)
  }
}
