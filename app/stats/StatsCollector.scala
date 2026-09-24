package stats

import akka.actor.{Actor, ActorRef, Cancellable, Terminated}
import org.slf4j.{Logger, LoggerFactory}

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object StatsCollector {
  private[stats] case object RefreshStatistics
  /** Ordered snapshots recover missed notifications without acknowledgements on the mining path. */
  final case class StratumObserved(session: UUID, sequence: Long, observedAt: Long, observedNanos: Long,
                                    connectedConnections: Int, activeJob: Option[ActiveStratumJob],
                                    stopped: Boolean = false, difficulty: Option[StratumDifficulty] = None)
}

class StatsCollector(cache: StatsCache, dexRefresh: Option[DexStatsRefresh], persistence: Option[StatsPersistence],
                       mining: Option[MiningStatsRefresh]) extends Actor {
  @Inject def this(cache: StatsCache, dexRefresh: DexStatsRefresh, persistence: StatsPersistence, mining: MiningStatsRefresh) =
    this(cache, Some(dexRefresh), Some(persistence), Some(mining))
  def this(cache: StatsCache, dexRefresh: Option[DexStatsRefresh], persistence: Option[StatsPersistence]) =
    this(cache, dexRefresh, persistence, None)
  def this(cache: StatsCache, dexRefresh: Option[DexStatsRefresh]) = this(cache, dexRefresh, None)
  def this(cache: StatsCache) = this(cache, None, None)
  import StatsCollector._
  private val logger: Logger = LoggerFactory.getLogger("StatsCollector")
  private val incarnation = UUID.randomUUID()
  private var refreshTicker: Option[Cancellable] = None
  private var nextDexRead = System.nanoTime()

  private var source: Option[(ActorRef, UUID, Long)] = None

  override def preStart(): Unit = if (cache.settings.enabled &&
    (dexRefresh.isDefined || persistence.isDefined || mining.isDefined)) {
    self ! RefreshStatistics
    refreshTicker = Some(context.system.scheduler.scheduleWithFixedDelay(1.second, 1.second,
      self, RefreshStatistics)(context.dispatcher))
  }
  override def postStop(): Unit = refreshTicker.foreach(_.cancel())

  override def receive: Receive = {
    case observation: LocalMiningObservation if cache.settings.enabled &&
      LocalMiningStats.Kinds.contains(observation.kind) =>
      val previous = cache.localMiningActivity.get(observation.kind)
      if (previous.forall(p => if (p.session == observation.session) p.sequence < observation.sequence
        else p.startedAt <= observation.startedAt)) {
        cache.publishLocal(observation)
        mining.foreach(_.offerLocal(observation))
      }
    case RefreshStatistics if cache.settings.enabled => refreshStatistics()
    case RefreshStatistics => ()
    case event: StratumObserved if cache.settings.enabled => observeStratum(event)
    case Terminated(ref) if source.exists(_._1 == ref) =>
      source = None
      cache.publish(StratumStatsView("stopped", Some(System.currentTimeMillis())), System.nanoTime())
    case _: StratumObserved | _: Terminated => ()
  }

  private def refreshStatistics(): Unit = {
    mining.foreach { refresh =>
      refresh.tick()
      cache.publishMining(refresh.view)
    }
    persistence.foreach { storage =>
      storage.takeRestored().foreach(cache.restoreDex)
      storage.tick()
      cache.publishStorage(storage.view)
    }
    dexRefresh.filter(_ => cache.settings.dex.enabled).foreach { refresh =>
      refresh.takeCompleted().foreach { case (flight, result) =>
        if (flight.owner == incarnation) {
          result match {
            case Success(data) =>
              cache.publishDex(data, flight.startedAt, flight.startedNanos)
              persistence.foreach(_.offer(StoredDexStats(flight.startedAt, data)))
            case Failure(ex) =>
              logger.warn(s"DEX statistics refresh failed: ${ex.getMessage}")
              cache.dexFailed()
          }
          nextDexRead = System.nanoTime() + cache.settings.dex.refreshIntervalMs.milliseconds.toNanos
        }
      }
      if (!refresh.busy && System.nanoTime() - nextDexRead >= 0) refresh.start(incarnation)
      cache.refreshingDex(refresh.busy)
    }
  }

  private def observeStratum(event: StratumObserved): Unit = {
    val sameSession = source.exists(s => s._1 == sender() && s._2 == event.session)
    if (!sameSession || source.exists(_._3 < event.sequence)) {
      if (!source.exists(_._1 == sender())) {
        source.foreach(s => context.unwatch(s._1))
        context.watch(sender())
      }
      source = Some((sender(), event.session, event.sequence))
      cache.publish(StratumStatsView(
        status = if (event.stopped) "stopped" else if (event.activeJob.isDefined) "active" else "waiting",
        observedAt = Some(event.observedAt), connectedConnections = event.connectedConnections,
        activeJob = if (event.stopped) None else event.activeJob,
        difficulty = if (event.stopped) None else event.difficulty), event.observedNanos)
    }
  }
}
