package stats

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
    summary.hashesPerSecond shouldBe Some("10000")
    summary.acceptedShares shouldBe 120L
    summary.rejectedShares shouldBe 5L
    summary.rejections shouldBe Map("rejected20" -> "4", "rejected23" -> "1")
    summary.superShares shouldBe 3L
    summary.superSharesPerHour shouldBe Some(180.0)
    summary.blockCandidates shouldBe 1L
    summary.solutionsAccepted shouldBe 2L
    summary.solutionsRejected shouldBe 1L
    summary.workComplete shouldBe true
    summary.status shouldBe "ready"
  }

  it should "mark the rate incomplete when reduced reporting dropped accepted shares" in {
    val summary = LocalMiningSummary.of(enabled = true, Map("shares" -> view(
      shares("s1", 2L, 0L, 1000L, "acceptedAssignedWork" -> "50",
        "acceptedWithReducedReporting" -> "7"))))
    summary.workComplete shouldBe false
    summary.hashesPerSecond shouldBe Some("50")
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
