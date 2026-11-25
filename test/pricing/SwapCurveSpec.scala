package pricing

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Random

/**
 * The swap maths every pool kind prices with, held to each contract's own check rather than to a copy of the
 * formula: `out` is the most an input buys under it, and `in` the least input that buys an output. The mainnet
 * replays in the arb, ErgoDEX and LithosDex specs hold the same maths against real swaps.
 */
class SwapCurveSpec extends AnyFlatSpec with Matchers {

  private val D = SwapCurve.LithosDexFeeDenominator

  /** Whether the pool's contract accepts `amountOut` for `amountIn`: `out · (rIn·denom + t·fee) ≤ rOut · t · fee`. */
  private def accepts(curve: SwapCurve, rIn: Long, rOut: Long, amountIn: Long, amountOut: Long): Boolean = {
    val (traded, fee, denom) = curve match {
      case SwapCurve.ErgoDex(feeNum) => (amountIn, feeNum, SwapCurve.ErgoDexFeeDenominator)
      case ld: SwapCurve.LithosDex => (amountIn - ld.sideFee(amountIn), ld.dexFeeNum, D)
    }
    traded > 0 && amountOut < rOut &&
      BigInt(amountOut) * (BigInt(rIn) * denom + BigInt(traded) * fee) <= BigInt(rOut) * traded * fee
  }

  private val rnd = new Random(7L)
  private def reserve(): Long = rnd.nextInt(3) match {
    case 0 => 1000L + rnd.nextInt(100000)
    case 1 => 1000L + (rnd.nextDouble() * 1e12).toLong
    case _ => 1000L + (rnd.nextDouble() * 1e18).toLong
  }
  private def curves(): Seq[SwapCurve] = {
    val side = rnd.nextInt(300).toLong
    val dex = 9900L + rnd.nextInt(101)
    Seq(SwapCurve.ErgoDex(990L + rnd.nextInt(11)), SwapCurve.LithosDexNative(dex, side), SwapCurve.LithosDexToken(dex, side))
  }

  "Every pool kind's curve" should "return the most its contract accepts for an input, and no more" in {
    (1 to 20000).foreach { _ =>
      val (rIn, rOut) = (reserve(), reserve())
      val amountIn = 1L + (rnd.nextDouble() * rIn).toLong
      curves().foreach { curve =>
        val out = curve.out(rIn, rOut, amountIn)
        withClue(s"$curve $rIn/$rOut in $amountIn out $out: ") {
          if (out > 0) accepts(curve, rIn, rOut, amountIn, out) shouldBe true
          accepts(curve, rIn, rOut, amountIn, out + 1) shouldBe false
        }
      }
    }
  }

  it should "ask the least input its contract accepts for an output" in {
    (1 to 20000).foreach { _ =>
      val (rIn, rOut) = (reserve(), reserve())
      val amountOut = 1L + (rnd.nextDouble() * (rOut - 1)).toLong
      curves().foreach { curve =>
        withClue(s"$curve $rIn/$rOut out $amountOut: ") {
          curve.in(rIn, rOut, amountOut).foreach { in =>
            accepts(curve, rIn, rOut, in, amountOut) shouldBe true
            accepts(curve, rIn, rOut, in - 1, amountOut) shouldBe false
            curve.out(rIn, rOut, in) should be >= amountOut
            curve.out(rIn, rOut, in - 1) should be < amountOut
          }
        }
      }
    }
  }

  it should "never buy more than its marginal rate times the input, but for a truncated side fee's last unit" in {
    (1 to 20000).foreach { _ =>
      val (rIn, rOut) = (reserve(), reserve())
      val amountIn = 1L + (rnd.nextDouble() * rIn).toLong
      curves().foreach { curve =>
        val reach = curve match {
          case native: SwapCurve.LithosDexNative => BigDecimal(amountIn) + BigDecimal(D) / (D - native.sideFeeNum)
          case _ => BigDecimal(amountIn)
        }
        withClue(s"$curve $rIn/$rOut in $amountIn: ") {
          BigDecimal(curve.out(rIn, rOut, amountIn)) should be <= BigDecimal(curve.marginal(rIn, rOut)) * reach * 1.000000001
        }
      }
    }
  }

  "An ErgoDEX pool" should "price a known swap to the unit" in {
    // 2,000,000 · 10,000 · 997 / (1,000,000 · 1,000 + 10,000 · 997), rounded down
    SwapCurve.ErgoDex(997L).out(1000000L, 2000000L, 10000L) shouldBe 19743L
    SwapCurve.ErgoDex(997L).in(1000000L, 2000000L, 19743L) shouldBe Some(10000L)
    SwapCurve.ErgoDex(997L).sideFee(10000L) shouldBe 0L
  }

  "A LithosDex ERG/token pool" should "truncate its side fee, and a token/token pool round it up" in {
    // 30 / 10,000 of 1,000 is 3 either way; of 1,001 it is 3.003
    SwapCurve.LithosDexNative(9970L, 30L).sideFee(1000L) shouldBe 3L
    SwapCurve.LithosDexToken(9970L, 30L).sideFee(1000L) shouldBe 3L
    SwapCurve.LithosDexNative(9970L, 30L).sideFee(1001L) shouldBe 3L
    SwapCurve.LithosDexToken(9970L, 30L).sideFee(1001L) shouldBe 4L
    // So the least input for the same traded amount differs by the rounding
    SwapCurve.LithosDexNative(9970L, 30L).sideFee(1L) shouldBe 0L
    SwapCurve.LithosDexToken(9970L, 30L).sideFee(1L) shouldBe 1L
    SwapCurve.LithosDexToken(9970L, 0L).sideFee(1L) shouldBe 0L
  }

  "A LithosDex exact-output quote" should "refuse an input past a Long at once, rather than step towards it" in {
    import scala.concurrent.ExecutionContext.Implicits.global
    import scala.concurrent.duration._
    import scala.concurrent.{Await, Future}
    // A 10,000 ERG pool against 10^15 of its token, asked for all but one unit: the input is about 10^28
    val (ergReserve, tokenReserve) = (10000000000000L, 1000000000000000L)
    val asked = Future(Seq(SwapCurve.LithosDexNative(9970L, 30L), SwapCurve.LithosDexToken(9970L, 30L))
      .map(_.in(ergReserve, tokenReserve, tokenReserve - 1L)))
    Await.result(asked, 5.seconds) shouldBe Seq(None, None)
    val pool = lithosdex.LDLiquidityPool(ergReserve, tokenReserve, 0L, 0L, 1000000L, Array(9970L, 30L, 30L), BigInt(0),
      BigInt(0), org.ergoplatform.sdk.ErgoId.create("aa" * 32), org.ergoplatform.sdk.ErgoId.create("bb" * 32),
      org.ergoplatform.sdk.ErgoId.create("cc" * 32), 10L)
    Await.result(Future(pool.simSwapForOutput(tokenReserve - 1L, ergIn = true)), 5.seconds) shouldBe None
  }

  "No pool" should "price an output it cannot pay, an empty side, or nothing" in {
    curves().foreach { curve =>
      curve.in(1000L, 5000L, 5000L) shouldBe None
      curve.in(1000L, 5000L, 0L) shouldBe None
      curve.in(0L, 5000L, 10L) shouldBe None
      curve.out(1000L, 5000L, 0L) shouldBe 0L
      curve.marginal(0L, 5000L) shouldBe 0.0
    }
  }
}
