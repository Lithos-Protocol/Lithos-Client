package transactions.batching.sigmafi.zc

import node.model.NodeAsset
import sigmafi.zc.{ZcContracts, ZcMarket}

/**
 * A simple block yield moving in a straight line between two heights: `startBps` up to `start`, `endBps` from `end`
 * on. Yields are basis points per 262,800 blocks. The yield at a height is exactly `rate / span`, as the yield pricing
 * expressions compare it.
 */
final case class YieldLine(start: Long, startBps: Long, end: Long, endBps: Long) {
  def valid: Boolean = startBps >= 0L && endBps >= 0L && end > start

  /** The yield at `height` as `(rate, span)`: exactly `rate / span` bps. */
  def at(height: Long): (BigInt, BigInt) = {
    val h = height.max(start).min(end)
    val span = BigInt(end - start)
    (BigInt(startBps) * span + (BigInt(endBps) - startBps) * (h - start), span)
  }

  /** The yield at `height` in whole bps, rounded down. */
  def bpsAt(height: Long): Long = { val (r, s) = at(height); (r / s).toLong }

  /** The least paid for `units` of `whole` of a bond repaying `repayment`, `blocksLeft` out, at `height`, rounded up. */
  def price(repayment: Long, units: Long, whole: Long, blocksLeft: Long, height: Long): BigInt = {
    val (rate, span) = at(height)
    val num = BigInt(units) * repayment * ZcPricing.Year * span
    val den = BigInt(whole) * (ZcPricing.Year * span + rate * blocksLeft)
    (num + den - 1) / den
  }
}

/**
 * A lend offer's required yield by blocks left to maturity: straight lines between `(bps, blocks)` points, flat
 * beyond the first and last, never below `minBps`. With no points it is `minBps` everywhere. [[accepts]] compares
 * exactly as the offer does.
 */
final case class YieldCurve(minBps: Long, points: Seq[(Long, Long)]) {
  def ordered: Boolean = ZcPricing.ordered(points)

  /** The yield required at `blocksLeft` as `(rate, span)`. */
  def at(blocksLeft: Long): (BigInt, BigInt) = {
    val (rate, span) = ZcPricing.interpolate(points, blocksLeft).getOrElse((BigInt(minBps), BigInt(1)))
    (rate.max(BigInt(minBps) * span), span)
  }

  /** The yield required at `blocksLeft` in whole bps, rounded up. */
  def bpsAt(blocksLeft: Long): Long = { val (r, s) = at(blocksLeft); ((r + s - 1) / s).toLong }

  /** Whether paying `cost` for `units` of `whole` of a bond repaying `repayment`, `blocksLeft` out, meets the curve. */
  def accepts(repayment: Long, units: Long, cost: Long, whole: Long, blocksLeft: Long): Boolean = {
    val (rate, span) = at(blocksLeft)
    BigInt(repayment) * units * ZcPricing.Year * span >= BigInt(cost) * whole * (ZcPricing.Year * span + rate * blocksLeft)
  }

  /** The most the offer pays for `units` of `whole` (an ERG offer's fee included), rounded down. */
  def ceiling(repayment: Long, units: Long, whole: Long, blocksLeft: Long): BigInt = {
    val (rate, span) = at(blocksLeft)
    BigInt(repayment) * units * ZcPricing.Year * span / (BigInt(whole) * (ZcPricing.Year * span + rate * blocksLeft))
  }
}

/**
 * A window order's curve (`CurveOrder_Pricing`): the most it pays at each number of blocks left, the mirror of
 * [[YieldCurve]], never above `maxBps`. With no points it is `maxBps` everywhere.
 */
final case class BorrowCurve(maxBps: Long, points: Seq[(Long, Long)]) {
  def valid: Boolean = maxBps >= 0L && points.size <= ZcBoxes.MaxCurvePoints && ZcPricing.ordered(points) &&
    points.forall(_._1 >= 0L)

  def at(blocksLeft: Long): (BigInt, BigInt) = {
    val (rate, span) = ZcPricing.interpolate(points, blocksLeft).getOrElse((BigInt(maxBps), BigInt(1)))
    (rate.min(BigInt(maxBps) * span), span)
  }

  def bpsAt(blocksLeft: Long): Long = { val (r, s) = at(blocksLeft); (r / s).toLong }

  /** The least principal for a bond repaying `repayment`, `blocksLeft` out: ceil(R B L / (B L + Y D)). */
  def price(repayment: Long, blocksLeft: Long): BigInt = {
    val (rate, span) = at(blocksLeft)
    val num = BigInt(repayment) * ZcPricing.Year * span
    val den = ZcPricing.Year * span + rate * blocksLeft
    (num + den - 1) / den
  }

  def breakpoints: Seq[Long] = points.map(_._2)
}

/** The zcs1 constants and the pure arithmetic every ZC quote, check and match shares. Exact, in `BigInt`. */
object ZcPricing {

  /** 262,800 blocks times the basis-point denominator, as every yield test uses it. */
  val Year: BigInt = BigInt(2628000000L)
  final val BlocksPerYear = 262800L

  /** Blocks a quote covers past the current height: a two-point yield can move the price either way. */
  final val QuoteBlocks = 3L

  /** zcs1's maturity grid: about 30 days of 2-minute blocks. */
  final val Grid = 21600L

  final val MinBatchingFee = 2000000L
  final val RecollateralizeWait = 60L
  final val RefreshAge = 525600L
  final val RefreshBounty = 10000000L
  final val RefreshMaturityGap = 720L
  final val MaxOracleAge = 100000L

  /** zcs1's share count: 100 shares with 6 decimals. */
  final val ShareCount = 100000000L

  /** The yield of a guarded fixed-price ask, so large that its floor is what a buyer pays. */
  final val GuardedFixedYield = 1000000000000000L

  def ordered(points: Seq[(Long, Long)]): Boolean =
    points.iterator.map(_._2).sliding(2).forall { case Seq(a, b) => b > a; case _ => true }

  /** The curve through `points` at `blocksLeft` as `(rate, span)`, flat beyond the ends; None with no points. */
  private[zc] def interpolate(points: Seq[(Long, Long)], blocksLeft: Long): Option[(BigInt, BigInt)] =
    if (points.isEmpty) None
    else {
      val below = points.count(_._2 <= blocksLeft)
      Some(
        if (below == 0) (BigInt(points.head._1), BigInt(1))
        else if (below == points.size) (BigInt(points.last._1), BigInt(1))
        else {
          val (y1, d1) = points(below - 1)
          val (y2, d2) = points(below)
          val l = BigInt(d2 - d1)
          (BigInt(y1) * l + (BigInt(y2) - y1) * (blocksLeft - d1), l)
        })
    }

  /** `(bps, blocks)` pairs from a flat curve tail. */
  def pairs(tail: Seq[Long]): Seq[(Long, Long)] = tail.grouped(2).collect { case Seq(y, d) => (y, d) }.toSeq

  /**
   * A maturity at least one grid step away moved to the nearest grid height, a tie going to the later one. A nearer
   * maturity keeps its height: rounding it could shorten a 10-day loan to minutes.
   */
  def snapMaturity(target: Long, height: Long): Long =
    if (target - height < Grid) target
    else {
      val below = Math.floorDiv(target, Grid) * Grid
      Seq(below, below + Grid).minBy(g => (math.abs(g - target), -g))
    }

  /** The grid heights from `from` to `to` inclusive, at most 64. */
  def gridBetween(from: Long, to: Long): Seq[Long] = {
    val first = Math.floorDiv(from + Grid - 1L, Grid) * Grid
    Iterator.iterate(first)(_ + Grid).takeWhile(_ <= to).take(64).toSeq
  }

  /** zcs1's default order expiry: `week` blocks, but never more than half a fixed maturity's remaining term. */
  def defaultOrderExpiry(height: Long, maturity: Option[Long], week: Long): Long =
    maturity.fold(height + week)(h => math.max(height + 2L, math.min(height + week, height + (h - height) / 2L)))

  private def sigUsdHeld(collateral: Seq[NodeAsset]): BigInt =
    collateral.filter(_.tokenId == ZcContracts.SigUsdId).map(t => BigInt(t.amount)).sum

  /**
   * Collateral value and debt in the oracle rule's common unit: C = 100 nanoERG + price SigUSD raw, L = 100 R for an
   * ERG repayment or price R for SigUSD. Other tokens count as zero. None for an unusable price.
   */
  def coverOf(market: ZcMarket, collateralErg: Long, collateral: Seq[NodeAsset], repayment: Long,
              price: Long): Option[(BigInt, BigInt)] =
    if (price <= 0L) None
    else {
      val value = BigInt(collateralErg) * 100 + BigInt(price) * sigUsdHeld(collateral)
      val debt = if (market.tokenId.isEmpty) BigInt(repayment) * 100 else BigInt(price) * repayment
      if (debt <= 0) None else Some(value -> debt)
    }

  /** Cover in basis points of the repayment, capped at `Long.MaxValue`. */
  def ratioBps(cover: (BigInt, BigInt)): Long = (cover._1 * 10000 / cover._2).min(BigInt(Long.MaxValue)).toLong

  /** Whether `cover` is at least `bps` of the repayment. */
  def atLeast(cover: (BigInt, BigInt), bps: Long): Boolean = cover._1 * 10000 >= cover._2 * bps

  /**
   * The oracle price (nanoERG per USD) where collateral crosses `ratioBps`, and whether prices above it put the
   * collateral below the ratio. None when no price moves it across.
   */
  def crossingPrice(market: ZcMarket, collateralErg: Long, collateral: Seq[NodeAsset], repayment: Long,
                    ratioBps: Long): Option[(BigInt, Boolean)] = {
    val sigUsd = sigUsdHeld(collateral)
    if (market.tokenId.isEmpty) {
      // (100 E + P U) 10000 < 100 R ratio  <=>  P < 100 (R ratio - 10000 E) / (10000 U)
      val gap = BigInt(100) * (BigInt(repayment) * ratioBps - BigInt(10000) * collateralErg)
      if (sigUsd <= 0 || gap <= 0) None else Some((gap / (sigUsd * 10000)) -> false)
    } else {
      // (100 E + P U) 10000 < P R ratio  <=>  P > 1000000 E / (R ratio - 10000 U)
      val gap = BigInt(repayment) * ratioBps - sigUsd * 10000
      if (gap <= 0) None else Some((BigInt(1000000) * collateralErg / gap) -> true)
    }
  }

  /** An oracle price in nanoERG per USD as dollars per ERG, to four places. */
  def usdPerErg(price: BigInt): String =
    if (price <= 0) "0"
    else (BigDecimal(1000000000) / BigDecimal(price)).setScale(4, BigDecimal.RoundingMode.HALF_UP).bigDecimal.toPlainString

  /**
   * The gross simple yield of paying `cost` for `face`, `blocksLeft` out, in bps rounded down:
   * `(face / cost - 1) × 262,800 / blocksLeft`. None when nothing is paid or no blocks are left.
   */
  def yieldBps(face: BigInt, cost: BigInt, blocksLeft: Long): Option[Long] =
    if (cost <= 0 || blocksLeft <= 0L) None
    else Some(((face - cost) * Year / (cost * blocksLeft)).max(BigInt(Long.MinValue)).min(BigInt(Long.MaxValue)).toLong)

  /** The same yield scaled by 1,000,000, for ranking: zcs1's matching order compares on it. */
  def rankYield(face: BigInt, cost: BigInt, blocksLeft: Long): BigInt =
    if (cost <= 0) BigInt(Long.MinValue) else (face - cost) * Year * 1000000 / (cost * blocksLeft.max(1L))
}
