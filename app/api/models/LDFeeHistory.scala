package api.models

import play.api.libs.json._

/**
 * One bucket of fee history.
 *
 * @param height      block height at the end of this bucket
 * @param timestamp   unix milliseconds at the end of this bucket, when the header was available
 * @param periodX     nanoERG fees taken during this bucket
 * @param cumulativeX nanoERG fees taken from the start of the range to here
 */
case class LDFeeHistoryPoint(height: Int,
                             timestamp: Option[Long],
                             periodX: String,
                             periodY: String,
                             cumulativeX: String,
                             cumulativeY: String)

object LDFeeHistoryPoint {
  implicit lazy val ldFeeHistoryPointJsonFormat: Format[LDFeeHistoryPoint] = Json.format[LDFeeHistoryPoint]
}

/**
 * @param partial true when the pool's lineage is longer than one request can walk, so this series
 *                starts later than the pool did. The points in it are exact; there are just fewer
 *                of them than the whole history would have. Reported rather than raised, because a
 *                lineage only grows — failing on it would break these endpoints permanently.
 */
case class LDFeeHistory(history: Seq[LDFeeHistoryPoint], partial: Boolean = false)

object LDFeeHistory {
  implicit lazy val ldFeeHistoryJsonFormat: Format[LDFeeHistory] = Json.format[LDFeeHistory]
}

case class LDProvisionFeeHistory(boxId: String,
                                 history: Seq[LDFeeHistoryPoint],
                                 partial: Boolean = false)

object LDProvisionFeeHistory {
  implicit lazy val ldProvisionFeeHistoryJsonFormat: Format[LDProvisionFeeHistory] =
    Json.format[LDProvisionFeeHistory]
}
