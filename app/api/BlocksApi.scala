package api

import play.api.libs.json._
import model.ApiError
import model.BlockMiners
import model.PoolBlock
import play.api.cache.SyncCacheApi

trait BlocksApi {
  /**
    * Block info by UTXO id
    * Get information about a specific block being processed by a Lithos contract
    * @param utxoId Id of the UTXO representing a Lithos block
    */
  def getBlockById(utxoId: String, cache: SyncCacheApi): Option[PoolBlock]

  /**
    * Get a block&#39;s miner set
    * Get information of miners associated with this pool block
    * @param utxoId UTXO id of the pool block whose miners are being queried
    */
  def getBlockMinersById(utxoId: String, cache: SyncCacheApi): Option[List[BlockMiners]]

  /**
    * UTXO ids by block height
    * Get UTXO ids of Lithos contracts for blocks mined in a range of heights
    * @param fromHeight Min height (start of the range) to look for Lithos blocks
    * @param toHeight Max height of the range (exclusive)
    */
  def getBlocksByHeight(fromHeight: Option[Int], toHeight: Option[Int], cache: SyncCacheApi): List[String]

  /**
    * List UTXO ids for blocks
    * Get an array of UTXO ids (hex encoded) representing contracts currently processing Lithos blocks. Returns a page of the whole list starting from &#x60;offset&#x60; and containing &#x60;limit&#x60; items.
    * @param limit The number of items in list to return
    * @param offset The offset to start the list from
    */
  def getContractIds(limit: Option[Int], offset: Option[Int], cache: SyncCacheApi): List[String]

  /**
    * Block info for multiple UTXO ids
    * Get pool blocks by given UTXO ids
    */
  def getPoolBlocksByIds(requestBody: List[String], cache: SyncCacheApi): List[Option[PoolBlock]]

  /**
   * List all pool blocks being processed by Lithos rollup contracts.
   * Returns a page of the whole list starting from `offset` and containing `limit` items.
   *
   * @param limit The number of items in list to return
   * @param offset  The offset to start the list from
   */
  def getAllPoolBlocks(limit: Option[Int], offset: Option[Int], cache: SyncCacheApi): List[PoolBlock]
}
