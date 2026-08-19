package api.models

import play.api.libs.json._

/** @param amount raw token units held */
case class WalletToken(tokenId: String, name: Option[String], decimals: Option[Int], amount: String)

object WalletToken {
  implicit lazy val walletTokenJsonFormat: Format[WalletToken] = Json.format[WalletToken]
}

/**
 * ERG and token balances for the client's wallet.
 *
 * Not specific to LithosDex — grouped under the tag as its only consumer today. It exists so an amount
 * can be validated before a transaction is signed, rather than the shortfall being discovered when the
 * node rejects it.
 */
case class WalletBalance(nanoErgs: String, tokens: Seq[WalletToken])

object WalletBalance {
  implicit lazy val walletBalanceJsonFormat: Format[WalletBalance] = Json.format[WalletBalance]
}
