package api.models

import play.api.libs.json._

case class FeeClaimResult(ergClaimed: Long, tokensClaimed: Long, txId: String)

object FeeClaimResult {
  implicit lazy val feeClaimResultJsonFormat: Format[FeeClaimResult] = Json.format[FeeClaimResult]
}
