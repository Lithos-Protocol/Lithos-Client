package api.models

import play.api.libs.json._

/**
 * One side of a pool. `tokenId` is "ERG" for the X side of an ERG/token pool. A verified token's name, ticker
 * and decimals come from the client's verified list; any other token's from the node's token index.
 */
case class LDPoolToken(tokenId: String, name: Option[String], ticker: Option[String], decimals: Option[Int],
                       verified: Boolean)

object LDPoolToken {
  implicit lazy val jsonFormat: Format[LDPoolToken] = Json.format[LDPoolToken]
}

/**
 * One LithosDex pool as the index last read it.
 *
 * @param kind           `n2t` for ERG against a token, `t2t` for token against token
 * @param price          units of Y per unit of X, at display decimals; absent when either side's decimals are unknown
 * @param verified       whether the client's verified list names this pool
 * @param genesisChecked whether its genesis was checked; verified pools are checked by hand instead
 * @param problems       genesis facts this pool breaks; any one can let the pool be drained
 * @param warnings       risks short of that, such as too little ERG for storage rent
 */
case class LDPoolSummary(poolNFT: String,
                         kind: String,
                         utxoId: String,
                         inclusionHeight: Int,
                         tokenX: LDPoolToken,
                         tokenY: LDPoolToken,
                         reservesX: String,
                         reservesY: String,
                         pendingX: String,
                         pendingY: String,
                         supply: String,
                         feeParams: LDFeeParams,
                         provTokensLeft: String,
                         price: Option[Double],
                         verified: Boolean,
                         genesisChecked: Boolean,
                         problems: Seq[String],
                         warnings: Seq[String])

object LDPoolSummary {
  implicit lazy val jsonFormat: Format[LDPoolSummary] = Json.format[LDPoolSummary]
}

/**
 * @param status    `building` until the first index completes, then `ready`
 * @param height    the tip the index was read at
 * @param truncated more pools exist than one index reads; the newest are listed
 */
case class LDPoolList(status: String, height: Int, truncated: Boolean, pools: Seq[LDPoolSummary])

object LDPoolList {
  implicit lazy val jsonFormat: Format[LDPoolList] = Json.format[LDPoolList]
}

/** A pool's fee vault: the accumulators as of the last flush and the fees it holds for provisions. */
case class LDPoolVault(vaultNFT: String, utxoId: String, accX: String, accY: String, balanceX: String, balanceY: String)

object LDPoolVault {
  implicit lazy val jsonFormat: Format[LDPoolVault] = Json.format[LDPoolVault]
}

case class LDPoolDetail(pool: LDPoolSummary, vault: LDPoolVault)

object LDPoolDetail {
  implicit lazy val jsonFormat: Format[LDPoolDetail] = Json.format[LDPoolDetail]
}
