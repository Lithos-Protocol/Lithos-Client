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

/**
 * The bids waiting in the queue. The queue activates boxes strictly in order, and live boxes then compete by
 * bid in whole 0.001 ERG steps, so a new box bidding a step above every queued bid is taken ahead of all of them.
 *
 * @param bestStepBoxes queued boxes whose bid falls in the best bid's step, and so tie with it
 * @param firstNanoErg  the least priority fee in the step above the best queued bid: the bid to be first
 */
final case class CollateralQueueStats(boxes: Int = 0, bidding: Int = 0, unreadable: Int = 0, bestNanoErg: String = "0",
                                       bestStepBoxes: Int = 0, firstNanoErg: String = RollupProtocol.MinPriorityFee.toString,
                                       partial: Boolean = true)

object CollateralQueueStats {
  def of(fees: Vector[Long], unreadable: Int, partial: Boolean): CollateralQueueStats = {
    val step = mining.CandidateTxBuilder.BidStep
    val best = if (fees.isEmpty) 0L else fees.max
    CollateralQueueStats(fees.size + unreadable, fees.count(_ > 0L), unreadable, best.toString,
      fees.count(_ / step == best / step), ((best / step + 1) * step).toString, partial)
  }
}

/**
 * What lenders of boxes selected one way put in and got back over the retained history.
 *
 * @param netNanoErg what came back less what was posted, signed
 * @param aprPercent the net over every box's ERG locked from its join until its reward unlocks, as a simple
 *                   yearly rate: pooled, so a large box weighs more than a small one. None with nothing locked
 */
final case class CollateralPathStats(boxes: Long, meanPriorityFeeNanoErg: Option[String], meanWaitBlocks: Option[Long],
                                     costNanoErg: String, receivedNanoErg: String, netNanoErg: String,
                                     aprPercent: Option[Double])

/**
 * Lender returns by how each spent box was selected: by bid, or through clearance once at least `clearanceAge`
 * blocks old. Lithos blocks recorded before selections were (or whose activation the index does not show) are
 * in `lithosBlocks` but not `coveredBlocks`, and in neither path.
 */
final case class CollateralSelectionStats(lithosBlocks: Long, coveredBlocks: Long, fromHeight: Option[Int],
                                          clearanceAge: Int, bid: CollateralPathStats, clearance: CollateralPathStats)

object CollateralSelectionStats {
  /** Ergo's two-minute target block time, in blocks a year. */
  final val BlocksPerYear: Int = 262800

  def of(totals: MiningAccountingTotals, fromHeight: Option[Int]): CollateralSelectionStats = {
    def path(name: String): CollateralPathStats = {
      def amount(key: String) = totals.amount(s"collateral.$name.$key")
      val boxes = amount("boxes")
      val (cost, received, capital) = (amount("costNanoErg"), amount("receivedNanoErg"), amount("capitalNanoErgBlocks"))
      val net = received - cost
      CollateralPathStats(boxes.toLong,
        if (boxes > 0) Some((amount("priorityFeeNanoErg") / boxes).toString) else None,
        if (boxes > 0) Some((amount("waitBlocks") / boxes).toLong) else None,
        cost.toString, received.toString, net.toString,
        if (capital > 0) Some((BigDecimal(net) * BlocksPerYear * 100 / BigDecimal(capital)).setScale(2,
          BigDecimal.RoundingMode.HALF_EVEN).toDouble) else None)
    }
    val (bid, clearance) = (path("bid"), path("clearance"))
    CollateralSelectionStats(totals.amount("lithos.blocks").toLong, bid.boxes + clearance.boxes, fromHeight,
      configs.CandidateConfig.Default.clearanceAge, bid, clearance)
  }
}

final case class CollateralStats(status: String = "loading", observedAt: Option[Long] = None,
                                   source: Option[MiningCursor] = None, boxes: Int = 0,
                                   nanoErg: String = "0", partial: Boolean = true,
                                   fees: CollateralFeeStats = CollateralFeeStats(),
                                   error: Option[String] = None, queue: CollateralQueueStats = CollateralQueueStats())
