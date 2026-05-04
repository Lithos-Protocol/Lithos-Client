package model

import play.api.libs.json._

case class DepositInfo(liquidity: Long)

object DepositInfo {
  implicit lazy val depositInfoJsonFormat: Format[DepositInfo] = Json.format[DepositInfo]
}
