package api

import cache.RollupCache
import model.ApiError
import model.LithosInfo
import play.api.cache.SyncCacheApi
import utils.Globals

/**
  * Provides a default implementation for [[InfoApi]].
  */
class InfoApiImpl extends InfoApi {
  /**
    * @inheritdoc
    */
  override def getLithosInfo(cache: SyncCacheApi): LithosInfo = {
    val rollupCache = new RollupCache(cache)
    val treeSet = rollupCache.getTreeSet
    LithosInfo(treeSet.size, Globals.getSyncState)
  }
}
