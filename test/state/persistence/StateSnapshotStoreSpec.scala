package state.persistence

import lfsm.LFSMPhase
import lfsm.states.{AuthenticatedDictionary, AuthenticatedDictionaryView, DictionaryManifestHeader, MinerDictionary, PlasmaDictionary, Rollup}
import org.ergoplatform.appkit.ErgoValue
import org.ergoplatform.sdk.ErgoId
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import state.messages.SyncMessages.SyncCursor
import state.synchronization.{CommittedSyncMetadata, CommittedSyncState, DictionaryId, QuarantineFault}
import storage.LevelDbKeyValueStore
import storage.InMemoryKeyValueStore
import support.SyncFixtures
import sigma.AvlTree
import sigma.data.AvlTreeFlags
import work.lithos.plasma.PlasmaParameters
import work.lithos.plasma.collections.Manifest

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

  "StateSnapshotCodec" should "round-trip only checkpoint metadata and dictionary digests" in {
    val persisted = populatedSnapshot(height = 100, version = 8L)
    val checkpoint = plan(persisted)

    val encoded = StateSnapshotCodec.encodeMeta(checkpoint, identity, MaxEntry).toOption.get
    val restored = StateSnapshotCodec.decodeMeta(encoded, identity).toOption.get

    restored.metadata shouldEqual checkpoint.metadata
    restored.recentCursors shouldEqual checkpoint.recentCursors
    restored.dictionaryDigests shouldEqual checkpoint.dictionaries.values.map(_.expectedDigest).toSet
  }

  it should "round-trip quarantine deadlines and attempt counts" in {
    val base = populatedSnapshot(100, 8L)
    val fault = QuarantineFault("dropped-rollup", 88, SyncFixtures.id(4999), "missing context variable 2",
      retryable = true, attempts = 2, removalHeight = Some(180), removalWarningLogged = true)
    val checkpoint = plan(base.copy(state = base.state.copy(
      minerDictionaryFault = Some("dictionary fault"),
      quarantined = Map(fault.rollupId -> fault))))

    val encoded = StateSnapshotCodec.encodeMeta(checkpoint, identity, MaxEntry).toOption.get
    val restored = StateSnapshotCodec.decodeMeta(encoded, identity).toOption.get.metadata

    restored.minerDictionaryFault shouldEqual Some("dictionary fault")
    restored.quarantined shouldEqual Map(fault.rollupId -> fault)
  }

  it should "reject corrupt metadata and a different protocol identity" in {
    val encoded = StateSnapshotCodec.encodeMeta(plan(populatedSnapshot(100, 8L)), identity,
      MaxEntry).toOption.get
    val corrupt = encoded.clone()
    corrupt(corrupt.length / 2) = (corrupt(corrupt.length / 2) ^ 0x01).toByte

    StateSnapshotCodec.decodeMeta(corrupt, identity).isLeft shouldBe true
    StateSnapshotCodec.decodeMeta(encoded, identity.copy(localMinerHash = "other"))
      .left.toOption.get should include("does not match")
  }

  it should "bound each physical entry including its checksum envelope" in {
    val refused = StateSnapshotCodec.encodeMeta(plan(populatedSnapshot(100, 8L)), identity, 64)

    refused.left.toOption.get should include("over the configured 64 limit")
  }

  it should "refuse a source whose digest does not name the entity metadata" in {
    val (_, snapshots) = store(retention = 2)
    val checkpoint = plan(populatedSnapshot(100, 8L))
    val source = checkpoint.dictionaries(DictionaryId.Miner)
    val mismatched = checkpoint.copy(dictionaries = checkpoint.dictionaries +
      (DictionaryId.Miner -> source.copy(expectedDigest = "00" * 32)))

    snapshots.save(mismatched, MaxEntry).left.toOption.get.message should include(
      "source digest does not match")
    snapshots.generationKeys().toOption.get shouldBe empty
    snapshots.close() shouldEqual Right(())
  }

  it should "reject a legacy 32-byte digest that omits the AVL height byte" in {
    val checkpoint = plan(populatedSnapshot(100, 8L))
    val legacy = "00" * 32
    val metadata = checkpoint.metadata.copy(minerDictionary =
      checkpoint.metadata.minerDictionary.copy(dictionaryDigest = legacy))
    val encoded = StateSnapshotCodec.encodeMeta(checkpoint.copy(metadata = metadata), identity,
      MaxEntry).toOption.get

    StateSnapshotCodec.decodeMeta(encoded, identity).left.toOption.get should include(
      "invalid dictionary digest")
  }

  "LevelDbStateSnapshotStore" should "restore digest-only state and load a requested dictionary" in {
    val (_, snapshots) = store(retention = 2)
    val persisted = populatedSnapshot(100, 8L)

    snapshots.save(plan(persisted), MaxEntry) shouldEqual Right(())
    val restored = snapshots.load(snapshots.generationKeys().toOption.get.head).toOption.get

    restored.state.rollups.values.head.dictionary.materialized shouldBe false
    restored.state.minerTree.dictionary.materialized shouldBe false
    restored.state.rollups.values.head.metadata shouldEqual persisted.state.rollups.values.head.metadata

    val digest = persisted.state.rollups.values.head.metadata.dictionaryDigest
    val dictionary = snapshots.loadDictionary(digest).toOption.get
    dictionary.digest should contain theSameElementsInOrderAs
      persisted.state.rollups.values.head.dictionary.digest

    val (nextKey, nextValue) = SyncFixtures.plasmaEntries(3, 32).last
    val originalNext = persisted.state.rollups.values.head.dictionary.copy().insert(nextKey -> nextValue)
    val restoredNext = dictionary.copy().insert(nextKey -> nextValue)
    restoredNext.proof.ergoValue.toHex shouldEqual originalNext.proof.ergoValue.toHex
    snapshots.close() shouldEqual Right(())
  }

  it should "reuse one content-addressed blob across identical rollups and generations" in {
    val (keyValueStore, snapshots) = store(retention = 2)
    val first = populatedSnapshot(100, 8L, rollups = 4)
    val second = populatedSnapshot(101, 9L, rollups = 4)

    snapshots.save(plan(first), MaxEntry) shouldEqual Right(())
    val blobKeysAfterFirst = keyValueStore.scanKeys(bytes("sync/snapshot/blob/" )).toOption.get
    snapshots.save(plan(second), MaxEntry) shouldEqual Right(())
    val blobKeysAfterSecond = keyValueStore.scanKeys(bytes("sync/snapshot/blob/" )).toOption.get

    // Every rollup has identical dictionary content, and the Miner Dictionary is identical too.
    blobKeysAfterSecond.size shouldEqual blobKeysAfterFirst.size
    blobKeysAfterSecond.count(key => text(key).endsWith("/header")) shouldEqual 2
    keyValueStore.scanKeys(bytes("sync/snapshot/gen/")).toOption.get
      .forall(key => text(key).endsWith("/meta")) shouldBe true
    snapshots.close() shouldEqual Right(())
  }

  it should "consume and persist one serialized AVL subtree at a time before committing its header" in {
    val keyValues = new InMemoryKeyValueStore
    val snapshots = new LevelDbStateSnapshotStore(keyValues, retention = 2, identity)
    val dictionary = new StreamingDictionary(3)
    val base = populatedSnapshot(100, 8L, rollups = 0)
    val state = base.state.copy(minerTree = base.state.minerTree.copy(dictionary = dictionary))

    snapshots.save(plan(base.copy(state = state)), MaxEntry) shouldEqual Right(())

    val batches = keyValues.recordedBatches
    val keys = batches.flatten.collect { case storage.KeyValueMutation.Put(key, _) => text(key) }
    keys.count(_.contains("/s/")) shouldEqual 3
    val headerIndex = keys.indexWhere(_.endsWith("/header"))
    headerIndex should be > keys.lastIndexWhere(_.contains("/s/"))
    snapshots.close() shouldEqual Right(())
  }

  it should "recover through corrupt newest metadata" in {
    val (keyValueStore, snapshots) = store(retention = 2)
    val first = populatedSnapshot(100, 8L)
    val second = populatedSnapshot(101, 9L)
    snapshots.save(plan(first), MaxEntry) shouldEqual Right(())
    snapshots.save(plan(second), MaxEntry) shouldEqual Right(())

    val newest = keyValueStore.scanPrefix(bytes("sync/snapshot/gen/" )).toOption.get
      .maxBy(entry => text(entry._1))
    val corrupt = newest._2.clone()
    corrupt(corrupt.length / 2) = (corrupt(corrupt.length / 2) ^ 0x01).toByte
    keyValueStore.put(newest._1, corrupt)

    firstUsable(snapshots).map(_.state.cursor) shouldEqual Some(first.state.cursor)
    snapshots.close() shouldEqual Right(())
  }

  it should "refuse a generation whose referenced dictionary header is absent" in {
    val (keyValueStore, snapshots) = store(retention = 2)
    val persisted = populatedSnapshot(100, 8L)
    snapshots.save(plan(persisted), MaxEntry) shouldEqual Right(())

    val header = keyValueStore.scanKeys(bytes("sync/snapshot/blob/" )).toOption.get
      .find(key => text(key).endsWith("/header")).get
    keyValueStore.delete(header)

    snapshots.load(snapshots.generationKeys().toOption.get.head)
      .left.toOption.get.message should include("no committed header")
    snapshots.close() shouldEqual Right(())
  }

  it should "refuse a dictionary with a missing serialized subtree" in {
    val (keyValueStore, snapshots) = store(retention = 2)
    val persisted = largeDictionarySnapshot(100)
    snapshots.save(plan(persisted), MaxEntry) shouldEqual Right(())
    val digest = persisted.state.minerTree.metadata.dictionaryDigest
    val subtreeKeys = keyValueStore.scanKeys(bytes(s"sync/snapshot/blob/$digest/s/"))
      .toOption.get.sortBy(text)
    subtreeKeys.size should be > 1
    keyValueStore.delete(subtreeKeys.head)

    snapshots.loadDictionary(digest).left.toOption.get.message should include("missing subtree 0")
    snapshots.close() shouldEqual Right(())
  }

  it should "refuse subtree bytes stored under another subtree index" in {
    val (keyValueStore, snapshots) = store(retention = 2)
    val persisted = largeDictionarySnapshot(100)
    snapshots.save(plan(persisted), MaxEntry) shouldEqual Right(())
    val digest = persisted.state.minerTree.metadata.dictionaryDigest
    val subtreeKeys = keyValueStore.scanKeys(bytes(s"sync/snapshot/blob/$digest/s/"))
      .toOption.get.sortBy(text)
    subtreeKeys.size should be > 1
    val encodedZero = keyValueStore.get(subtreeKeys.head).toOption.flatten.get
    keyValueStore.put(subtreeKeys(1), encodedZero)

    val error = snapshots.loadDictionary(digest).left.toOption.get.message
    snapshots.close() shouldEqual Right(())
    error should include("does not name index 1")
  }

  it should "prune old generations and dictionaries unreachable from the retained set" in {
    val (keyValueStore, snapshots) = store(retention = 2)
    (100 to 103).foreach { height =>
      val persisted = populatedSnapshot(height, height.toLong, valueSeed = height)
      snapshots.save(plan(persisted), MaxEntry) shouldEqual Right(())
    }

    snapshots.generationKeys().toOption.get.size shouldEqual 2
    val retainedDigests = snapshots.generationKeys().toOption.get
      .flatMap(key => snapshots.load(key).toOption.toSeq)
      .flatMap(value => value.state.rollups.values.map(_.metadata.dictionaryDigest) ++
        Seq(value.state.minerTree.metadata.dictionaryDigest)).toSet
    val storedDigests = keyValueStore.scanKeys(bytes("sync/snapshot/blob/" )).toOption.get
      .filter(key => text(key).endsWith("/header"))
      .map(key => text(key).stripPrefix("sync/snapshot/blob/").stripSuffix("/header")).toSet

    retainedDigests.subsetOf(storedDigests) shouldBe true
    storedDigests shouldEqual retainedDigests
    snapshots.close() shouldEqual Right(())
  }

  private def store(retention: Int): (LevelDbKeyValueStore, LevelDbStateSnapshotStore) = {
    val directory = Files.createTempDirectory("lithos-sync-snapshot-")
    directories :+= directory
    val keyValueStore = LevelDbKeyValueStore.openOrThrow(directory).asInstanceOf[LevelDbKeyValueStore]
    keyValueStore -> new LevelDbStateSnapshotStore(keyValueStore, retention, identity)
  }

  private def plan(persisted: PersistedSyncState): SnapshotCheckpoint = {
    val state = persisted.state
    val sources: Map[DictionaryId, SnapshotDictionarySource] = state.rollups.map { case (id, rollup) =>
      (DictionaryId.Rollup(id): DictionaryId) -> source(rollup.dictionary)
    } + (DictionaryId.Miner -> source(state.minerTree.dictionary))
    SnapshotCheckpoint(CommittedSyncMetadata.from(state), persisted.recentCursors, sources)
  }

  private def source(dictionary: lfsm.states.AuthenticatedDictionaryView): SnapshotDictionarySource =
    SnapshotDictionarySource(org.bouncycastle.util.encoders.Hex.toHexString(dictionary.digest),
      dictionary.flags, dictionary.parameters, SnapshotDictionaryBase.Materialized(dictionary), Vector.empty)

  private def firstUsable(store: LevelDbStateSnapshotStore): Option[PersistedSyncState] =
    store.generationKeys().toOption.toSeq.flatten
      .flatMap(key => store.load(key).toOption).headOption

  private def populatedSnapshot(height: Int,
                                version: Long,
                                rollups: Int = 1,
                                valueSeed: Int = 1): PersistedSyncState =
    PersistedSyncState(populatedState(height, version, rollups, valueSeed),
      Vector(
        SyncCursor(height - 2, SyncFixtures.id(height - 2), SyncFixtures.id(height - 3)),
        SyncCursor(height - 1, SyncFixtures.id(height - 1), SyncFixtures.id(height - 2)),
        SyncCursor(height, SyncFixtures.id(height), SyncFixtures.id(height - 1))))

  private def largeDictionarySnapshot(height: Int): PersistedSyncState = {
    val dictionary = PlasmaDictionary.empty()
    val entries = SyncFixtures.plasmaEntries(2051, 32)
    dictionary.insert(entries: _*)
    val base = populatedSnapshot(height, height.toLong, rollups = 0)
    base.copy(state = base.state.copy(minerTree = base.state.minerTree.copy(
      dictionary = dictionary, numMiners = entries.size, syncHeight = height, savedHeight = height)))
  }

  private def populatedState(height: Int,
                             version: Long,
                             rollupCount: Int,
                             valueSeed: Int): CommittedSyncState = {
    val entries = SyncFixtures.plasmaEntries(valueSeed + 2, 32).drop(valueSeed).take(2)
    val minerDictionary = PlasmaDictionary.empty()
    minerDictionary.insert(entries(1))
    val tracked = (0 until rollupCount).map { index =>
      val dictionary = PlasmaDictionary.empty()
      dictionary.insert(entries.head)
      val rollupId = SyncFixtures.id(5000 + index)
      val utxoId = SyncFixtures.id(5100 + index * 1000 + height)
      rollupId -> Rollup(dictionary, 1, BigInt(50), Some(90L), 1000000L, 90, hasMiner = true,
        LFSMPhase.HOLDING, evaluated = true, rollupId, utxoId)
    }
    val minerTree = MinerDictionary.initialState.copy(dictionary = minerDictionary, numMiners = 1,
      hasMiner = true, syncHeight = height, savedHeight = height)
    CommittedSyncState(SyncCursor(height, SyncFixtures.id(height), SyncFixtures.id(height - 1)),
      version, tracked.toMap, tracked.map { case (id, tree) => tree.utxoId -> id }.toMap,
      tracked.zipWithIndex.map { case ((id, _), index) => id -> SyncFixtures.id(5200 + index) }.toMap,
      minerTree, Some(ErgoId.create(SyncFixtures.id(6000))))
  }

  private def bytes(value: String): Array[Byte] = value.getBytes("UTF-8")
  private def text(value: Array[Byte]): String = new String(value, "UTF-8")

  private final class StreamingDictionary(subtrees: Int) extends AuthenticatedDictionaryView {
    // An AVL digest is the 32-byte root label followed by its one-byte tree height.
    private val ownedDigest = scorex.crypto.hash.Blake2b256.hash(bytes("streaming-dictionary")) :+ 0.toByte
    override def digest: Array[Byte] = ownedDigest.clone()
    override val flags: AvlTreeFlags = AvlTreeFlags.AllOperationsAllowed
    override val parameters: PlasmaParameters = PlasmaParameters.default
    override val materialized: Boolean = true
    override def ergoValue: ErgoValue[AvlTree] = unsupported()
    override def copy(): AuthenticatedDictionary = unsupported()
    override def foreachKey(visit: Array[Byte] => Unit): Unit = unsupported()
    override def getManifest(): Manifest = unsupported()
    override def writeManifest(subtreeDepth: Int)
                              (writeSubtree: (Int, Array[Byte]) => Unit): DictionaryManifestHeader = {
      (0 until subtrees).foreach(index => writeSubtree(index, Array.fill(128)((index + 1).toByte)))
      DictionaryManifestHeader(flags, parameters, digest, bytes("manifest"), subtrees)
    }
    private def unsupported[A](): A = throw new UnsupportedOperationException("not needed by this test")
  }
}
