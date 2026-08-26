package api.models

import play.api.libs.json._

/** @param amount raw token units held */
case class WalletToken(tokenId: String, name: Option[String], decimals: Option[Int], amount: String)

object WalletToken {
  implicit lazy val walletTokenJsonFormat: Format[WalletToken] = Json.format[WalletToken]
}

/**
 * Everything about this wallet in one read: ERG, tokens, and its collateral position.
 *
 * Replaces the pair `/wallet/balance` and `/collateral/wallet`, which could disagree — the two were
 * fetched at different moments, and a join landing between them showed a balance that had already
 * paid for a position the other half did not yet report. One call is one instant.
 *
 * `nanoErgs` is the SPENDABLE balance — unreserved wallet boxes plus matured coinbases — not the
 * node's total. It is the figure a join quote's `affordNow` is decided against, so the two agree.
 * LIT appears in `tokens` like any other holding; the collateral half carries no balance of its own.
 */
case class WalletBalances(primaryAddress: String,
                          nanoErgs: String,
                          tokens: Seq[WalletToken],
                          collateral: WalletCollateralStatus)

object WalletBalances {
  implicit lazy val jsonFormat: Format[WalletBalances] = Json.format[WalletBalances]
}
