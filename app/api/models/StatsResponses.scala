package api.models

import configs.VerifiedRegistry
import play.api.libs.json.{JsArray, JsObject, Json, OWrites}
import stats._

/** `tokens` names every verified token a `.tokens.<id>` key in `totals` refers to. */
final case class MiningTotalsResponse(status: String, persistent: Boolean, observedAt: Option[Long],
                                      sourceHeight: Option[Int], sourceBlockId: Option[String],
                                      targetHeight: Option[Int], fromHeight: Option[Int],
                                      fromTimestamp: Option[Long], totals: Map[String, String],
                                      tokens: Map[String, VerifiedTokenInfo] = Map.empty)

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
  implicit val queueStatsWrites: OWrites[CollateralQueueStats] = Json.writes[CollateralQueueStats]
  implicit val collateralWrites: OWrites[CollateralStats] = Json.writes[CollateralStats]
  implicit val pathStatsWrites: OWrites[CollateralPathStats] = Json.writes[CollateralPathStats]
  implicit val selectionStatsWrites: OWrites[CollateralSelectionStats] = Json.writes[CollateralSelectionStats]

  /** The inventory, with lender returns by selection path from the retained history's totals. */
  def collateral(inventory: CollateralStats, view: MiningStatsView): JsObject =
    collateralWrites.writes(inventory) +
      ("selection" -> selectionStatsWrites.writes(CollateralSelectionStats.of(view.totals.accounting, view.fromHeight)))
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

  /** Each row adds what came back and the net, so a caller does not have to sum strings. */
  private val lendingRowWrites: OWrites[LendingRecord] = OWrites { l =>
    MiningStatsData.lendingFormat.writes(l) ++
      Json.obj("receivedNanoErg" -> l.receivedNanoErg.toString, "netNanoErg" -> l.netNanoErg.toString)
  }
  implicit val lendingTotalsWrites: OWrites[LendingTotals] = Json.writes[LendingTotals]
  implicit val lendingWrites: OWrites[LendingPage] = OWrites { page =>
    Json.obj("status" -> page.status, "source" -> page.source, "retainedFromHeight" -> page.retainedFromHeight,
      "retainedFromTimestamp" -> page.retainedFromTimestamp, "offset" -> page.offset, "total" -> page.total,
      "positions" -> JsArray(page.positions.map(lendingRowWrites.writes)), "totals" -> page.totals,
      "rewardLockBlocks" -> page.rewardLockBlocks)
  }

  def totals(view: MiningStatsView, registry: VerifiedRegistry): MiningTotalsResponse =
    MiningTotalsResponse(view.status, view.persistent, view.observedAt, view.sourceHeight, view.sourceBlockId,
      view.targetHeight, view.fromHeight, view.fromTimestamp, view.totals.accounting.values,
      tokensNamed(view.totals.accounting.values.keys, registry))

  /** Buckets with the verified tokens their keys name, so a reader can format each amount by its decimals. */
  def buckets(history: MiningBucketHistory, registry: VerifiedRegistry): JsObject =
    bucketsWrites.writes(history) +
      ("tokens" -> Json.toJson(tokensNamed(history.buckets.iterator.flatMap(_.totals.values.keys).toSet, registry)))

  /** Token ids end every `.tokens.<id>` accounting key. Only verified tokens are ever written there. */
  def tokensNamed(keys: Iterable[String], registry: VerifiedRegistry): Map[String, VerifiedTokenInfo] =
    keys.iterator.flatMap { key =>
      val at = key.lastIndexOf(".tokens.")
      if (at < 0) None else registry.token(key.substring(at + ".tokens.".length))
    }.map(t => t.tokenId -> VerifiedTokenInfo(t.tokenId, t.name, t.ticker, t.decimals)).toMap
}
