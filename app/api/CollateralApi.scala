package api

import play.api.libs.json._
import model.ApiError
import model.CollateralInfo
import model.CollateralUTXO
import model.SuccessfulTransaction
import play.api.Configuration

trait CollateralApi {
  /**
    * Create a new collateral UTXO
    * Create a new collateral UTXO which will be used by Lithos miners
    */
  def createCollateralUTXO(collateralInfo: CollateralInfo, config: Configuration): SuccessfulTransaction

  /**
    * List all collateral UTXOs
    * Get list of collateral UTXOs which will be used by Lithos while mining. Returns a page of the whole list starting from &#x60;offset&#x60; and containing &#x60;limit&#x60; items.
    * @param limit The number of items in list to return
    * @param offset The offset to start the list from
    */
  def getAllCollateralInfo(limit: Option[Int], offset: Option[Int], config: Configuration): List[CollateralUTXO]

  /**
    * List personal collateral UTXOs
    * Get the list of collateral UTXOs made by you. Returns a page of the whole list starting from &#x60;offset&#x60; and containing &#x60;limit&#x60; items.
    * @param limit The number of items in list to return
    * @param offset The offset to start the list from
    */
  def getLocalCollateralInfo(limit: Option[Int], offset: Option[Int], config: Configuration): List[CollateralUTXO]
}
