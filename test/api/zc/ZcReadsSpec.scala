package api.zc

import api.LithosApiErrors.{LithosBadRequest, LithosNotFound, LithosUnavailable}
import configs.ZcConfig
import node.model.{IndexedBox, NodeAsset, NodeBox}
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import play.api.libs.json.{JsObject, JsValue, Json}
import support.LDNodeFixtures
import transactions.batching.sigmafi.zc.{ZcResolver, ZcTracker}
import transactions.batching.tracking.ScriptedChain

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.ExecutionContext
import scala.util.Success

/**
 * The `/sigmafi/zc` reads over a tracker seeded from a scripted node: each answers from one snapshot with `at` set,
 * the matcher's answers agree across curves, order book and matches, and a request never makes more than its node
 * reads however much of the book is unverified.
 */
class ZcReadsSpec extends AnyFlatSpec with Matchers with Eventually with org.scalatest.LoneElement {

  import transactions.batching.sigmafi.zc.ZcFixtures._
  import transactions.batching.sigmafi.zc.ZcMarketFixtures._

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(Span(10, Seconds), Span(20, Millis))

  private val order1 = "c0ffee" + id(1).drop(6)
  private val offer1 = id(20)
  private val asset = id(30)

  private final case class World(chain: ScriptedChain, tracker: ZcTracker, resolver: ZcResolver, reads: ZcReads)

  private def world(boxes: Seq[NodeBox], hashes: Set[String] = Set.empty,
                    balance: (Long, Seq[NodeAsset]) = (0L, Seq.empty)): World = {
    val c = new ScriptedChain
    c.extend(H - 40, H)
    c.indexed = H
    c.scans((boxes :+ oracle()).map(b => LDNodeFixtures.indexed(b, H - 10)): _*)
    val t = new ZcTracker(() => c.api, ExecutionContext.global, ZcConfig(enabled = true, 1000), retryMs = 20L)
    t.poke()
    eventually(t.view.map(_.book.height) shouldBe Some(H))
    val r = new ZcResolver(() => c.api, t)
    World(c, t, r, new ZcReads(t, r, () => c.api, () => hashes, () => balance, warmer = None))
  }

  private def market: Seq[NodeBox] = Seq(ergOffer(offer1), oracleOrder(order1), oracleBond(id(31), asset))

  private def items(j: JsObject): Seq[JsValue] = (j \ "items").as[Seq[JsValue]]

  "Every read" should "answer 503 when tracking is off, and before the first scan" in {
    val c = new ScriptedChain
    val off = new ZcTracker(() => c.api, ExecutionContext.global, ZcConfig(enabled = false, 1000))
    an[LithosUnavailable] should be thrownBy new ZcReads(off, new ZcResolver(() => c.api, off), () => c.api, () => Set.empty,
      () => (0L, Seq.empty), warmer = None).info()
    val unseeded = new ZcTracker(() => c.api, ExecutionContext.global, ZcConfig(enabled = true, 1000))
    an[LithosUnavailable] should be thrownBy new ZcReads(unseeded, new ZcResolver(() => c.api, unseeded), () => c.api,
      () => Set.empty, () => (0L, Seq.empty), warmer = None).asks("ERG", ZcFilters(), None, None, None, None, None)
  }

  "info" should "carry the snapshot, the confirmed oracle, every pinned contract and expression, and the defaults" in {
    val w = world(market)
    val j = w.reads.info()
    (j \ "at" \ "height").as[Int] shouldBe H
    (j \ "at" \ "revision").as[Long] shouldBe w.tracker.view.get.revision
    (j \ "oracle" \ "nanoErgPerUsd").as[String] shouldBe "465116279"
    (j \ "oracle" \ "age").as[Int] shouldBe 5
    val markets = (j \ "markets").as[Seq[JsObject]]
    markets.map(m => (m \ "market").as[String]) shouldBe Seq("ERG", "SigUSD")
    markets.foreach { m =>
      (m \ "contracts").as[JsObject].keys should have size 14
      (m \ "expressions").as[JsObject].keys should have size 8
    }
    (j \ "defaults" \ "gridBlocks").as[Long] shouldBe 21600L
  }

  "asks" should "list an open order with its quote at the default maturity, its status and its checks" in {
    val j = world(market).reads.asks("ERG", ZcFilters(), None, None, None, None, None)
    val o = items(j).loneElement
    (o \ "boxId").as[String] shouldBe order1
    (o \ "kind").as[String] shouldBe "order"
    (o \ "status").as[String] shouldBe "ok"
    (o \ "quote" \ "cost").as[String] shouldBe "1000000000"
    (o \ "quote" \ "maturity").as[Long] shouldBe 1749600L
    (o \ "coverBps").as[Long] shouldBe 28523L
    (o \ "rule" \ "kind").as[String] shouldBe "oracle"
    (j \ "at" \ "blockId").as[String] shouldBe blockId(H)
  }

  it should "filter by cover, kind and matchability, and page with a cursor" in {
    val w = world(market ++ Seq(oracleOrder(id(2)), oracleOrder(id(3))))
    items(w.reads.asks("ERG", ZcFilters(coverage = 30000L), None, None, None, None, None)) shouldBe empty
    items(w.reads.asks("ERG", ZcFilters(kind = "resale"), None, None, None, None, None)) shouldBe empty
    val first = w.reads.asks("ERG", ZcFilters(), None, None, Some("maturity"), None, Some(2))
    items(first) should have size 2
    (first \ "total").as[Int] shouldBe 3
    val rest = w.reads.asks("ERG", ZcFilters(), None, None, Some("maturity"), (first \ "next").asOpt[String], Some(2))
    items(rest) should have size 1
    an[LithosBadRequest] should be thrownBy w.reads.asks("ERG", ZcFilters(), None, None, Some("colour"), None, None)
    an[LithosBadRequest] should be thrownBy w.reads.asks("DOGE", ZcFilters(), None, None, None, None, None)
  }

  "offers" should "list an offer with its curve, policy and current reward" in {
    val o = items(world(market).reads.offers("ERG", ZcFilters(), None, None, None, None, None)).loneElement
    (o \ "boxId").as[String] shouldBe offer1
    (o \ "curve").as[Seq[JsObject]].map(p => (p \ "bps").as[Long]) shouldBe Seq(820L, 940L)
    (o \ "policy" \ "floorBps").as[Long] shouldBe 15000L
    (o \ "rewardNowNanoErg").as[String] shouldBe "5000000"
    (o \ "status").as[String] shouldBe "ok"
  }

  "curves" should "draw the offers' lower envelope and the asks, with totals and what was searched" in {
    val j = world(market).reads.curves("ERG", ZcFilters())
    val at21600 = (j \ "bidLine").as[Seq[JsObject]].find(p => (p \ "blocks").as[Long] == 21600L).get
    (at21600 \ "bps").as[Long] shouldBe 820L
    (at21600 \ "offerId").as[String] shouldBe offer1
    (j \ "askDots").as[Seq[JsObject]].map(d => (d \ "askId").as[String]) shouldBe Seq(order1)
    (j \ "totals" \ "offers" \ "matchable").as[Int] shouldBe 1
    (j \ "totals" \ "asks" \ "matchable").as[Int] shouldBe 1
    (j \ "totals" \ "searched" \ "offers").as[Int] shouldBe 1
  }

  private def rows(j: JsObject, side: String): Seq[JsObject] = (j \ side).as[Seq[JsObject]]
  private def long(j: JsObject, k: String): Option[Long] = (j \ k).asOpt[Long]

  "orderbook" should "place each ask at its maturity and read each bid at the anchor, with its range over the slice" in {
    val j = world(market).reads.orderbook("ERG", ZcFilters(), Some(262800L), None, None)
    val bid = rows(j, "bids").head
    long(bid, "bps") shouldBe Some(940L)
    long(bid, "minBps") shouldBe Some(820L)
    long(bid, "maxBps") shouldBe Some(940L)
    (rows(j, "asks").head \ "boxId").as[String] shouldBe order1
    long(j, "anchorBlocks") shouldBe Some(262800L)
    long(j, "anchorBidBps") shouldBe Some(940L)
    long(j, "bestBidBps") shouldBe Some(940L)
    an[LithosBadRequest] should be thrownBy world(market).reads.orderbook("ERG", ZcFilters(), Some(1000L), Some(5L), None)
  }

  it should "compare each ask with the bid at its own maturity, and give the tightest such spread" in {
    val j = world(market).reads.orderbook("ERG", ZcFilters(), Some(262800L), None, None)
    val ask = rows(j, "asks").head
    val (d, y, bidAt) = (long(ask, "blocks").get, long(ask, "bps").get, long(ask, "bidAtBps").get)
    bidAt should (be > 820L and be < 940L)
    long(ask, "spreadBps") shouldBe Some(bidAt - y)
    long(j, "spreadBps") shouldBe Some(bidAt - y)
    long(j, "spreadBps").get should be < 0L
    long(j, "spreadBlocks") shouldBe Some(d)
    long(j, "spreadBidBps") shouldBe Some(bidAt)
    long(j, "spreadAskBps") shouldBe Some(y)
  }

  it should "not call an ask crossed because the bid curve is lower at a shorter maturity" in {
    // A steep bid curve: 700 bps to 21,600 blocks, about 799 at the order's ~39,600. The order pays about 756: over
    // the curve's low end, under it at its own maturity.
    val steep = Seq(ergOffer(offer1, curve = Seq(700L -> 21600L, 1300L -> 131400L)), oracleOrder(order1, repayment = 1011400000L))
    val j = world(steep).reads.orderbook("ERG", ZcFilters(), Some(64800L), None, None)
    val ask = rows(j, "asks").loneElement
    long(ask, "bps").get should (be > 700L and be < long(ask, "bidAtBps").get)
    long(rows(j, "bids").loneElement, "minBps") shouldBe Some(700L)
    long(j, "spreadBps").get should be > 0L
    long(j, "spreadBps") shouldBe Some(long(j, "spreadBidBps").get - long(j, "spreadAskBps").get)
  }

  it should "track the bid at the anchor, and give no spread when no ask shares a maturity with a bid" in {
    val w = world(market)
    def book(to: Option[Long], around: Option[Long] = None) = w.reads.orderbook("ERG", ZcFilters(), to, around, None)
    val short = book(Some(21600L))
    rows(short, "asks") shouldBe empty
    long(short, "anchorBidBps") shouldBe Some(820L)
    long(short, "spreadBps") shouldBe None
    val mid = long(book(Some(64800L)), "anchorBidBps").get
    mid should (be > 820L and be < 940L)
    val near = book(None, Some(131400L))
    long(near, "anchorBlocks") shouldBe Some(131400L)
    long(near, "anchorBidBps") shouldBe Some(940L)

    // A bid from 43,200 blocks and an ask at ~39,600: both in range, at no shared maturity.
    val apart = world(Seq(ergOffer(offer1, min = 43200L), oracleOrder(order1))).reads
      .orderbook("ERG", ZcFilters(), Some(64800L), None, None)
    val ask = rows(apart, "asks").loneElement
    long(ask, "bidAtBps") shouldBe None
    long(ask, "spreadBps") shouldBe None
    (rows(apart, "bids").loneElement \ "range").as[Seq[Long]] shouldBe Seq(43200L, 64800L)
    long(apart, "spreadBps") shouldBe None
    long(apart, "spreadBlocks") shouldBe None
  }

  it should "search a window order's maturities for its tightest gap to the bid curve" in {
    val j = world(Seq(ergOffer(offer1), windowOrder(id(50), 20000L, 60000L, 5000L))).reads
      .orderbook("ERG", ZcFilters(matchable = false), Some(262800L), None, None)
    val ask = rows(j, "asks").loneElement
    (ask \ "window").as[Boolean] shouldBe true
    // The window runs 20,000 to 60,000 blocks from now; blocks left count from the next block, as a fill would.
    val d = long(j, "spreadBlocks").get
    d should (be >= 19999L and be <= 59999L)
    long(ask, "spreadBps") shouldBe long(j, "spreadBps")
    long(ask, "bidAtBps") shouldBe long(j, "spreadBidBps")
    long(j, "spreadBps") shouldBe Some(long(j, "spreadBidBps").get - long(j, "spreadAskBps").get)
    long(j, "spreadBps").get should be <= 0L
  }

  it should "quote a window order at its curve's breakpoint, counted in blocks left from the next block" in {
    // The borrower pays most, 9,000 bps, at exactly 30,000 blocks left, and less on either side.
    val curve = Seq(1000L -> 25000L, 9000L -> 30000L, 1000L -> 35000L)
    val j = world(Seq(ergOffer(offer1), windowOrder(id(50), 20000L, 60000L, 9000L, curve = curve))).reads
      .orderbook("ERG", ZcFilters(matchable = false), Some(262800L), None, None)
    long(rows(j, "asks").loneElement, "blocks") shouldBe Some(30000L)
  }

  "matches" should "agree from both sides: what an offer would buy, and which offer takes an ask first" in {
    val w = world(market)
    val forOffer = w.reads.matches(offer1)
    ((forOffer \ "buys").as[Seq[JsObject]].head \ "askId").as[String] shouldBe order1
    (forOffer \ "next" \ "taken").as[Seq[String]] shouldBe Seq(order1)
    (w.reads.matches(order1) \ "first").as[String] shouldBe offer1
    a[LithosNotFound] should be thrownBy w.reads.matches(id(99))
  }

  "lookup" should "resolve a bond asset to its live bond, and an open box to its record" in {
    val w = world(market)
    val bond = w.reads.lookup(asset)
    (bond \ "type").as[String] shouldBe "bond"
    (bond \ "record" \ "state").as[String] shouldBe "live"
    (w.reads.lookup(order1) \ "type").as[String] shouldBe "order"
  }

  "search" should "match a prefix of six or more hex characters" in {
    val w = world(market)
    ((w.reads.search("c0ffee") \ "hits").as[Seq[JsObject]].head \ "id").as[String] shouldBe order1
    an[LithosBadRequest] should be thrownBy w.reads.search("c0ff")
  }

  "simulate" should "lend the wallet's amount into the best sources now" in {
    val j = world(market).reads.simulate(Json.obj("draft" -> Json.obj("type" -> "walletLend", "market" -> "ERG",
      "amount" -> "2000000000")))
    (j \ "filled").as[String] shouldBe "1000000000"
    (j \ "outcome").as[String] shouldBe "partial"
    ((j \ "matches").as[Seq[JsObject]].head \ "counterpartyId").as[String] shouldBe order1
  }

  it should "run a draft offer in the matching order, so a richer reward goes first" in {
    def draft(fee: Long) = Json.obj("draft" -> Json.obj("type" -> "offer", "market" -> "ERG", "capital" -> "5000000000",
      "minYieldBps" -> 700, "floorBps" -> 15000, "erg" -> Json.obj("batcherFeeNanoErg" -> fee.toString)))
    val w = world(market)
    (w.reads.simulate(draft(5000000L)) \ "outcome").as[String] shouldBe "waiting"
    val first = w.reads.simulate(draft(9000000L))
    ((first \ "matches").as[Seq[JsObject]].head \ "counterpartyId").as[String] shouldBe order1
  }

  it should "say a draft order fills now when it beats the book, and refuse a body missing terms zcs1 never defaults" in {
    val body = Json.obj("type" -> "order", "market" -> "ERG", "principal" -> "1000000000", "repayment" -> "1100000000",
      "timing" -> Json.obj("mode" -> "fixed", "maturity" -> 1749600, "exact" -> true),
      "collateral" -> Json.obj("nanoErg" -> "3000000000"), "rule" -> Json.obj("kind" -> "oracle", "ratioBps" -> 15000))
    val w = world(market)
    (w.reads.simulate(Json.obj("draft" -> body)) \ "outcome").as[String] shouldBe "full"
    a[LithosBadRequest] should be thrownBy w.reads.simulate(Json.obj("draft" -> (body - "repayment")))
  }

  "A request" should "make no more node reads than its budget, however much of the book is unverified" in {
    val tokens = (1 to 40).map(n => f"$n%02x" + "e" * 62)
    val asks = tokens.zipWithIndex.map { case (t, i) => ask(id(100 + i), t, 1L, Erg, 1L) }
    val w = world(market ++ asks)
    doAnswer(_ => Success(None)).when(w.chain.api).indexedTransactionById(anyString())
    val lookups = new AtomicInteger(0)
    val perToken = new java.util.concurrent.ConcurrentHashMap[String, Integer]()
    doAnswer { inv =>
      lookups.incrementAndGet()
      perToken.merge(inv.getArgument[String](0), 1, (a: Integer, b: Integer) => a + b)
      Success(None)
    }.when(w.chain.api).indexedBoxById(anyString())
    val j = w.reads.asks("ERG", ZcFilters(matchable = false, kind = "resale"), None, None, None, None, Some(200))
    items(j).map(a => (a \ "status").as[String]).toSet shouldBe Set("flagged")
    w.resolver.reads.get shouldBe ZcReads.ReadsPerRequest.toLong
    lookups.get should be <= ZcReads.ReadsPerRequest
    // What found nothing is not read again within its retry window, so the next request reads other tokens
    val before = lookups.get
    w.reads.asks("ERG", ZcFilters(matchable = false, kind = "resale"), None, None, None, None, Some(200))
    lookups.get - before should be <= ZcReads.ReadsPerRequest
    lookups.get should be > before
    perToken.values().toArray.toSeq.distinct shouldBe Seq(1)
  }

  "The portfolio" should "list the wallet's bond units, marked at the best bid, and the bonds it borrows" in {
    val origin = IndexedBox(oracleOrder(asset), "order", H - 1001, 1L, Some("aa" * 32), Some(H - 1000))
    val w = world(market, hashes = Set(hashOf(party(1))), balance = (5000000000L, Seq(NodeAsset(asset, 1L))))
    doAnswer(inv => Success(if (inv.getArgument[String](0) == asset) Some(origin) else None))
      .when(w.chain.api).indexedBoxById(anyString())
    val j = w.reads.portfolio()
    val lending = (j \ "lending").as[Seq[JsObject]].loneElement
    (lending \ "status").as[String] shouldBe "live"
    (lending \ "borrowedByMe").as[Boolean] shouldBe true
    (lending \ "mark" \ "basis").as[String] shouldBe "bid"
    (lending \ "actions").as[Seq[JsObject]].find(a => (a \ "name").as[String] == "extinguish").map(a => (a \ "enabled").as[Boolean]) shouldBe Some(true)
    val borrowing = (j \ "borrowing").as[Seq[JsObject]].loneElement
    (borrowing \ "bond" \ "assetId").as[String] shouldBe asset
    (borrowing \ "holdsUnit").as[Boolean] shouldBe true
    (borrowing \ "actions").as[Seq[JsObject]].find(a => (a \ "name").as[String] == "extinguish").map(a => (a \ "enabled").as[Boolean]) shouldBe Some(true)
    ((j \ "open" \ "orders").as[Seq[JsObject]].loneElement \ "boxId").as[String] shouldBe order1

    // Without the bond unit in wallet, extinguish is disabled on the borrowing row
    val wNoUnit = world(market, hashes = Set(hashOf(party(1))), balance = (5000000000L, Seq.empty))
    val bNoUnit = (wNoUnit.reads.portfolio() \ "borrowing").as[Seq[JsObject]].loneElement
    (bNoUnit \ "holdsUnit").as[Boolean] shouldBe false
    (bNoUnit \ "actions").as[Seq[JsObject]].find(a => (a \ "name").as[String] == "extinguish").map(a => (a \ "enabled").as[Boolean]) shouldBe Some(false)
  }

  "borrowedByMe" should "mark resale asks, curves, orderbook, lots and search hits for a bond this wallet borrowed" in {
    val origin = IndexedBox(oracleOrder(asset), "order", H - 1001, 1L, Some("aa" * 32), Some(H - 1000))
    val askId = "deadbe" + id(40).drop(6)
    val lotId = id(41)
    val resale = ask(askId, asset, 1L, Erg, 1L, seller = 3)
    val lot = box(lotId, tree(sigmafi.zc.ZcKind.LotAuction), 3000000L, Seq(asset -> 1L),
      regs(bytes(hashOf(party(3))), longs(2 * Erg, Erg, H - 10L, H + 100L, H + 500L, 2000000L)))
    val w = world(market ++ Seq(resale, lot), hashes = Set(hashOf(party(1))))
    doAnswer(inv => Success(if (inv.getArgument[String](0) == asset) Some(origin) else None))
      .when(w.chain.api).indexedBoxById(anyString())

    // Asks: seller is party(3), so mine is false while borrowedByMe is true
    val resaleItem = items(w.reads.asks("ERG", ZcFilters(kind = "resale", matchable = false), None, None, None, None, None)).loneElement
    (resaleItem \ "boxId").as[String] shouldBe askId
    (resaleItem \ "mine").as[Boolean] shouldBe false
    (resaleItem \ "borrowedByMe").as[Boolean] shouldBe true
    (resaleItem \ "bond" \ "borrowedByMe").as[Boolean] shouldBe true

    // Orderbook & curves carry borrowedByMe on the resale ask
    val obAsk = (w.reads.orderbook("ERG", ZcFilters(kind = "resale", matchable = false), Some(262800L), None, None) \ "asks")
      .as[Seq[JsObject]].loneElement
    (obAsk \ "boxId").as[String] shouldBe askId
    (obAsk \ "borrowedByMe").as[Boolean] shouldBe true
    val dot = (w.reads.curves("ERG", ZcFilters(kind = "resale", matchable = false)) \ "askDots").as[Seq[JsObject]].loneElement
    (dot \ "askId").as[String] shouldBe askId
    (dot \ "kind").as[String] shouldBe "resale"
    (dot \ "borrowedByMe").as[Boolean] shouldBe true

    // Lot auction containing the borrowed bond unit marks borrowedByMe and lists its bondId
    val lotJson = (w.reads.auctions(Some("ERG")) \ "lots").as[Seq[JsObject]].loneElement
    (lotJson \ "mine").as[Boolean] shouldBe false
    (lotJson \ "borrowedByMe").as[Boolean] shouldBe true
    (lotJson \ "borrowedBondIds").as[Seq[String]] shouldBe Seq(asset)

    // Search marks borrowedByMe on matching ask hit
    val hit = (w.reads.search("deadbe") \ "hits").as[Seq[JsObject]].loneElement
    (hit \ "id").as[String] shouldBe askId
    (hit \ "borrowedByMe").as[Boolean] shouldBe true
  }

  "Lru" should "coalesce concurrent cache misses for the same key" in {
    val cache = new Lru[String, Int](16)
    val started = new java.util.concurrent.CountDownLatch(1)
    val release = new java.util.concurrent.CountDownLatch(1)
    val calls = new AtomicInteger(0)
    val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
    try {
      val tasks = (1 to 4).map { _ =>
        pool.submit(new java.util.concurrent.Callable[Int] {
          override def call(): Int = cache.get("k", () => {
            calls.incrementAndGet()
            started.countDown()
            release.await(5, java.util.concurrent.TimeUnit.SECONDS)
            42
          })
        })
      }
      started.await(5, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
      Thread.sleep(50)
      release.countDown()
      tasks.map(_.get(5, java.util.concurrent.TimeUnit.SECONDS)).toSet shouldBe Set(42)
      calls.get() shouldBe 1
    } finally pool.shutdownNow()
  }

  "orderbook" should "bound work across many multi-point offers and window orders" in {
    val offers = (1 to 250).map { i =>
      val curve = (1 to 10).map(j => (500L + i + j * 10L) -> (2000L * j + i * 3L))
      ergOffer(id(200 + i), min = 1000L, max = 262800L, curve = curve)
    }
    val windows = (1 to 250).map { i =>
      val curve = (1 to 5).map(j => (1000L + j * 500L) -> (10000L * j))
      windowOrder(id(600 + i), 10000L, 100000L, 4000L, curve = curve)
    }
    val w = world(offers ++ windows)
    val t0 = System.nanoTime()
    val j = w.reads.orderbook("ERG", ZcFilters(matchable = false), Some(262800L), None, None)
    val elapsedMs = (System.nanoTime() - t0) / 1000000L
    rows(j, "asks") should have size ZcReads.MaxBookRows
    rows(j, "bids") should have size ZcReads.MaxBookRows
    elapsedMs should be < 5000L
  }

  "asks and offers" should "reuse cached priced rows across repeated small-page reads at the same revision" in {
    val origin = IndexedBox(oracleOrder(asset), "order", H - 1001, 1L, Some("aa" * 32), Some(H - 1000))
    val resale = ask(id(60), asset, 1L, Erg, 1L)
    val w = world(market ++ Seq(oracleOrder(id(2)), resale, ergOffer(id(21))))
    doAnswer(inv => Success(if (inv.getArgument[String](0) == asset) Some(origin) else None))
      .when(w.chain.api).indexedBoxById(anyString())

    // Prime and exercise repeated asks/offers pages with different sorts and limit=1 at the same snapshot
    val a1 = w.reads.asks("ERG", ZcFilters(matchable = false), None, None, Some("apr"), None, Some(1))
    val a2 = w.reads.asks("ERG", ZcFilters(matchable = false), None, None, Some("maturity"), Some("1"), Some(1))
    val a3 = w.reads.asks("ERG", ZcFilters(matchable = false), None, None, Some("size"), None, Some(1))
    items(a1) should have size 1
    items(a2) should have size 1
    items(a3) should have size 1
    (a1 \ "total").as[Int] shouldBe 3

    val o1 = w.reads.offers("ERG", ZcFilters(matchable = false), None, None, Some("apr"), None, Some(1))
    val o2 = w.reads.offers("ERG", ZcFilters(matchable = false), None, None, Some("size"), Some("1"), Some(1))
    items(o1) should have size 1
    items(o2) should have size 1
    (o1 \ "total").as[Int] shouldBe 2

    // Lru.getIf does not store uncacheable results while still coalescing concurrent callers
    val cache = new Lru[String, Int](8)
    val calls = new AtomicInteger(0)
    cache.getIf("k", () => (calls.incrementAndGet(), false)) shouldBe 1
    cache.getIf("k", () => (calls.incrementAndGet(), true)) shouldBe 2
    cache.getIf("k", () => (calls.incrementAndGet(), true)) shouldBe 2
    calls.get() shouldBe 2
  }

  it should "deduplicate pending mempool tx script lookups so undisclosed spam cannot defeat asks caching" in {
    val w = world(market)
    val spamTxId = "ab" * 32
    val spamOrders = (1 to 16).map { i =>
      val boxId = f"dd$i%062x"
      // Distinct undisclosed borrower hashes all created by one mempool tx
      oracleOrder(boxId, borrower = 100 + i).copy(transactionId = spamTxId, index = i)
    }
    val mempoolTx = node.model.NodeTransaction(spamTxId, Seq.empty, Seq.empty, spamOrders)
    val unconfCalls = new AtomicInteger(0)
    doAnswer { inv =>
      if (inv.getArgument[String](0) == spamTxId) {
        unconfCalls.incrementAndGet()
        Success(Some(mempoolTx))
      } else Success(None)
    }.when(w.chain.api).unconfirmedTransactionById(anyString())

    val snap = state.synchronization.CompleteMempool.Snapshot(
      blockId(H), Set(spamTxId), Set.empty, System.nanoTime(),
      transactions = Vector(state.synchronization.CompleteMempool.MempoolTx(spamTxId, mempoolTx, 1000)))
    w.tracker.observe(state.synchronization.CompleteMempool.Observation(1L, Some(snap), None))
    eventually(w.tracker.view.flatMap(_.mempool).map(_.pending.size) shouldBe Some(16))

    // First asks call reads the mempool tx once (not 16 times) and populates the pricedAsks cache
    val a1 = w.reads.asks("ERG", ZcFilters(matchable = false), None, None, None, None, Some(10))
    (a1 \ "total").as[Int] shouldBe 17
    unconfCalls.get() shouldBe 1

    // Second asks call hits the cached priced rows with zero additional mempool reads
    val a2 = w.reads.asks("ERG", ZcFilters(matchable = false), None, None, Some("size"), None, Some(10))
    (a2 \ "total").as[Int] shouldBe 17
    unconfCalls.get() shouldBe 1

    // Confirming the tx still allows a confirmed indexed lookup (pending negative cache never poisons confirmed scripts)
    val confirmedReads = new AtomicInteger(0)
    val disclosedTree = party(101)
    val indexedTx = node.model.IndexedTransaction(spamTxId,
      Seq(LDNodeFixtures.indexed(spamOrders.head.copy(ergoTree = disclosedTree), H - 1)),
      Seq.empty, Seq.empty, H, 1, blockId(H), 0L, 0, 0L, 100)
    doAnswer { inv =>
      if (inv.getArgument[String](0) == spamTxId) {
        confirmedReads.incrementAndGet()
        Success(Some(indexedTx))
      } else Success(None)
    }.when(w.chain.api).indexedTransactionById(anyString())
    val rec = transactions.batching.sigmafi.zc.ZcBoxes.read(spamOrders.head).get
    w.resolver.script(rec, pending = false, new transactions.batching.sigmafi.zc.ZcBudget(4)) shouldBe Some(disclosedTree.toLowerCase)
    confirmedReads.get() shouldBe 1
  }
}
