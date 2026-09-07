package transactions.engine

import java.util.UUID

/** Compact in-memory admission and retry state shared by immediate and future work. */
private[transactions] object ExecutionSchedule {
  sealed trait Timing { def notBefore: Long; def expires: Long }
  final case class Immediate(expires: Long) extends Timing { val notBefore = 0L }
  final case class Scheduled(notBefore: Long, expires: Long) extends Timing
  final case class Policy(maxAttempts: Int, backoffMillis: Long)
  val Critical = Policy(5, 5000L)
  val Automatic = Policy(3, 15000L)
  val OneOff = Policy(1, 0L)
  final case class Entry[A](key: String, work: A, lane: String, timing: Timing, policy: Policy,
                            attempt: Option[UUID] = None, attempts: Int = 0, due: Long = 0L)
}

private[transactions] final class ExecutionSchedule[A](limit: Int = 128) {
  import ExecutionSchedule._
  private var entries = Vector.empty[Entry[A]]
  def size: Int = entries.size
  def contains(key: String): Boolean = entries.exists(_.key == key)
  def busy(lane: String): Boolean = entries.exists(e => e.lane == lane && e.attempt.nonEmpty)
  def nonEmpty: Boolean = entries.nonEmpty
  def register(entry: Entry[A]): Boolean = {
    if (contains(entry.key)) false
    else if (entries.size >= limit) false
    else { entries :+= entry; true }
  }
  def expire(now: Long): Vector[Entry[A]] = {
    val expired = entries.filter(e => e.attempt.isEmpty && now >= e.timing.expires)
    entries = entries.filterNot(e => expired.exists(_.key == e.key))
    expired
  }
  def next(lane: String, now: Long): Option[Entry[A]] = {
    if (busy(lane)) None
    else entries.find(e => e.lane == lane && e.attempt.isEmpty &&
      now >= math.max(e.timing.notBefore, e.due) && now < e.timing.expires).map { e =>
      val running = e.copy(attempt = Some(UUID.randomUUID()), attempts = e.attempts + 1)
      entries = entries.map(x => if (x.key == e.key) running else x)
      running
    }
  }
  def finish(key: String, attempt: UUID, retry: Boolean, now: Long): Option[Entry[A]] = {
    entries.find(e => e.key == key && e.attempt.contains(attempt)).map { e =>
      if (retry && e.attempts < e.policy.maxAttempts && now < e.timing.expires) {
        val delay = e.policy.backoffMillis * (1L << math.min(10, e.attempts - 1))
        entries = entries.map(x => if (x.key == key) e.copy(attempt = None, due = now + delay) else x)
      } else entries = entries.filterNot(_.key == key)
      e
    }
  }
}
