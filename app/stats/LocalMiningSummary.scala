package stats

import play.api.libs.json.{Json, OWrites}

/** A single reading of cumulative share work, kept so a current rate can be differenced from it. */
final case class WorkSample(session: String, observedAt: Long, work: BigInt, accepted: Long)

object WorkSample {
  def of(observation: LocalMiningObservation): WorkSample =
    WorkSample(observation.session, observation.observedAt,
      BigInt(observation.counters.getOrElse("acceptedAssignedWork", "0")),
      BigInt(observation.counters.getOrElse("accepted", "0")).toLong)
}

/**
 * What this client's own workers are doing, reduced to rates and totals.
 *
 * Deliberately narrower than the producer observations behind `/stats/local`: no session
 * identifiers, no fraud targets and no per-worker breakdown, so it carries nothing about the
 * operator that the already-public Stratum connection count does not.
 *
 * Assigned work is the expected hash count behind each accepted share, taken from the threshold
 * its job advertised. That puts it in the same units as network difficulty, so these rates and the
 * Lithos hashrate estimate are directly comparable.
 *
 * `hashesPerSecond` is the rate over the recent window and is the one to display as current
 * hashrate; `windowShares` says how many shares it rests on, which is how noisy it is.
 * `sessionHashesPerSecond` averages the whole session instead, so it reads low whenever the
 * workers joined after the client started or stopped before it did.
 */
final case class LocalMiningSummary(status: String, enabled: Boolean,
                                    sessionStartedAt: Option[Long] = None,
                                    observedAt: Option[Long] = None,
                                    hashesPerSecond: Option[String] = None,
                                    sessionHashesPerSecond: Option[String] = None,
                                    windowMs: Long = 0L, windowShares: Long = 0L,
                                    acceptedShares: Long = 0L, rejectedShares: Long = 0L,
                                    blockCandidates: Long = 0L, superShares: Long = 0L,
                                    superSharesPerHour: Option[Double] = None,
                                    assignedWork: String = "0",
                                    solutionsAccepted: Long = 0L, solutionsRejected: Long = 0L,
                                    reducedReporting: Boolean = false,
                                    rejections: Map[String, String] = Map.empty)

/** One interval of local work, measured between two observations of the same producer session. */
final case class LocalHashratePoint(start: Long, widthMs: Long, hashesPerSecond: String,
                                    acceptedShares: Long, superShares: Long, reducedReporting: Boolean)

object LocalHashratePoint {
  implicit val writes: OWrites[LocalHashratePoint] = Json.writes[LocalHashratePoint]
}

final case class LocalHashrateHistory(from: Long, until: Long, widthMs: Long,
                                      points: Vector[LocalHashratePoint], status: String)

object LocalHashrateHistory {
  implicit val writes: OWrites[LocalHashrateHistory] = Json.writes[LocalHashrateHistory]
}

object LocalMiningSummary {
  implicit val writes: OWrites[LocalMiningSummary] = Json.writes[LocalMiningSummary]

  private def counter(observation: LocalMiningObservation, name: String): BigInt =
    BigInt(observation.counters.getOrElse(name, "0"))

  private def rate(work: BigInt, elapsedMs: Long): Option[String] =
    if (work > 0 && elapsedMs > 0) Some(((work * 1000) / elapsedMs).toString) else None

  /**
   * The current rate, differenced across the retained window.
   *
   * Anchored at the oldest sample sharing the newest one's session, because counters restart with
   * the session and a delta across that boundary is meaningless rather than merely imprecise.
   */
  private def windowed(samples: Vector[WorkSample]): Option[(String, Long, Long)] =
    samples.lastOption.flatMap { latest =>
      samples.find(_.session == latest.session).filter(_.observedAt < latest.observedAt).flatMap { anchor =>
        val elapsed = latest.observedAt - anchor.observedAt
        rate(latest.work - anchor.work, elapsed).map((_, elapsed, latest.accepted - anchor.accepted))
      }
    }

  def of(enabled: Boolean, producers: Map[String, LocalMiningActivityView],
         samples: Vector[WorkSample] = Vector.empty): LocalMiningSummary = {
    val shares = producers.get("shares")
    val solutions = producers.get("solutions")
    if (shares.isEmpty && solutions.isEmpty)
      return LocalMiningSummary(if (enabled) "loading" else "disabled", enabled)

    val status = shares.orElse(solutions).map(_.status).getOrElse("loading")
    val summary = shares.map(_.observation).map { o =>
      val elapsed = o.observedAt - o.startedAt
      val work = counter(o, "acceptedAssignedWork")
      val rejected = o.counters.filter(_._1.startsWith("rejected"))
      val window = windowed(samples)
      LocalMiningSummary(status, enabled, Some(o.startedAt), Some(o.observedAt),
        hashesPerSecond = window.map(_._1).orElse(rate(work, elapsed)),
        sessionHashesPerSecond = rate(work, elapsed),
        windowMs = window.map(_._2).getOrElse(0L), windowShares = window.map(_._3).getOrElse(0L),
        acceptedShares = counter(o, "accepted").toLong,
        rejectedShares = rejected.values.map(BigInt(_)).sum.toLong,
        blockCandidates = counter(o, "blockCandidates").toLong,
        superShares = counter(o, "superShares").toLong,
        superSharesPerHour =
          if (elapsed > 0) Some(counter(o, "superShares").toDouble * 3600000 / elapsed) else None,
        assignedWork = work.toString,
        reducedReporting = counter(o, "acceptedWithReducedReporting") > 0,
        rejections = rejected)
    }.getOrElse(LocalMiningSummary(status, enabled))

    solutions.map(_.observation).fold(summary) { o =>
      summary.copy(solutionsAccepted = counter(o, "nodeAccepted").toLong,
        solutionsRejected = counter(o, "nodeRejectedOrFailed").toLong)
    }
  }

  /**
   * Turns stored share observations into per-interval rates.
   *
   * Only consecutive observations of the same session describe a measurable interval: a restart
   * resets the counters, so a delta across that boundary would read as a negative or absurd rate.
   * Those pairs are dropped rather than clamped, leaving a gap that honestly says the interval was
   * not measured.
   */
  def history(observations: Vector[LocalMiningObservation], from: Long, until: Long,
              widthMs: Long, status: String): LocalHashrateHistory = {
    val ordered = observations.filter(_.kind == "shares").sortBy(o => (o.observedAt, o.sequence))
    val points = ordered.sliding(2).collect {
      case Vector(before, after) if before.session == after.session &&
        after.sequence > before.sequence && after.observedAt > before.observedAt =>
        val work = counter(after, "acceptedAssignedWork") - counter(before, "acceptedAssignedWork")
        val accepted = counter(after, "accepted") - counter(before, "accepted")
        val supers = counter(after, "superShares") - counter(before, "superShares")
        val reduced = counter(after, "acceptedWithReducedReporting") -
          counter(before, "acceptedWithReducedReporting")
        if (work >= 0 && accepted >= 0 && supers >= 0)
          Some(LocalHashratePoint(MiningHistory.bucketStart(after.observedAt, widthMs), widthMs,
            rate(work, after.observedAt - before.observedAt).getOrElse("0"),
            accepted.toLong, supers.toLong, reduced == 0))
        else None
    }.flatten.toVector
    LocalHashrateHistory(from, until, widthMs, points, status)
  }
}
