package api

import api.models._

/**
 * The `collateral-market` tag — stats for the collateral market and participation in it.
 *
 * Read endpoints are pure functions over current boxes and need no key. `wallet`, `rewards/claim`
 * and `join` touch this wallet's funds and sit behind `api_key`.
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
   * The `collateral` half of GET /wallet/balances — no route of its own.
   *
   * Served from a short-TTL snapshot, so a panel polling on a two-second timer does not rescan the
   * queue every tick. Invalidated by anything here that moves a position or a reward box.
   */
  def getWalletStatus: WalletCollateralStatus

  /** GET /collateral/rewards */
  def getRewardSummary: CollateralRewardSummary

  /** POST /collateral/rewards/claim */
  def claimUnlockedRewards: CollateralRewardClaimResult

  /** POST /collateral/join/check */
  def checkJoin(request: CollateralJoinCheckRequest): CollateralJoinQuote

  /** POST /collateral/join */
  def join(request: CollateralJoinExecuteRequest): CollateralJoinResult
}

object CollateralMarketApi {

  /** 400. There are no unlocked reward boxes to sweep right now. */
  class NothingToClaim(reason: String) extends RuntimeException(reason)
}
