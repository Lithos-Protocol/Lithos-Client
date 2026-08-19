package api.models

import play.api.libs.json._

/**
 * @param newShares  size the provision should become. Must be positive — a resize to zero is a
 *                   redemption.
 * @param maxAmountX most the caller will contribute on the ERG side. Checked only when increasing.
 * @param maxAmountY the same on the token side
 * @param minAmountX least the caller will take back on the ERG side. Checked only when decreasing.
 * @param minAmountY the same on the token side
 */
case class LDResizeRequest(newShares: String,
                           maxAmountX: Option[String] = None,
                           maxAmountY: Option[String] = None,
                           minAmountX: Option[String] = None,
                           minAmountY: Option[String] = None,
                           expectedPoolBoxId: Option[String] = None,
                           expectedVaultBoxId: Option[String] = None,
                           expectedProvisionBoxId: Option[String] = None)

object LDResizeRequest {
  implicit lazy val ldResizeRequestJsonFormat: Format[LDResizeRequest] = Json.format[LDResizeRequest]
}

/**
 * @param delta      change in shares; positive adds liquidity, negative removes it
 * @param amountX    nanoERG the pool takes when increasing, or returns when decreasing
 * @param nextEntryX entry the resized provision must carry, chosen so nothing already earned is lost
 */
case class LDResizeQuote(oldShares: String,
                         newShares: String,
                         delta: String,
                         increasing: Boolean,
                         amountX: String,
                         amountY: String,
                         nextEntryX: String,
                         nextEntryY: String,
                         withinMinSupply: Boolean)

object LDResizeQuote {
  implicit lazy val ldResizeQuoteJsonFormat: Format[LDResizeQuote] = Json.format[LDResizeQuote]

  def apply(q: lithosdex.LDResizeQuote): LDResizeQuote =
    LDResizeQuote(
      oldShares = LDAmounts(q.oldShares),
      newShares = LDAmounts(q.newShares),
      delta = LDAmounts(q.delta),
      increasing = q.increasing,
      amountX = LDAmounts(q.amountX),
      amountY = LDAmounts(q.amountY),
      nextEntryX = LDAmounts(q.nextEntryX),
      nextEntryY = LDAmounts(q.nextEntryY),
      withinMinSupply = q.withinMinSupply)
}

/** @param boxId UTXO id of the resized provision box */
case class LDResizeResult(boxId: String,
                          newShares: String,
                          amountX: String,
                          amountY: String,
                          txId: String)

object LDResizeResult {
  implicit lazy val ldResizeResultJsonFormat: Format[LDResizeResult] = Json.format[LDResizeResult]
}
