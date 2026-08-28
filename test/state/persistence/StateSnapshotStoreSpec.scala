package state.persistence

import lfsm.LFSMPhase
import lfsm.states.{MinerTree, NISPTree, PlasmaDictionary}
import org.ergoplatform.sdk.ErgoId
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import state.messages.SyncMessages.SyncCursor
import state.synchronization.{CommittedSyncState, QuarantineFault}
import storage.LevelDbKeyValueStore
import support.SyncFixtures

import java.nio.file.{Files, Path}
import scala.collection.JavaConverters._

class StateSnapshotStoreSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {
  private var directories = Vector.empty[Path]
  private val MaxEntry = 64 * 1024 * 1024
  private val identity = StateSnapshotIdentity("TESTNET", 100, "miner-hash",
    "holding-tree", "evaluation-tree", "payout-tree", "collateral-tree", SyncFixtures.id(7000),
    SyncFixtures.id(7002), SyncFixtures.id(7001))

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

    val restored = roundTrip(persisted).state

    restored.cursor shouldEqual original.cursor
    restored.version shouldEqual original.version
    restored.routes shouldEqual original.routes
    restored.rollupOrigins shouldEqual original.rollupOrigins
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

    roundTrip(persisted).recentCursors shouldEqual persisted.recentCursors
  }

  it should "round-trip a Miner Dictionary fault so a snapshot cannot claim a stale tree is current" in {
    val faulted = populatedSnapshot(100, 8L)
    val persisted = faulted.copy(
      state = faulted.state.copy(minerDictionaryFault = Some("input 1 does not spend the tracked box")))

    roundTrip(persisted).state.minerDictionaryFault shouldEqual
      Some("input 1 does not spend the tracked box")
  }

  /** A fault that did not survive restart would silently start its attempt count again. */
  it should "round-trip quarantine faults with their attempt counts" in {
    val base = populatedSnapshot(100, 8L)
    val fault = QuarantineFault("dropped-rollup", 88, SyncFixtures.id(4999), "missing context variable 2",
      retryable = true, attempts = 2, removalHeight = Some(180), removalWarningLogged = true)
    val persisted = base.copy(state = base.state.copy(quarantined = Map(fault.rollupId -> fault)))

    roundTrip(persisted).state.quarantined shouldEqual Map(fault.rollupId -> fault)
  }

  it should "reject a corrupt payload before publishing any state" in {
    val encoded = encode(populatedSnapshot(100, 8L)).toOption.get
    val meta = encoded.meta.clone()
    meta(meta.length / 2) = (meta(meta.length / 2) ^ 0x01).toByte

    StateSnapshotCodec.decodeMeta(meta, identity).isLeft shouldBe true
  }

  it should "reject state created for another network, wallet, or protocol deployment" in {
    val encoded = encode(populatedSnapshot(100, 8L)).toOption.get
    val other = identity.copy(localMinerHash = "another-miner")

    StateSnapshotCodec.decodeMeta(encoded.meta, other).left.toOption.get should include("does not match")
  }

  /**
   * One entry per rollup is what keeps a generation's largest value independent of the rollup count.
   */
  it should "write one entry per rollup rather than one value for the whole state" in {
    val persisted = populatedSnapshot(100, 8L, rollups = 4)

    val encoded = encode(persisted).toOption.get

    encoded.rollups.map(_._1).toSet shouldEqual persisted.state.rollups.keySet
    // Metadata carries routes and the dictionary, never a rollup's own manifest.
    encoded.rollups.foreach { case (_, value) => value.length should be > encoded.meta.length / 8 }
  }

  /** A generation too large to read back must be refused at the point it would be written. */
  it should "refuse a generation whose entry would exceed the configured ceiling" in {
    val persisted = populatedSnapshot(100, 8L)

    val refused = encode(persisted, maxEntry = 64)

    refused.isLeft shouldBe true
    refused.left.toOption.get should include("over the configured 64 limit")
  }

  "LevelDbStateSnapshotStore" should "retain generations and recover through a corrupt newest one" in {
    val (keyValueStore, snapshots) = store(retention = 2)
    val first = populatedSnapshot(100, 8L)
    val second = populatedSnapshot(101, 9L)

    save(snapshots, first) shouldEqual Right(())
    save(snapshots, second) shouldEqual Right(())
    firstUsable(snapshots).map(_.state.cursor) shouldEqual Some(second.state.cursor)

    val newest = keyValueStore.scanPrefix("sync/snapshot/gen/".getBytes("UTF-8"))
      .toOption.get.filter(entry => new String(entry._1, "UTF-8").endsWith("/meta"))
      .maxBy(entry => new String(entry._1, "UTF-8"))
    val corrupt = newest._2.clone()
    corrupt(corrupt.length / 2) = (corrupt(corrupt.length / 2) ^ 0x01).toByte
    keyValueStore.put(newest._1, corrupt)

    firstUsable(snapshots).map(_.state.cursor) shouldEqual Some(first.state.cursor)
    snapshots.close() shouldEqual Right(())
  }

  /** A generation missing one rollup entry is not partially restorable. */
  it should "refuse a generation whose rollup entry is absent" in {
    val (keyValueStore, snapshots) = store(retention = 2)
    val persisted = populatedSnapshot(100, 8L)
    save(snapshots, persisted) shouldEqual Right(())

    val rollupEntry = keyValueStore.scanKeys("sync/snapshot/gen/".getBytes("UTF-8"))
      .toOption.get.find(key => new String(key, "UTF-8").contains("/r/")).get
    keyValueStore.delete(rollupEntry)

    val key = snapshots.generationKeys().toOption.get.head
    snapshots.load(key).left.toOption.get.message should include("missing rollup")
    snapshots.close() shouldEqual Right(())
  }

  /** Retention is what stops the database growing forever, and nothing else exercised it. */
  it should "prune every entry of a generation past its retention" in {
    val (keyValueStore, snapshots) = store(retention = 2)
    (100 to 103).foreach(height => save(snapshots, populatedSnapshot(height, height.toLong)) shouldEqual Right(()))

    val generations = snapshots.generationKeys().toOption.get
    generations.size shouldEqual 2

    // No stray rollup entries survive from the generations that were pruned.
    val remaining = keyValueStore.scanKeys("sync/snapshot/gen/".getBytes("UTF-8")).toOption.get
      .map(key => new String(key, "UTF-8"))
    remaining.filter(_.contains("/r/")).size shouldEqual 2
    firstUsable(snapshots).map(_.state.cursor.height) shouldEqual Some(103)
    snapshots.close() shouldEqual Right(())
  }

  private def store(retention: Int): (LevelDbKeyValueStore, LevelDbStateSnapshotStore) = {
    val directory = Files.createTempDirectory("lithos-sync-snapshot-")
    directories :+= directory
    val keyValueStore = LevelDbKeyValueStore.openOrThrow(directory).asInstanceOf[LevelDbKeyValueStore]
    (keyValueStore, new LevelDbStateSnapshotStore(keyValueStore, retention, identity))
  }

  private def encode(persisted: PersistedSyncState,
                     id: StateSnapshotIdentity = identity,
                     maxEntry: Int = MaxEntry): Either[String, SnapshotWrite] =
    StateSnapshotCodec.encode(persisted, id, maxEntry)

  /** Decodes through the same two steps the store uses, so the spec exercises the real path. */
  private def roundTrip(persisted: PersistedSyncState): PersistedSyncState = {
    val encoded = encode(persisted).toOption.get
    val meta = StateSnapshotCodec.decodeMeta(encoded.meta, identity).toOption.get
    val rollups = encoded.rollups.map { case (id, value) =>
      id -> StateSnapshotCodec.decodeRollup(value, id).toOption.get
    }.toMap
    StateSnapshotCodec.assemble(meta, rollups).toOption.get
  }

  /** Encodes and writes in one step, as the state owner does. */
  private def save(store: LevelDbStateSnapshotStore,
                   persisted: PersistedSyncState): Either[SnapshotError, Unit] =
    encode(persisted) match {
      case Right(encoded) => store.save(persisted.state.cursor, persisted.state.version, encoded)
      case Left(reason) => Left(SnapshotError.Corrupt(reason))
    }

  /** Returns the newest generation that decodes successfully. */
  private def firstUsable(store: LevelDbStateSnapshotStore): Option[PersistedSyncState] =
    store.generationKeys().toOption.toSeq.flatten
      .flatMap(key => store.load(key).toOption).headOption

  private def populatedSnapshot(height: Int,
                                version: Long,
                                rollups: Int = 1): PersistedSyncState =
    PersistedSyncState(populatedState(height, version, rollups),
      Vector(
        SyncCursor(height - 2, SyncFixtures.id(height - 2), SyncFixtures.id(height - 3)),
        SyncCursor(height - 1, SyncFixtures.id(height - 1), SyncFixtures.id(height - 2)),
        SyncCursor(height, SyncFixtures.id(height), SyncFixtures.id(height - 1))))

  private def populatedState(height: Int, version: Long, rollupCount: Int): CommittedSyncState = {
    val entries = SyncFixtures.plasmaEntries(2, 32)
    val minerDictionary = PlasmaDictionary.empty()
    minerDictionary.insert(entries(1))
    val tracked = (0 until rollupCount).map { index =>
      val dictionary = PlasmaDictionary.empty()
      dictionary.insert(entries.head)
      val rollupId = SyncFixtures.id(5000 + index)
      val utxoId = SyncFixtures.id(5100 + index * 1000 + height)
      rollupId -> NISPTree(dictionary, 1, BigInt(50), Some(90L), 1000000L, 90, hasMiner = true,
        LFSMPhase.HOLDING, Set("miner"), evaluated = true, rollupId, utxoId)
    }
    val minerTree = MinerTree.initialState.copy(dictionary = minerDictionary, numMiners = 1,
      hasMiner = true, syncHeight = height, savedHeight = height)
    CommittedSyncState(SyncCursor(height, SyncFixtures.id(height), SyncFixtures.id(height - 1)),
      version, tracked.toMap, tracked.map { case (id, tree) => tree.utxoId -> id }.toMap,
      tracked.zipWithIndex.map { case ((id, _), index) => id -> SyncFixtures.id(5200 + index) }.toMap,
      minerTree,
      Some(ErgoId.create(SyncFixtures.id(6000))))
  }
}
