package stats

import configs.StatsConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LocalMiningSummarySpec extends AnyFlatSpec with Matchers {
  private val hour = MiningHistory.HourMs

  private def shares(session: String, sequence: Long, startedAt: Long, observedAt: Long,
                     counters: (String, String)*): LocalMiningObservation =
    LocalMiningObservation("shares", session, sequence, startedAt, observedAt, counters.toMap)

  private def view(observation: LocalMiningObservation, status: String = "ready") =
    LocalMiningActivityView(status, observation, deduplicationComplete = true)

  "A worker summary" should "rate assigned work over the session and total the rejection codes" in {
    val observation = shares("s1", 4L, 1000L, 61000L,
      "accepted" -> "120", "acceptedAssignedWork" -> "600000", "superShares" -> "3",
      "blockCandidates" -> "1", "rejected20" -> "4", "rejected23" -> "1")
    val summary = LocalMiningSummary.of(enabled = true, Map("shares" -> view(observation),
      "solutions" -> view(LocalMiningObservation("solutions", "s2", 1L, 1000L, 61000L,
        Map("nodeAccepted" -> "2", "nodeRejectedOrFailed" -> "1")))))

    // 600000 hashes of assigned work over 60 seconds of session.
    summary.sessionHashesPerSecond shouldBe Some("10000")
    // With no window samples yet, the current rate falls back to the session's.
    summary.hashesPerSecond shouldBe Some("10000")
    summary.windowShares shouldBe 0L
    summary.acceptedShares shouldBe 120L
    summary.rejectedShares shouldBe 5L
    summary.rejections shouldBe Map("rejected20" -> "4", "rejected23" -> "1")
    summary.superShares shouldBe 3L
    summary.superSharesPerHour shouldBe Some(180.0)
    summary.blockCandidates shouldBe 1L
    summary.solutionsAccepted shouldBe 2L
    summary.solutionsRejected shouldBe 1L
    summary.reducedReporting shouldBe false
    summary.status shouldBe "ready"
  }

  it should "flag reduced reporting without discounting the work it measured" in {
    // Reduced reporting sends only super shares, each already credited the super-share threshold's
    // work upstream. The rate is as complete as any other; it is just built from fewer samples.
    val summary = LocalMiningSummary.of(enabled = true, Map("shares" -> view(
      shares("s1", 2L, 0L, 1000L, "acceptedAssignedWork" -> "50",
        "acceptedWithReducedReporting" -> "7"))))
    summary.reducedReporting shouldBe true
    summary.hashesPerSecond shouldBe Some("50")
  }

  it should "report the current rate over the window rather than the whole session" in {
    // Workers idle for the first ten minutes, then hash at 1000/s. The session average is dragged
    // down by the idle stretch; the window describes what the workers are doing now.
    val observation = shares("s1", 900L, 0L, 900000L, "accepted" -> "30", "acceptedAssignedWork" -> "300000")
    val window = Vector(
      WorkSample("s1", 600000L, BigInt(0), 0L),
      WorkSample("s1", 750000L, BigInt(150000), 15L),
      WorkSample("s1", 900000L, BigInt(300000), 30L))
    val summary = LocalMiningSummary.of(enabled = true, Map("shares" -> view(observation)), window)
    summary.hashesPerSecond shouldBe Some("1000")
    summary.sessionHashesPerSecond shouldBe Some("333")
    summary.windowMs shouldBe 300000L
    summary.windowShares shouldBe 30L
  }

  it should "read zero once the workers stop, not the session average" in {
    val observation = shares("s1", 900L, 0L, 1800000L, "accepted" -> "30", "acceptedAssignedWork" -> "300000")
    val window = Vector(
      WorkSample("s1", 900000L, BigInt(300000), 30L),
      WorkSample("s1", 1800000L, BigInt(300000), 30L))
    val summary = LocalMiningSummary.of(enabled = true, Map("shares" -> view(observation)), window)
    summary.hashesPerSecond shouldBe Some("0")
    summary.windowShares shouldBe 0L
    summary.sessionHashesPerSecond shouldBe Some("166")
  }

  it should "anchor the window inside the newest session" in {
    // A restart resets the counters; differencing across it would read a new session's small total
    // against the old one's large total and report nothing, or something negative.
    val observation = shares("s2", 60L, 700000L, 760000L, "accepted" -> "6", "acceptedAssignedWork" -> "6000")
    val window = Vector(
      WorkSample("s1", 640000L, BigInt(9000000), 900L),
      WorkSample("s2", 700000L, BigInt(0), 0L),
      WorkSample("s2", 760000L, BigInt(6000), 6L))
    val summary = LocalMiningSummary.of(enabled = true, Map("shares" -> view(observation)), window)
    summary.hashesPerSecond shouldBe Some("100")
    summary.windowShares shouldBe 6L
  }

  it should "report no producers without inventing a rate" in {
    val absent = LocalMiningSummary.of(enabled = true, Map.empty)
    absent.status shouldBe "loading"
    absent.hashesPerSecond shouldBe None
    absent.acceptedShares shouldBe 0L
    LocalMiningSummary.of(enabled = false, Map.empty).status shouldBe "disabled"
    // A producer that has reported nothing yet still has no elapsed time to divide by.
    LocalMiningSummary.of(enabled = true, Map("shares" -> view(shares("s1", 1L, 5000L, 5000L))))
      .hashesPerSecond shouldBe None
  }

  "The work window" should "keep one sample past its far edge as the anchor and drop older ones" in {
    val cache = new StatsCache(StatsConfig.Default)
    val window = StatsCache.WorkWindowMs
    Seq(0L, 1000L, window, window + 2000L).zipWithIndex.foreach { case (at, i) =>
      cache.publishLocal(shares("s1", i + 1L, 0L, at, "acceptedAssignedWork" -> (i * 10).toString))
    }
    // The newest sample puts the edge at 2000. The sample at 1000 is the last one before it and is
    // kept, so the rate can still be measured across the full window; the one at 0 is not needed.
    cache.recentWork.map(_.observedAt) shouldBe Vector(1000L, window, window + 2000L)
    // Only share observations describe hashrate.
    cache.publishLocal(LocalMiningObservation("fraud", "s1", 9L, 0L, window + 3000L, Map.empty))
    cache.recentWork.map(_.observedAt).last shouldBe window + 2000L
  }

  "Worker history" should "measure only within one producer session" in {
    val a = shares("s1", 1L, 0L, hour, "acceptedAssignedWork" -> "0", "accepted" -> "0")
    val b = shares("s1", 2L, 0L, 2 * hour, "acceptedAssignedWork" -> "3600000", "accepted" -> "10",
      "superShares" -> "2")
    // A restart resets every counter, so the b -> c step is not a measurable interval.
    val c = shares("s2", 1L, 2 * hour + 1000, 3 * hour, "acceptedAssignedWork" -> "50", "accepted" -> "1")
    val d = shares("s2", 2L, 2 * hour + 1000, 4 * hour, "acceptedAssignedWork" -> "7250", "accepted" -> "4")

    val history = LocalMiningSummary.history(Vector(a, b, c, d), 0L, 5 * hour, hour, "ready")
    history.points.map(_.start) shouldBe Vector(2 * hour, 4 * hour)
    // 3600000 hashes over the hour between a and b.
    history.points.head.hashesPerSecond shouldBe "1000"
    history.points.head.acceptedShares shouldBe 10L
    history.points.head.superShares shouldBe 2L
    history.points.last.hashesPerSecond shouldBe "2"
    history.points.last.acceptedShares shouldBe 3L
    history.points.map(_.reducedReporting) shouldBe Vector(false, false)
    history.status shouldBe "ready"
    history.widthMs shouldBe hour
  }

  it should "drop a pair whose counters went backwards rather than report a negative rate" in {
    val a = shares("s1", 1L, 0L, hour, "acceptedAssignedWork" -> "900")
    val rewound = shares("s1", 2L, 0L, 2 * hour, "acceptedAssignedWork" -> "100")
    LocalMiningSummary.history(Vector(a, rewound), 0L, 3 * hour, hour, "ready").points shouldBe empty
    // Observations of other producers never contribute a share rate.
    LocalMiningSummary.history(Vector(
      LocalMiningObservation("fraud", "s1", 1L, 0L, hour, Map.empty),
      LocalMiningObservation("fraud", "s1", 2L, 0L, 2 * hour, Map.empty)),
      0L, 3 * hour, hour, "ready").points shouldBe empty
  }
}
