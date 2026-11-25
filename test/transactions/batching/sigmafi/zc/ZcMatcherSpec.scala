package transactions.batching.sigmafi.zc

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sigmafi.zc.{ZcExpression, ZcKind, ZcMarket}

/**
 * zcs1's matching over fixture books: which offer goes first, which source it takes, and every refusal the CLI's
 * batch scan applies, including the ones no contract enforces (a bond under its own ratio, shares outside an offer's
 * bounds, the window maturity rule). Each refusal is shown against a book where the same pair otherwise matches.
 */
class ZcMatcherSpec extends AnyFlatSpec with Matchers with org.scalatest.LoneElement {

  import ZcFixtures._
  import ZcMarketFixtures._

  private val Mkt = ZcMarket.Erg

  private def offerOf(m: ZcMatcher, boxId: String): ZcOfferState =
    m.offerStates(Mkt).find(_.boxId == boxId).getOrElse(fail(s"$boxId is not a matchable offer"))

  private def sourceOf(m: ZcMatcher, boxId: String): ZcSource =
    m.sources(Mkt).find(_.boxId == boxId).getOrElse(fail(s"$boxId is not a source"))

  "An offer" should "fill an order at the order's principal, priced and ranked as the CLI ranks it" in {
    val m = matcher(Seq(ergOffer(id(20)), oracleOrder(id(1))))
    val run = m.batch(Mkt)
    val taken = run.taken(id(20)).loneElement
    taken.sourceId shouldBe id(1)
    taken.price shouldBe Erg
    taken.cost shouldBe BigInt(1005000000L)
    taken.maturity shouldBe 1749600L
    taken.blocksLeft shouldBe 1749600L - (H + 1L)
    taken.yieldBps shouldBe ZcPricing.yieldBps(BigInt(1050000000L), BigInt(1005000000L), taken.blocksLeft).get
    taken.requiredBps shouldBe 840L
  }

  it should "refuse an order paying less than its curve" in {
    val m = matcher(Seq(ergOffer(id(20)), oracleOrder(id(1), repayment = 1006000000L)))
    m.evaluate(offerOf(m, id(20)), sourceOf(m, id(1))).left.toOption.get should include("under the offer's")
    m.batch(Mkt).taken shouldBe empty
  }

  "The matching order" should "give offers their turn by most remaining reward, each taking its highest yield" in {
    val m = matcher(Seq(ergOffer(id(20), fee = 5000000L), ergOffer(id(21), fee = 8000000L),
      oracleOrder(id(1), repayment = 1100000000L), oracleOrder(id(2), repayment = 1050000000L)))
    val run = m.batch(Mkt, rounds = 1)
    run.taken(id(21)).map(_.sourceId) shouldBe Seq(id(1))
    run.taken(id(20)).map(_.sourceId) shouldBe Seq(id(2))
  }

  it should "break a tie in yield to the lower box id" in {
    val m = matcher(Seq(ergOffer(id(20)), oracleOrder(id(11)), oracleOrder(id(10))))
    m.batch(Mkt, rounds = 1).taken(id(20)).map(_.sourceId) shouldBe Seq(id(10))
  }

  it should "chain an offer through its continuations, round by round" in {
    val boxes = Seq(ergOffer(id(20), value = 2200000000L), oracleOrder(id(1)), oracleOrder(id(2), repayment = 1040000000L))
    matcher(boxes).batch(Mkt, rounds = 1).taken(id(20)).map(_.sourceId) shouldBe Seq(id(1))
    val run = matcher(boxes).batch(Mkt)
    run.taken(id(20)).map(_.sourceId) shouldBe Seq(id(1), id(2))
    run.rounds shouldBe 3
  }

  it should "leave a fill the continuation cannot fund a box minimum for" in {
    // 1.056 ERG leaves the offer per fill: fee, delivery box, reserve and principal
    val m = matcher(Seq(ergOffer(id(20), value = 1056500000L), oracleOrder(id(1))))
    m.evaluate(offerOf(m, id(20)), sourceOf(m, id(1))).left.toOption.get should include("box minimum")
  }

  it should "leave a fill whose continuation would be under its size minimum, though above 0.001 ERG" in {
    val note = Hex.toHexString(("x" * 600).getBytes("UTF-8"))
    val curve = (1 to 20).map(i => (800L + i) -> (10000L * i))
    def offer(value: Long) = ergOffer(id(20), value = value, curve = curve, metadata = Seq(note))
    val least = ZcSizing.offerRestLeast(offer(1056500000L))
    least should be > ZcBoxes.MinBox
    // 1.056 ERG leaves the offer per fill: fee, delivery box, reserve and principal
    val short = matcher(Seq(offer(1056000000L + least - 1L), oracleOrder(id(1))))
    short.evaluate(offerOf(short, id(20)), sourceOf(short, id(1))).left.toOption.get should include("box minimum")
    val enough = matcher(Seq(offer(1056000000L + least), oracleOrder(id(1))))
    enough.evaluate(offerOf(enough, id(20)), sourceOf(enough, id(1))).isRight shouldBe true
  }

  "zcs1's cover rule" should "refuse a fill whose bond would start under its own ratio, though the offer's floor passes" in {
    // 1.5225 ERG of collateral against 1.05 ERG is 14,500 bps: over the offer's 14,000, under the order's 15,000
    val under = matcher(Seq(ergOffer(id(20), floor = 14000L), oracleOrder(id(1), value = 1527500000L)))
    under.evaluate(offerOf(under, id(20)), sourceOf(under, id(1))).left.toOption.get should include("under its own ratio")
    val clear = matcher(Seq(ergOffer(id(20), floor = 14000L), oracleOrder(id(1), value = 1580000000L)))
    clear.evaluate(offerOf(clear, id(20)), sourceOf(clear, id(1))).isRight shouldBe true
  }

  it should "honour an order's fill guard over its own ratio" in {
    val guarded = matcher(Seq(ergOffer(id(20)), oracleOrder(id(1), guard = Some(30000L))))
    guarded.evaluate(offerOf(guarded, id(20)), sourceOf(guarded, id(1))).left.toOption.get should include("fill guard")
    val met = matcher(Seq(ergOffer(id(20)), oracleOrder(id(1), guard = Some(28000L))))
    met.evaluate(offerOf(met, id(20)), sourceOf(met, id(1))).isRight shouldBe true
  }

  "The oracle" should "be unusable while a mempool refresh spends it, so nothing matches" in {
    val m = matcher(Seq(ergOffer(id(20)), oracleOrder(id(1))), refreshing = true)
    m.evaluate(offerOf(m, id(20)), sourceOf(m, id(1))).left.toOption.get should include("no usable oracle")
    matcher(Seq(ergOffer(id(20)), oracleOrder(id(1)))).batch(Mkt).taken should not be empty
  }

  "An ask" should "be a source only once its token is proven a bond unit, and then be bought" in {
    val boxes = Seq(ergOffer(id(20)), oracleBond(id(31), id(30)), ask(id(32), id(30), 1L, Erg, 1L))
    val unproven = matcher(boxes)
    unproven.askSource(unproven.book.asks.head).left.toOption.get should include("unverified")
    unproven.sources(Mkt) shouldBe empty
    val proven = matcher(boxes, Map(id(30) -> unitOrigin(id(30))))
    proven.batch(Mkt).taken(id(20)).map(_.sourceId) shouldBe Seq(id(32))
  }

  "Shares" should "not be bought when their bond holds more collateral tokens than a Dutch vault can take" in {
    val share = id(40)
    def book(tokens: Int) = matcher(Seq(ergOffer(id(20), shareBounds = Some((8000L, 3000L))),
      oracleBond(id(31), id(30), collateral = (1 to tokens).map(i => id(600 + i) -> 10L)),
      shareVault(id(41), share, id(30)), ask(id(42), share, 100000000L, 10L, 1L)),
      Map(id(30) -> unitOrigin(id(30)), share -> shareOrigin(share, id(30))))
    val fits = book(ZcSizing.MaxCollateralTokens)
    fits.evaluate(offerOf(fits, id(20)), sourceOf(fits, id(42))).isRight shouldBe true
    val over = book(ZcSizing.MaxCollateralTokens + 1)
    over.evaluate(offerOf(over, id(20)), sourceOf(over, id(42))).left.toOption.get should
      include("more than a Dutch vault can take")
  }

  it should "be bought only from a Dutch-pair mint whose auction is inside the offer's bounds" in {
    val share = id(40)
    val boxes = Seq(ergOffer(id(20), shareBounds = Some((8000L, 3000L))), ergOffer(id(21)),
      oracleBond(id(31), id(30)), shareVault(id(41), share, id(30)), ask(id(42), share, 100000000L, 10L, 1L))
    val origins = Map(id(30) -> unitOrigin(id(30)), share -> shareOrigin(share, id(30)))
    val m = matcher(boxes, origins)
    val source = sourceOf(m, id(42))
    m.evaluate(offerOf(m, id(20)), source).map(_.units) shouldBe Right(100000000L)
    m.evaluate(offerOf(m, id(21)), source).left.toOption.get should include("buys no shares")
    val tooLong = matcher(boxes, origins.updated(share, shareOrigin(share, id(30), expiry = 4000L)))
    tooLong.evaluate(offerOf(tooLong, id(20)), sourceOf(tooLong, id(42))).left.toOption.get should include("outside")
  }

  it should "not be a claim when the mint names another share count than the vault" in {
    val share = id(40)
    val boxes = Seq(oracleBond(id(31), id(30)), shareVault(id(41), share, id(30), n = 1000L), ask(id(42), share, 1000L, 10L, 1L))
    val m = matcher(boxes, Map(id(30) -> unitOrigin(id(30)), share -> shareOrigin(share, id(30))))
    m.claimOf(share).left.toOption.get should include("does not match")
  }

  "A window order" should "mature at a grid height the offer accepts, by zcs1's rule" in {
    val m = matcher(Seq(ergOffer(id(20)), windowOrder(id(50), 20000L, 60000L, 5000L)))
    m.batch(Mkt).taken(id(20)).loneElement.maturity shouldBe 1749600L
  }

  "A priced order" should "be quoted at the most it asks over the next three blocks" in {
    val o = ZcBoxes.read(yieldOrder(id(60), y0 = 3000L, y1 = 1000L)).get.asInstanceOf[ZcOrder]
    val at = ZcRules.orderPrincipal(o, H, 1749600L).toOption.get
    val later = ZcRules.orderPrincipal(o, H + 3L, 1749600L).toOption.get
    later should be > at
    ZcRules.resolvePrincipal(o, H, 1749600L) shouldBe Right(later)
  }

  "The search" should "look at no more sources than its cap, best own yield first" in {
    val boxes = Seq(ergOffer(id(20)), oracleOrder(id(1), repayment = 1100000000L), oracleOrder(id(2)))
    val run = matcher(boxes, maxSources = 1).batch(Mkt)
    run.searched.sourcesSearched shouldBe 1
    run.searched.sources shouldBe 2
    run.taken(id(20)).map(_.sourceId) shouldBe Seq(id(1))
  }

  it should "skip boxes known to break script disclosure" in {
    val m = new ZcMatcher(ZcMarketBook.of(view(Seq(ergOffer(id(20)), oracleOrder(id(1)), oracleOrder(id(2))))),
      _ => None, excluded = _.boxId == id(1))
    m.sources(Mkt).map(_.boxId) shouldBe Seq(id(2))
  }

  it should "drop dust ERG offers and non-oracle orders so spam cannot crowd out MaxOffers or MaxSources" in {
    // Dust offer claiming a huge batcher fee (10 ERG) while holding only 0.2 ERG cannot fill anything
    val dustOffer = ergOffer(id(70), value = 200000000L, fee = 10 * Erg)
    val realOffer = ergOffer(id(71), value = 10 * Erg, fee = 5000000L)
    val matOrder = order(id(72))
    val realOrder = oracleOrder(id(73))
    val m = new ZcMatcher(ZcMarketBook.of(view(Seq(dustOffer, realOffer, matOrder, realOrder))),
      _ => None, maxOffers = 1, maxSources = 1)
    m.offerStates(Mkt).map(_.boxId) shouldBe Vector(id(71))
    m.sources(Mkt).map(_.boxId) shouldBe Vector(id(73))
  }

  it should "exclude asks whose live bond is in the excluded set (e.g. liquidated in the same block)" in {
    val boxes = Seq(oracleBond(id(81), id(80)), ask(id(82), id(80), 1L, Erg, 1L))
    val origins = Map(id(80) -> unitOrigin(id(80)))
    val m = new ZcMatcher(ZcMarketBook.of(view(boxes)), origins.get, excluded = _.boxId == id(81))
    m.askSource(m.book.asks.head).left.toOption.get should include("excluded")
    m.sources(Mkt) shouldBe empty
  }

  // ─── rules brought to the reference CLI's ──────────────────────────────────

  "A broadcast match" should "keep the offer's least maturity at every block it may land in" in {
    val boxes = Seq(ergOffer(id(20)), oracleOrder(id(1), maturity = H + 1L + 720L))
    val own = matcher(boxes)
    own.evaluate(offerOf(own, id(20)), sourceOf(own, id(1))).isRight shouldBe true
    val late = new ZcMatcher(ZcMarketBook.of(view(boxes)), Map.empty[String, ZcOrigin].get, lateBlocks = 2L)
    late.evaluate(offerOf(late, id(20)), sourceOf(late, id(1))).left.toOption.get should include("outside the offer's 720")
  }

  "An ERG offer's refusal" should "count the box minimum its continuation keeps, after the price leaves it" in {
    val m = matcher(Seq(ergOffer(id(20), value = 1056500000L), oracleOrder(id(1))))
    val order = ZcBoxes.read(oracleOrder(id(1))).get.asInstanceOf[ZcOrder]
    val bond = ZcBondTerms.prospect(order, H, order.terms(3), Erg)
    ZcRules.offerRefusal(offerOf(m, id(20)), bond, Erg, 1L, 1L, m.book.currentOracle, H, primary = true) shouldBe
      Some("the offer cannot keep a box minimum after this fill")
  }

  "A bond's own ratio" should "be measured in its oracle rule's market, which may be the other market's" in {
    val terms = ZcBondTerms(ZcMarket.Erg, 1900000000L, Erg, IndexedSeq(15000L, 60L),
      expr(ZcExpression.OracleLiquidation, ZcMarket.SigUsd), 3 * Erg, Seq.empty, H - 1000L, H + 10000L)
    ZcRules.cover(terms, 500000000L).exists(ZcPricing.atLeast(_, 15000L)) shouldBe true
    ZcRules.belowOwnRatio(terms, 500000000L) shouldBe true
  }

  "A window order" should "be checked at the maturity a lender chooses" in {
    val order = ZcBoxes.read(windowOrder(id(50), 20000L, 60000L, 5000L)).get.asInstanceOf[ZcOrder]
    ZcRules.orderRefusal(order, H) shouldBe None
    ZcRules.orderRefusal(order, H, Some(H + 10L)).get should include("outside the window")
  }

  it should "mature at the most an ERG offer earns inside a span, where its batcher fee bends the yield" in {
    // No grid height or breakpoint lies in the window and the offer asks one yield throughout, so only the fee's bend
    // picks a height inside it.
    val m = matcher(Seq(ergOffer(id(20), curve = Seq.empty),
      windowOrder(id(50), 83000L, 104000L, 28390L, principal = 100000000L)))
    val maturity = m.batch(Mkt).taken(id(20)).loneElement.maturity
    maturity should be > H + 83000L
    maturity should be < H + 104000L
  }

  "Shares an offer cannot pay for whole" should "be bought in part, as many as it can pay for" in {
    val share = id(40)
    val m = matcher(Seq(ergOffer(id(20), value = 600000000L, shareBounds = Some((8000L, 3000L))),
      oracleBond(id(31), id(30)), shareVault(id(41), share, id(30)), ask(id(42), share, 100000000L, 10L, 1L)),
      Map(id(30) -> unitOrigin(id(30)), share -> shareOrigin(share, id(30))))
    val units = m.evaluate(offerOf(m, id(20)), sourceOf(m, id(42))).map(_.units).toOption.get
    units should be > 50000000L
    units should be < 100000000L
  }

  "A floor auction's target" should "be no target at all past a Long, so nothing is built to pay it" in {
    val vault = ZcBoxes.read(box(id(90), tree(ZcKind.DutchAuctionVault), 10000000L, Seq(id(91) -> 100000001L),
      regs(bytes(id(92)), longs(100000000L, 100000000L, 5000000L), byte(1),
        longs(Long.MaxValue / 2, 1000000L, 9000L, 720L, 2160L, 100L, H - 10L), bytes("")))).get.asInstanceOf[ZcShareVault]
    ZcRules.lotTarget(vault, H) shouldBe None
  }
}
