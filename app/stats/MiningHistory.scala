package stats

/** UTC buckets retain exact sums and counts; callers choose their rounding at display time. */
final case class MiningBucket(start: Long, widthMs: Long, totals: MiningAccountingTotals)
final case class MiningBucketHistory(source: MiningCursor, retainedFrom: Option[Long],
                                        from: Long, until: Long, widthMs: Long,
                                        buckets: Vector[MiningBucket], partial: Boolean, status: String = "unverified")
final case class MiningHistoryPage(source: MiningCursor, retainedFromHeight: Int,
                                      records: Vector[MiningBlockRecord], nextHeight: Option[Int], status: String = "unverified")
final case class LithosHashrateEstimate(status: String, sourceStatus: String, source: MiningCursor,
                                           from: Long, until: Long, sampleCount: Long, difficultySum: String,
                                           hashesPerSecond: Option[String], relativeSamplingError: Option[Double],
                                           partial: Boolean,
                                           population: String = "miners-producing-configured-lithos-genesis",
                                           formula: String = "sum-confirmed-lithos-block-difficulty/covered-seconds")

object MiningHistory {
  val HourMs: Long = 3600000L
  val DayMs: Long = 86400000L
  val MaxResults: Int = 500
  def bucketStart(timestamp: Long, width: Long): Long = timestamp / width * width
  def validateRange(from: Long, until: Long, width: Long): Unit = {
    require(width == HourMs || width == DayMs, "mining buckets must be one hour or one day")
    require(from >= 0 && until > from && from % width == 0 && until % width == 0,
      "mining history range must use UTC bucket boundaries")
    require((BigInt(until) - from) / width <= MaxResults, "mining history exceeds 500 buckets")
  }

  /** Block-sampling estimate, independent of NISP claims. The error term assumes Poisson arrivals. */
  def hashrate(history: MiningBucketHistory): LithosHashrateEstimate = {
    val from = math.max(history.from, history.retainedFrom.getOrElse(history.until))
    val until = math.min(history.until, history.source.timestamp)
    val samples = history.buckets.map(_.totals.amount("lithos.blocks")).sum
    val work = history.buckets.map(_.totals.amount("lithos.difficultySum")).sum
    require(samples.isValidLong, "hashrate sample count exceeds Long range")
    val rate = if (until > from && samples > 0 && work > 0)
      Some((BigDecimal(work) * 1000 / BigDecimal(until - from)).bigDecimal.stripTrailingZeros.toPlainString) else None
    val status = if (rate.isEmpty) "insufficient-data" else if (samples < 20) "sparse" else "estimated"
    LithosHashrateEstimate(status, history.status, history.source, from, math.max(from, until), samples.toLong,
      work.toString, rate, if (samples > 0) Some(1.0 / math.sqrt(samples.toDouble)) else None, history.partial)
  }
}
