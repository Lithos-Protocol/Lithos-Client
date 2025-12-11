package api

import play.api.libs.json._
import model.ApiError
import model.PaymentTransaction
import play.api.Configuration
import play.api.cache.SyncCacheApi

trait PaymentsApi {
  /**
    * Get Lithos payouts
    * Get a list of payment transactions sent to you from Lithos contracts. Returns a page of the whole list starting from &#x60;offset&#x60; and containing &#x60;limit&#x60; items.
    * @param limit The number of items in list to return
    * @param offset The offset to start the list from
    */
  def getPayments(limit: Option[Int], offset: Option[Int], config: Configuration, cache: SyncCacheApi): List[PaymentTransaction]
}
