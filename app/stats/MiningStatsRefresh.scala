package stats

import akka.Done
import akka.actor.{ActorSystem, CoordinatedShutdown}
import configs.{Contexts, MiningStatsConfig, NodeContext}
import org.slf4j.LoggerFactory
import state.synchronization.SyncProtocolContext

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration._
import scala.util.control.NonFatal

/** One physical replay/storage slot, surviving collector replacement. Never runs on the mining path. */
@Singleton
class MiningStatsRefresh(settings: MiningStatsConfig, persistent: Boolean, startHeight: Int,
                           openStore: () => MiningStatsStore, openSource: () => MiningStatsSource,
                           worker: ExecutionContext, mainnet: Boolean = true) {
  @Inject def this(cache: StatsCache, node: NodeContext, protocol: SyncProtocolContext, system: ActorSystem) = {
    this(cache.settings.mining.copy(enabled = cache.settings.enabled && cache.settings.mining.enabled),
      cache.settings.storage.enabled, math.max(1, protocol.rollupStartHeight),
      () => MiningStatsStore.open(cache.settings.storage, node, protocol),
      () => NodeMiningStatsSource.open(node, cache.settings.mining, protocol),
      system.dispatchers.lookup(Contexts.key(Contexts.MiningStats)),
      protocol.networkType == org.ergoplatform.appkit.NetworkType.MAINNET)
    CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseBeforeActorSystemTerminate, "close-mining-stats") {
      () => shutdown()
    }
  }
  private val logger = LoggerFactory.getLogger("MiningStatsRefresh")
  private val active = new AtomicBoolean(false)
  private val reads = new ConcurrentLinkedQueue[PendingRead[_]]()
  private val queuedReads = new AtomicInteger(0)
  private val closing = new AtomicBoolean(false)
  private val closed = Promise[Done]()
  private val pendingLocal = new AtomicReference(Map.empty[String, LocalMiningObservation])
  private val inventory = new AtomicReference(CollateralStats(status = if (settings.enabled) "loading" else "disabled"))
  /** The epoch in progress and whether the table has reached its floor; both recomputed per cycle. */
  private val epochs = new AtomicReference((Option.empty[DifficultyEpoch], false))
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

  /**
   * A page of the epoch table, newest-anchored by default so a graph gets the recent curve without
   * having to know what the table holds.
   */
  def difficulty(from: Option[Int], to: Option[Int], limit: Int): Future[DifficultyEpochHistory] = query { db =>
    require(limit > 0 && limit <= DifficultyEpochs.MaxPage, "invalid difficulty epoch limit")
    require(from.forall(_ >= 0) && to.forall(_ >= 0), "difficulty epoch indices are nonnegative")
    val (current, backfilled) = epochs.get()
    val bounds = db.epochBounds
    val rows = bounds match {
      case Some((oldest, newest)) =>
        val last = math.min(to.getOrElse(newest), newest)
        val first = math.max(from.getOrElse(last - limit + 1), oldest)
        if (last >= first) db.epochRange(first, math.min(last, first + limit - 1)) else Vector.empty
      case None => Vector.empty
    }
    DifficultyEpochHistory(db.state.get.cursor, DifficultyEpochs.EpochLength, rows, current,
      bounds.map(_._1), bounds.map(_._2), backfilled, view.status)
  }

  /** A queued read: `answer` runs it on the worker, `fail` settles it when the worker cannot. */
  private final class PendingRead[A](read: MiningStatsStore => A, result: Promise[A]) {
    def answer(): Unit = try {
      val db = store.getOrElse(throw new IllegalStateException("mining statistics loading"))
      db.reloadCheckpoint()
      result.success(read(db))
    } catch { case NonFatal(error) => result.failure(error) }
    def fail(error: Throwable): Unit = result.tryFailure(error)
  }

  /**
   * Reads wait for the one worker slot instead of being refused. A dashboard asks for several
   * series at once, and refusing every one that lost the race blanked those charts on every poll.
   * The queue is bounded, so a stalled worker still answers busy rather than holding requests
   * without limit.
   */
  private def query[A](read: MiningStatsStore => A): Future[A] = {
    val result = Promise[A]()
    if (!settings.enabled || closing.get()) result.failure(new IllegalStateException("mining statistics disabled or closing"))
    else if (queuedReads.incrementAndGet() > MiningStatsRefresh.MaxQueuedReads) {
      queuedReads.decrementAndGet()
      result.failure(new IllegalStateException("mining statistics worker busy"))
    } else {
      reads.add(new PendingRead(read, result))
      drain()
    }
    result.future
  }

  /** Answers everything queued in one worker task. Only the holder of `active` ever submits work. */
  private def drain(): Unit = if (!reads.isEmpty && active.compareAndSet(false, true)) {
    def next(): Option[PendingRead[_]] = Option(reads.poll()).map { r => queuedReads.decrementAndGet(); r }
    try worker.execute(new Runnable {
      override def run(): Unit = try Iterator.continually(next()).takeWhile(_.isDefined).foreach(_.get.answer())
        finally release()
    }) catch {
      case NonFatal(error) =>
        Iterator.continually(next()).takeWhile(_.isDefined).foreach(_.get.fail(error))
        release()
    }
  }

  /**
   * Frees the slot and passes it on. Checked after clearing `active`, so a read queued while the
   * slot was held is never stranded: either its own `drain` wins the slot or this one does.
   * Queued reads go before a pending close, since `query` stops admitting them once closing starts.
   */
  private def release(): Unit = {
    active.set(false)
    if (!reads.isEmpty) drain()
    else if (closing.get() && !closed.isCompleted) start()
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
        var raced = false
        try {
          if (closing.get()) {
            if (store.isEmpty && pendingLocal.get().nonEmpty) store = Some(openStore())
            try store.foreach { db =>
              try db.saveLocal(pendingLocal.get().values) finally db.close()
            } finally store = None
            closed.trySuccess(Done)
          } else cycle()
        } catch {
          // Blocks already appended stay appended; the next cycle resumes from them.
          case moved: MiningStatsRefresh.ChainMoved =>
            raced = true
            logger.debug("Mining statistics read raced a new block; retrying", moved)
          case NonFatal(error) =>
            logger.warn("Mining statistics refresh failed", error)
            // History already collected is still true. Report it as behind, not as gone.
            val previous = published.get()
            published.set(previous.copy(status = if (previous.sourceHeight.isDefined) "stale" else "unavailable",
              error = Some("Mining history refresh failed; see the client log")))
            if (closing.get() && store.isEmpty) closed.tryFailure(error)
        } finally {
          val delay = if (raced || Set("catching-up", "recovering").contains(published.get().status)) 1000
            else settings.refreshIntervalMs
          nextRunNanos = System.nanoTime() + delay.milliseconds.toNanos
          release()
        }
      }
    }) catch {
      case NonFatal(error) =>
        nextRunNanos = System.nanoTime() + settings.refreshIntervalMs.milliseconds.toNanos
        logger.warn("Could not schedule mining statistics", error)
        published.set(published.get().copy(status = "unavailable", error = Some("Mining history worker unavailable")))
        // Completed before releasing, so a shutdown that cannot schedule does not retry itself forever.
        if (closing.get()) closed.tryFailure(error)
        release()
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
      MiningStatsRefresh.requireSameChain(at.parentId == previous.blockId,
        "chain changed while replaying mining statistics")
      val record = source.read(at, previous)
      MiningStatsRefresh.requireSameChain(source.header(at.height) == at,
        "chain changed during mining statistics read")
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
      catch {
        case moved: MiningStatsRefresh.ChainMoved =>
          logger.debug("Collateral statistics read raced a new block", moved)
        case NonFatal(error) =>
          logger.warn("Collateral statistics refresh failed", error)
          // An earlier inventory is still a real observation; mark it behind rather than discard it.
          val previous = inventory.get()
          inventory.set(previous.copy(status = if (previous.observedAt.isDefined) "stale" else "unavailable",
            error = Some("Collateral index read failed")))
      }
    } else inventory.set(inventory.get().copy(status = "stale"))
    if (!closing.get()) try updateEpochs(db, source, currentHeights.chain)
    catch { case NonFatal(error) => logger.debug("Difficulty epoch refresh failed", error) }
  }

  /**
   * Extends the epoch table towards the tip, then backfills towards its floor.
   *
   * Two header reads describe a whole epoch, because difficulty is constant inside one. Runs last
   * in the cycle and on its own budget, so a slow node costs the table a cycle rather than costing
   * the canonical replay its progress.
   */
  private def updateEpochs(db: MiningStatsStore, source: MiningStatsSource, tip: Int): Unit = {
    def read(index: Int): DifficultyEpoch = DifficultyEpoch.of(index,
      source.sample(DifficultyEpochs.startHeight(index)),
      source.sample(DifficultyEpochs.endHeight(index)), complete = true)

    var budget = settings.blocksPerRefresh
    DifficultyEpochs.finalizableIndex(tip).foreach { finalizable =>
      val floor = DifficultyEpochs.floorIndex(finalizable, mainnet)
      if (finalizable >= floor) {
        // Forward first: an empty table starts at the newest safe epoch, so the recent curve — the
        // part anyone is looking at — appears on the first cycle rather than after a full backfill.
        val forward = Vector.newBuilder[DifficultyEpoch]
        var up = math.max(db.epochBounds.map(_._2 + 1).getOrElse(finalizable), floor)
        while (up <= finalizable && budget > 0) {
          forward += read(up)
          up += 1
          budget -= 1
        }
        db.saveEpochs(forward.result())

        val back = Vector.newBuilder[DifficultyEpoch]
        var down = db.epochBounds.map(_._1 - 1).getOrElse(-1)
        while (down >= floor && budget > 0) {
          back += read(down)
          down -= 1
          budget -= 1
        }
        db.saveEpochs(back.result())
        if (settings.pruningEnabled) db.pruneEpochs(floor)
      }
      epochs.updateAndGet(state => (state._1, db.epochBounds.exists(_._1 <= floor)))
    }
    // The epoch in progress ends at the tip, so it is recomputed rather than stored.
    val index = DifficultyEpochs.indexOf(math.max(1, tip))
    val live = DifficultyEpoch.of(index, source.sample(DifficultyEpochs.startHeight(index)),
      source.sample(math.max(DifficultyEpochs.startHeight(index), tip)), complete = false)
    epochs.updateAndGet(state => (Some(live), state._2))
  }
}

object MiningStatsRefresh {
  /** Reads allowed to wait for the worker. Past this a request is answered busy at once. */
  val MaxQueuedReads: Int = 16

  /** The tip moved while a read was in flight. An ordinary race, not a fault, so it is retried at once. */
  final class ChainMoved(message: String) extends IllegalStateException(message)

  def requireSameChain(condition: Boolean, message: => String): Unit =
    if (!condition) throw new ChainMoved(message)
}
