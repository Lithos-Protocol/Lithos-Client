package api.models

import play.api.libs.json._

/**
 * One token this wallet holds. For a verified token, `name`, `ticker` and `decimals` come from the
 * shipped list; otherwise from the node's token index, where whoever minted the token chose them.
 *
 * @param amount raw token units held
 */
case class WalletToken(tokenId: String,
                       name: Option[String],
                       ticker: Option[String],
                       decimals: Option[Int],
                       amount: String,
                       verified: Boolean)

object WalletToken {
  implicit lazy val walletTokenJsonFormat: Format[WalletToken] = Json.format[WalletToken]
}

/**
 * This wallet's ERG and tokens.
 *
 * `nanoErgs` is unreserved plain wallet value. Rewards must be claimed or consolidated first.
 * It is the figure a join quote's `affordNow` is decided against, so the two agree.
 * `reservedBoxes` and `reservedNanoErgs` are wallet boxes held for transactions in flight or for this
 * miner's own blocks, and are left out of `nanoErgs`.
 */
case class WalletBalances(primaryAddress: String,
                          nanoErgs: String,
                          reservedBoxes: Int,
                          reservedNanoErgs: String,
                          tokens: Seq[WalletToken])

object WalletBalances {
  implicit lazy val jsonFormat: Format[WalletBalances] = Json.format[WalletBalances]
}

/**
 * This wallet's coinbase reward boxes. `boxes` and `totalNanoErgs` count every one, including those
 * still behind the 720-block timelock; the unlocked fields count the part spendable now.
 */
case class WalletRewardSummary(boxes: Int,
                               unlockedBoxes: Int,
                               totalNanoErgs: String,
                               unlockedNanoErgs: String,
                               blocksUntilFirstUnlock: Option[Int])

object WalletRewardSummary {
  implicit lazy val jsonFormat: Format[WalletRewardSummary] = Json.format[WalletRewardSummary]
}

/** A verified token as the shipped list states it. */
case class VerifiedTokenInfo(tokenId: String, name: String, ticker: String, decimals: Int)

object VerifiedTokenInfo {
  implicit lazy val jsonFormat: Format[VerifiedTokenInfo] = Json.format[VerifiedTokenInfo]
}

case class VerifiedTokenList(tokens: Seq[VerifiedTokenInfo])

object VerifiedTokenList {
  implicit lazy val jsonFormat: Format[VerifiedTokenList] = Json.format[VerifiedTokenList]
}
