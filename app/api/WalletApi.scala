package api

import api.models._

/**
 * The `wallet` tag — what this client's own wallet holds, and the verified token list its tokens
 * are matched against. Reads tied to one feature stay on that feature's path: the lending status
 * under `/collateral`, orders under `/dex`, positions under `/forwards`.
 *
 * Balances and rewards sit behind `api_key`. The verified list is public, so a page can draw it
 * before a key is entered.
 */
trait WalletApi {

  /** GET /wallet/balances */
  def getWalletBalances: WalletBalances

  /** GET /wallet/rewards */
  def getRewardSummary: WalletRewardSummary

  /** POST /wallet/rewards/claim */
  def claimUnlockedRewards: WalletRewardClaimResult

  /** GET /wallet/tokens/verified */
  def getVerifiedTokens: VerifiedTokenList
}

object WalletApi {

  /** 400. There are no unlocked reward boxes to sweep right now. */
  class NothingToClaim(reason: String) extends RuntimeException(reason)
}
