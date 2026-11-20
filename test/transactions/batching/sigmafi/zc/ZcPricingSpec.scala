package transactions.batching.sigmafi.zc

import node.model.NodeAsset
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sigmafi.zc.{ZcContracts, ZcMarket}

/**
 * The zcs1 arithmetic, against the reference CLI's own spec values where it has them (the grid, the default expiry,
 * crossing prices) and against the pricing guide's worked example otherwise. Every comparison is exact: a quote one
 * raw unit off is a refused transaction.
 */
class ZcPricingSpec extends AnyFlatSpec with Matchers {

  import ZcPricing._

  "The maturity grid" should "snap a maturity a step or more away to the nearest multiple of 21,600, a tie going later" in {
    snapMaturity(1743200L, 1700000L) shouldBe 1749600L
    snapMaturity(1749600L, 1700000L) shouldBe 1749600L
    snapMaturity(1738800L, 1700000L) shouldBe 1749600L
    snapMaturity(1730000L, 1700000L) shouldBe 1728000L
    snapMaturity(1749590L, 1727990L) shouldBe 1749600L
    snapMaturity(1728010L, 1706410L) shouldBe 1728000L
  }

  it should "keep a nearer maturity's height" in {
    snapMaturity(1735190L, 1727990L) shouldBe 1735190L
    snapMaturity(1728005L, 1727999L) shouldBe 1728005L
  }

  it should "list the grid heights in a range, inclusive" in {
    gridBetween(1728000L, 1771200L) shouldBe Seq(1728000L, 1749600L, 1771200L)
    gridBetween(1728001L, 1749599L) shouldBe empty
  }

  "The default order expiry" should "be a week, never past half a fixed maturity's term" in {
    defaultOrderExpiry(1700000L, Some(1749600L), 5040L) shouldBe 1705040L
    defaultOrderExpiry(1700000L, Some(1700400L), 5040L) shouldBe 1700200L
    defaultOrderExpiry(1700000L, None, 5040L) shouldBe 1705040L
    defaultOrderExpiry(1700000L, Some(1700003L), 5040L) shouldBe 1700002L
  }

  "A yield line" should "price a bond repaying 110 halfway through a year at 10% at 110 / 1.05, rounded up" in {
    YieldLine(1700000L, 1000L, 1700100L, 1000L).price(110000000L, 1L, 1L, 131400L, 1700050L) shouldBe BigInt(104761905L)
  }

  it should "move in a straight line between its two points, and hold past them" in {
    val line = YieldLine(1700000L, 800L, 1700100L, 1200L)
    line.bpsAt(1700050L) shouldBe 1000L
    line.price(110000000L, 1L, 1L, 131400L, 1700050L) shouldBe BigInt(104761905L)
    line.price(110000000L, 1L, 1L, 131400L, 1699000L) shouldBe BigInt(105769231L)
    line.price(110000000L, 1L, 1L, 131400L, 1800000L) shouldBe BigInt(103773585L)
  }

  it should "price part of a bond in proportion" in {
    val line = YieldLine(1700000L, 1000L, 1700100L, 1000L)
    line.price(110000000L, 25000000L, 100000000L, 131400L, 1700050L) shouldBe BigInt(26190477L)
  }

  "An offer's curve" should "interpolate between its points, hold beyond them, and never fall under its least yield" in {
    val curve = YieldCurve(700L, Seq(820L -> 21600L, 940L -> 131400L))
    curve.bpsAt(10000L) shouldBe 820L
    curve.bpsAt(21600L) shouldBe 820L
    curve.bpsAt(76500L) shouldBe 880L
    curve.bpsAt(200000L) shouldBe 940L
    YieldCurve(900L, curve.points).bpsAt(21600L) shouldBe 900L
    YieldCurve(700L, Seq.empty).bpsAt(5L) shouldBe 700L
  }

  it should "accept its own ceiling and nothing a raw unit above it" in {
    val curve = YieldCurve(700L, Seq(820L -> 21600L, 940L -> 131400L))
    Seq(21600L, 50000L, 131400L, 250000L).foreach { d =>
      val c = curve.ceiling(1000000000L, 1L, 1L, d).toLong
      withClue(s"$d blocks: ")(curve.accepts(1000000000L, 1L, c, 1L, d) shouldBe true)
      withClue(s"$d blocks: ")(curve.accepts(1000000000L, 1L, c + 1L, 1L, d) shouldBe false)
    }
  }

  "A window order's curve" should "interpolate as an offer's does, capped at its most yield" in {
    val curve = BorrowCurve(1200L, Seq(800L -> 21600L, 1200L -> 131400L))
    curve.bpsAt(76500L) shouldBe 1000L
    BorrowCurve(900L, curve.points).bpsAt(131400L) shouldBe 900L
    BorrowCurve(1000L, Seq.empty).price(110000000L, 131400L) shouldBe BigInt(104761905L)
    BorrowCurve(1000L, Seq(1L -> 5L, 2L -> 5L)).valid shouldBe false
  }

  "A yield" should "be the simple return over 262,800 blocks, in bps" in {
    yieldBps(BigInt(110), BigInt(100), 262800L) shouldBe Some(1000L)
    yieldBps(BigInt(110), BigInt(100), 131400L) shouldBe Some(2000L)
    yieldBps(BigInt(110), BigInt(0), 131400L) shouldBe None
    rankYield(BigInt(110), BigInt(100), 262800L) shouldBe BigInt(1000L) * 1000000
  }

  "Cover" should "measure ERG collateral against an ERG repayment as a plain ratio" in {
    ratioBps(coverOf(ZcMarket.Erg, 3000000000L, Seq.empty, 2000000000L, 465116279L).get) shouldBe 15000L
    coverOf(ZcMarket.Erg, 3000000000L, Seq.empty, 2000000000L, 0L) shouldBe None
  }

  it should "find where the oracle price crosses a ratio, in both markets, as the CLI does" in {
    val (sigUsd, cheaper) = crossingPrice(ZcMarket.SigUsd, 10000000000L, Seq.empty, 700L, 15000L).get
    usdPerErg(sigUsd) shouldBe "1.0500"
    cheaper shouldBe true
    val (erg, cheaper2) = crossingPrice(ZcMarket.Erg, 1000000000L, Seq(NodeAsset(ZcContracts.SigUsdId, 1000L)),
      6000000000L, 15000L).get
    usdPerErg(erg) shouldBe "1.2500"
    cheaper2 shouldBe false
    crossingPrice(ZcMarket.Erg, 10000000000L, Seq(NodeAsset("ab" * 32, 5L)), 6000000000L, 15000L) shouldBe None
  }

  "Adversarial extreme register bounds" should "never overflow or divide by zero in YieldLine, interpolate or curves" in {
    // YieldLine with start/end spanning > Long.MaxValue or negative start
    YieldLine(500L, -10L, 1000L, 100L).valid shouldBe false
    YieldLine(500L, 0L, 1000L, Long.MaxValue).valid shouldBe true
    YieldLine(500L, 0L, 1000L, Long.MaxValue).at(Long.MaxValue / 2)._2 should be > BigInt(0)
    YieldLine(500L, Long.MinValue, 1000L, Long.MaxValue).valid shouldBe false
    YieldLine(500L, Long.MinValue, 1000L, Long.MaxValue).price(1000L, 100L, 1L, 1L, 1000L) should be > BigInt(0)
    // interpolate with reversed or extreme Long coordinates
    interpolate(Seq(500L -> Long.MinValue, 1000L -> Long.MaxValue), 1000L).get._2 should be > BigInt(0)
    interpolate(Seq(500L -> 100L, 1000L -> 50L), 1000L) shouldBe None
    YieldCurve(0L, Seq(500L -> 0L, 1000L -> Long.MaxValue)).ordered shouldBe true
    YieldCurve(0L, Seq(500L -> 0L, 1000L -> Long.MaxValue)).ceiling(1000000L, 1000L, 1L, 1L) should be > BigInt(0)
    BorrowCurve(5000L, Seq(500L -> 0L, 1000L -> Long.MaxValue)).valid shouldBe true
    BorrowCurve(5000L, Seq(500L -> 0L, 1000L -> Long.MaxValue)).price(1000000L, 1000L) should be > BigInt(0)
  }
}
