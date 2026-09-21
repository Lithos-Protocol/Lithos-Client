package stats

import akka.actor.{ActorIdentity, ActorSystem, Identify, Props}
import akka.testkit.{TestKit, TestProbe}
import api.{DexHistory, LithosDexApi}
import api.models._
import com.typesafe.config.ConfigFactory
import configs.{DexStatsConfig, StatsConfig}
import controllers.LithosDexApiController
import lithosdex.LDHelpers
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import org.mockito.Mockito.verifyNoInteractions
import play.api.Configuration
import play.api.test.FakeRequest
import play.api.test.Helpers._
import stats.StatsCollector.{RefreshStatistics, StratumObserved}
import support.FakeCache
import transactions.batching.lithosdex.LDBoxes.PoolSnapshot

import java.util.UUID
import java.util.concurrent.{CompletableFuture, LinkedBlockingQueue, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._

object DexStatsSpec {
  def data(block: String = "block"): DexStatsData = {
    val snapshots = Vector(
      PoolSnapshot(100, 1, "a", "tx-a", 1000000000L, 1000000L, 0L, 0L, BigInt(0), BigInt(0), 1L),
      PoolSnapshot(110, 2, "b", "tx-b", 2000000000L, 1000000L, 0L, 0L, LDHelpers.SCALE * Long.MaxValue, BigInt(0), 1L),
      PoolSnapshot(120, 3, "c", "tx-c", 2000000000L, 1000000L, 0L, 0L, LDHelpers.SCALE * Long.MaxValue * 2, BigInt(0), 1L))
    val current = LDPricePoint(1000, None, 0.5, "2000000000", "1000000")
    val prices = LDPriceHistory.Ranges.keys.map(name => name -> DexHistory.price(snapshots, true,
      current, 6, Some(name), None, _ => Map.empty)).toMap
    DexStatsData(1000, block, 0, snapshots, true, current, 6, Map.empty, prices,
      DexHistory.fees(snapshots, true, 0, 1000, 720, _ => Map.empty), LDRecentActivity(Vector.empty))
  }
}

class DexStatsSpec extends TestKit(ActorSystem("dex-stats-spec",
  ConfigFactory.parseResources("application.conf").resolve()))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {
  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)
  private val settings = StatsConfig(dex = DexStatsConfig(refreshIntervalMs = 1000, staleAfterMs = 3000))

  private class HeldSource extends DexStatsSource {
    val calls = new LinkedBlockingQueue[CompletableFuture[DexStatsData]]()
    val count = new AtomicInteger()
    override def read(): DexStatsData = {
      count.incrementAndGet()
      val call = new CompletableFuture[DexStatsData]()
      calls.add(call)
      call.get(8, TimeUnit.SECONDS)
    }
    def next(): CompletableFuture[DexStatsData] = {
      val call = calls.poll(3, TimeUnit.SECONDS)
      call should not be null
      call
    }
  }

  "DEX refresh" should "leave Stratum observations responsive and retain the last successful graph on failure" in {
    val source = new HeldSource
    val cache = new StatsCache(settings)
    val worker = new DexStatsRefresh(source, system.dispatchers.lookup("lithos-contexts.stats-read-dispatcher"))
    val actor = system.actorOf(Props(new StatsCollector(cache, Some(worker)))
      .withDispatcher("lithos-contexts.stats-dispatcher").withMailbox("stats-mailbox"))
    val first = source.next()
    var recovery: Option[CompletableFuture[DexStatsData]] = None
    try {
      val probe = TestProbe()
      probe.send(actor, StratumObserved(UUID.randomUUID(), 1, 1L, System.nanoTime(), 4, None))
      awaitAssert(cache.snapshot().local.stratum.connectedConnections shouldBe 4)
      (1 to 20).foreach(_ => actor ! RefreshStatistics)
      probe.send(actor, Identify("drained"))
      probe.expectMsgType[ActorIdentity].correlationId shouldBe "drained"
      source.count.get() shouldBe 1
      first.complete(DexStatsSpec.data())
      actor ! RefreshStatistics
      awaitAssert(cache.dexSnapshot().view.status shouldBe "ready", 3.seconds)
      val second = source.next()
      second.completeExceptionally(new IllegalStateException("node unavailable"))
      actor ! RefreshStatistics
      awaitAssert({
        cache.dexSnapshot().view.status shouldBe "stale"
        cache.dexSnapshot().view.error should not be empty
      }, 3.seconds)
      cache.dexSnapshot().data.get.blockId shouldBe "block"
      cache.snapshot().local.stratum.connectedConnections shouldBe 4
      recovery = Some(source.next())
      recovery.get.complete(DexStatsSpec.data("recovered"))
      actor ! RefreshStatistics
      awaitAssert(cache.dexSnapshot().data.map(_.blockId) shouldBe Some("recovered"))
      cache.dexSnapshot().view.error shouldBe None
    } finally {
      first.completeExceptionally(new IllegalStateException("test complete"))
      recovery.foreach(_.completeExceptionally(new IllegalStateException("test complete")))
      system.stop(actor)
    }
  }

  it should "keep one physical read across collector replacement and discard the old incarnation's result" in {
    val source = new HeldSource
    val cache = new StatsCache(settings)
    val worker = new DexStatsRefresh(source, system.dispatchers.lookup("lithos-contexts.stats-read-dispatcher"))
    val firstActor = system.actorOf(Props(new StatsCollector(cache, Some(worker))))
    val first = source.next()
    val observer = TestProbe()
    observer.watch(firstActor)
    system.stop(firstActor)
    observer.expectTerminated(firstActor)
    val replacement = system.actorOf(Props(new StatsCollector(cache, Some(worker))))
    var second: Option[CompletableFuture[DexStatsData]] = None
    try {
      replacement ! RefreshStatistics
      observer.send(replacement, Identify("ready"))
      observer.expectMsgType[ActorIdentity]
      source.count.get() shouldBe 1
      first.complete(DexStatsSpec.data("old"))
      replacement ! RefreshStatistics
      second = Some(source.next())
      cache.dexSnapshot().data shouldBe None
      second.get.complete(DexStatsSpec.data("new"))
      replacement ! RefreshStatistics
      awaitAssert(cache.dexSnapshot().data.map(_.blockId) shouldBe Some("new"))
    } finally {
      first.completeExceptionally(new IllegalStateException("test complete"))
      second.foreach(_.completeExceptionally(new IllegalStateException("test complete")))
      system.stop(replacement)
    }
  }

  it should "start no node work when DEX collection is disabled" in {
    val source = mock[DexStatsSource]
    val cache = new StatsCache(settings.copy(dex = settings.dex.copy(enabled = false)))
    val worker = new DexStatsRefresh(source, system.dispatcher)
    val actor = system.actorOf(Props(new StatsCollector(cache, Some(worker))))
    val probe = TestProbe()
    try {
      probe.send(actor, RefreshStatistics)
      probe.send(actor, Identify("processed"))
      probe.expectMsgType[ActorIdentity]
      cache.dexSnapshot().view.status shouldBe "disabled"
      verifyNoInteractions(source)
    } finally system.stop(actor)
  }

  "Graph endpoints" should "read cached data without touching the live API, including stale and cold responses" in {
    val live = mock[LithosDexApi]
    val cache = new StatsCache(settings)
    val config = Configuration("lithos.apiKeyHash" -> ("00" * 32))
    val controller = new LithosDexApiController(stubControllerComponents(), live, config, new FakeCache,
      TestProbe().ref, system, cache)
    val request = FakeRequest(GET, "/dex/price/history")
    val cold = controller.getPriceHistory(None, None).apply(request)
    status(cold) shouldBe SERVICE_UNAVAILABLE
    (contentAsJson(cold) \ "stats" \ "status").as[String] shouldBe "loading"
    cache.publishDex(DexStatsSpec.data(), System.currentTimeMillis(), System.nanoTime())
    val ready = controller.getPriceHistory(Some("7d"), None).apply(request)
    status(ready) shouldBe OK
    (contentAsJson(ready) \ "range").as[String] shouldBe "7D"
    (contentAsJson(ready) \ "stats" \ "sourceBlockId").as[String] shouldBe "block"
    cache.dexFailed()
    val stale = controller.getFeeHistory(None, None, None).apply(request)
    status(stale) shouldBe OK
    (contentAsJson(stale) \ "partial").as[Boolean] shouldBe true
    ((contentAsJson(stale) \ "history")(0) \ "cumulativeX").as[String] shouldBe (BigInt(Long.MaxValue) * 2).toString
    header("Cache-Control", stale) shouldBe Some("no-store")
    status(controller.getRecentActivity(Some(12)).apply(request)) shouldBe OK
    status(controller.getPriceHistory(Some("30D"), Some(1)).apply(request)) shouldBe BAD_REQUEST
    status(controller.getFeeHistory(Some(-1), None, None).apply(request)) shouldBe BAD_REQUEST
    verifyNoInteractions(live)
  }

  it should "expire by observation age and retain exact cumulative fees when buckets change" in {
    val cache = new StatsCache(settings)
    val data = DexStatsSpec.data()
    cache.publishDex(data, 1L, 100L)
    cache.dexSnapshot(100L + 4.seconds.toNanos).view.status shouldBe "stale"
    val points = data.fees(None, None, Some(10)).history
    points.map(p => BigInt(p.periodX)).sum shouldBe BigInt(Long.MaxValue) * 2
    points.last.cumulativeX shouldBe (BigInt(Long.MaxValue) * 2).toString
    new StatsCache(settings.copy(enabled = false)).dexSnapshot().view.status shouldBe "disabled"
  }

  "Graph boundaries" should "carry the starting price into a quiet range and count endpoint buckets toward the limit" in {
    val data = DexStatsSpec.data()
    val base = data.history.head.copy(height = 960)
    val trade = data.history(1).copy(height = 995)
    val current = data.current.copy(price = 0.5)
    val graph = DexHistory.price(Vector(base, trade), true, current, 6, Some("1H"), None, _ => Map.empty)
    graph.history.head.height shouldBe 970
    graph.history.head.price shouldBe 1.0
    graph.changePct shouldBe -0.5
    graph.partial shouldBe false
    val fees = DexHistory.fees(Vector(base, trade), true, 0, 999, 2, _ => Map.empty)
    fees.partial shouldBe false
    intercept[api.LithosApiErrors.LithosBadRequest] {
      DexHistory.fees(Vector(base, trade), true, 0, 1000, 2, _ => Map.empty)
    }
  }
}
