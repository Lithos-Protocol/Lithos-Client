package api.models

import api.LDErrors.LDStateChanged
import play.api.libs.json._

/**
 * Response sent when an unwanted change of state is found during a LithosDex API call.
 */
case class LDStateChangedResponse(error: Int,
                                  reason: String,
                                  detail: String,
                                  poolBoxId: Option[String],
                                  vaultBoxId: Option[String],
                                  provisionBoxId: Option[String])

object LDStateChangedResponse {
  implicit lazy val ldStateChangedResponseJsonFormat: Format[LDStateChangedResponse] =
    Json.format[LDStateChangedResponse]

  def apply(e: LDStateChanged): LDStateChangedResponse =
    LDStateChangedResponse(409, "State changed", e.getMessage, e.poolBoxId, e.vaultBoxId, e.provisionBoxId)
}
