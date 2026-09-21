package api

import api.LithosApiErrors.LithosBadRequest
import api.models._
import lithosdex.LDHelpers
import transactions.batching.lithosdex.LDBoxes.{PoolSnapshot, PoolTransition, TransitionKind}

/** Shared graph calculations; timestamp lookup is supplied by the caller or an in-memory snapshot. */
object DexHistory {
  def priceRange(range: Option[String], bucket: Option[Int]): (String, Int, Int) = {
    val name = range.map(_.trim.toUpperCase(java.util.Locale.ROOT)).getOrElse(LDPriceHistory.Default)
    val blocks = LDPriceHistory.Ranges.getOrElse(name,
      throw LithosBadRequest(s"'range' must be one of ${LDPriceHistory.Ranges.keys.toSeq.sorted.mkString(", ")}"))
    val size = bucket.getOrElse(math.max(1, blocks / LDPriceHistory.TargetPoints))
    // Include the carried-in price and the current observation as well as the height buckets.
    if (size <= 0 || blocks.toLong / size + 3 > LDPriceHistory.MaxPoints)
      throw LithosBadRequest(s"'bucket' must be positive and return at most ${LDPriceHistory.MaxPoints} points")
    (name, blocks, size)
  }

  def spotPrice(reservesX: Long, reservesY: Long, decimals: Int): Double =
    if (reservesX <= 0 || reservesY <= 0) 0.0
    else ((BigDecimal(reservesY) / BigDecimal(10).pow(decimals)) /
      (BigDecimal(reservesX) / BigDecimal(10).pow(9))).toDouble

  def price(snapshots: Seq[PoolSnapshot], complete: Boolean, current: LDPricePoint, decimals: Int,
            range: Option[String], bucket: Option[Int], timestamps: Seq[Int] => Map[Int, Long]): LDPriceHistory = {
    val (name, blocks, size) = priceRange(range, bucket)
    val lo = math.max(0, current.height - blocks)
    val start = snapshots.filter(_.height <= lo).lastOption.map(_.copy(height = lo))
    val selected = start.toVector ++ snapshots.filter(s => s.height > lo && s.height <= current.height)
      .groupBy(s => (s.height - lo) / size).toVector.sortBy(_._1)
      .map { case (_, points) => points.maxBy(s => (s.height, s.globalIndex)) }
    val times = timestamps(selected.map(_.height))
    val points = selected.map(s => LDPricePoint(s.height, times.get(s.height),
      spotPrice(s.reservesX, s.reservesY, decimals), s.reservesX.toString, s.reservesY.toString))
    val series = (if (points.lastOption.exists(_.height >= current.height)) points.init else points) :+ current
    val first = series.head.price
    LDPriceHistory(name, if (first <= 0) 0.0 else (current.price - first) / first,
      partial = !complete || !snapshots.headOption.exists(_.height <= lo), history = series)
  }

  def fees(snapshots: Seq[PoolSnapshot], complete: Boolean, lo: Int, hi: Int, size: Int,
           timestamps: Seq[Int] => Map[Int, Long]): LDFeeHistory = {
    if (lo < 0 || hi < lo || size <= 0 || (hi.toLong - lo) / size + 1 > LDPriceHistory.MaxPoints)
      throw LithosBadRequest("invalid fee range or bucket: at most 500 points are supported")
    var x = BigInt(0)
    var y = BigInt(0)
    def fee(delta: BigInt, supply: Long): BigInt =
      if (delta <= 0 || supply <= 0) BigInt(0) else delta * supply / LDHelpers.SCALE
    val cumulative = snapshots.sliding(2).collect {
      case Seq(prev, next) if next.height >= lo && next.height <= hi =>
        x += fee(next.accX - prev.accX, prev.supply)
        y += fee(next.accY - prev.accY, prev.supply)
        (next.height, x, y)
    }.toVector
    val buckets = cumulative.groupBy(p => (p._1 - lo) / size).toVector.sortBy(_._1).map {
      case (index, points) => (math.min(lo.toLong + (index.toLong + 1) * size - 1, hi).toInt,
        points.last._2, points.last._3)
    }
    val times = timestamps(buckets.map(_._1))
    val previous = (BigInt(0), BigInt(0)) +: buckets.map(p => p._2 -> p._3)
    LDFeeHistory(buckets.zip(previous).map { case ((height, cx, cy), (px, py)) =>
      LDFeeHistoryPoint(height, times.get(height), (cx - px).toString, (cy - py).toString,
        cx.toString, cy.toString)
    }, partial = !complete)
  }

  def activity(transitions: Seq[PoolTransition], times: Map[Int, Long]): LDRecentActivity =
    LDRecentActivity(transitions.map { t =>
      val liquidity = t.kind != TransitionKind.Swap
      LDActivityEntry(t.txId, t.kind.name, if (t.orderBoxId.isDefined) "ORDER" else "DIRECT", t.orderBoxId,
        if (t.height.isDefined) "CONFIRMED" else "MEMPOOL", t.height, t.height.flatMap(times.get),
        t.swap.map(_.ergIn), t.swap.map(_.amountIn.toString), t.swap.map(_.amountOut.toString),
        if (liquidity) Some(t.amountX.toString) else None,
        if (liquidity) Some(t.amountY.toString) else None,
        if (liquidity && t.kind != TransitionKind.Flush) Some(t.shares.toString) else None)
    }.toVector)
}
