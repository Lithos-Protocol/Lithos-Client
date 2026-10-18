package configs

import akka.actor.{ActorSystem, ExtendedActorSystem, Extension, ExtensionId}

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/**
 * A bounded dispatcher that is only handed work while it has a free slot. A full Akka thread-pool
 * queue runs a rejected task on the thread that submitted it, which for an actor is its own mailbox.
 * Counting started tasks against pool size plus queue size means the pool never rejects, as long as
 * everything submitting to it goes through the same instance.
 */
final class BoundedWorker(val ec: ExecutionContext, val capacity: Int) {
  private val inFlight = new AtomicInteger(0)

  /** Tasks started and not yet finished. */
  def running: Int = inFlight.get

  /** Runs `work` on the dispatcher, or returns None without running it when every slot is taken. */
  def submit[T](work: => T): Option[Future[T]] =
    if (inFlight.incrementAndGet() > capacity) {
      inFlight.decrementAndGet()
      None
    } else {
      try Some(Future(try work finally inFlight.decrementAndGet())(ec))
      catch { case NonFatal(ex) => inFlight.decrementAndGet(); Some(Future.failed[T](ex)) }
    }
}

object BoundedWorker {
  /** No limit, for a caller that bounds its own submissions. */
  def unbounded(ec: ExecutionContext): BoundedWorker = new BoundedWorker(ec, Int.MaxValue)
}

/** One [[BoundedWorker]] per `lithos-contexts` dispatcher per actor system, shared by its submitters. */
final class BoundedWorkers(system: ActorSystem) extends Extension {
  private val workers = TrieMap.empty[String, BoundedWorker]

  /** The worker for a thread-pool dispatcher named as in [[Contexts]], sized from its config. */
  def apply(name: String): BoundedWorker = workers.getOrElse(name, {
    val key = Contexts.key(name)
    val pool = system.settings.config.getConfig(s"$key.thread-pool-executor")
    val created = new BoundedWorker(system.dispatchers.lookup(key),
      pool.getInt("fixed-pool-size") + pool.getInt("task-queue-size"))
    workers.putIfAbsent(name, created).getOrElse(created)
  })
}

object BoundedWorkers extends ExtensionId[BoundedWorkers] {
  override def createExtension(system: ExtendedActorSystem): BoundedWorkers = new BoundedWorkers(system)
}
