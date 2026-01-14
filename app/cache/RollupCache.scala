package cache

import lfsm.states.NISPTree
import play.api.cache.SyncCacheApi

/**
 * Cache Mapping of blockId -> NISPTree
 * @param cacheApi
 */
class RollupCache(cacheApi: SyncCacheApi){
  // TODO: Extend UTXOSetCache
  private final val TREE_LIST = "TREE_LIST"

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
}

object RollupCache {
  def apply(cacheApi: SyncCacheApi) = new RollupCache(cacheApi)
}
