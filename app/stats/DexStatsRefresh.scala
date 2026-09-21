package stats

import akka.actor.ActorSystem
import configs.Contexts

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Try}

object DexStatsRefresh {
  final case class Flight(owner: UUID, startedAt: Long, startedNanos: Long, result: Future[DexStatsData])
}

/** The physical read slot survives collector restarts. A timeout cannot release running I/O. */
@Singleton
class DexStatsRefresh(source: DexStatsSource, worker: ExecutionContext) {
  @Inject def this(source: DexStatsSource, system: ActorSystem) =
    this(source, system.dispatchers.lookup(Contexts.key(Contexts.StatsRead)))
  import DexStatsRefresh._
  private val active = new AtomicReference[Flight]()

  def busy: Boolean = active.get() != null

  def start(owner: UUID): Boolean = {
    val promise = Promise[DexStatsData]()
    val flight = Flight(owner, System.currentTimeMillis(), System.nanoTime(), promise.future)
    if (!active.compareAndSet(null, flight)) false
    else {
      Try(worker.execute(new Runnable {
        override def run(): Unit = promise.complete(Try(source.read()))
      })).failed.foreach(ex => promise.tryComplete(Failure(ex)))
      true
    }
  }

  /** Polling the completed Future also recovers when a bounded actor mailbox drops a notification. */
  def takeCompleted(): Option[(Flight, Try[DexStatsData])] = Option(active.get()).flatMap { flight =>
    flight.result.value.flatMap { result =>
      if (active.compareAndSet(flight, null)) Some(flight -> result) else None
    }
  }
}
