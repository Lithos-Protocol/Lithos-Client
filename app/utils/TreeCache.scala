package utils

import lfsm.NISPTree
import play.api.cache.SyncCacheApi

/**
 * Cache Mapping of blockId -> NISPTree
 * @param cacheApi
 */
class TreeCache(cacheApi: SyncCacheApi){

  private final val TREE_MAP = "TREE_MAP"

  def get(id: String): Option[NISPTree] = {
    cacheApi.get[NISPTree](id)
  }

  def set(id: String, nispTree: NISPTree): Unit = {
    cacheApi.set(id, nispTree)
  }

  def remove(id: String): Unit = {
    cacheApi.remove(id)
  }

  def getBlockMap: Map[String, String] = {
    cacheApi.getOrElseUpdate[Map[String, String]](TREE_MAP)(Map.empty[String, String])
  }

  def updateBlockMap(nextMap: Map[String, String]): Unit = {
    cacheApi.set(TREE_MAP, nextMap)
  }

  def addToBlockMap(nextBlockId: String, nextUtxoId: String): Unit = {

    updateBlockMap(getBlockMap + (nextBlockId -> nextUtxoId))
  }

  def delFromBlockSet(existingBlockId: String): Unit = {
    val lastBlockSet = getBlockMap
    require(lastBlockSet.contains(existingBlockId), "Cannot remove non-existent blockId from block set")
    updateBlockMap(lastBlockSet - existingBlockId)
  }

  def addToTreeCache(newId: String, blockId: String, nispTree: NISPTree): Unit = {
    cacheApi.set(newId, nispTree)
    addToBlockMap(blockId, newId)
  }

  def updateTreeCache(oldId: String, newId: String, blockId: String, newNISPTree: NISPTree): Unit = {
    cacheApi.remove(oldId)
    cacheApi.set(newId, newNISPTree)
    addToBlockMap(blockId, newId)
  }

  def removeFromTreeCache(utxoId: String, blockId: String): Unit = {
    remove(utxoId)
    delFromBlockSet(blockId)
  }
}

object TreeCache {
  def apply(cacheApi: SyncCacheApi) = new TreeCache(cacheApi)
}
