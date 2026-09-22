package stats

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import configs.{DexStatsConfig, MiningStatsConfig, StatsConfig}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import state.synchronization.NodeHeights
import stats.StatsCollector.{RefreshStatistics, StratumObserved}

import java.io.IOException
import java.util.UUID
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Await
import scala.concurrent.duration._

class MiningStatsRefreshSpec extends TestKit(ActorSystem("mining-stats-spec",
  ConfigFactory.parseResources("application.conf").resolve()))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {
  import MiningStatsStoreSpec._
  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)
  private val executor = system.dispatchers.lookup("lithos-contexts.mining-stats-dispatcher")
  private val settings = MiningStatsConfig(refreshIntervalMs = 1000, blocksPerRefresh = 2)
  private class Chain(size: Int) extends MiningStatsSource {
    private val baseTime = System.currentTimeMillis() - 1000000L
    @volatile var chain = (1 to size).map(h => cursor(h, time = baseTime)).toVector
    @volatile var indexedHeight = size
    @volatile var failed = false
    override def heights: NodeHeights = {
      if (failed) throw new IOException("node unavailable")
      NodeHeights(chain.size, math.min(chain.size, indexedHeight))
    }
    override def header(height: Int): MiningCursor = chain(height - 1)
    override def read(at: MiningCursor, previous: MiningCursor): MiningBlockRecord = record(at, previous, 10)
    override def sample(height: Int): DifficultySample = {
      val at = chain(height - 1)
      DifficultySample(at.height, at.timestamp, (BigInt(1) << 100).toString)
    }
    def fork(from: Int): Unit = {
      chain = chain.take(from - 1) ++ chain.drop(from - 1).map(h => h.copy(blockId = s"b-${h.height}",
        parentId = if (h.height == from) chain(from - 2).blockId else s"b-${h.height - 1}"))
    }
  }
  private def caughtUp(refresh: MiningStatsRefresh, height: Int): Unit = {
    awaitAssert({ refresh.tick(); refresh.view.sourceHeight shouldBe Some(height); refresh.view.status shouldBe "ready" }, 8.seconds)
    awaitAssert(refresh.busy shouldBe false)
  }

  "Mining replay" should "catch up in bounded batches, resume after downtime and reverse a same-height reorg" in {
    val chain = new Chain(5)
    val db = new MiningStatsStore(None, identity)
    val first = new MiningStatsRefresh(settings, false, 2, () => db, () => chain, executor)
    try {
      caughtUp(first, 5)
      first.view.totals.copy(accounting = MiningAccountingTotals()) shouldBe MiningTotals(4, 4, "40")
      chain.fork(4)
      caughtUpAfterFork(first, chain)
      first.view.payments.map(_.blockId) should contain("b-5")
      first.view.payments.map(_.blockId) should not contain "a-5"
      first.view.totals.copy(accounting = MiningAccountingTotals()) shouldBe MiningTotals(4, 4, "40")
    } finally Await.result(first.shutdown(), 3.seconds)
    // In-memory repository substitutes disk reopening; the real store's reopen is tested separately.
    chain.chain :+= cursor(6, "b", chain.chain.head.timestamp - 1000)
    chain.indexedHeight = 6
    val resumed = new MiningStatsRefresh(settings, false, 2, () => db, () => chain, executor)
    try { caughtUp(resumed, 6); resumed.view.totals.payments shouldBe 5 }
    finally Await.result(resumed.shutdown(), 3.seconds)
  }

  private def caughtUpAfterFork(refresh: MiningStatsRefresh, chain: Chain): Unit = {
    awaitAssert({ refresh.tick(); refresh.view.sourceBlockId shouldBe Some(chain.chain.last.blockId)
      refresh.view.status shouldBe "ready" }, 8.seconds)
    awaitAssert(refresh.busy shouldBe false)
  }

  it should "distinguish index lag from a shortened chain and rebuild beyond its retained anchor" in {
    val chain = new Chain(8)
    val db = new MiningStatsStore(None, identity)
    val refresh = new MiningStatsRefresh(settings.copy(blocksPerRefresh = 20), false, 5, () => db, () => chain, executor)
    try {
      caughtUp(refresh, 8)
      val committed = db.state.get
      chain.indexedHeight = 6
      awaitAssert({ refresh.tick(); refresh.busy shouldBe false }, 3.seconds)
      db.state.get shouldBe committed
      chain.chain = chain.chain.take(6)
      chain.fork(3)
      caughtUpAfterFork(refresh, chain)
      refresh.view.fromHeight shouldBe Some(5)
      refresh.view.totals.payments shouldBe 2
    } finally Await.result(refresh.shutdown(), 3.seconds)
  }

  it should "retain one physical slot across collector replacement while Stratum updates remain available" in {
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val reads = new AtomicInteger()
    val closes = new AtomicInteger()
    val db = new MiningStatsStore(None, identity) { override def close(): Unit = { closes.incrementAndGet(); () } }
    val chain = new Chain(3) {
      override def read(at: MiningCursor, previous: MiningCursor): MiningBlockRecord = {
        reads.incrementAndGet(); entered.countDown(); release.await(10, TimeUnit.SECONDS)
        super.read(at, previous)
      }
    }
    val refresh = new MiningStatsRefresh(settings, false, 2, () => db, () => chain, executor)
    val cache = new StatsCache(StatsConfig(dex = DexStatsConfig(enabled = false)))
    val first = system.actorOf(Props(new StatsCollector(cache, None, None, Some(refresh))))
    var second = Option.empty[akka.actor.ActorRef]
    try {
      entered.await(3, TimeUnit.SECONDS) shouldBe true
      system.stop(first)
      second = Some(system.actorOf(Props(new StatsCollector(cache, None, None, Some(refresh)))))
      (1 to 30).foreach(_ => second.get ! RefreshStatistics)
      val producer = TestProbe()
      producer.send(second.get, StratumObserved(UUID.randomUUID(), 1, System.currentTimeMillis(), System.nanoTime(), 7, None))
      awaitAssert(cache.snapshot().local.stratum.connectedConnections shouldBe 7)
      reads.get() shouldBe 1
      intercept[IllegalStateException](Await.result(refresh.history(1, 10), 1.second)).getMessage should include("busy")
      val local = LocalMiningObservation("shares", "session", 1, 1, System.currentTimeMillis(), Map("accepted" -> "42"))
      producer.send(second.get, local)
      awaitAssert(cache.localMiningViews("shares").observation.counters("accepted") shouldBe "42")
      val shutdown = refresh.shutdown()
      shutdown.isCompleted shouldBe false
      release.countDown()
      Await.result(shutdown, 3.seconds)
      closes.get() shouldBe 1
    } finally {
      release.countDown(); second.foreach(system.stop); system.stop(first)
      Await.result(refresh.shutdown(), 3.seconds)
    }
  }

  it should "keep a restored view unavailable on node failure and recover without duplicate totals" in {
    val chain = new Chain(3)
    val db = new MiningStatsStore(None, identity)
    db.initialize(chain.header(1))
    db.append(record(chain.header(2), chain.header(1), 10))
    chain.failed = true
    val refresh = new MiningStatsRefresh(settings, true, 2, () => db, () => chain, executor)
    try {
      awaitAssert({ refresh.tick(); refresh.view.status shouldBe "unavailable" })
      refresh.view.totals.payments shouldBe 1
      awaitAssert(refresh.busy shouldBe false)
      val stored = Await.result(refresh.history(0, 10), 1.second)
      stored.status shouldBe "unavailable"
      stored.records.map(_.cursor.height) shouldBe Vector(2) // no node read on this path
      chain.failed = false
      caughtUp(refresh, 3)
      refresh.view.totals.payments shouldBe 2
    } finally Await.result(refresh.shutdown(), 3.seconds)
  }

  it should "do no work when disabled and age cached mining data independently of Stratum" in {
    val refresh = new MiningStatsRefresh(settings.copy(enabled = false), false, 2,
      () => fail("opened disabled database"), () => fail("read disabled source"), executor)
    refresh.tick()
    refresh.view.status shouldBe "disabled"
    Await.result(refresh.shutdown(), 1.second)
    val cache = new StatsCache(StatsConfig.Default)
    cache.publishMining(MiningStatsView("ready", Some(System.currentTimeMillis())))
    cache.snapshot(System.nanoTime() + 121.seconds.toNanos).mining.status shouldBe "stale"
    cache.snapshot().local.stratum.status shouldBe "waiting"
  }

  it should "reject a branch change during a read before committing its contribution" in {
    val chain = new Chain(3) {
      @volatile var changed = false
      override def read(at: MiningCursor, previous: MiningCursor): MiningBlockRecord = {
        val result = super.read(at, previous)
        if (!changed) { changed = true; fork(2) }
        result
      }
    }
    val db = new MiningStatsStore(None, identity)
    val refresh = new MiningStatsRefresh(settings, false, 2, () => db, () => chain, executor)
    try {
      awaitAssert({ refresh.tick(); refresh.view.status shouldBe "unavailable" })
      db.state.get.cursor.height shouldBe 1
      db.state.get.totals shouldBe MiningTotals()
      caughtUp(refresh, 3)
      refresh.view.payments.map(_.blockId).toSet shouldBe Set("b-2", "b-3")
    } finally Await.result(refresh.shutdown(), 3.seconds)
  }

  it should "apply the timestamp lower bound and leave pruning disabled when requested" in {
    val chain = new Chain(4)
    val now = System.currentTimeMillis()
    chain.chain = chain.chain.zipWithIndex.map { case (at, i) => at.copy(timestamp = now - (4L - i) * 86400000L + 60000L) }
    val prunes = new AtomicInteger()
    val db = new MiningStatsStore(None, identity) {
      override def prune(cutoff: Long, limit: Int, rollbackBlocks: Int): Int = { prunes.incrementAndGet(); 0 }
    }
    val refresh = new MiningStatsRefresh(settings.copy(historyDays = 2, pruningEnabled = false), false, 2,
      () => db, () => chain, executor)
    try {
      caughtUp(refresh, 4)
      refresh.view.fromHeight shouldBe Some(3)
      refresh.view.totals.payments shouldBe 2
      prunes.get() shouldBe 0
    } finally Await.result(refresh.shutdown(), 3.seconds)
  }
}
