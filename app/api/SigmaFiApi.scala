package api

import api.models._

/**
 * The `sigmafi` tag, one method per endpoint.
 *
 * The order and bond lists, the asset lookup and every `check` need no key. The portfolio and every
 * endpoint that signs and broadcasts are behind `api_key`; those sign with the node wallet.
 */
trait SigmaFiApi {

  /** GET /sigmafi/old/orders */
  def listOrders(offset: Option[Int], limit: Option[Int], collateral: Option[String], loan: Option[String]): SFOrderBook

  /** GET /sigmafi/old/bonds */
  def listBonds(offset: Option[Int], limit: Option[Int], all: Option[Boolean]): SFBondList

  /** POST /sigmafi/old/assets */
  def lookupAssets(request: SFAssetLookupRequest): Seq[SFAssetLookup]

  /** GET /sigmafi/old/portfolio */
  def getPortfolio: SFPortfolio

  /** POST /sigmafi/old/orders/check, and POST /sigmafi/old/orders with `send` */
  def createOrder(request: SFOrderRequest, send: Boolean): Either[SFQuote, SFResult]

  /** POST /sigmafi/old/orders/:boxId/cancel[/check] */
  def cancelOrder(boxId: String, send: Boolean): Either[SFQuote, SFResult]

  /** POST /sigmafi/old/orders/:boxId/fill[/check] */
  def fillOrder(boxId: String, send: Boolean, reserve: Option[String] = None): Either[SFQuote, SFResult]

  /** POST /sigmafi/old/bonds/:boxId/repay[/check] */
  def repay(boxId: String, send: Boolean): Either[SFQuote, SFResult]

  /** POST /sigmafi/old/bonds/:boxId/liquidate[/check] */
  def liquidate(boxId: String, send: Boolean): Either[SFQuote, SFResult]

  /** POST /sigmafi/old/claims/:tokenId[/check] */
  def claim(tokenId: String, send: Boolean): Either[SFQuote, SFResult]
}
