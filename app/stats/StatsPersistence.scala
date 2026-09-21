package stats

import akka.Done
import akka.actor.{ActorSystem, CoordinatedShutdown}
import configs.{Contexts, NodeContext, StatsStorageConfig}
import org.slf4j.{Logger, LoggerFactory}
import play.api.libs.json.{Json, OWrites}

import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicReference}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration._
import scala.util.control.NonFatal

final case class StatsStorageView(status: String = "loading", busy: Boolean = false,
                                  pending: Boolean = false, lastSavedObservationAt: Option[Long] = None,
                                  lastPrunedAt: Option[Long] = None, coalescedSnapshots: Long = 0L,
                                  error: Option[String] = None)
object StatsStorageView {
  implicit val writes: OWrites[StatsStorageView] = Json.writes[StatsStorageView]
}

/** One storage worker and one replaceable pending snapshot. Disk stalls cannot queue every refresh. */
@Singleton
class StatsPersistence(settings: StatsStorageConfig, openStore: () => StatsStore, worker: ExecutionContext) {
  @Inject def this(cache: StatsCache, node: NodeContext, system: ActorSystem) = {
    this(cache.settings.storage.copy(enabled = cache.settings.enabled && cache.settings.dex.enabled &&
      cache.settings.storage.enabled), () => StatsStore.open(cache.settings.storage, node),
      system.dispatchers.lookup(Contexts.key(Contexts.StatsStore)))
    CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseBeforeActorSystemTerminate, "close-stats-store") {
      () => shutdown()
    }
  }

  private val logger: Logger = LoggerFactory.getLogger("StatsPersistence")
  private val active = new AtomicBoolean(false)
  private val closing = new AtomicBoolean(false)
  private val closed = Promise[Done]()
  private val pending = new AtomicReference[Option[StoredDexStats]](None)
  private val restored = new AtomicReference[Option[StoredDexStats]](None)
  private val coalesced = new AtomicLong(0L)
  private val published = new AtomicReference(StatsStorageView(status = if (settings.enabled) "loading" else "disabled"))
  @volatile private var nextRunNanos = System.nanoTime()

  // Only the storage worker accesses these fields, including during shutdown.
  private var store: Option[StatsStore] = None
  private var restoreFinished = false
  private var nextPruneNanos = System.nanoTime()
  private var pruningFailed = false

  def offer(snapshot: StoredDexStats): Unit = if (settings.enabled && !closing.get()) {
    if (pending.getAndSet(Some(snapshot)).isDefined) coalesced.incrementAndGet()
  }

  def view: StatsStorageView = published.get().copy(
    busy = active.get(), pending = pending.get().isDefined, coalescedSnapshots = coalesced.get())

  def takeRestored(): Option[StoredDexStats] = restored.getAndSet(None)

  /** Uses the existing physical storage slot. A busy store refuses a request instead of queueing it. */
  def observations(fromMinute: Long, untilMinute: Long, limit: Int): Future[DexObservationPage] = {
    val result = Promise[DexObservationPage]()
    if (!settings.enabled || closing.get()) result.failure(new IllegalStateException("DEX storage disabled or closing"))
    else if (!active.compareAndSet(false, true)) result.failure(new IllegalStateException("DEX storage worker busy"))
    else {
      def release(): Unit = {
        active.set(false)
        if (closing.get()) startWorker()
      }
      try worker.execute(new Runnable {
        override def run(): Unit = try {
          val db = store.getOrElse(throw new IllegalStateException("DEX storage loading"))
          result.success(db.observations(fromMinute, untilMinute, limit))
        } catch { case NonFatal(error) => result.failure(error) }
        finally release()
      }) catch { case NonFatal(error) => release(); result.failure(error) }
    }
    result.future
  }

  /** Called by the collector's existing tick; no database operation runs on that actor. */
  def tick(): Unit =
    if (settings.enabled && !closing.get() && System.nanoTime() - nextRunNanos >= 0) startWorker()

  def shutdown(): Future[Done] = {
    closing.set(true)
    if (settings.enabled) startWorker() else closed.trySuccess(Done)
    closed.future
  }

  private def startWorker(): Unit = if (!closed.isCompleted && active.compareAndSet(false, true)) {
    try worker.execute(new Runnable {
      override def run(): Unit = {
        try {
          if (closing.get()) closeStore()
          else runCycle()
        } catch {
          case NonFatal(error) =>
            reportFailure("Stats persistence failed", error)
            if (closing.get()) closed.tryFailure(error)
        } finally {
          nextRunNanos = System.nanoTime() + settings.flushIntervalMs.milliseconds.toNanos
          active.set(false)
          // Handles shutdown arriving while an uncancellable read/write was running.
          if (closing.get() && !closed.isCompleted) startWorker()
        }
      }
    }) catch {
      case NonFatal(error) =>
        active.set(false)
        nextRunNanos = System.nanoTime() + settings.flushIntervalMs.milliseconds.toNanos
        reportFailure("Could not schedule stats persistence", error)
        if (closing.get()) closed.tryFailure(error)
    }
  }

  private def runCycle(): Unit = {
    val database = store.getOrElse {
      val opened = openStore()
      store = Some(opened)
      opened
    }
    var failed = false
    if (!restoreFinished) {
      try {
        val saved = database.load()
        require(saved.forall(_.observedAt <= System.currentTimeMillis()), "stored stats observation is in the future")
        restored.set(saved)
        restoreFinished = true
        published.set(published.get().copy(lastSavedObservationAt = saved.map(_.observedAt)))
      } catch {
        case NonFatal(error) =>
          failed = true
          reportFailure("Could not restore stats snapshot", error)
      }
    }

    try {
      if (flushPending(database)) restoreFinished = true
    } catch {
      case NonFatal(error) =>
        failed = true
        reportFailure("Could not save stats snapshot", error)
    }

    if (settings.pruningEnabled && System.nanoTime() - nextPruneNanos >= 0) {
      try {
        database.prune(System.currentTimeMillis())
        pruningFailed = false
        published.set(published.get().copy(lastPrunedAt = Some(System.currentTimeMillis())))
      } catch {
        case NonFatal(error) =>
          pruningFailed = true
          failed = true
          reportFailure("Could not prune stats observations", error)
      }
      nextPruneNanos = System.nanoTime() + settings.pruneIntervalMs.milliseconds.toNanos
    }
    if (!failed && !pruningFailed) published.set(published.get().copy(status = "ready", error = None))
  }

  private def flushPending(database: StatsStore): Boolean = pending.getAndSet(None) match {
    case Some(snapshot) =>
      try {
        database.save(snapshot)
        published.set(published.get().copy(lastSavedObservationAt = Some(snapshot.observedAt)))
        true
      } catch {
        case NonFatal(error) =>
          // A newer observation wins if one arrived while the failed write was running.
          if (!pending.compareAndSet(None, Some(snapshot))) coalesced.incrementAndGet()
          throw error
      }
    case None => false
  }

  private def closeStore(): Unit = {
    // A fresh observation may arrive before the first storage cycle has opened the database.
    if (store.isEmpty && pending.get().isDefined) store = Some(openStore())
    try store.foreach { database =>
      try flushPending(database)
      finally database.close()
    } finally store = None
    closed.trySuccess(Done)
  }

  private def reportFailure(message: String, error: Throwable): Unit = {
    logger.warn(message, error)
    published.set(published.get().copy(status = "error", error = Some("Stats persistence failed; see the client log")))
  }
}
