package api.models

import play.api.libs.json.{Json, OWrites}
import stats._

final case class MiningTotalsResponse(status: String, persistent: Boolean, observedAt: Option[Long],
                                      sourceHeight: Option[Int], sourceBlockId: Option[String],
                                      targetHeight: Option[Int], fromHeight: Option[Int],
                                      fromTimestamp: Option[Long], totals: Map[String, String])

final case class LocalMiningResponse(enabled: Boolean, producers: Map[String, LocalMiningActivityView])

/** Public response writers are separate from the persisted record formats. */
object StatsResponses {
  import MiningStatsData.{cursorFormat, localObservationFormat}

  implicit val totalsWrites: OWrites[MiningTotalsResponse] = Json.writes[MiningTotalsResponse]
  implicit val accountingWrites: OWrites[MiningAccountingTotals] = OWrites(value => Json.toJsObject(value.values))
  implicit val bucketWrites: OWrites[MiningBucket] = Json.writes[MiningBucket]
  implicit val bucketsWrites: OWrites[MiningBucketHistory] = Json.writes[MiningBucketHistory]
  implicit val hashrateWrites: OWrites[LithosHashrateEstimate] = Json.writes[LithosHashrateEstimate]
  implicit val feeBucketWrites: OWrites[CollateralFeeBucket] = Json.writes[CollateralFeeBucket]
  implicit val feeStatsWrites: OWrites[CollateralFeeStats] = Json.writes[CollateralFeeStats]
  implicit val collateralWrites: OWrites[CollateralStats] = Json.writes[CollateralStats]
  implicit val activityWrites: OWrites[LocalMiningActivityView] = Json.writes[LocalMiningActivityView]
  implicit val localWrites: OWrites[LocalMiningResponse] = Json.writes[LocalMiningResponse]

  def totals(view: MiningStatsView): MiningTotalsResponse =
    MiningTotalsResponse(view.status, view.persistent, view.observedAt, view.sourceHeight, view.sourceBlockId,
      view.targetHeight, view.fromHeight, view.fromTimestamp, view.totals.accounting.values)
}
