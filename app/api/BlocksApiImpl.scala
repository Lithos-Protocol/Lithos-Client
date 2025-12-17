package api

import lfsm.{LFSMPhase, NISPTree}
import model.ApiError
import model.BlockMiners
import model.PoolBlock
import play.api.cache.SyncCacheApi
import utils.TreeCache

/**
  * Provides a default implementation for [[BlocksApi]].
  */
class BlocksApiImpl extends BlocksApi {
  /**
    * @inheritdoc
    */
  override def getBlockById(utxoId: String, cache: SyncCacheApi): Option[PoolBlock] = {
    val optNISPTree = cache.get[NISPTree](utxoId)
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
    val optNISPTree = cache.get[NISPTree](utxoId)
    optNISPTree.map(n => n.minerSet.map(BlockMiners(_)).toList)
  }

  /**
    * @inheritdoc
    */
  override def getBlocksByHeight(fromHeight: Option[Int], toHeight: Option[Int], cache: SyncCacheApi): List[String] = {
    // TODO: Implement better logic
    val treeCache = new TreeCache(cache)
    val fullSet = treeCache.getTreeSet
    val blocksOpt = for(t <- fullSet) yield treeCache.get(t).map(n => t -> n).toSeq
    val blocks = blocksOpt.flatten
    val startHeight = fromHeight.getOrElse(0)
    val endHeight = toHeight.flatMap{h => if(h < 1) None else Some(h)}.getOrElse(10000000)
    blocks.filter(b => b._2.startHeight >= startHeight && b._2.startHeight < endHeight).map(_._1).toList
  }

  /**
    * @inheritdoc
    */
  override def getContractIds(limit: Option[Int], offset: Option[Int], cache: SyncCacheApi): List[String] = {
    val treeCache = TreeCache(cache)
    val set = treeCache.getTreeSet
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
