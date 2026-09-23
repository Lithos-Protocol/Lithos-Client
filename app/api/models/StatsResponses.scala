package api.models

import play.api.libs.json.{JsArray, Json, OWrites}
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
  implicit val claimWrites: OWrites[PaymentClaim] = Json.writes[PaymentClaim]
  implicit val bountyWrites: OWrites[FraudBounty] = Json.writes[FraudBounty]

  /** Composition inlined. `minerHash` and `local` say nothing here: every row is this client's own. */
  private val ledgerPaymentWrites: OWrites[MiningPaymentRecord] = OWrites { p =>
    val row = MiningStatsData.paymentFormat.writes(p) - "amounts" - "minerHash" - "local"
    p.amounts.fold(row)(a => row ++ MiningStatsData.payoutAmountsFormat.writes(a))
  }

  implicit val ledgerWrites: OWrites[PaymentLedgerPage] = OWrites { page =>
    Json.obj("status" -> page.status, "source" -> page.source, "retainedFromHeight" -> page.retainedFromHeight,
      "offset" -> page.offset, "total" -> page.total,
      "sort" -> page.sort, "order" -> (if (page.ascending) "asc" else "desc"),
      "payments" -> JsArray(page.payments.map(ledgerPaymentWrites.writes)),
      "claims" -> page.claims, "bounties" -> page.bounties,
      "holdingBlocks" -> page.holdingBlocks, "evaluationBlocks" -> page.evaluationBlocks)
  }

  def totals(view: MiningStatsView): MiningTotalsResponse =
    MiningTotalsResponse(view.status, view.persistent, view.observedAt, view.sourceHeight, view.sourceBlockId,
      view.targetHeight, view.fromHeight, view.fromTimestamp, view.totals.accounting.values)
}
