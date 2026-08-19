package api.models

import play.api.libs.json._

/**
 * A deposit to quote. Supply `availableX` and `availableY` for the most shares those funds can buy,
 * or `shares` for the cheapest deposit that buys exactly that many.
 */
case class LDDepositRequest(availableX: Option[String],
                            availableY: Option[String],
                            shares: Option[String])

object LDDepositRequest {
  implicit lazy val ldDepositRequestJsonFormat: Format[LDDepositRequest] = Json.format[LDDepositRequest]
}

/**
 * @param shares            LP shares to unlock, as returned by the quote
 * @param maxAmountX        most the caller will pay on the ERG side. The amounts a deposit costs are
 *                          derived from the pool's reserves, so a pool that moved between the quote
 *                          and this request charges a different price with nothing to refuse it.
 * @param maxAmountY        the same on the token side
 * @param expectedPoolBoxId the pool box the caller was quoted against
 */
case class LDDepositExecuteRequest(shares: String,
                                   maxAmountX: Option[String] = None,
                                   maxAmountY: Option[String] = None,
                                   expectedPoolBoxId: Option[String] = None)

object LDDepositExecuteRequest {
  implicit lazy val ldDepositExecuteRequestJsonFormat: Format[LDDepositExecuteRequest] =
    Json.format[LDDepositExecuteRequest]
}

/**
 * @param provisionBoxValue nanoERG that must also fund the new provision box, on top of `amountX`
 * @param entryX            accumulator the provision enters at; it is owed nothing accrued before this
 */
case class LDDepositQuote(shares: String,
                          amountX: String,
                          amountY: String,
                          shareOfSupply: Double,
                          provisionBoxValue: String,
                          totalErgRequired: String,
                          entryX: String,
                          entryY: String)

object LDDepositQuote {
  implicit lazy val ldDepositQuoteJsonFormat: Format[LDDepositQuote] = Json.format[LDDepositQuote]

  def apply(q: lithosdex.LDDepositQuote): LDDepositQuote =
    LDDepositQuote(
      shares = LDAmounts(q.shares),
      amountX = LDAmounts(q.amountX),
      amountY = LDAmounts(q.amountY),
      shareOfSupply = q.shareOfSupply,
      provisionBoxValue = LDAmounts(q.provisionBoxValue),
      totalErgRequired = LDAmounts(q.totalErgRequired),
      entryX = LDAmounts(q.entryX),
      entryY = LDAmounts(q.entryY))
}

/**
 * @param provisionBoxId UTXO id of the newly minted provision box
 * @param ownerNFT       the minted token that owns it. Bearer: whoever holds this token can claim,
 *                       resize or redeem the provision, so it is the thing to keep, not the box id.
 */
case class LDDepositResult(provisionBoxId: String,
                           ownerNFT: String,
                           shares: String,
                           amountX: String,
                           amountY: String,
                           txId: String)

object LDDepositResult {
  implicit lazy val ldDepositResultJsonFormat: Format[LDDepositResult] = Json.format[LDDepositResult]
}
