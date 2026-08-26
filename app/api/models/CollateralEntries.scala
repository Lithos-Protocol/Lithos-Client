package api.models

import play.api.libs.json._

/** A position waiting in the collateral queue, head first. */
case class CollateralQueueEntry(boxId: String,
                                position: Long,
                                lenderAddress: String,
                                principalNanoErgs: String,
                                permitLit: String,
                                creationHeight: Int)

object CollateralQueueEntry {
  implicit lazy val jsonFormat: Format[CollateralQueueEntry] = Json.format[CollateralQueueEntry]
}

/** A live collateral box waiting to be spent by whichever miner finds the next block. */
case class CollateralActiveEntry(boxId: String,
                                 lenderAddress: String,
                                 valueNanoErgs: String,
                                 carriedLit: String,
                                 creationHeight: Int)

object CollateralActiveEntry {
  implicit lazy val jsonFormat: Format[CollateralActiveEntry] = Json.format[CollateralActiveEntry]
}

/** The permits paid by still-waiting positions, oldest first - the permit price curve as actually joined. */
case class CollateralPermitHistoryPoint(position: Long, height: Int, permitLit: String)

object CollateralPermitHistoryPoint {
  implicit lazy val jsonFormat: Format[CollateralPermitHistoryPoint] =
    Json.format[CollateralPermitHistoryPoint]
}

/**
 * Permit prices over the queue's live history.
 *
 * Coverage runs from `coveredHeadPosition` to `coveredTailPosition` — the positions still waiting.
 * Joins already activated or cleared are not reconstructible, so the series is the queue's visible
 * window, not its whole past. `truncated` is true when the scan cap cut the window short.
 */
case class CollateralPermitHistory(points: Seq[CollateralPermitHistoryPoint],
                                   coveredHeadPosition: Long,
                                   coveredTailPosition: Long,
                                   truncated: Boolean)

object CollateralPermitHistory {
  implicit lazy val jsonFormat: Format[CollateralPermitHistory] = Json.format[CollateralPermitHistory]
}
