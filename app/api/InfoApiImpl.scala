package api

import model.ApiError
import model.LithosInfo
import play.api.cache.SyncCacheApi
import utils.{Globals, TreeCache}

/**
  * Provides a default implementation for [[InfoApi]].
  */
class InfoApiImpl extends InfoApi {
  /**
    * @inheritdoc
    */
  override def getLithosInfo(cache: SyncCacheApi): LithosInfo = {
    val treeCache = new TreeCache(cache)
    val treeSet = treeCache.getTreeSet
    LithosInfo(treeSet.size, Globals.getSyncState)
  }
}
