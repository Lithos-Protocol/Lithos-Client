package transactions.batching.sigmafi.zc

import api.LithosApiErrors.LithosBadRequest
import api.zc.{ZcDrafts, ZcFilters, ZcReads}
import configs.ZcConfig
import node.model.{NodeAsset, NodeBox}
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import play.api.libs.json.Json
import sigmafi.zc.{ZcKind, ZcMarket}
import support.LDNodeFixtures
import transactions.batching.tracking.ScriptedChain

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/**
 * Regression tests for Follow-Up Audit 2 fixes in ZC:
 * T5 (ZcTracker refusing truncated rescan over complete book), T6 (poisoned LendOffer holding extra tokens),
 * T8.2 (ZcRules.windowMaturity cap & ZcMatcher.batch deadline), O10 (orderbook band, lot item cap, shareRequestBox overflow).
 */
class AuditZcHardeningSpec extends AnyFlatSpec with Matchers with Eventually {

  import ZcFixtures._
  import ZcMarketFixtures._

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(Span(10, Seconds), Span(20, Millis))

  "ZcBoxes.offer" should "reject ERG offers carrying any token and SigUSD offers carrying extra tokens (T6)" in {
    val cleanErg = ergOffer(id(20))
    ZcBoxes.read(cleanErg) shouldBe defined

    val poisonedErg = cleanErg.copy(assets = Seq(NodeAsset(id(99), 1L)))
    ZcBoxes.read(poisonedErg) shouldBe None

    val cleanSig = cleanErg.copy(
      boxId = id(21),
      ergoTree = tree(ZcKind.LendOffer, ZcMarket.SigUsd),
      value = ZcBoxes.MinBox + 5000000L,
      assets = Seq(NodeAsset(sigmafi.zc.ZcContracts.SigUsdId, 100000L)))
    ZcBoxes.read(cleanSig) shouldBe defined

    val poisonedSig = cleanSig.copy(assets = cleanSig.assets :+ NodeAsset(id(99), 1L))
    ZcBoxes.read(poisonedSig) shouldBe None
  }

  "ZcMatcher.batch" should "respect an already-overdue deadline without running full matching rounds (T8.2)" in {
    val c = new ScriptedChain
    c.extend(H - 40, H)
    c.indexed = H
    c.scans(Seq(ergOffer(id(20)), oracleOrder(id(1)), oracle()).map(b => LDNodeFixtures.indexed(b, H - 10)): _*)
    val t = new ZcTracker(() => c.api, ExecutionContext.global, ZcConfig(enabled = true, 1000), retryMs = 20L)
    t.poke()
    eventually(t.view.isDefined shouldBe true)
    val book = ZcMarketBook.of(t.view.get)
    val matcher = new ZcMatcher(book, _ => None)
    val overdue = Deadline.now - 1.second
    val run = matcher.batch(ZcMarket.Erg, deadline = Some(overdue))
    run.taken shouldBe empty
    run.takenBy shouldBe empty
    run.rounds shouldBe 0
  }

  "ZcReads.orderbook" should "reject negative band and clamp band overflow safely (O10.1)" in {
    val c = new ScriptedChain
    c.extend(H - 40, H)
    c.indexed = H
    c.scans(Seq(ergOffer(id(20)), oracleOrder(id(1)), oracle()).map(b => LDNodeFixtures.indexed(b, H - 10)): _*)
    val t = new ZcTracker(() => c.api, ExecutionContext.global, ZcConfig(enabled = true, 1000), retryMs = 20L)
    t.poke()
    eventually(t.view.isDefined shouldBe true)
    val r = new ZcResolver(() => c.api, t)
    val reads = new ZcReads(t, r, () => c.api, () => Set.empty, () => (0L, Seq.empty), warmer = None)
    val f = ZcFilters(15000L, "all", matchable = true, grid = false)
    a[LithosBadRequest] should be thrownBy reads.orderbook("ERG", f, None, Some(10000L), Some(-1L))
    // Near Long.MaxValue band must not wrap negative
    val res = reads.orderbook("ERG", f, None, Some(10000L), Some(Long.MaxValue - 100L))
    (res \ "range" \ "from").as[Long] shouldBe 1L
    (res \ "range" \ "to").as[Long] shouldBe Long.MaxValue
  }

  "ZcDrafts.lotOf" should "cap distinct item count at ZcSizing.MaxCollateralTokens (O10.2)" in {
    val tooMany = (1 to (ZcSizing.MaxCollateralTokens + 1)).map(i =>
      Json.obj("tokenId" -> id(500 + i), "amount" -> "1"))
    val body = Json.obj("market" -> "ERG", "items" -> tooMany, "opening" -> "2000000000", "floor" -> "1000000000",
      "end" -> (H + 1000L))
    a[LithosBadRequest] should be thrownBy ZcDrafts.lotOf(body, H.toLong)
  }

  "ZcUserTransactions.shareRequestBox" should "throw ArithmeticException on fee overflow (O10.4)" in {
    an[ArithmeticException] should be thrownBy ZcUserTransactions.shareRequestBox(
      id(1), ZcMarket.Erg, Seq(1000L, Long.MaxValue, 5000000L), auction = false, Seq("n", "d", "0"), "aa" * 32)
  }
}
