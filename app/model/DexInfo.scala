package model

import play.api.libs.json._

case class DexInfo(boxId: String, LP_Id: String, liquidity: Long)

object DexInfo {
  implicit lazy val dexInfoJsonFormat: Format[DexInfo] = Json.format[DexInfo]
}
