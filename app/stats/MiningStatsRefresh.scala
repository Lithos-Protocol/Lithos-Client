package stats

import akka.Done
import akka.actor.{ActorSystem, CoordinatedShutdown}
import configs.{Contexts, MiningStatsConfig, NodeContext}
import org.slf4j.LoggerFactory
import state.synchronization.SyncProtocolContext

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration._
import scala.util.control.NonFatal

/** One physical replay/storage slot, surviving collector replacement. Never runs on the mining path. */
@Singleton
class MiningStatsRefresh(settings: MiningStatsConfig, persistent: Boolean, startHeight: Int,
                           openStore: () => MiningStatsStore, openSource: () => MiningStatsSource,
                           worker: ExecutionContext) {
  @Inject def this(cache: StatsCache, node: NodeContext, protocol: SyncProtocolContext, system: ActorSystem) = {
    this(cache.settings.mining.copy(enabled = cache.settings.enabled && cache.settings.mining.enabled),
      cache.settings.storage.enabled, math.max(1, protocol.rollupStartHeight),
      () => MiningStatsStore.open(cache.settings.storage, node, protocol),
      () => NodeMiningStatsSource.open(node, cache.settings.mining, protocol),
      system.dispatchers.lookup(Contexts.key(Contexts.MiningStats)))
    CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseBeforeActorSystemTerminate, "close-mining-stats") {
      () => shutdown()
    }
  }
  private val logger = LoggerFactory.getLogger("MiningStatsRefresh")
  private val active = new AtomicBoolean(false)
  private val closing = new AtomicBoolean(false)
  private val closed = Promise[Done]()
  private val pendingLocal = new AtomicReference(Map.empty[String, LocalMiningObservation])
  private val inventory = new AtomicReference(CollateralStats(status = if (settings.enabled) "loading" else "disabled"))
  private val published = new AtomicReference(MiningStatsView(
    status = if (settings.enabled) "loading" else "disabled", persistent = persistent))
  @volatile private var nextRunNanos = System.nanoTime()
  @volatile private var observedNanos = 0L
  @volatile private var inventoryObservedNanos = 0L
  // Accessed only by the worker.
  private var store: Option[MiningStatsStore] = None

  def view: MiningStatsView = {
    val value = published.get()
    if (value.status == "ready" && System.nanoTime() - observedNanos > settings.staleAfterMs.milliseconds.toNanos)
      value.copy(status = "stale") else value
  }
  def busy: Boolean = active.get()
  def collateral: CollateralStats = {
    val value = inventory.get()
    if (value.status == "ready" && System.nanoTime() - inventoryObservedNanos > settings.staleAfterMs.milliseconds.toNanos)
      value.copy(status = "stale") else value
  }
  def offerLocal(observation: LocalMiningObservation): Unit = if (settings.enabled && !closing.get() &&
    LocalMiningStats.Kinds.contains(observation.kind)) {
    pendingLocal.updateAndGet(current => current.updated(observation.kind, observation))
  }
  def localHistory(kind: String, from: Long, until: Long): Future[Vector[LocalMiningObservation]] =
    query(_.localHistory(kind, from, until))
  def history(fromHeight: Int, limit: Int): Future[MiningHistoryPage] = query { db =>
    db.history(fromHeight, limit).copy(status = view.status)
  }
  def buckets(from: Long, until: Long, widthMs: Long): Future[MiningBucketHistory] = query { db =>
    db.buckets(from, until, widthMs).copy(status = view.status)
  }
  def hashrate(from: Long, until: Long, widthMs: Long): Future[LithosHashrateEstimate] = query { db =>
    MiningHistory.hashrate(db.buckets(from, until, widthMs).copy(status = view.status))
  }

  /** No unbounded request queue: a busy worker reports busy and the future API can retry later. */
  private def query[A](read: MiningStatsStore => A): Future[A] = {
    val result = Promise[A]()
    if (!settings.enabled || closing.get()) result.failure(new IllegalStateException("mining statistics disabled or closing"))
    else if (!active.compareAndSet(false, true)) result.failure(new IllegalStateException("mining statistics worker busy"))
    else {
      def release(): Unit = {
        active.set(false)
        if (closing.get()) start()
      }
      try worker.execute(new Runnable {
        override def run(): Unit = try {
          val db = store.getOrElse(throw new IllegalStateException("mining statistics loading"))
          db.reloadCheckpoint()
          result.success(read(db))
        } catch { case NonFatal(error) => result.failure(error) }
        finally release()
      }) catch { case NonFatal(error) => release(); result.failure(error) }
    }
    result.future
  }
  def tick(): Unit = if (settings.enabled && !closing.get() && System.nanoTime() - nextRunNanos >= 0) start()
  def shutdown(): Future[Done] = {
    closing.set(true)
    if (settings.enabled) start() else closed.trySuccess(Done)
    closed.future
  }

  private def start(): Unit = if (!closed.isCompleted && active.compareAndSet(false, true)) {
    try worker.execute(new Runnable {
      override def run(): Unit = {
        try {
          if (closing.get()) {
            if (store.isEmpty && pendingLocal.get().nonEmpty) store = Some(openStore())
            try store.foreach { db =>
              try db.saveLocal(pendingLocal.get().values) finally db.close()
            } finally store = None
            closed.trySuccess(Done)
          } else cycle()
        } catch {
          case NonFatal(error) =>
            logger.warn("Mining statistics refresh failed", error)
            published.set(published.get().copy(status = "unavailable",
              error = Some("Mining history refresh failed; see the client log")))
            if (closing.get() && store.isEmpty) closed.tryFailure(error)
        } finally {
          val delay = if (Set("catching-up", "recovering").contains(published.get().status)) 1000 else settings.refreshIntervalMs
          nextRunNanos = System.nanoTime() + delay.milliseconds.toNanos
          active.set(false)
          if (closing.get() && !closed.isCompleted) start()
        }
      }
    }) catch {
      case NonFatal(error) =>
        active.set(false)
        nextRunNanos = System.nanoTime() + settings.refreshIntervalMs.milliseconds.toNanos
        logger.warn("Could not schedule mining statistics", error)
        published.set(published.get().copy(status = "unavailable", error = Some("Mining history worker unavailable")))
        if (closing.get()) closed.tryFailure(error)
    }
  }

  private def cycle(): Unit = {
    val db = store.getOrElse {
      val opened = openStore()
      store = Some(opened)
      opened.state.foreach(s => published.set(opened.view("stale", s.cursor.height, persistent, 0L)
        .copy(observedAt = None)))
      opened
    }
    db.reloadCheckpoint()
    val local = pendingLocal.get()
    db.saveLocal(local.values)
    pendingLocal.updateAndGet(current => current.filterNot { case (kind, value) => local.get(kind).contains(value) })
    if (settings.pruningEnabled) db.pruneLocal(System.currentTimeMillis() - settings.historyDays.toLong * 86400000L,
      settings.blocksPerRefresh)
    val source = openSource()
    val heights = source.heights
    require(heights.chain > 0 && heights.usable > 0, "indexed chain is not available")
    val now = System.currentTimeMillis()
    val cutoff = now - settings.historyDays.toLong * 86400000L
    def canonical(at: MiningCursor): Boolean = at.height <= heights.chain && source.header(at.height).blockId == at.blockId
    def initialize(): Unit = {
      // Header timestamps strictly increase along Ergo's canonical chain.
      var lo = math.min(math.max(1, startHeight), heights.usable + 1)
      var hi = heights.usable + 1
      while (lo < hi) {
        val mid = lo + (hi - lo) / 2
        if (source.header(mid).timestamp < cutoff) lo = mid + 1 else hi = mid
      }
      // Height 1 has no queryable predecessor. Starting at 2 is explicit in coverage metadata.
      db.initialize(source.header(math.max(1, lo - 1)))
    }
    if (db.state.isEmpty) initialize()

    var work = 0
    var matches = canonical(db.state.get.cursor)
    if (!matches) published.set(published.get().copy(status = "recovering"))
    while (!matches && work < settings.blocksPerRefresh && !closing.get()) {
      val state = db.state.get
      if (state.cursor.height >= state.firstHeight) db.rollback()
      else initialize() // Fork crossed retained history; rebuild the configured window.
      work += 1
      matches = canonical(db.state.get.cursor)
    }
    while (matches && db.state.get.cursor.height < heights.usable && work < settings.blocksPerRefresh && !closing.get()) {
      val previous = db.state.get.cursor
      val at = source.header(previous.height + 1)
      require(at.parentId == previous.blockId, "chain changed while replaying mining statistics")
      val record = source.read(at, previous)
      require(source.header(at.height) == at, "chain changed during mining statistics read")
      db.append(record)
      work += 1
    }
    if (matches && settings.pruningEnabled) db.prune(cutoff, settings.blocksPerRefresh, rollbackBlocks = 256)
    val state = db.state.get
    val currentHeights = source.heights
    val stillCanonical = state.cursor.height <= currentHeights.chain && source.header(state.cursor.height).blockId == state.cursor.blockId
    val status = if (!matches || !stillCanonical) "recovering"
      else if (state.cursor.height < currentHeights.chain) "catching-up" else "ready"
    observedNanos = System.nanoTime()
    published.set(db.view(status, currentHeights.chain, persistent, now))
    if (status == "ready" && !closing.get()) {
      val began = System.nanoTime()
      try source.collateral(state.cursor).foreach { value =>
        inventoryObservedNanos = began
        inventory.set(value)
      }
      catch { case NonFatal(error) =>
        logger.warn("Collateral statistics refresh failed", error)
        inventory.set(inventory.get().copy(status = "unavailable", error = Some("Collateral index read failed")))
      }
    } else inventory.set(inventory.get().copy(status = "stale"))
  }
}
