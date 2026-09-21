package stats

import akka.actor.{ActorIdentity, ActorSystem, Identify, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import configs.{DexStatsConfig, StatsConfig, StatsStorageConfig}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import stats.StatsCollector.{RefreshStatistics, StratumObserved}

import java.io.IOException
import java.util.UUID
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Await
import scala.concurrent.duration._

class StatsPersistenceSpec extends TestKit(ActorSystem("stats-persistence-spec",
  ConfigFactory.parseResources("application.conf").resolve()))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)
  private val settings = StatsStorageConfig(flushIntervalMs = 1000, pruneIntervalMs = 1000)
  private val executor = system.dispatchers.lookup("lithos-contexts.stats-store-dispatcher")
  private def record(time: Long, block: String = "block"): StoredDexStats = StoredDexStats(time, DexStatsSpec.data(block))

  private class TestStore extends StatsStore {
    @volatile var saved: Option[StoredDexStats] = None
    @volatile var loadFailure = false
    @volatile var saveFailure = false
    @volatile var pruneFailure = false
    val saves = new AtomicInteger()
    val closes = new AtomicInteger()
    override def load(): Option[StoredDexStats] = {
      if (loadFailure) throw new IOException("unreadable snapshot")
      saved
    }
    override def save(snapshot: StoredDexStats): Unit = {
      saves.incrementAndGet()
      if (saveFailure) throw new IOException("write failed")
      saved = Some(snapshot)
    }
    override def prune(now: Long): Int = {
      if (pruneFailure) throw new IOException("prune failed")
      0
    }
    override def close(): Unit = { closes.incrementAndGet(); () }
  }

  private def finishCycle(persistence: StatsPersistence)(assertion: => Unit): Unit = {
    awaitAssert({ persistence.tick(); assertion }, 4.seconds)
    awaitAssert(persistence.view.busy shouldBe false)
  }

  "Stats restore" should "publish saved DEX graphs as stale without restoring an active Stratum job" in {
    val store = new TestStore
    store.saved = Some(record(1000))
    val persistence = new StatsPersistence(settings, () => store, executor)
    val cache = new StatsCache(StatsConfig(storage = settings))
    val actor = system.actorOf(Props(new StatsCollector(cache, None, Some(persistence))))
    try {
      awaitAssert(cache.dexSnapshot().view.restored shouldBe true)
      cache.dexSnapshot().view.status shouldBe "stale"
      cache.dexSnapshot().view.updatedAt shouldBe Some(1000L)
      cache.snapshot().local.stratum.activeJob shouldBe None
      cache.snapshot().storage.lastSavedObservationAt shouldBe Some(1000L)
      cache.publishDex(DexStatsSpec.data("live"), 2000, System.nanoTime())
      cache.restoreDex(record(1000, "old"))
      cache.dexSnapshot().data.get.blockId shouldBe "live"
      cache.dexSnapshot().view.restored shouldBe false
    } finally {
      system.stop(actor)
      Await.result(persistence.shutdown(), 3.seconds)
    }
  }

  it should "keep live DEX reads and Stratum responsive during a blocked write and collector replacement" in {
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val store = new TestStore {
      override def save(snapshot: StoredDexStats): Unit = {
        entered.countDown()
        if (!release.await(8, TimeUnit.SECONDS)) throw new IOException("test write timed out")
        super.save(snapshot)
      }
    }
    val opens = new AtomicInteger()
    val persistence = new StatsPersistence(settings, () => { opens.incrementAndGet(); store }, executor)
    val reads = new AtomicInteger()
    val source = new DexStatsSource {
      override def read(): DexStatsData = DexStatsSpec.data(s"live-${reads.incrementAndGet()}")
    }
    val refresh = new DexStatsRefresh(source, system.dispatchers.lookup("lithos-contexts.stats-read-dispatcher"))
    val cache = new StatsCache(StatsConfig(dex = DexStatsConfig(refreshIntervalMs = 1000), storage = settings))
    def collector() = system.actorOf(Props(new StatsCollector(cache, Some(refresh), Some(persistence)))
      .withDispatcher("lithos-contexts.stats-dispatcher").withMailbox("stats-mailbox"))
    val observer = TestProbe()
    val original = collector()
    var replacement: Option[akka.actor.ActorRef] = None
    try {
      entered.await(5, TimeUnit.SECONDS) shouldBe true
      persistence.view.busy shouldBe true
      observer.watch(original)
      system.stop(original)
      observer.expectTerminated(original)
      replacement = Some(collector())
      observer.send(replacement.get, StratumObserved(UUID.randomUUID(), 1, 1, System.nanoTime(), 7, None))
      awaitAssert(cache.snapshot().local.stratum.connectedConnections shouldBe 7)
      awaitAssert(cache.dexSnapshot().data.get.blockId should not be "live-1", 4.seconds)
      observer.send(replacement.get, Identify("offered"))
      observer.expectMsgType[ActorIdentity]
      (1 to 50).foreach(i => persistence.offer(record(10000 + i, s"pending-$i")))
      (1 to 50).foreach(_ => persistence.tick())
      opens.get() shouldBe 1
      persistence.view.coalescedSnapshots should be >= 49L
      observer.watch(replacement.get)
      system.stop(replacement.get)
      observer.expectTerminated(replacement.get)
      replacement = None
      // Explicit final observation makes the shutdown flush assertion independent of tick timing.
      persistence.offer(record(20000, "last"))
      val closing = persistence.shutdown()
      closing.isCompleted shouldBe false
      release.countDown()
      Await.result(closing, 4.seconds)
      store.saved.get.data.blockId shouldBe "last"
      store.closes.get() shouldBe 1
    } finally {
      release.countDown()
      system.stop(original)
      replacement.foreach(system.stop)
      Await.ready(persistence.shutdown(), 4.seconds)
    }
  }

  "Storage failures" should "retain a pending snapshot without advancing the durable observation time, then recover" in {
    val store = new TestStore
    store.saved = Some(record(1, "durable"))
    store.saveFailure = true
    val persistence = new StatsPersistence(settings, () => store, executor)
    try {
      persistence.offer(record(2, "failed"))
      finishCycle(persistence)(persistence.view.status shouldBe "error")
      persistence.view.pending shouldBe true
      persistence.view.lastSavedObservationAt shouldBe Some(1L)
      store.saved.get.data.blockId shouldBe "durable"
      persistence.offer(record(3, "newest"))
      store.saveFailure = false
      finishCycle(persistence)(persistence.view.lastSavedObservationAt shouldBe Some(3L))
      persistence.view.status shouldBe "ready"
      persistence.view.error shouldBe None
      store.saved.get.data.blockId shouldBe "newest"
    } finally Await.result(persistence.shutdown(), 3.seconds)
  }

  it should "save a fresh replacement after an unreadable restore and report pruning independently of that commit" in {
    val store = new TestStore
    store.loadFailure = true
    store.pruneFailure = true
    val persistence = new StatsPersistence(settings, () => store, executor)
    try {
      persistence.offer(record(10))
      finishCycle(persistence)(persistence.view.lastSavedObservationAt shouldBe Some(10L))
      persistence.view.status shouldBe "error"
      store.saved.get.observedAt shouldBe 10L
      store.pruneFailure = false
      finishCycle(persistence)(persistence.view.status shouldBe "ready")
      store.saves.get() shouldBe 1
    } finally Await.result(persistence.shutdown(), 3.seconds)
  }

  it should "retry a failed open and perform no storage work when disabled" in {
    val store = new TestStore
    val attempts = new AtomicInteger()
    val persistence = new StatsPersistence(settings, () => {
      if (attempts.incrementAndGet() == 1) throw new IOException("database locked")
      store
    }, executor)
    try {
      persistence.offer(record(1))
      finishCycle(persistence)(persistence.view.status shouldBe "error")
      persistence.view.pending shouldBe true
      finishCycle(persistence)(persistence.view.lastSavedObservationAt shouldBe Some(1L))
    } finally Await.result(persistence.shutdown(), 3.seconds)
    val disabled = new StatsPersistence(settings.copy(enabled = false), () => fail("must not open"), executor)
    disabled.offer(record(2))
    disabled.tick()
    disabled.view.status shouldBe "disabled"
    disabled.view.pending shouldBe false
    Await.result(disabled.shutdown(), 1.second)
  }

  it should "keep pruning failures visible while later saves succeed before the next pruning retry" in {
    val store = new TestStore
    store.pruneFailure = true
    val persistence = new StatsPersistence(settings.copy(pruneIntervalMs = 60000), () => store, executor)
    try {
      persistence.offer(record(1))
      finishCycle(persistence)(persistence.view.status shouldBe "error")
      persistence.offer(record(2))
      finishCycle(persistence)(persistence.view.lastSavedObservationAt shouldBe Some(2L))
      persistence.view.status shouldBe "error"
      persistence.view.error should not be empty
    } finally Await.result(persistence.shutdown(), 3.seconds)
  }

  "Shutdown" should "save a pending observation before the first storage tick and close only once" in {
    val store = new TestStore
    val persistence = new StatsPersistence(settings, () => store, executor)
    persistence.offer(record(1))
    Await.result(persistence.shutdown(), 3.seconds)
    store.saved.get.observedAt shouldBe 1L
    store.closes.get() shouldBe 1
    Await.result(persistence.shutdown(), 1.second)
    store.closes.get() shouldBe 1
  }
}
