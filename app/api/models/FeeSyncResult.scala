package api.models

import play.api.libs.json._

case class FeeSyncResult(isSynced: Boolean, isSyncing: Boolean, feeRewardBox: String)

object FeeSyncResult {
  implicit lazy val feeSyncResultJsonFormat: Format[FeeSyncResult] = Json.format[FeeSyncResult]
}
