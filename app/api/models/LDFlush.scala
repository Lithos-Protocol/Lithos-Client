package api.models

import play.api.libs.json._

/**
 * State of a prospective flush from the pool into the vault.
 *
 * Both sets of accumulators are exposed so a client can show the gap directly — that gap is what
 * explains why claimable trails earned.
 */
case class LDFlushCheck(pendingX: String,
                        pendingY: String,
                        canFlush: Boolean,
                        poolAccX: String,
                        poolAccY: String,
                        vaultAccX: String,
                        vaultAccY: String,
                        vaultBalanceAfterX: String,
                        vaultBalanceAfterY: String,
                        lastFlushHeight: Option[Int],
                        currentHeight: Int)

object LDFlushCheck {
  implicit lazy val ldFlushCheckJsonFormat: Format[LDFlushCheck] = Json.format[LDFlushCheck]
}

/**
 * Bounds for a flush. Both optional and the whole body may be omitted: a flush moves exactly what is
 * pending and pays the caller nothing, so there is no price to protect — only the two singletons.
 */
case class LDFlushRequest(expectedPoolBoxId: Option[String] = None,
                          expectedVaultBoxId: Option[String] = None)

object LDFlushRequest {
  val Empty: LDFlushRequest = LDFlushRequest()

  implicit lazy val ldFlushRequestJsonFormat: Format[LDFlushRequest] = Json.format[LDFlushRequest]
}

case class LDFlushResult(flushedX: String, flushedY: String, txId: String)

object LDFlushResult {
  implicit lazy val ldFlushResultJsonFormat: Format[LDFlushResult] = Json.format[LDFlushResult]
}
