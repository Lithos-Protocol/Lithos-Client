package api

import configs.NodeContext
import models.ApiError
import models.LithosInfo
import play.api.cache.SyncCacheApi
import utils.Globals

import javax.inject.Inject

/**
  * Provides a default implementation for [[InfoApi]].
  */
class InfoApiImpl @Inject()(node: NodeContext) extends InfoApi {
  /**
    * @inheritdoc
    */
  override def getLithosInfo(cache: SyncCacheApi): LithosInfo = {
    val view = Globals.syncView
    LithosInfo(
      numPoolBlocks = view.rollups.size,
      synced = view.canonical.available,
      status = view.status.getClass.getSimpleName.stripSuffix("$"),
      height = view.cursor.map(_.height),
      blockId = view.cursor.map(_.blockId),
      canonicalReason = view.canonical.reason,
      minerDictionaryAvailable = view.minerDictionary.available,
      minerDictionaryReason = view.minerDictionary.reason,
      mempoolAvailable = view.mempool.available,
      mempoolReason = view.mempool.reason,
      network = node.getNetwork.name())
  }
}
