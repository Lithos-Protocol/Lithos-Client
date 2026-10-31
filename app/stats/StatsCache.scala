package stats

import configs.StatsConfig
import play.api.Configuration

import java.util.concurrent.atomic.AtomicReference
import javax.inject.{Inject, Singleton}
import scala.concurrent.duration._

object StatsCache {
  private final case class Published(view: StatsView, observedNanos: Long)
  private final case class DexPublished(snapshot: DexStatsSnapshot, observedNanos: Long)

  /**
   * How far back a current-hashrate reading looks. Long enough that a handful of shares does not
   * swing it, short enough that it still describes now rather than the whole session.
   */
  val WorkWindowMs: Long = 900000L
}

/** The collector is the only writer. HTTP reads never wait for its mailbox. */
@Singleton
class StatsCache(val settings: StatsConfig) {
  import StatsCache.{Published, DexPublished}
  @Inject def this(config: Configuration) = this(StatsConfig(config))

  private val initial = StratumStatsView(status = if (settings.enabled) "waiting" else "disabled")
  private val current = new AtomicReference(Published(StatsView(settings.enabled, LocalStatsView(initial)), 0L))
  private val dex = new AtomicReference(DexPublished(DexStatsSnapshot(
    DexStatsView(status = if (settings.enabled && settings.dex.enabled) "loading" else "disabled"), None), 0L))
  /** Verified pools other than ERG:LIT, by pool NFT, as the last pass over them read them. In memory only. */
  private val verified = new AtomicReference(Map.empty[String, DexPublished])
  private val storage = new AtomicReference(StatsStorageView(
    status = if (settings.enabled && settings.dex.enabled && settings.storage.enabled) "loading" else "disabled"))
  private val mining = new AtomicReference(MiningStatsView(
    status = if (settings.enabled && settings.mining.enabled) "loading" else "disabled",
    persistent = settings.storage.enabled) -> 0L)
  private val activity = new AtomicReference(Map.empty[String, (LocalMiningObservation, Long)])
  private val samples = new AtomicReference(Vector.empty[WorkSample])

  /** A session change marks a counter reset, never negative activity. */
  def localMiningActivity: Map[String, LocalMiningObservation] = activity.get().map { case (kind, (observed, _)) => kind -> observed }
  def localMiningViews: Map[String, LocalMiningActivityView] = activity.get().map { case (kind, (observed, observedNanos)) =>
    val status = if (observed.stopped) "stopped"
      else if (System.nanoTime() - observedNanos > settings.staleAfterMs.milliseconds.toNanos) "stale" else "ready"
    kind -> LocalMiningActivityView(status, observed,
      observed.counters.getOrElse("discoveryEvictions", "0") == "0")
  }
  private[stats] def publishLocal(observation: LocalMiningObservation): Unit = {
    val ageMs = math.min(math.max(0L, System.currentTimeMillis() - observation.observedAt), settings.staleAfterMs.toLong + 1)
    activity.set(activity.get().updated(observation.kind, observation -> (System.nanoTime() - ageMs.milliseconds.toNanos)))
    if (observation.kind == "shares") retainSample(WorkSample.of(observation))
  }

  /** One window's worth of share samples, oldest first, for the current-rate reading. */
  def recentWork: Vector[WorkSample] = samples.get()

  private def retainSample(sample: WorkSample): Unit = samples.updateAndGet { current =>
    // Keep one sample beyond the window so a rate is still measurable at its far edge.
    val cutoff = sample.observedAt - StatsCache.WorkWindowMs
    val kept = current.lastIndexWhere(_.observedAt <= cutoff) match {
      case -1 => current
      case index => current.drop(index)
    }
    kept :+ sample
  }

  private[stats] def publishMining(view: MiningStatsView): Unit = {
    val previous = mining.get()
    val observed = if (previous._1.observedAt == view.observedAt) previous._2 else System.nanoTime()
    mining.set(view -> observed)
  }

  private[stats] def refreshingDex(value: Boolean): Unit = {
    val previous = dex.get()
    dex.set(previous.copy(snapshot = previous.snapshot.copy(view = previous.snapshot.view.copy(refreshing = value))))
  }

  private[stats] def publishDex(data: DexStatsData, observedAt: Long, observedNanos: Long): Unit =
    dex.set(DexPublished(ready(data, observedAt), observedNanos))

  private def ready(data: DexStatsData, observedAt: Long): DexStatsSnapshot =
    DexStatsSnapshot(DexStatsView("ready", Some(observedAt), Some(data.height), Some(data.blockId), Some(data.fromHeight),
      data.history.headOption.map(_.height), Some(data.complete), collectedFrom = data.collectedFrom), Some(data))

  /**
   * Publishes one pass over the verified pools other than ERG:LIT. A pool whose read failed keeps its last data,
   * marked stale; a pool no longer in the pass is dropped.
   */
  private[stats] def publishVerifiedDex(results: Map[String, scala.util.Try[DexStatsData]], observedAt: Long,
                                        observedNanos: Long): Unit = {
    val previous = verified.get()
    verified.set(results.map { case (nft, result) =>
      nft -> (result match {
        case scala.util.Success(data) => DexPublished(ready(data, observedAt), observedNanos)
        case scala.util.Failure(_) => previous.get(nft) match {
          case Some(p) => p.copy(snapshot = p.snapshot.copy(view = p.snapshot.view.copy(status = "stale",
            error = Some("DEX refresh failed; see the client log"))))
          case None => DexPublished(DexStatsSnapshot(DexStatsView("unavailable",
            error = Some("DEX refresh failed; see the client log")), None), observedNanos)
        }
      })
    })
  }

  /** A verified pool's graphs, other than ERG:LIT's, or None when no pass has read it. */
  def verifiedDexSnapshot(poolNft: String, nowNanos: Long = System.nanoTime()): Option[DexStatsSnapshot] =
    verified.get().get(poolNft.toLowerCase).map(published => withStaleness(published, nowNanos))

  /** Restored chain data is display-only until a new node read succeeds. Never replace a live read. */
  private[stats] def restoreDex(snapshot: StoredDexStats): Unit = {
    if (settings.enabled && settings.dex.enabled && dex.get().snapshot.data.isEmpty) {
      publishDex(snapshot.data, snapshot.observedAt, System.nanoTime())
      val previous = dex.get()
      dex.set(previous.copy(snapshot = previous.snapshot.copy(view = previous.snapshot.view.copy(
        status = "stale", restored = true))))
    }
  }

  private[stats] def publishStorage(view: StatsStorageView): Unit = storage.set(view)

  private[stats] def dexFailed(): Unit = {
    val previous = dex.get()
    dex.set(previous.copy(snapshot = previous.snapshot.copy(view = previous.snapshot.view.copy(
      status = if (previous.snapshot.data.isDefined) "stale" else "unavailable", refreshing = false,
      error = Some("DEX refresh failed; see the client log")))))
  }

  def dexSnapshot(nowNanos: Long = System.nanoTime()): DexStatsSnapshot = withStaleness(dex.get(), nowNanos)

  private def withStaleness(published: DexPublished, nowNanos: Long): DexStatsSnapshot =
    if (published.snapshot.data.isDefined &&
      nowNanos - published.observedNanos > settings.dex.staleAfterMs.milliseconds.toNanos)
      published.snapshot.copy(view = published.snapshot.view.copy(status = "stale"))
    else published.snapshot

  private[stats] def publish(stratum: StratumStatsView, observedNanos: Long): Unit =
    if (settings.enabled) current.set(Published(StatsView(enabled = true, local = LocalStatsView(stratum)), observedNanos))

  def snapshot(nowNanos: Long = System.nanoTime()): StatsView = {
    val published = current.get()
    val stratum = published.view.local.stratum
    val view = if (stratum.observedAt.isDefined && stratum.status != "stopped" &&
      nowNanos - published.observedNanos > settings.staleAfterMs.milliseconds.toNanos)
      published.view.copy(local = LocalStatsView(stratum.copy(status = "stale")))
    else published.view
    val (miningView, miningObserved) = mining.get()
    val miningStatus = if (miningView.status == "ready" &&
      nowNanos - miningObserved > settings.mining.staleAfterMs.milliseconds.toNanos) miningView.copy(status = "stale")
    else miningView
    view.copy(dex = dexSnapshot(nowNanos).view, storage = storage.get(), mining = miningStatus)
  }
}
