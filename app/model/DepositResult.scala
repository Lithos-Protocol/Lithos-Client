package model

import play.api.libs.json._

case class DepositResult(ergDeposited: Long, tokenDeposited: Long, txId: String, provisionNFT: String)

object DepositResult {
  implicit lazy val depositResultJsonFormat: Format[DepositResult] = Json.format[DepositResult]
}
