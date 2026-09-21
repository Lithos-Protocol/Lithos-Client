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
}
