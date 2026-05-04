package cache

import cache.MDCache.{DATA_BOX, MD_BOX}
import cache.PDCache.{HISTORICAL_PD_BOX, PD_BOX}
import lfsm.states.MinerTree
import plasmadex.states.LiquidityState
import play.api.cache.SyncCacheApi

class PDCache(cacheApi: SyncCacheApi) {

  def setPD(ls: LiquidityState): Unit = cacheApi.set(PD_BOX, ls)
  def getPD: Option[LiquidityState] = cacheApi.get[LiquidityState](PD_BOX)
  def setHistoricalPD(ls: LiquidityState): Unit = cacheApi.set(HISTORICAL_PD_BOX, ls)
  def getHistoricalPD: Option[LiquidityState] = cacheApi.get[LiquidityState](HISTORICAL_PD_BOX)

}

object PDCache {
  private final val PD_BOX = "PD_BOX"
  private final val HISTORICAL_PD_BOX = "HISTORICAL_PD_BOX"
  def apply(cache: SyncCacheApi) = new PDCache(cache)
}
