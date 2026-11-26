package pricing

import scala.util.Try

/**
 * One ErgoDEX concentrated-liquidity position and its swap rule, in the contract's exact integer maths.
 *
 * The curve trades net reserves `x` and `y`, what the box holds less one locked unit (1,000,000 nanoERG for an ERG
 * side) and less the LP fees it keeps in R5. A swap must leave `(x' + a)(y' + b) >= (x + a)(y + b)` with neither
 * reserve below zero, which bounds the price range. The LP fee is taken on the input side into R5: its growth `f` must
 * satisfy `f * (10^6 - feePpm) >= net * feePpm`. The protocol fee is paid beside the position, to the config's key.
 *
 * @param x       net reserve of the X side (ERG for an ERG position)
 * @param y       net reserve of the Y side
 * @param a       the curve's offset on X (R4(0))
 * @param b       the curve's offset on Y (R4(1))
 * @param feePpm  the LP fee, parts per million (R4(2))
 * @param feesX   LP fees held on X (R5(0))
 * @param feesY   LP fees held on Y (R5(1))
 */
final case class ClPosition(x: Long, y: Long, a: Long, b: Long, feePpm: Long, feesX: Long, feesY: Long) {
  import ClPosition._

  /** Whether the contract accepts this state as a swap's start or end. */
  def valid: Boolean =
    x >= 0L && y >= 0L && a >= 0L && b >= 0L && feesX >= 0L && feesY >= 0L && feePpm >= 0L && feePpm < FeeDenom &&
      BigInt(x) + a > 0 && BigInt(y) + b > 0

  /**
   * The swap that puts `amountIn` in on X when `xIn`, else on Y, everything the trader pays: what reaches the curve,
   * the LP fee and the protocol fee at `protocolPpm` (from [[ClProtocol.rate]]). The curve takes the most it can;
   * whatever is left after both fees joins the LP fee. None when nothing comes out or the contract would refuse it.
   */
  def swap(amountIn: Long, xIn: Boolean, protocolPpm: Long): Option[ClSwap] =
    if (amountIn <= 0L || !valid) None
    else Try {
      val net = netFor(amountIn, protocolPpm)
      val out = curveOut(net, xIn)
      if (net <= 0L || out <= 0L) None
      else {
        val protocolFee = ClPosition.protocolFee(net, protocolPpm)
        settle(amountIn, net, amountIn - net - protocolFee, protocolFee, out, xIn)
      }
    }.toOption.flatten

  /**
   * The least payment that takes exactly `amountOut` out of the other side, with the least net input the curve
   * accepts for it, the least LP fee for that and the protocol fee on it. None when the side cannot pay that much.
   */
  def swapFor(amountOut: Long, xIn: Boolean, protocolPpm: Long): Option[ClSwap] =
    if (amountOut <= 0L || !valid) None
    else Try {
      val (rIn, rOut, offIn, offOut) = sides(xIn)
      if (amountOut > rOut) None
      else {
        val k = (BigInt(rIn) + offIn) * (BigInt(rOut) + offOut)
        val left = BigInt(rOut) - amountOut + offOut
        val net = (ceilDiv(k, left) - offIn - rIn).max(BigInt(1))
        if (!net.isValidLong) None
        else {
          val n = net.toLong
          val lp = lpFee(n)
          val protocolFee = ClPosition.protocolFee(n, protocolPpm)
          val paid = BigInt(n) + lp + protocolFee
          if (!paid.isValidLong) None else settle(paid.toLong, n, lp, protocolFee, amountOut, xIn)
        }
      }
    }.toOption.flatten

  /**
   * Units out per unit paid in for a vanishing input, LP fee included: the slope of the curve at zero, over what one
   * unit of net input costs. The protocol fee rounds to nothing on small inputs, so it is left out and no payment
   * returns more than this rate times itself.
   */
  def marginal(xIn: Boolean): Double = {
    val (rIn, rOut, offIn, offOut) = sides(xIn)
    if (!valid || rOut <= 0L) 0.0
    else (rOut.toDouble + offOut) / (rIn.toDouble + offIn) * (FeeDenom - feePpm).toDouble / FeeDenom
  }

  /** The least LP fee the contract accepts on `net` reaching the curve. */
  def lpFee(net: Long): Long =
    ceilDiv(BigInt(net) * feePpm, BigInt(FeeDenom - feePpm)).toLong

  /** What `net` on the input side buys from the other, capped at that side's reserve. */
  def curveOut(net: Long, xIn: Boolean): Long =
    if (net <= 0L) 0L
    else {
      val (rIn, rOut, offIn, offOut) = sides(xIn)
      val k = (BigInt(rIn) + offIn) * (BigInt(rOut) + offOut)
      val out = BigInt(rOut) + offOut - ceilDiv(k, BigInt(rIn) + offIn + net)
      out.min(BigInt(rOut)).max(BigInt(0)).toLong
    }

  /** The most net input `amountIn` pays for once the least LP fee and the protocol fee on it are taken. */
  private def netFor(amountIn: Long, protocolPpm: Long): Long = {
    def cost(net: Long): BigInt = BigInt(net) + lpFee(net) + ClPosition.protocolFee(net, protocolPpm)
    // cost(net) is near net * D / (D - fee) + net * p / 10^10; start there and step to the exact answer
    val scale = BigInt(ProtocolDenom)
    val guess = BigInt(amountIn) * (FeeDenom - feePpm) * scale /
      (BigInt(FeeDenom) * scale + BigInt(protocolPpm) * (FeeDenom - feePpm))
    var net = guess.max(BigInt(0)).min(BigInt(amountIn)).toLong
    while (net > 0L && cost(net) > amountIn) net -= 1L
    while (net < amountIn && cost(net + 1L) <= amountIn) net += 1L
    net
  }

  /** The swap with `net` reaching the curve and `lp` added to the input side's fees, or None when the contract refuses it. */
  private def settle(paid: Long, net: Long, lp: Long, protocolFee: Long, out: Long, xIn: Boolean): Option[ClSwap] = {
    val after =
      if (xIn) copy(x = Math.addExact(x, net), y = Math.subtractExact(y, out), feesX = Math.addExact(feesX, lp))
      else copy(y = Math.addExact(y, net), x = Math.subtractExact(x, out), feesY = Math.addExact(feesY, lp))
    val k = (BigInt(x) + a) * (BigInt(y) + b)
    val holds = after.valid && lp >= 0L && (BigInt(after.x) + a) * (BigInt(after.y) + b) >= k &&
      BigInt(lp) * (FeeDenom - feePpm) >= BigInt(net) * feePpm
    if (holds) Some(ClSwap(paid, net, lp, protocolFee, out, after)) else None
  }

  /** (input reserve, output reserve, input offset, output offset) for a swap putting in X when `xIn`. */
  private def sides(xIn: Boolean): (Long, Long, Long, Long) = if (xIn) (x, y, a, b) else (y, x, b, a)
}

/**
 * A priced CL swap: `paid` is everything the trader puts in, `net` what reaches the curve, `lpFee` what joins the
 * position's fees, `protocolFee` what goes to the config's key, and `out` what leaves the other side.
 */
final case class ClSwap(paid: Long, net: Long, lpFee: Long, protocolFee: Long, out: Long, after: ClPosition)

/**
 * The protocol fee's parameters from the config box's R5, clamped as the swap script clamps them: `c0` to 0..10,000,
 * `c1` and `c2` to 0..50. On a position with LP fee `feePpm`, the protocol takes min(max(c0 * feePpm, c1 * 10^6),
 * c2 * 10^6) over 10^10 of the net input.
 */
final case class ClProtocol(c0: Long, c1: Long, c2: Long) {

  /** The protocol fee's rate on a position with LP fee `feePpm`, over 10^10. */
  def rate(feePpm: Long): Long =
    (BigInt(c0) * feePpm).max(BigInt(c1) * ClPosition.FeeDenom).min(BigInt(c2) * ClPosition.FeeDenom).toLong
}

object ClProtocol {

  /** The parameters `r5` holds, or None when it holds fewer than three. */
  def of(r5: Seq[Long]): Option[ClProtocol] =
    if (r5.size < 3) None
    else Some(ClProtocol(clamp(r5.head, 10000L), clamp(r5(1), 50L), clamp(r5(2), 50L)))

  private def clamp(v: Long, cap: Long): Long = math.max(0L, math.min(v, cap))
}

object ClPosition {

  /** The LP fee's denominator: fees are parts per million. */
  final val FeeDenom: Long = 1000000L

  /** The protocol fee's denominator. */
  final val ProtocolDenom: Long = 10000000000L

  /** What the protocol takes from `net` at `rate` over 10^10, rounded down; nothing on a non-positive input. */
  def protocolFee(net: Long, rate: Long): Long =
    if (net <= 0L) 0L else (BigInt(net) * rate / ProtocolDenom).toLong

  private def ceilDiv(a: BigInt, b: BigInt): BigInt = (a + b - 1) / b
}
