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

  "TransformJournal" should "reconstruct an exact boundary from a checkpoint and normalized operations" in {
    val rollupId = SyncFixtures.id(9000)
    val input = SyncFixtures.id(9001)
    val output = SyncFixtures.id(9002)
    val baseDictionary = PlasmaDictionary.empty()
    val baseRollup = Rollup(baseDictionary, 0, BigInt(0), Some(99L), 1000000L, 99,
      hasMiner = false, LFSMPhase.HOLDING, evaluated = false, rollupId, input)
    val base = CommittedSyncState(SyncCursor(99, SyncFixtures.id(99), SyncFixtures.id(98)), 0L,
      Map(rollupId -> baseRollup), Map(input -> rollupId), Map(rollupId -> SyncFixtures.id(8999)),
      lfsm.states.MinerDictionary.initialState, None)

    val (key, value) = SyncFixtures.plasmaEntries(1, 32).head
    val expectedDictionary = baseDictionary.copy()
    expectedDictionary.insert(key -> value)
    val nextRollup = baseRollup.copy(dictionary = expectedDictionary, numMiners = 1,
      totalScore = BigInt(10), hasMiner = true, utxoId = output)
    val next = base.copy(cursor = SyncCursor(100, SyncFixtures.id(100), base.cursor.blockId), version = 1L,
      rollups = Map(rollupId -> nextRollup), routes = Map(output -> rollupId))
    val transform = DictionaryTransform(DictionaryId.Rollup(rollupId),
      Hex.toHexString(baseDictionary.digest), Vector(DictionaryOperation.Insert("tx", Seq(key -> value))),
      Hex.toHexString(expectedDictionary.digest))
    val entry = TransformJournalEntry(next.cursor, CommittedSyncMetadata.from(next), Vector(transform))

    val restored = TransformJournal.materialize(base, Vector(entry)).toOption.get

    restored.cursor shouldEqual next.cursor
    restored.rollups(rollupId).metadata shouldEqual nextRollup.metadata
    restored.rollups(rollupId).dictionary.digest should
      contain theSameElementsInOrderAs expectedDictionary.digest
  }

  it should "reject a transform when either boundary digest is wrong" in {
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
}
