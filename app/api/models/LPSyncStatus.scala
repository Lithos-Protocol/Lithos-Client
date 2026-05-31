package api.models

import play.api.libs.json._

case class LPSyncStatus(blockHeight: Int, isSynced: Boolean, utxoId: String, digest: String)

object LPSyncStatus {
  implicit lazy val lpSyncStatusJsonFormat: Format[LPSyncStatus] = Json.format[LPSyncStatus]
}
