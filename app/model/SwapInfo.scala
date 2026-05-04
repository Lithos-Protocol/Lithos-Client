package model

import play.api.libs.json._

case class SwapInfo(amount: Long, isERG: Boolean)

object SwapInfo {
  implicit lazy val swapInfoJsonFormat: Format[SwapInfo] = Json.format[SwapInfo]
}
