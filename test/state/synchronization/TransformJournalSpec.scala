package state.synchronization

import lfsm.LFSMPhase
import lfsm.states.{PlasmaDictionary, Rollup}
import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import state.messages.SyncMessages.SyncCursor
import support.SyncFixtures

class TransformJournalSpec extends AnyFlatSpec with Matchers {

  "DictionaryOperation" should "own every key and value byte used for later replay" in {
    val (key, value) = SyncFixtures.plasmaEntries(1, 32).head
    val expectedKey = key.clone()
    val expectedValue = value.clone()
    val operation = DictionaryOperation.Insert("tx", Seq(key -> value))
    key(0) = (key(0) ^ 0x7f).toByte
    value(0) = (value(0) ^ 0x7f).toByte

    val replayed = PlasmaDictionary.empty()
    operation.applyTo(replayed)
    val expected = PlasmaDictionary.empty()
    expected.insert(expectedKey -> expectedValue)

    replayed.digest should contain theSameElementsInOrderAs expected.digest
    operation.entries.head._1 should contain theSameElementsInOrderAs expectedKey
    operation.entries.head._2 should contain theSameElementsInOrderAs expectedValue
  }

  it should "own deletion keys returned to callers and used for later replay" in {
    val (key, value) = SyncFixtures.plasmaEntries(1, 32).head
    val expectedKey = key.clone()
    val dictionary = PlasmaDictionary.empty()
    dictionary.insert(expectedKey -> value)
    val operation = DictionaryOperation.Delete("tx", Seq(key))

    key(0) = (key(0) ^ 0x7f).toByte
    val exposed = operation.keys.head
    exposed(1) = (exposed(1) ^ 0x7f).toByte
    operation.applyTo(dictionary)

    dictionary.foldKeys(Vector.empty[Array[Byte]])(_ :+ _) shouldBe empty
    operation.keys.head should contain theSameElementsInOrderAs expectedKey
  }

  "TransformJournal" should "reject a transform when either boundary digest is wrong" in {
    val dictionary = PlasmaDictionary.empty()
    val (key, value) = SyncFixtures.plasmaEntries(1, 32).head
    val wrongBefore = DictionaryTransform(DictionaryId.Miner, "00",
      Vector(DictionaryOperation.Insert("tx", Seq(key -> value))), "00")

    wrongBefore.replay(dictionary).left.toOption.get should include("expected digest")

    val wrongAfter = DictionaryTransform(DictionaryId.Miner, Hex.toHexString(dictionary.digest),
      Vector(DictionaryOperation.Insert("tx", Seq(key -> value))), "00")
    wrongAfter.replay(dictionary).left.toOption.get should include("produced digest")
  }

  it should "reject a first entry that does not descend from the checkpoint" in {
    val base = SyncCursor(99, SyncFixtures.id(99), SyncFixtures.id(98))
    val nextCursor = SyncCursor(100, SyncFixtures.id(100), SyncFixtures.id(777))
    val state = CommittedSyncState(nextCursor, 1L, Map.empty, Map.empty, Map.empty,
      lfsm.states.MinerDictionary.initialState, None)
    val entry = TransformJournalEntry(nextCursor, CommittedSyncMetadata.from(state), Vector.empty)

    val error = intercept[IllegalArgumentException](TransformJournal(base, Vector(entry)))
    error.getMessage should include("immediately after its checkpoint")
  }

  it should "account for retained metadata even when a block has no dictionary payload" in {
    val cursor = SyncCursor(100, SyncFixtures.id(100), SyncFixtures.id(99))
    val state = CommittedSyncState(cursor, 1L, Map.empty, Map.empty, Map.empty,
      lfsm.states.MinerDictionary.initialState, None,
      minerDictionaryFault = Some("x"), quarantined = Map.empty)
    val short = TransformJournalEntry(cursor, CommittedSyncMetadata.from(state), Vector.empty)
    val longerState = state.copy(minerDictionaryFault = Some("x" * 1000))
    val longer = TransformJournalEntry(cursor, CommittedSyncMetadata.from(longerState), Vector.empty)

    short.estimatedBytes shouldEqual 0L
    short.accountedBytes should be > 0L
    longer.accountedBytes should be > short.accountedBytes
  }
}
