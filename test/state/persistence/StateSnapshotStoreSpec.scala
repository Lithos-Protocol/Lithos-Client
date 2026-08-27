package state.persistence

import lfsm.LFSMPhase
import lfsm.states.{MinerTree, NISPTree, PlasmaDictionary}
import org.ergoplatform.sdk.ErgoId
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import state.messages.SyncMessages.SyncCursor
import state.synchronization.CommittedSyncState
import storage.LevelDbKeyValueStore
import support.SyncFixtures

import java.nio.file.{Files, Path}
import scala.collection.JavaConverters._

class StateSnapshotStoreSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {
  private var directories = Vector.empty[Path]
  private val identity = StateSnapshotIdentity("TESTNET", 100, "miner-hash",
    "holding-tree", "evaluation-tree", "payout-tree", SyncFixtures.id(7000), SyncFixtures.id(7002),
    SyncFixtures.id(7001))

  override def afterEach(): Unit = {
    directories.foreach { path =>
      if (Files.exists(path)) {
        val paths = Files.walk(path)
        try paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists)
        finally paths.close()
      }
    }
    directories = Vector.empty
  }

  "StateSnapshotCodec" should "round-trip metadata and native Plasma manifests" in {
    val persisted = populatedSnapshot(height = 100, version = 8L)
    val original = persisted.state

    val encoded = StateSnapshotCodec.encode(persisted, identity).toOption.get
    val decoded = StateSnapshotCodec.decode(encoded, identity).toOption.get
    val restored = decoded.state

    restored.cursor shouldEqual original.cursor
    restored.version shouldEqual original.version
    restored.routes shouldEqual original.routes
    restored.dataBoxToken shouldEqual original.dataBoxToken
    restored.minerDictionaryFault shouldEqual original.minerDictionaryFault
    restored.rollups.keySet shouldEqual original.rollups.keySet
    restored.rollups.values.head.dictionary.digest should contain theSameElementsInOrderAs
      original.rollups.values.head.dictionary.digest
    restored.minerTree.dictionary.digest should contain theSameElementsInOrderAs
      original.minerTree.dictionary.digest

    val (nextKey, nextValue) = SyncFixtures.plasmaEntries(3, 32).last
    val originalNext = original.rollups.values.head.dictionary.copy().insert(nextKey -> nextValue)
    val restoredNext = restored.rollups.values.head.dictionary.copy().insert(nextKey -> nextValue)
    restoredNext.proof.ergoValue.toHex shouldEqual originalNext.proof.ergoValue.toHex
  }

  // Persisted cursors restore the common-ancestor search window after restart.
  it should "round-trip the canonical cursors behind the committed state" in {
    val persisted = populatedSnapshot(height = 100, version = 8L)

    val encoded = StateSnapshotCodec.encode(persisted, identity).toOption.get

    StateSnapshotCodec.decode(encoded, identity).toOption.get.recentCursors shouldEqual
      persisted.recentCursors
  }

  it should "round-trip a Miner Dictionary fault so a snapshot cannot claim a stale tree is current" in {
    val faulted = populatedSnapshot(100, 8L)
    val persisted = faulted.copy(
      state = faulted.state.copy(minerDictionaryFault = Some("input 1 does not spend the tracked box")))

    val encoded = StateSnapshotCodec.encode(persisted, identity).toOption.get

    StateSnapshotCodec.decode(encoded, identity).toOption.get.state.minerDictionaryFault shouldEqual
      Some("input 1 does not spend the tracked box")
  }

  it should "reject a corrupt payload before publishing any state" in {
    val encoded = StateSnapshotCodec.encode(populatedSnapshot(100, 8L), identity).toOption.get
    encoded(encoded.length / 2) = (encoded(encoded.length / 2) ^ 0x01).toByte

    StateSnapshotCodec.decode(encoded, identity).isLeft shouldBe true
  }

  it should "reject state created for another network, wallet, or protocol deployment" in {
    val encoded = StateSnapshotCodec.encode(populatedSnapshot(100, 8L), identity).toOption.get
    val other = identity.copy(localMinerHash = "another-miner")

    StateSnapshotCodec.decode(encoded, other).left.toOption.get should include("does not match")
  }

  "LevelDbStateSnapshotStore" should "retain generations and recover through a corrupt newest one" in {
    val directory = Files.createTempDirectory("lithos-sync-snapshot-")
    directories :+= directory
    val keyValueStore = LevelDbKeyValueStore.openOrThrow(directory)
    val snapshots = new LevelDbStateSnapshotStore(keyValueStore, retention = 2, identity)
    val first = populatedSnapshot(100, 8L)
    val second = populatedSnapshot(101, 9L)

    save(snapshots, first) shouldEqual Right(())
    save(snapshots, second) shouldEqual Right(())
    firstUsable(snapshots).map(_.state.cursor) shouldEqual Some(second.state.cursor)

    val newest = keyValueStore.scanPrefix("sync/snapshot/data/".getBytes("UTF-8"))
      .toOption.get.maxBy(entry => new String(entry._1, "UTF-8"))
    val corrupt = newest._2.clone()
    corrupt(corrupt.length / 2) = (corrupt(corrupt.length / 2) ^ 0x01).toByte
    keyValueStore.put(newest._1, corrupt)

    firstUsable(snapshots).map(_.state.cursor) shouldEqual Some(first.state.cursor)
    snapshots.close() shouldEqual Right(())
  }

  /** Encodes and writes in one step, as the state owner does. */
  private def save(store: LevelDbStateSnapshotStore,
                   persisted: PersistedSyncState): Either[SnapshotError, Unit] =
    StateSnapshotCodec.encode(persisted, identity) match {
      case Right(encoded) => store.save(persisted.state.cursor, persisted.state.version, encoded)
      case Left(reason) => Left(SnapshotError.Corrupt(reason))
    }

  /** Returns the newest generation that decodes successfully. */
  private def firstUsable(store: LevelDbStateSnapshotStore): Option[PersistedSyncState] =
    store.generationKeys().toOption.toSeq.flatten
      .flatMap(key => store.load(key).toOption).headOption

  private def populatedSnapshot(height: Int, version: Long): PersistedSyncState =
    PersistedSyncState(populatedState(height, version),
      Vector(
        SyncCursor(height - 2, SyncFixtures.id(height - 2), SyncFixtures.id(height - 3)),
        SyncCursor(height - 1, SyncFixtures.id(height - 1), SyncFixtures.id(height - 2)),
        SyncCursor(height, SyncFixtures.id(height), SyncFixtures.id(height - 1))))

  private def populatedState(height: Int, version: Long): CommittedSyncState = {
    val entries = SyncFixtures.plasmaEntries(2, 32)
    val rollupDictionary = PlasmaDictionary.empty()
    rollupDictionary.insert(entries.head)
    val minerDictionary = PlasmaDictionary.empty()
    minerDictionary.insert(entries(1))
    val rollupId = SyncFixtures.id(5000)
    val utxoId = SyncFixtures.id(5001 + height)
    val rollup = NISPTree(rollupDictionary, 1, BigInt(50), Some(90L), 1000000L,
      90, hasMiner = true, LFSMPhase.HOLDING, Set("miner"), evaluated = true, rollupId, utxoId)
    val minerTree = MinerTree.initialState.copy(dictionary = minerDictionary, numMiners = 1,
      hasMiner = true, syncHeight = height, savedHeight = height)
    CommittedSyncState(SyncCursor(height, SyncFixtures.id(height), SyncFixtures.id(height - 1)),
      version, Map(rollupId -> rollup), Map(utxoId -> rollupId), minerTree,
      Some(ErgoId.create(SyncFixtures.id(6000))))
  }
}
