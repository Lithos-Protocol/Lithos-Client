package api.models

import play.api.libs.json._

case class RedemptionResult(ergRedeemed: Long, tokensRedeemed: Long, liqRemoved: Long, txId: String)

object RedemptionResult {
  implicit lazy val redemptionResultJsonFormat: Format[RedemptionResult] = Json.format[RedemptionResult]
}
