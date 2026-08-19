package api.models

import play.api.libs.json._

/**
 * The provision to close.
 *
 * @param minAmountX               least the caller will accept back on the ERG side
 * @param minAmountY               the same on the token side
 * @param expectedPoolBoxId        the pool box the caller was quoted against
 * @param acknowledgeUnclaimedFees required when the provision has settled fees it has not claimed.
 *                                 Spending the box forfeits them to nobody, which the contract
 *                                 permits and cannot be undone, so it is not something a caller may
 *                                 do by omission.
 */
case class LDRedeemRequest(provisionBoxId: String,
                           minAmountX: Option[String] = None,
                           minAmountY: Option[String] = None,
                           expectedPoolBoxId: Option[String] = None,
                           acknowledgeUnclaimedFees: Option[Boolean] = None)

object LDRedeemRequest {
  implicit lazy val ldRedeemRequestJsonFormat: Format[LDRedeemRequest] = Json.format[LDRedeemRequest]
}

/**
 * @param amountX         nanoERG returned. Liquidity only — fees settle against the vault separately.
 * @param withinMinSupply false when closing this provision would take supply under the contract's
 *                        minimum, which the pool refuses
 * @param unclaimedX      settled but unclaimed ERG fees. FORFEITED when the box is spent, so a client
 *                        should block the redemption until they have been claimed.
 */
case class LDRedeemQuote(shares: String,
                         amountX: String,
                         amountY: String,
                         withinMinSupply: Boolean,
                         unclaimedX: String,
                         unclaimedY: String)

object LDRedeemQuote {
  implicit lazy val ldRedeemQuoteJsonFormat: Format[LDRedeemQuote] = Json.format[LDRedeemQuote]
}

case class LDRedeemResult(shares: String, amountX: String, amountY: String, txId: String)

object LDRedeemResult {
  implicit lazy val ldRedeemResultJsonFormat: Format[LDRedeemResult] = Json.format[LDRedeemResult]
}
