package transactions.engine

import java.util.UUID

/** Compact in-memory admission and retry state shared by immediate and future work. */
private[transactions] object ExecutionSchedule {

  /**
   * Execution lanes. Each runs one entry at a time and they never share a worker, so optional
   * revenue and maintenance work cannot delay a NISP submission or a fraud proof.
   */
  object Lane {
    final val Critical = "critical"
    final val Optional = "optional"
  }

  /** When an entry may start and when it stops being worth starting. */
  sealed trait Timing { def notBefore: Long; def expires: Long }
  final case class Immediate(expires: Long) extends Timing { val notBefore = 0L }
  final case class Scheduled(notBefore: Long, expires: Long) extends Timing

  /**
   * How often an entry may be retried and the base delay between attempts, which doubles per
   * attempt. Retries are safe only because each operation rechecks its own contract eligibility.
   */
  final case class RetryPolicy(maxAttempts: Int, backoffMillis: Long)

  /** NISP and fraud work: retried hardest, because missing its window costs the miner a payout. */
  val Critical = RetryPolicy(5, 5000L)
  /** Timer-driven work that will be rediscovered anyway, so a few attempts are enough. */
  val Automatic = RetryPolicy(3, 15000L)
  /** A single API request. Retrying would resend work the caller can no longer see the result of. */
  val OneOff = RetryPolicy(1, 0L)

  /**
   * One admitted unit of work. `attempt` is set while a worker owns it, so a completion carrying a
   * stale id can be discarded; `dueAt` holds the earliest retry time after a failure.
   */
  final case class Entry[A](key: String, work: A, lane: String, timing: Timing, policy: RetryPolicy,
                            attempt: Option[UUID] = None, attempts: Int = 0, dueAt: Long = 0L)

  /** Attempts beyond this stop lengthening the backoff, capping it at 1024x the base delay. */
  private final val MaxBackoffDoublings = 10
}

/**
 * Admission queue for engine work, keyed by intent so duplicates coalesce. One entry per lane runs
 * at a time, which is what keeps optional work from consuming the critical lane's capacity.
 */
/** @param limit entries across all lanes; callers size it from their per-lane depths. */
private[transactions] final class ExecutionSchedule[A](limit: Int = 128) {
  import ExecutionSchedule._
  private var entries = Vector.empty[Entry[A]]

  def size: Int = entries.size
  def contains(key: String): Boolean = entries.exists(_.key == key)
  /** Whether a worker already owns an entry in this lane. */
  def busy(lane: String): Boolean = entries.exists(entry => entry.lane == lane && entry.attempt.nonEmpty)
  def nonEmpty: Boolean = entries.nonEmpty

  /** Admit new work. A key already queued or running is left alone rather than duplicated. */
  def register(entry: Entry[A]): Boolean = {
    if (contains(entry.key)) false
    else if (entries.size >= limit) false
    else { entries :+= entry; true }
  }

  /** Drop queued work past its deadline. An entry a worker still owns is never expired here. */
  def expire(now: Long): Vector[Entry[A]] = {
    val expired = entries.filter(entry => entry.attempt.isEmpty && now >= entry.timing.expires)
    entries = entries.filterNot(entry => expired.exists(_.key == entry.key))
    expired
  }

  /** Claim the next eligible entry in this lane, stamping it with a fresh attempt id. */
  def next(lane: String, now: Long): Option[Entry[A]] = {
    if (busy(lane)) None
    else entries.find(entry => entry.lane == lane && entry.attempt.isEmpty &&
      now >= math.max(entry.timing.notBefore, entry.dueAt) && now < entry.timing.expires).map { eligible =>
      val running = eligible.copy(attempt = Some(UUID.randomUUID()), attempts = eligible.attempts + 1)
      entries = entries.map(entry => if (entry.key == eligible.key) running else entry)
      running
    }
  }

  /**
   * Record a completion. Returns the finished entry, or None when `attempt` belongs to a superseded
   * worker whose result must not touch current state.
   */
  def finish(key: String, attempt: UUID, retry: Boolean, now: Long): Option[Entry[A]] = {
    entries.find(entry => entry.key == key && entry.attempt.contains(attempt)).map { finished =>
      if (retry && finished.attempts < finished.policy.maxAttempts && now < finished.timing.expires) {
        val delay = finished.policy.backoffMillis *
          (1L << math.min(MaxBackoffDoublings, finished.attempts - 1))
        entries = entries.map(entry =>
          if (entry.key == key) finished.copy(attempt = None, dueAt = now + delay) else entry)
      } else entries = entries.filterNot(_.key == key)
      finished
    }
  }
}
