package api

import models.LithosInfo
import play.api.cache.SyncCacheApi

trait InfoApi {
  /**
    * Lithos client information
    * Get information about the Lithos client and the current LFSM state
    */
  def getLithosInfo(cache: SyncCacheApi): LithosInfo
}
