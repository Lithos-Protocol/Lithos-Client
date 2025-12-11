package api

import model.ApiError
import model.LithosInfo
import play.api.cache.SyncCacheApi
import state.LFSMSync
import utils.NISPTreeCache

/**
  * Provides a default implementation for [[InfoApi]].
  */
class InfoApiImpl extends InfoApi {
  /**
    * @inheritdoc
    */
  override def getLithosInfo(cache: SyncCacheApi): LithosInfo = {

    val treeSet = cache.get[Seq[String]](NISPTreeCache.TREE_SET).get
    LithosInfo(treeSet.size, LFSMSync.synced)
  }
}
