package api.models

import play.api.libs.json._

/**
 * One transaction that moved the pool, newest first in the list. The amount fields present depend on
 * `type`: `ergIn`, `amountIn` and `amountOut` for a SWAP, `amountX` and `amountY` for everything else,
 * and `shares` for DEPOSIT, REDEEM and RESIZE.
 *
 * @param via        ORDER when the transaction filled an order, DIRECT otherwise
 * @param status     MEMPOOL or CONFIRMED
 * @param height     block height, absent while unconfirmed
 * @param timestamp  unix milliseconds, absent while unconfirmed and for heights whose header was not resolved
 * @param shares     shares issued or closed; for a RESIZE, the signed change
 */
case class LDActivityEntry(txId: String,
                           `type`: String,
                           via: String,
                           orderBoxId: Option[String],
                           status: String,
                           height: Option[Int],
                           timestamp: Option[Long],
                           ergIn: Option[Boolean] = None,
                           amountIn: Option[String] = None,
                           amountOut: Option[String] = None,
                           amountX: Option[String] = None,
                           amountY: Option[String] = None,
                           shares: Option[String] = None)

object LDActivityEntry {
  implicit lazy val ldActivityEntryJsonFormat: Format[LDActivityEntry] = Json.format[LDActivityEntry]
}

case class LDRecentActivity(activity: Seq[LDActivityEntry])

object LDRecentActivity {
  implicit lazy val ldRecentActivityJsonFormat: Format[LDRecentActivity] = Json.format[LDRecentActivity]

  /** Default and ceiling for `limit`. */
  val DefaultLimit: Int = 10
  val MaxLimit: Int = 50
}
