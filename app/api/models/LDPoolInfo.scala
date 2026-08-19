package api.models

import play.api.libs.json._

/**
 * State of the LithosDex liquidity pool box.
 *
 * `reservesX` / `reservesY` are what the AMM trades with; `pendingX` / `pendingY` are fees already
 * accrued but not yet flushed to the vault, held by the same box and never traded against.
 */
case class LDPoolInfo(poolNFT: String,
                      utxoId: String,
                      tokenY: String,
                      tokenName: Option[String],
                      tokenDecimals: Option[Int],
                      reservesX: String,
                      reservesY: String,
                      pendingX: String,
                      pendingY: String,
                      supply: String,
                      feeParams: LDFeeParams,
                      accX: String,
                      accY: String,
                      provTokensLeft: String,
                      canFlush: Boolean,
                      canDeposit: Boolean,
                      syncHeight: Int)

object LDPoolInfo {
  implicit lazy val ldPoolInfoJsonFormat: Format[LDPoolInfo] = Json.format[LDPoolInfo]
}
