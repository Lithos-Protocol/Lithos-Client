package api.models

import play.api.libs.json._

case class RedemptionInfo(provisionNFT: String)

object RedemptionInfo {
  implicit lazy val redemptionInfoJsonFormat: Format[RedemptionInfo] = Json.format[RedemptionInfo]
}
