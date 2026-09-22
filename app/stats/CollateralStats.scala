package stats

import lfsm.RollupProtocol

/** One band of the bid distribution. `to` is absent on the open-ended top band. */
final case class CollateralFeeBucket(fromNanoErg: String, toNanoErg: Option[String], boxes: Int)

/**
 * The priority fees carried by the observed collateral boxes — the book a miner picks from.
 *
 * Amounts are the WHOLE fee a box adds above the floor, not the share the finder keeps out of it.
 * Percentiles cover every observed box, fee and floor alike, so they describe the distribution a
 * lender is actually competing against rather than the paying boxes alone. With most boxes at the
 * floor the median is 0, which is the honest answer. `best` is the fee to beat.
 */
final case class CollateralFeeStats(atFloor: Int = 0, bidding: Int = 0, unreadable: Int = 0,
                                      totalNanoErg: String = "0", bestNanoErg: String = "0",
                                      medianNanoErg: String = "0", p90NanoErg: String = "0",
                                      buckets: Seq[CollateralFeeBucket] = Seq.empty)

object CollateralFeeStats {
  /**
   * The smallest fee anyone may post, then quarters of break-even, then everything above it.
   * Derived rather than written out, so the bands track the constants they describe.
   */
  private val edges: Seq[Long] = {
    val breakEven = RollupProtocol.breakEvenPriorityFee
    Seq(RollupProtocol.MinPriorityFee, breakEven / 4, breakEven / 2, breakEven * 3 / 4, breakEven)
  }

  /** Nearest-rank percentile over the sorted fees, which avoids inventing a value no box carries. */
  private def percentile(sorted: Vector[Long], fraction: Double): Long =
    if (sorted.isEmpty) 0L
    else sorted(math.min(sorted.size - 1, math.max(0, math.ceil(fraction * sorted.size).toInt - 1)))

  def of(fees: Vector[Long], unreadable: Int): CollateralFeeStats = {
    val sorted = fees.sorted
    // The last band is open-ended, so it is counted with no upper test at all. Bounding it by
    // Long.MaxValue instead would drop a bid sitting exactly on that value.
    val banded = edges.zipWithIndex.map { case (from, i) =>
      edges.lift(i + 1) match {
        case Some(until) => CollateralFeeBucket(from.toString, Some((until - 1).toString),
          sorted.count(fee => fee >= from && fee < until))
        case None => CollateralFeeBucket(from.toString, None, sorted.count(_ >= from))
      }
    }
    CollateralFeeStats(
      atFloor = sorted.count(_ == 0L),
      bidding = sorted.count(_ > 0L),
      unreadable = unreadable,
      totalNanoErg = sorted.foldLeft(BigInt(0))(_ + _).toString,
      bestNanoErg = sorted.lastOption.getOrElse(0L).toString,
      medianNanoErg = percentile(sorted, 0.5).toString,
      p90NanoErg = percentile(sorted, 0.9).toString,
      buckets = CollateralFeeBucket("0", Some("0"), sorted.count(_ == 0L)) +: banded)
  }
}

final case class CollateralStats(status: String = "loading", observedAt: Option[Long] = None,
                                   source: Option[MiningCursor] = None, boxes: Int = 0,
                                   nanoErg: String = "0", partial: Boolean = true,
                                   fees: CollateralFeeStats = CollateralFeeStats(),
                                   error: Option[String] = None)
