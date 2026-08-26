package api.models

import play.api.libs.json._

/**
 * One swap against the pool, newest first in the list.
 *
 * @param height    block height, or absent while the swap is still in the mempool. An unconfirmed
 *                  swap can still roll back, so this is left absent rather than defaulted.
 * @param timestamp unix milliseconds, absent for mempool entries and for heights whose header could
 *                  not be resolved
 */
case class LDSwapEntry(txId: String,
                       ergIn: Boolean,
                       amountIn: String,
                       amountOut: String,
                       height: Option[Int],
                       timestamp: Option[Long])

object LDSwapEntry {
  implicit lazy val ldSwapEntryJsonFormat: Format[LDSwapEntry] = Json.format[LDSwapEntry]
}

case class LDRecentSwaps(swaps: Seq[LDSwapEntry])

object LDRecentSwaps {
  implicit lazy val ldRecentSwapsJsonFormat: Format[LDRecentSwaps] = Json.format[LDRecentSwaps]

  /** Default and ceiling for `limit`. */
  val DefaultLimit: Int = 10
  val MaxLimit: Int = 50
}
