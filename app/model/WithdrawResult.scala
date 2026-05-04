package model

import play.api.libs.json._

case class WithdrawResult(ergFees: Long, tokenFees: Long, feeNFT: String, utxoId: String, txId: String)

object WithdrawResult {
  implicit lazy val withdrawResultJsonFormat: Format[WithdrawResult] = Json.format[WithdrawResult]
}
