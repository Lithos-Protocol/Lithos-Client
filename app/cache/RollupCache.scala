package cache

import cache.MempoolCache.MEMSTATE_PREFIX
import cache.RollupCache.TREE_LIST
import lfsm.states.NISPTree
import play.api.cache.SyncCacheApi
import state.messages.MempoolMessages.MempoolRollupState

import scala.reflect.ClassTag

/**
 * Cache Mapping of blockId -> NISPTree
 * @param cacheApi
 */
class RollupCache(cacheApi: SyncCacheApi){

  def get(id: String): Option[NISPTree] = {
    cacheApi.get[NISPTree](id)
  }

  def set(id: String, nispTree: NISPTree): Unit = {
    cacheApi.set(id, nispTree)
  }

  def remove(id: String): Unit = {
    cacheApi.remove(id)
  }

  def getTreeSet: Set[String] = {
    cacheApi.getOrElseUpdate[Set[String]](TREE_LIST)(Set.empty[String])
  }

  def setTreeSet(treeSet: Set[String]): Unit = {
    cacheApi.set(TREE_LIST, treeSet)
  }

  def addToTreeSet(utxoId: String): Unit = {
    setTreeSet(getTreeSet + utxoId)
  }

  def removeFromTreeSet(utxoId: String): Unit = {
    setTreeSet(getTreeSet - utxoId)
  }

  def addToTreeCache(newId: String, nispTree: NISPTree): Unit = {
    cacheApi.set(newId, nispTree)
  }

  def updateTreeCache(oldId: String, newId: String, newNISPTree: NISPTree): Unit = {
    removeFromTreeCache(oldId)
    addToTreeCache(newId, newNISPTree)
  }

  def removeFromTreeCache(utxoId: String): Unit = {
    remove(utxoId)
  }

  /**
   * Set mempool state for a rollup
   * @param blockId blockId for the rollup
   * @param mempoolRollupState State to set
   */
  def setMempoolState(blockId: String, mempoolRollupState: MempoolRollupState): Unit = {
    MempoolCache(cacheApi).setMempoolState[MempoolRollupState](blockId, mempoolRollupState)
  }

  /**
   * Remove mempool state for a rollup
   * @param blockId blockId of the rollup
   */
  def removeMempoolState(blockId: String): Unit = {
    MempoolCache(cacheApi).removeMempoolState(blockId)
  }

  /**
   * Get the mempool state for a rollup
   * @param blockId blockId of the rollup
   * @return MempoolRollupState for the associated rollup, if it exists
   */
  def getMempoolState(blockId: String): Option[MempoolRollupState] = {
    MempoolCache(cacheApi).getMempoolState[MempoolRollupState](blockId)
  }
}

object RollupCache {
  def apply(cacheApi: SyncCacheApi) = new RollupCache(cacheApi)
  private final val TREE_LIST = "TREE_LIST"
}
