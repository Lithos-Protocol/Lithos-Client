package api

import api.models._

/**
 * The `wallet` tag — what this client's own wallet holds, and the verified token list its tokens
 * are matched against. Reads tied to one feature stay on that feature's path: the lending status
 * under `/collateral`, orders under `/dex`, positions under `/forwards`.
 *
 * Balances, rewards and the arb stake sit behind `api_key`. The verified list is public, so a page can
 * draw it before a key is entered.
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

  /** GET /wallet/arb */
  def getArbStake: WalletArbStake

  /** POST /wallet/arb/reclaim */
  def reclaimArbStake: WalletArbReclaimResult
}

object WalletApi {

  /** 400. There is nothing to sweep right now: no unlocked reward box, or no box at the arb address. */
  class NothingToClaim(reason: String) extends RuntimeException(reason)
}
