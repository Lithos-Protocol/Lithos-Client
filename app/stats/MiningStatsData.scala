package stats

import play.api.libs.json._

final case class MiningCursor(height: Int, blockId: String, parentId: String, timestamp: Long)
/**
 * `priorityFeeNanoErg` is the whole fee this block's collateral box carried above the floor. The
 * share of it that went to the pool is not a separate field: it landed in the holding box and is
 * already inside `initialHoldingNanoErg`.
 */
final case class LithosBlockRecord(blockId: String, height: Int, timestamp: Long, transactionId: String,
                                    holdingBoxId: String, collateralBoxId: String,
                                    collateralNanoErg: String, initialHoldingNanoErg: String,
                                    priorityFeeNanoErg: String)
/** Gross designated payout output, including the refunded submission bond. This is not net earnings. */
final case class MiningPaymentRecord(transactionId: String, outputId: String, payoutBoxId: String,
                                      rollupNft: String, minedBlockId: String, minedHeight: Int,
                                      minedTimestamp: Long, blockId: String, height: Int, timestamp: Long,
                                      grossNanoErg: String, amounts: Option[PayoutAmounts] = None,
                                      minerHash: Option[String] = None, local: Boolean = true)
/**
 * What this wallet got back for one of its collateral boxes, which this block spent.
 *
 * @param postedNanoErg   the box's whole value: the principal and the priority fee bid on top of it
 * @param coinbaseNanoErg the block reward paid to the lender's key, less the re-emission tokens a spend of it
 *                        must pay on mainnet
 * @param feesNanoErg     the block's transaction fees, which the fee transaction pays to the same key
 * @param joinedHeight    the block that included the queue box, or None when the index does not show it
 */
final case class LendingRecord(blockId: String, height: Int, timestamp: Long, collateralBoxId: String,
                               lenderAddress: String, postedNanoErg: String, priorityFeeNanoErg: String,
                               coinbaseNanoErg: String, feesNanoErg: String, joinedHeight: Option[Int]) {
  def receivedNanoErg: BigInt = BigInt(coinbaseNanoErg) + BigInt(feesNanoErg)
  def netNanoErg: BigInt = receivedNanoErg - BigInt(postedNanoErg)
}
final case class MiningBlockRecord(cursor: MiningCursor, previous: MiningCursor, difficulty: String,
                                    blocks: Vector[LithosBlockRecord], payments: Vector[MiningPaymentRecord],
                                    activity: MiningActivity = MiningActivity(), transactionFeesNanoErg: String = "0",
                                    lending: Option[LendingRecord] = None)
final case class MiningTotals(blocks: Long = 0L, payments: Long = 0L, grossPaidNanoErg: String = "0",
                                accounting: MiningAccountingTotals = MiningAccountingTotals()) {
  def include(record: MiningBlockRecord, sign: Int): MiningTotals = {
    val result = MiningTotals(Math.addExact(blocks, sign.toLong * (if (record.blocks.nonEmpty) 1 else 0)),
      Math.addExact(payments, sign.toLong * record.payments.size),
      (BigInt(grossPaidNanoErg) + sign * record.payments.map(p => BigInt(p.grossNanoErg)).sum).toString,
      accounting.combine(MiningAccounting.contribution(record), sign))
    require(result.blocks >= 0 && result.payments >= 0 && BigInt(result.grossPaidNanoErg) >= 0,
      "mining statistics totals became negative")
    result
  }
}
final case class MiningLedgerState(firstHeight: Int, cursor: MiningCursor, totals: MiningTotals)
/**
 * The per-block difficulty series this view used to carry was 120 copies of one number, since
 * difficulty only moves on epoch boundaries. `/stats/mining/difficulty` serves the epoch table
 * instead, which covers years rather than the retention window.
 */
final case class MiningStatsView(status: String = "loading", observedAt: Option[Long] = None,
                                  persistent: Boolean = true, sourceHeight: Option[Int] = None,
                                  sourceBlockId: Option[String] = None, targetHeight: Option[Int] = None,
                                  fromHeight: Option[Int] = None, fromTimestamp: Option[Long] = None,
                                  recentFromHeight: Option[Int] = None, totals: MiningTotals = MiningTotals(),
                                  blocks: Vector[LithosBlockRecord] = Vector.empty,
                                  payments: Vector[MiningPaymentRecord] = Vector.empty,
                                  difficulty: Option[String] = None,
                                  error: Option[String] = None)

object MiningStatsData {
  implicit val cursorFormat: OFormat[MiningCursor] = Json.format[MiningCursor]
  implicit val blockFormat: OFormat[LithosBlockRecord] = Json.format[LithosBlockRecord]
  implicit val payoutAmountsFormat: OFormat[PayoutAmounts] = Json.format[PayoutAmounts]
  implicit val paymentFormat: OFormat[MiningPaymentRecord] = Json.format[MiningPaymentRecord]
  implicit val lendingFormat: OFormat[LendingRecord] = Json.format[LendingRecord]
  implicit val lendingTotalsFormat: OFormat[LendingTotals] = Json.format[LendingTotals]
  implicit val rollupActivityFormat: OFormat[RollupActivity] = Json.format[RollupActivity]
  implicit val transactionFeeFormat: OFormat[MiningTransactionFee] = Json.format[MiningTransactionFee]
  implicit val batchingFeeFormat: OFormat[BatchingFee] = Json.format[BatchingFee]
  implicit val ledgerEventFormat: OFormat[LedgerEvent] = Json.format[LedgerEvent]
  implicit val registrationFormat: OFormat[MinerRegistrationActivity] = Json.format[MinerRegistrationActivity]
  implicit val activityFormat: OFormat[MiningActivity] = Json.format[MiningActivity]
  implicit val accountingFormat: OFormat[MiningAccountingTotals] = Json.format[MiningAccountingTotals]
  implicit val bucketFormat: OFormat[MiningBucket] = Json.format[MiningBucket]
  implicit val fraudObservationFormat: OFormat[FraudObservation] = Json.format[FraudObservation]
  // Defaults fill fields absent from observations persisted before they existed.
  implicit val localObservationFormat: OFormat[LocalMiningObservation] =
    Json.using[Json.WithDefaultValues].format[LocalMiningObservation]
  implicit val recordFormat: OFormat[MiningBlockRecord] = Json.format[MiningBlockRecord]
  implicit val totalsFormat: OFormat[MiningTotals] = Json.format[MiningTotals]
  implicit val stateFormat: OFormat[MiningLedgerState] = Json.format[MiningLedgerState]
  private val viewFields = Json.writes[MiningStatsView]
  implicit val viewWrites: OWrites[MiningStatsView] = OWrites { view =>
    // Preserve the existing /stats response; expanded statistics use dedicated API responses.
    val totals = Json.obj("blocks" -> view.totals.blocks, "payments" -> view.totals.payments,
      "grossPaidNanoErg" -> view.totals.grossPaidNanoErg)
    val payments = JsArray(view.payments.map(p => paymentFormat.writes(p) - "amounts" - "minerHash" - "local"))
    viewFields.writes(view) ++ Json.obj("totals" -> totals, "payments" -> payments, "totalsScope" -> "retained-history",
      "blockScope" -> "configured-protocol", "paymentScope" -> "primary-mining-address",
      "paymentAmountScope" -> "gross-output-including-bond-refund")
  }
}
