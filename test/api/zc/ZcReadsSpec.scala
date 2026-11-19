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

  "orderbook" should "place each ask at its maturity and each bid at its lowest yield in range" in {
    val j = world(market).reads.orderbook("ERG", ZcFilters(), Some(262800L), None, None)
    ((j \ "bids").as[Seq[JsObject]].head \ "bps").as[Long] shouldBe 820L
    ((j \ "asks").as[Seq[JsObject]].head \ "boxId").as[String] shouldBe order1
    (j \ "spreadBps").as[Long] should be < 0L
    an[LithosBadRequest] should be thrownBy world(market).reads.orderbook("ERG", ZcFilters(), Some(1000L), Some(5L), None)
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
    (lending \ "mark" \ "basis").as[String] shouldBe "bid"
    ((j \ "borrowing").as[Seq[JsObject]].loneElement \ "bond" \ "assetId").as[String] shouldBe asset
    ((j \ "open" \ "orders").as[Seq[JsObject]].loneElement \ "boxId").as[String] shouldBe order1
  }
}
