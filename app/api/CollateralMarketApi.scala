package api

import api.models._

/**
 * The `collateral-market` tag — stats for the collateral market and participation in it.
 *
 * Read endpoints are pure functions over current boxes and need no key. `wallet`, `join/check` and
 * `join` describe or spend this wallet's funds and sit behind `api_key`.
 *
 * All amounts cross the wire as decimal strings — nanoERG for ERG, LIT base units for permits.
 */
trait CollateralMarketApi {

  /** GET /collateral/market */
  def getMarketInfo: CollateralMarketInfo

  /** GET /collateral/queue */
  def getQueue(limit: Option[Int], offset: Option[Int]): Seq[CollateralQueueEntry]

  /** GET /collateral/active */
  def getActiveSet(limit: Option[Int], offset: Option[Int]): Seq[CollateralActiveEntry]

  /** GET /collateral/permits/history */
  def getPermitHistory(limit: Option[Int]): CollateralPermitHistory

  /**
   * GET /collateral/wallet
   *
   * Served from a short-TTL snapshot, so a panel polling on a two-second timer does not rescan the
   * queue every tick. Invalidated by anything here that moves a position.
   */
  def getWalletStatus: WalletCollateralStatus

  /** POST /collateral/join/check */
  def checkJoin(request: CollateralJoinCheckRequest): CollateralJoinQuote

  /** POST /collateral/join */
  def join(request: CollateralJoinExecuteRequest): CollateralJoinResult
}
