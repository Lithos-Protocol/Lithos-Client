package pricing

/**
 * How a pool kind prices a swap, in exact integer maths rounded as its contract rounds, so an amount computed here
 * is one the contract accepts. Every pool kind this client trades through uses one of these: ErgoDEX pools of both
 * shapes, and LithosDex ERG/token and token/token pools. The reserves passed in are what the curve trades with,
 * which for a LithosDex pool is its balance less the fees it holds for its vault.
 *
 * Every pool checks its curve with an inequality, so an input may take less than it buys and the rest stays in the
 * pool. `out` is the most an input buys; `in` is the least input that buys an output.
 */
sealed trait SwapCurve {

  /** What leaves `amountIn` before the curve: a LithosDex pool's side fee, nothing on ErgoDEX. */
  def sideFee(amountIn: Long): Long

  /** What `amountIn` buys from `reserveOut`, rounded down. 0 when nothing reaches the curve or `amountIn` is not positive. */
  def out(reserveIn: Long, reserveOut: Long, amountIn: Long): Long

  /**
   * The least input that buys at least `amountOut`. None when `amountOut` is not positive, not under `reserveOut`
   * (the curve only approaches its reserve), `reserveIn` is empty, or the input would not fit a Long.
   */
  def in(reserveIn: Long, reserveOut: Long, amountOut: Long): Option[Long]

  /**
   * Units out per unit in for a vanishing input, fees included: the slope of `out` at zero. 0 for an empty
   * `reserveIn`. No input buys more than this rate times itself, except where a truncated side fee lets one more
   * unit reach the curve.
   */
  def marginal(reserveIn: Long, reserveOut: Long): Double
}

object SwapCurve {

  /** ErgoDEX's fee denominator, on both pool shapes. */
  final val ErgoDexFeeDenominator: Long = 1000L

  /** LithosDex's fee denominator, for the dex fee and both side fees. */
  final val LithosDexFeeDenominator: Long = lithosdex.LDHelpers.FEE_DENOM

  /** An ErgoDEX pool, either shape: `feeNum` over 1,000 taken on the curve, no side fee. */
  final case class ErgoDex(feeNum: Long) extends SwapCurve {
    override def sideFee(amountIn: Long): Long = 0L

    override def out(reserveIn: Long, reserveOut: Long, amountIn: Long): Long =
      curveOut(reserveIn, reserveOut, amountIn, feeNum, ErgoDexFeeDenominator)

    override def in(reserveIn: Long, reserveOut: Long, amountOut: Long): Option[Long] =
      curveIn(reserveIn, reserveOut, amountOut, feeNum, ErgoDexFeeDenominator)

    override def marginal(reserveIn: Long, reserveOut: Long): Double =
      if (reserveIn <= 0L || reserveOut <= 0L || feeNum <= 0L || feeNum > ErgoDexFeeDenominator) 0.0
      else reserveOut.toDouble * feeNum / (reserveIn.toDouble * ErgoDexFeeDenominator)
  }

  /**
   * A LithosDex pool: the side fee for the input's side, `sideFeeNum` over 10,000, leaves the input first, and the
   * rest reaches a curve that keeps `dexFeeNum` over 10,000 of it. The ERG/token contract truncates the side fee;
   * the token/token contract rounds it up, so any side fee there costs at least one unit.
   */
  sealed abstract class LithosDex(val dexFeeNum: Long, val sideFeeNum: Long, roundsUp: Boolean) extends SwapCurve {
    private val D = LithosDexFeeDenominator
    private val validFees: Boolean = dexFeeNum > 0L && dexFeeNum <= D && sideFeeNum >= 0L && sideFeeNum < D

    override def sideFee(amountIn: Long): Long =
      if (amountIn <= 0L || sideFeeNum <= 0L || sideFeeNum >= D) 0L
      else {
        val fee = BigInt(amountIn) * sideFeeNum
        (if (roundsUp) ceilDiv(fee, D) else fee / D).toLong
      }

    override def out(reserveIn: Long, reserveOut: Long, amountIn: Long): Long =
      if (amountIn <= 0L || !validFees) 0L
      else curveOut(reserveIn, reserveOut, amountIn - sideFee(amountIn), dexFeeNum, D)

    override def in(reserveIn: Long, reserveOut: Long, amountOut: Long): Option[Long] =
      if (!validFees) None
      else curveIn(reserveIn, reserveOut, amountOut, dexFeeNum, D).flatMap { traded =>
        // The least input that leaves `traded` after the side fee. Both closed forms are exact; stepping up guards them
        val denom = BigInt(D - sideFeeNum)
        val start =
          if (roundsUp) ceilDiv(BigInt(traded) * D, denom)
          else BigInt(traded - 1) * D / denom + 1
        if (start <= 0 || !start.isValidLong) None
        else {
          var amountIn = start.toLong
          var steps = 0
          while (amountIn - sideFee(amountIn) < traded && steps < MaxStepUp && amountIn < Long.MaxValue) {
            amountIn += 1L
            steps += 1
          }
          if (amountIn - sideFee(amountIn) >= traded) Some(amountIn) else None
        }
      }

    override def marginal(reserveIn: Long, reserveOut: Long): Double = {
      val d = D.toDouble
      if (reserveIn <= 0L || reserveOut <= 0L || !validFees) 0.0
      else (d - sideFeeNum) / d * (dexFeeNum / d) * (reserveOut.toDouble / reserveIn)
    }
  }

  /** A LithosDex ERG/token pool, whose contract truncates the side fee. */
  final case class LithosDexNative(override val dexFeeNum: Long, override val sideFeeNum: Long)
    extends LithosDex(dexFeeNum, sideFeeNum, roundsUp = false)

  /** A LithosDex token/token pool, whose contract rounds the side fee up. */
  final case class LithosDexToken(override val dexFeeNum: Long, override val sideFeeNum: Long)
    extends LithosDex(dexFeeNum, sideFeeNum, roundsUp = true)

  private final val MaxStepUp = 4

  /**
   * The constant-product curve every pool kind shares, with `feeNum / feeDenom` of the input kept by the pool:
   * `reserveOut · in · feeNum / (reserveIn · feeDenom + in · feeNum)`, rounded down.
   */
  def curveOut(reserveIn: Long, reserveOut: Long, amountIn: Long, feeNum: Long, feeDenom: Long): Long =
    if (amountIn <= 0L || reserveIn <= 0L || reserveOut <= 0L || feeNum <= 0L || feeDenom <= 0L || feeNum > feeDenom) 0L
    else {
      val withFee = BigInt(amountIn) * feeNum
      val out = BigInt(reserveOut) * withFee / (BigInt(reserveIn) * feeDenom + withFee)
      if (out.isValidLong) out.toLong else 0L
    }

  /**
   * The least input the curve accepts for `amountOut`: the contract checks
   * `amountOut · (reserveIn · feeDenom + in · feeNum) ≤ reserveOut · in · feeNum`, solved for `in` and rounded up.
   */
  def curveIn(reserveIn: Long, reserveOut: Long, amountOut: Long, feeNum: Long, feeDenom: Long): Option[Long] =
    if (amountOut <= 0L || amountOut >= reserveOut || reserveIn <= 0L ||
      feeNum <= 0L || feeDenom <= 0L || feeNum > feeDenom) None
    else {
      val in = ceilDiv(BigInt(amountOut) * reserveIn * feeDenom, BigInt(feeNum) * (reserveOut - amountOut))
      if (in.isValidLong) Some(in.toLong) else None
    }

  private def ceilDiv(a: BigInt, b: BigInt): BigInt = (a + b - 1) / b
}
