package stats

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LocalMiningStatsSpec extends AnyFlatSpec with Matchers {
  "Local fraud observations" should "deduplicate discovery and node acceptance separately and disclose bounded tracking" in {
    val producer = new LocalMiningStats("fraud")
    producer.found("rollup", "miner", "proof")
    producer.found("rollup", "miner", "proof")
    val event = FraudSubmissionObserved("rollup", "miner", "proof", "transaction")
    producer.submitted(event)
    producer.submitted(event)
    val first = producer.snapshot()
    first.counters("discoveriesWithinSession") shouldBe "1"
    first.counters("nodeAcceptedProofsWithinSession") shouldBe "1"
    first.fraud.head.transactionId shouldBe Some("transaction")
    (1 to 100).foreach(i => producer.found(s"rollup-$i", "miner", "proof"))
    val later = producer.snapshot()
    later.fraud should have size 100
    later.counters("discoveryEvictions") shouldBe "1"
    later.session shouldBe first.session
    later.sequence should be > first.sequence
    new LocalMiningStats("fraud").snapshot().session should not be first.session
  }

  "Super-share heights" should "keep the ten highest, newest first, whatever order they arrive in" in {
    val producer = new LocalMiningStats("shares")
    // A share for an older job can land after a newer one; the order kept is by height.
    Seq(100, 105, 101, 110, 102, 103, 104, 109, 106, 107, 108, 99).foreach(producer.superShare)
    producer.snapshot().superShareHeights shouldBe Vector(110, 109, 108, 107, 106, 105, 104, 103, 102, 101)
    // Two shares at one height are distinct shares, and a NISP counts both.
    val twice = new LocalMiningStats("shares")
    twice.superShare(50); twice.superShare(50)
    twice.snapshot().superShareHeights shouldBe Vector(50, 50)
  }

  "A persisted observation" should "still load after fields were added to it" in {
    // Observations are written to disk. One saved before super-share heights existed has no such
    // field, and failing to decode it would fail every refresh that reads local history.
    import MiningStatsData.localObservationFormat
    val stored = play.api.libs.json.Json.parse(
      """{"kind":"shares","session":"s","sequence":3,"startedAt":1,"observedAt":2,"counters":{"accepted":"4"}}""")
    val observation = stored.as[LocalMiningObservation]
    observation.counters shouldBe Map("accepted" -> "4")
    observation.superShareHeights shouldBe empty
    observation.fraud shouldBe empty
    observation.stopped shouldBe false
  }
}
