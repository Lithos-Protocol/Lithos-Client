package cache

import cache.RollupCache.TREE_LIST
import lfsm.states.NISPTree
import play.api.cache.SyncCacheApi

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
}

object RollupCache {
  def apply(cacheApi: SyncCacheApi) = new RollupCache(cacheApi)
  private final val TREE_LIST = "TREE_LIST"
}
