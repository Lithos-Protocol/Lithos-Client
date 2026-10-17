package api

import api.models._

/**
 * The `forwards` tag, one method per endpoint.
 *
 * Reads and every `check` need no key. Listing this wallet's positions, and every endpoint that signs and
 * broadcasts, is behind `api_key`; those sign with the node wallet.
 */
trait ForwardsApi {

  /** GET /forwards/accumulator */
  def getAccumulator: DFAccumulatorInfo

  /** GET /forwards/offers */
  def listOffers(offset: Option[Int], limit: Option[Int]): DFOfferBook

  /** POST /forwards/offers/check */
  def checkOffer(request: DFOfferRequest): DFOfferQuote

  /** POST /forwards/offers */
  def createOffer(request: DFOfferRequest): DFOfferResult

  /** POST /forwards/offers/:boxId/fill/check */
  def checkFill(boxId: String): DFFillQuote

  /** POST /forwards/offers/:boxId/fill */
  def fill(boxId: String): DFFillResult

  /** POST /forwards/offers/:boxId/cancel/check */
  def checkCancel(boxId: String): DFCancelQuote

  /** POST /forwards/offers/:boxId/cancel */
  def cancel(boxId: String): DFCancelResult

  /** GET /forwards/market */
  def getMarket: DFMarket

  /** GET /forwards/positions */
  def listPositions: DFPositionList

  /** GET /forwards/history */
  def getHistory: DFHistory

  /** POST /forwards/positions/:boxId/settle/check */
  def checkSettle(boxId: String): DFSettleQuote

  /** POST /forwards/positions/:boxId/settle */
  def settle(boxId: String): DFSettleResult
}
