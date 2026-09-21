package stats

import play.api.libs.json._

final case class MiningCursor(height: Int, blockId: String, parentId: String, timestamp: Long)
final case class LithosBlockRecord(blockId: String, height: Int, timestamp: Long, transactionId: String,
                                    holdingBoxId: String, collateralBoxId: String,
                                    collateralNanoErg: String, initialHoldingNanoErg: String)
/** Gross designated payout output, including the refunded submission bond. This is not net earnings. */
final case class MiningPaymentRecord(transactionId: String, outputId: String, payoutBoxId: String,
                                      rollupNft: String, minedBlockId: String, minedHeight: Int,
                                      blockId: String, height: Int, timestamp: Long, grossNanoErg: String,
                                      amounts: Option[PayoutAmounts] = None, minerHash: Option[String] = None,
                                      local: Boolean = true)
final case class MiningBlockRecord(cursor: MiningCursor, previous: MiningCursor, difficulty: String,
                                    blocks: Vector[LithosBlockRecord], payments: Vector[MiningPaymentRecord],
                                    activity: MiningActivity = MiningActivity(), transactionFeesNanoErg: String = "0")
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
final case class MiningDifficultyPoint(height: Int, timestamp: Long, difficulty: String)
final case class MiningStatsView(status: String = "loading", observedAt: Option[Long] = None,
                                  persistent: Boolean = true, sourceHeight: Option[Int] = None,
                                  sourceBlockId: Option[String] = None, targetHeight: Option[Int] = None,
                                  fromHeight: Option[Int] = None, fromTimestamp: Option[Long] = None,
                                  recentFromHeight: Option[Int] = None, totals: MiningTotals = MiningTotals(),
                                  blocks: Vector[LithosBlockRecord] = Vector.empty,
                                  payments: Vector[MiningPaymentRecord] = Vector.empty,
                                  difficulty: Vector[MiningDifficultyPoint] = Vector.empty,
                                  error: Option[String] = None)

object MiningStatsData {
  implicit val cursorFormat: OFormat[MiningCursor] = Json.format[MiningCursor]
  implicit val blockFormat: OFormat[LithosBlockRecord] = Json.format[LithosBlockRecord]
  implicit val payoutAmountsFormat: OFormat[PayoutAmounts] = Json.format[PayoutAmounts]
  implicit val paymentFormat: OFormat[MiningPaymentRecord] = Json.format[MiningPaymentRecord]
  implicit val rollupActivityFormat: OFormat[RollupActivity] = Json.format[RollupActivity]
  implicit val transactionFeeFormat: OFormat[MiningTransactionFee] = Json.format[MiningTransactionFee]
  implicit val batchingFeeFormat: OFormat[BatchingFee] = Json.format[BatchingFee]
  implicit val registrationFormat: OFormat[MinerRegistrationActivity] = Json.format[MinerRegistrationActivity]
  implicit val activityFormat: OFormat[MiningActivity] = Json.format[MiningActivity]
  implicit val accountingFormat: OFormat[MiningAccountingTotals] = Json.format[MiningAccountingTotals]
  implicit val bucketFormat: OFormat[MiningBucket] = Json.format[MiningBucket]
  implicit val fraudObservationFormat: OFormat[FraudObservation] = Json.format[FraudObservation]
  implicit val localObservationFormat: OFormat[LocalMiningObservation] = Json.format[LocalMiningObservation]
  implicit val recordFormat: OFormat[MiningBlockRecord] = Json.format[MiningBlockRecord]
  implicit val totalsFormat: OFormat[MiningTotals] = Json.format[MiningTotals]
  implicit val stateFormat: OFormat[MiningLedgerState] = Json.format[MiningLedgerState]
  implicit val difficultyWrites: OWrites[MiningDifficultyPoint] = Json.writes[MiningDifficultyPoint]
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
