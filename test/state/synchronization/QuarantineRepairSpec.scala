package state.synchronization

import lfsm.states.PlasmaDictionary
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import state.messages.BlockInfo
import support.{ReducerFixtures, SyncFixtures}

/**
 * A quarantined rollup is a lasting loss: this client never submits to it or claims its payout again.
 * The fault has to say whether rebuilding could help, and survive the state it was recorded against.
 */
class QuarantineRepairSpec extends AnyFlatSpec with Matchers with OptionValues {

  private val protocol = ReducerFixtures.protocol()
  private val rollupId = SyncFixtures.id(600001)
  private val inputUtxo = SyncFixtures.id(600002)
  private val origin = SyncFixtures.id(800000)

  "A quarantine" should "record the genesis height so the rollup can be walked again" in {
    val transition = quarantineWith(ReducerFixtures.proofHex(Array[Byte](1, 2, 3)))

    val fault = transition.quarantined.loneElement
    fault.rollupId shouldEqual rollupId
    fault.genesisHeight shouldEqual 99
    fault.collateralBoxId shouldEqual origin
    fault.attempts shouldEqual 0
    transition.state.quarantined.keySet shouldEqual Set(rollupId)
  }

  /**
   * A missing or undecodable context variable is what an index gap looks like, and re-reading can
   * clear it. A state the chain reports cannot change, so retrying only spends node calls.
   */
  it should "mark a decode failure retryable and a state mismatch terminal" in {
    BlockReducer.isRetryable(
      SyncApplyError.MalformedTransaction("tx", "missing context variable 2")) shouldBe true
    BlockReducer.isRetryable(SyncApplyError.InternalFailure("block", "boom")) shouldBe true
    BlockReducer.isRetryable(
      SyncApplyError.StateInvariant("tx", "unexpected output contract")) shouldBe false
    BlockReducer.isRetryable(SyncApplyError.InvalidProof("tx", "insertion")) shouldBe false
  }

  /** A later fault must not reset attempts already recorded for another quarantined rollup. */
  it should "preserve attempt counts when a later block introduces another quarantine" in {
    val first = quarantineWith(ReducerFixtures.proofHex(Array[Byte](1, 2, 3)))
    val attempted = first.state.copy(
      quarantined = first.state.quarantined.mapValues(_.attempted.attempted).toMap)
    val laterId = SyncFixtures.id(600010)
    val laterInput = SyncFixtures.id(600011)
    val withLater = ReducerFixtures.addRollup(attempted, laterId, laterInput, PlasmaDictionary.empty())
    val (key, value) = SyncFixtures.plasmaEntries(1, 16).head
    val expected = PlasmaDictionary.empty()
    expected.insert(key -> value)
    val bad = ReducerFixtures.submissionTx(31, laterInput, SyncFixtures.id(600012), 101,
      key, value, ReducerFixtures.proofHex(Array[Byte](9)), expected,
      numMiners = 1, totalScore = BigInt(0), period = 99L)

    val next = BlockReducer.applyBlock(withLater,
      BlockInfo(SyncFixtures.id(101), 101, Seq(bad), withLater.cursor.blockId), protocol)
      .toOption.value

    next.state.quarantined(rollupId).attempts shouldEqual 2
    next.state.quarantined(laterId).attempts shouldEqual 0
  }

  /** Only faults that could clear, and have attempts left, are worth the walk. */
  it should "offer only retryable faults inside the configured attempt budget" in {
    val base = ReducerFixtures.emptyState()
    val retryable = QuarantineFault("a", 10, "origin-a", "decode", retryable = true, attempts = 1)
    val exhausted = QuarantineFault("b", 20, "origin-b", "decode", retryable = true, attempts = 3)
    val terminal = QuarantineFault("c", 30, "origin-c", "mismatch", retryable = false, attempts = 0)
    val state = base.copy(quarantined =
      Map("a" -> retryable, "b" -> exhausted, "c" -> terminal))

    state.repairableQuarantines(maxAttempts = 3).map(_.rollupId) shouldEqual Seq("a")
  }

  /** Newest first, because a rollup still inside its windows is the one worth rebuilding. */
  it should "offer the newest repairable fault first" in {
    val base = ReducerFixtures.emptyState()
    val older = QuarantineFault("older", 10, "origin-older", "decode", retryable = true)
    val newer = QuarantineFault("newer", 90, "origin-newer", "decode", retryable = true)
    val state = base.copy(quarantined = Map("older" -> older, "newer" -> newer))

    state.repairableQuarantines(maxAttempts = 3).map(_.rollupId) shouldEqual Seq("newer", "older")
  }

  /** Tracking and quarantine are exclusive, so a rebuild cannot leave the fault behind. */
  it should "refuse a state that both tracks and quarantines the same rollup" in {
    val tracked = ReducerFixtures.stateWithRollup(99, SyncFixtures.id(99), rollupId, inputUtxo,
      PlasmaDictionary.empty())

    an[IllegalArgumentException] should be thrownBy tracked.copy(
      quarantined = Map(rollupId -> QuarantineFault(
        rollupId, 99, origin, "decode", retryable = true)))
  }

  private def quarantineWith(proof: String): BlockTransition = {
    val base = ReducerFixtures.stateWithRollup(99, SyncFixtures.id(99), rollupId, inputUtxo,
      PlasmaDictionary.empty())
    val (key, value) = SyncFixtures.plasmaEntries(1, 16).head
    val expected = PlasmaDictionary.empty()
    expected.insert(key -> value)
    val tx = ReducerFixtures.submissionTx(30, inputUtxo, SyncFixtures.id(600003), 100,
      key, value, proof, expected, numMiners = 1, totalScore = BigInt(0), period = 99L)

    BlockReducer.applyBlock(base,
      BlockInfo(SyncFixtures.id(100), 100, Seq(tx), base.cursor.blockId), protocol).toOption.value
  }

  private implicit class LoneElement[A](values: Seq[A]) {
    def loneElement: A = {
      values should have size 1
      values.head
    }
  }
}
