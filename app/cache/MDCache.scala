package cache

import cache.MDCache.{DATA_BOX, MD_BOX}
import lfsm.states.MinerTree
import play.api.cache.SyncCacheApi

class MDCache(cacheApi: SyncCacheApi) {

  def setMD(md: MinerTree): Unit = cacheApi.set(MD_BOX, md)
  def getMD: Option[MinerTree] = cacheApi.get[MinerTree](MD_BOX)
  def getDataBox: Option[String] = cacheApi.get[String](DATA_BOX)
  def setDataBox(dataBoxId: String): Unit = cacheApi.set(DATA_BOX, dataBoxId)
  def delDataBox: Unit = cacheApi.remove(DATA_BOX)
}

object MDCache {
  private final val DATA_BOX = "DATA_BOX"
  private final val MD_BOX = "MD_BOX"
  def apply(cache: SyncCacheApi) = new MDCache(cache)
}
