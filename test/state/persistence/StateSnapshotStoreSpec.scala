package state.persistence

import lfsm.LFSMPhase
import lfsm.states.{AuthenticatedDictionary, AuthenticatedDictionaryView, DictionaryManifestHeader, MinerDictionary, PlasmaDictionary, Rollup, RollupInfoState}
import org.ergoplatform.appkit.ErgoValue
import org.ergoplatform.sdk.ErgoId
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import state.messages.SyncMessages.SyncCursor
import state.synchronization.{CommittedSyncMetadata, CommittedSyncState, DictionaryId, QuarantineFault}
import storage.{InMemoryKeyValueStore, KeyValueMutation, KeyValueReadView, KeyValueStore,
  LevelDbKeyValueStore, StorageBackend, StoreError, WriteDurability}
import support.SyncFixtures
import sigma.AvlTree
import sigma.data.AvlTreeFlags
import work.lithos.plasma.PlasmaParameters
import work.lithos.plasma.collections.Manifest

import java.nio.file.{Files, Path}
import java.io.IOException
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

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

  /**
   * The three values and the phase that says what they mean travel together. A payout snapshot read
   * back as a holding one would turn a reward into a period start and put the rollup out of step
   * with the chain from the next transition on.
   */
  it should "round-trip every rollup phase's three state values" in {
    val base = populatedSnapshot(height = 100, version = 8L)
    val id = base.state.rollups.keys.head
    val phases = Seq(
      RollupInfoState.holding(140L, 100L, 6000000L),
      RollupInfoState.evaluation(500L, 100L, 6000000L),
      RollupInfoState.payout(2994000000L, 750L, 6000000L))

    phases.foreach { state =>
      val tree = base.state.rollups(id).copy(state = state, value = 3000000000L)
      val snapshot = base.copy(
        state = base.state.copy(rollups = base.state.rollups.updated(id, tree)))
      val encoded = StateSnapshotCodec.encodeMeta(plan(snapshot), identity, MaxEntry).toOption.get
      val restored = StateSnapshotCodec.decodeMeta(encoded, identity).toOption.get

      val read = restored.metadata.rollups(id)
      read.state shouldEqual state
      read.state.values shouldEqual state.values
      read.phase shouldEqual state.phase
      read.value shouldEqual 3000000000L
    }
  }

  /**
   * A rollup box's registers and tokens both changed, so nothing written before this schema can be
   * decoded into fields that still mean what they say. Failing sends the caller to a rebuild.
   */
  it should "refuse a snapshot written under the previous schema" in {
    val encoded = StateSnapshotCodec.encodeMeta(
      plan(populatedSnapshot(100, 8L)), identity, MaxEntry).toOption.get
    // The version is the second int of the sealed payload's body; the seal is re-applied by hand.
    val previous = withSchemaVersion(encoded, 1)

    StateSnapshotCodec.decodeMeta(previous, identity).left.toOption.get should
      include("unsupported snapshot schema version")
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

  it should "replace a corrupt shared blob when the same dictionary is checkpointed again" in {
    val (keyValueStore, snapshots) = store(retention = 2)
    val persisted = largeDictionarySnapshot(100)
    val checkpoint = plan(persisted)
    snapshots.save(checkpoint, MaxEntry) shouldEqual Right(())
    val digest = persisted.state.minerTree.metadata.dictionaryDigest
    val subtreeKeys = keyValueStore.scanKeys(bytes(s"sync/snapshot/blob/$digest/s/"))
      .toOption.get.sortBy(text)
    subtreeKeys.size should be > 1

    val corrupt = keyValueStore.get(subtreeKeys.head).toOption.flatten.get.clone()
    corrupt(corrupt.length / 2) = (corrupt(corrupt.length / 2) ^ 0x01).toByte
    keyValueStore.put(subtreeKeys.head, corrupt) shouldEqual Right(())
    snapshots.loadDictionary(digest).isLeft shouldBe true

    // A successful repair/checkpoint must not keep reusing bytes already proven corrupt.
    snapshots.save(checkpoint, MaxEntry) shouldEqual Right(())
    snapshots.loadDictionary(digest).toOption.get.digest should
      contain theSameElementsInOrderAs persisted.state.minerTree.dictionary.digest
    snapshots.close() shouldEqual Right(())
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

  it should "report success after the checkpoint commits even when post-commit cleanup fails" in {
    val underlying = new InMemoryKeyValueStore
    val keyValueStore = new CleanupFailingStore(underlying)
    val snapshots = new LevelDbStateSnapshotStore(keyValueStore, retention = 2, identity)
    val persisted = populatedSnapshot(100, 8L)

    snapshots.save(plan(persisted), MaxEntry) shouldEqual Right(())
    keyValueStore.cleanupFailures shouldEqual 1
    val restored = snapshots.load(snapshots.generationKeys().toOption.get.head).toOption.get
    restored.state.cursor shouldEqual persisted.state.cursor
    snapshots.close() shouldEqual Right(())
  }

  it should "exclude checkpoint cleanup while a dictionary reconstruction is reading its blob" in {
    implicit val executionContext: ExecutionContext = ExecutionContext.global
    val underlying = new InMemoryKeyValueStore
    val keyValueStore = new BlockingReadStore(underlying)
    val snapshots = new LevelDbStateSnapshotStore(keyValueStore, retention = 2, identity)
    val first = largeDictionarySnapshot(100)
    snapshots.save(plan(first), MaxEntry) shouldEqual Right(())
    val digest = first.state.minerTree.metadata.dictionaryDigest

    keyValueStore.blockSubtreeReads = true
    val loading = Future(snapshots.loadDictionary(digest))
    keyValueStore.readEntered.await(5, TimeUnit.SECONDS) shouldBe true

    keyValueStore.watchWrites = true
    val saving = Future(snapshots.save(plan(largeDictionarySnapshot(101)), MaxEntry))
    keyValueStore.writeEntered.await(250, TimeUnit.MILLISECONDS) shouldBe false

    keyValueStore.releaseRead.countDown()
    Await.result(loading, 10.seconds).isRight shouldBe true
    Await.result(saving, 10.seconds) shouldEqual Right(())
    keyValueStore.writeEntered.getCount shouldEqual 0L
    snapshots.close() shouldEqual Right(())
  }

  it should "exclude store close while a dictionary reconstruction owns the read lifecycle" in {
    implicit val executionContext: ExecutionContext = ExecutionContext.global
    val underlying = new InMemoryKeyValueStore
    val keyValueStore = new BlockingReadStore(underlying)
    val snapshots = new LevelDbStateSnapshotStore(keyValueStore, retention = 2, identity)
    val persisted = largeDictionarySnapshot(100)
    snapshots.save(plan(persisted), MaxEntry) shouldEqual Right(())

    keyValueStore.blockSubtreeReads = true
    val loading = Future(snapshots.loadDictionary(
      persisted.state.minerTree.metadata.dictionaryDigest))
    keyValueStore.readEntered.await(5, TimeUnit.SECONDS) shouldBe true

    val closing = Future(snapshots.close())
    keyValueStore.closeEntered.await(250, TimeUnit.MILLISECONDS) shouldBe false

    keyValueStore.releaseRead.countDown()
    Await.result(loading, 10.seconds).isRight shouldBe true
    Await.result(closing, 10.seconds) shouldEqual Right(())
    keyValueStore.closeEntered.getCount shouldEqual 0L
  }

  it should "recover after a failure at every physical write before the generation commit" in {
    val baselineStore = new CommitCountingStore(new InMemoryKeyValueStore)
    val baseline = new LevelDbStateSnapshotStore(baselineStore, retention = 2, identity)
    baseline.save(plan(populatedSnapshot(100, 8L)), MaxEntry) shouldEqual Right(())
    val commitWrite = baselineStore.pointerWrite.getOrElse(
      fail("the checkpoint pointer was never written"))
    baseline.close() shouldEqual Right(())

    (1 to commitWrite).foreach { failedWrite =>
      withClue(s"physical write $failedWrite of $commitWrite: ") {
        val keyValues = new CommitCountingStore(new InMemoryKeyValueStore,
          failAt = Some(failedWrite))
        val snapshots = new LevelDbStateSnapshotStore(keyValues, retention = 2, identity)
        val checkpoint = plan(populatedSnapshot(100, 8L))

        snapshots.save(checkpoint, MaxEntry).isLeft shouldBe true
        snapshots.generationKeys().toOption.get shouldBe empty

        keyValues.failAt = None
        snapshots.save(checkpoint, MaxEntry) shouldEqual Right(())
        val restored = snapshots.load(snapshots.generationKeys().toOption.get.head).toOption.get
        restored.state.cursor shouldEqual checkpoint.metadata.cursor
        snapshots.loadDictionary(checkpoint.metadata.minerDictionary.dictionaryDigest).isRight shouldBe true
        snapshots.close() shouldEqual Right(())
      }
    }
  }

  /**
   * Deciding what to collect means decoding every retained generation and scanning every blob key.
   * Held under the lifecycle write lock, that whole scan sat inside the window in which no
   * dictionary can be materialized — including the reconstruction a canonical block is waiting on,
   * whose caller gives up after thirty seconds.
   */
  it should "let a dictionary load run while a committed checkpoint is still cleaning up" in {
    val (keyValueStore, _) = store(retention = 2)
    val blocking = new BlockingCleanupStore(keyValueStore)
    val snapshots = new LevelDbStateSnapshotStore(blocking, 2, identity)
    val first = plan(populatedSnapshot(height = 100, version = 8L))
    snapshots.save(first, MaxEntry) shouldEqual Right(())
    val digest = first.metadata.minerDictionary.dictionaryDigest

    implicit val ec: ExecutionContext = ExecutionContext.global
    blocking.blockCleanup = true
    val saving = Future(snapshots.save(plan(populatedSnapshot(height = 101, version = 9L)), MaxEntry))
    blocking.cleanupEntered.await(10, TimeUnit.SECONDS) shouldBe true

    // The generation pointer is already committed here; only best-effort cleanup is still running.
    val loading = Future(snapshots.loadDictionary(digest))
    Await.result(loading, 10.seconds).isRight shouldBe true

    blocking.releaseCleanup.countDown()
    Await.result(saving, 10.seconds) shouldEqual Right(())
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
      rollupId -> Rollup(dictionary, 1, BigInt(50), RollupInfoState.holding(90L, 90L, 0L),
        1000000L, 90, hasMiner = true, evaluated = true, blockId = rollupId, utxoId = utxoId)
    }
    val minerTree = MinerDictionary.initialState.copy(dictionary = minerDictionary, numMiners = 1,
      hasMiner = true, syncHeight = height, savedHeight = height)
    CommittedSyncState(SyncCursor(height, SyncFixtures.id(height), SyncFixtures.id(height - 1)),
      version, tracked.toMap, tracked.map { case (id, tree) => tree.utxoId -> id }.toMap,
      tracked.zipWithIndex.map { case ((id, _), index) => id -> SyncFixtures.id(5200 + index) }.toMap,
      minerTree, Some(ErgoId.create(SyncFixtures.id(6000))))
  }

  /**
   * The same envelope with a different schema version in its payload.
   *
   * A sealed entry is [magic][payload length][payload][checksum length][checksum], and the payload
   * opens with its own magic then the version, so this rewrites four bytes and re-seals.
   */
  private def withSchemaVersion(encoded: Array[Byte], version: Int): Array[Byte] = {
    val payloadStart = 8
    val payloadLength = java.nio.ByteBuffer.wrap(encoded, 4, 4).getInt
    val payload = encoded.slice(payloadStart, payloadStart + payloadLength)
    java.nio.ByteBuffer.wrap(payload, 4, 4).putInt(version)
    val checksum = scorex.crypto.hash.Blake2b256.hash(payload)
    val out = java.nio.ByteBuffer.allocate(4 + 4 + payload.length + 4 + checksum.length)
    out.putInt(java.nio.ByteBuffer.wrap(encoded, 0, 4).getInt)
    out.putInt(payload.length)
    out.put(payload)
    out.putInt(checksum.length)
    out.put(checksum)
    out.array()
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
    override val estimatedHeapBytes: Long = 4096L + subtrees.toLong * 512L
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

  /** Blocks inside the post-commit blob collection, which is the only generation-prefix scan. */
  private final class BlockingCleanupStore(underlying: KeyValueStore)
    extends DelegatingStore(underlying) {
    val cleanupEntered = new CountDownLatch(1)
    val releaseCleanup = new CountDownLatch(1)
    @volatile var blockCleanup = false

    override def scanPrefix(prefix: Array[Byte]):
      Either[StoreError, Vector[(Array[Byte], Array[Byte])]] = {
      if (blockCleanup && text(prefix) == "sync/snapshot/gen/") {
        blockCleanup = false
        cleanupEntered.countDown()
        releaseCleanup.await(10, TimeUnit.SECONDS)
      }
      super.scanPrefix(prefix)
    }
  }

  private class DelegatingStore(underlying: KeyValueStore) extends KeyValueStore {
    override def path: Path = underlying.path
    override def backend: StorageBackend = underlying.backend
    override def get(key: Array[Byte]): Either[StoreError, Option[Array[Byte]]] = underlying.get(key)
    override def scanPrefix(prefix: Array[Byte]): Either[StoreError, Vector[(Array[Byte], Array[Byte])]] =
      underlying.scanPrefix(prefix)
    override def scanKeys(prefix: Array[Byte]): Either[StoreError, Vector[Array[Byte]]] =
      underlying.scanKeys(prefix)
    override def write(mutations: Seq[KeyValueMutation], durability: WriteDurability):
      Either[StoreError, Unit] = underlying.write(mutations, durability)
    override def readSnapshot[A](f: KeyValueReadView => Either[StoreError, A]): Either[StoreError, A] =
      underlying.readSnapshot(f)
    override def close(): Either[StoreError, Unit] = underlying.close()
  }

  private final class CleanupFailingStore(underlying: KeyValueStore)
    extends DelegatingStore(underlying) {
    private var pointerCommitted = false
    private var failures = 0
    def cleanupFailures: Int = synchronized(failures)

    override def write(mutations: Seq[KeyValueMutation], durability: WriteDurability):
      Either[StoreError, Unit] = {
      val result = super.write(mutations, durability)
      if (result.isRight && mutations.exists {
        case KeyValueMutation.Put(key, _) => text(key) == "sync/snapshot/current"
        case _ => false
      }) synchronized(pointerCommitted = true)
      result
    }

    override def scanKeys(prefix: Array[Byte]): Either[StoreError, Vector[Array[Byte]]] = synchronized {
      if (pointerCommitted && failures == 0 && text(prefix) == "sync/snapshot/gen/") {
        failures += 1
        Left(StoreError.ReadFailed(path, "injected post-commit prune", new IOException("cleanup failed")))
      } else super.scanKeys(prefix)
    }
  }

  private final class BlockingReadStore(underlying: KeyValueStore)
    extends DelegatingStore(underlying) {
    val readEntered = new CountDownLatch(1)
    val releaseRead = new CountDownLatch(1)
    val writeEntered = new CountDownLatch(1)
    val closeEntered = new CountDownLatch(1)
    @volatile var blockSubtreeReads = false
    @volatile var watchWrites = false

    override def get(key: Array[Byte]): Either[StoreError, Option[Array[Byte]]] = {
      if (blockSubtreeReads && text(key).contains("/s/")) {
        blockSubtreeReads = false
        readEntered.countDown()
        releaseRead.await(10, TimeUnit.SECONDS)
      }
      super.get(key)
    }

    override def write(mutations: Seq[KeyValueMutation], durability: WriteDurability):
      Either[StoreError, Unit] = {
      if (watchWrites) writeEntered.countDown()
      super.write(mutations, durability)
    }

    override def close(): Either[StoreError, Unit] = {
      closeEntered.countDown()
      super.close()
    }
  }

  private final class CommitCountingStore(underlying: KeyValueStore,
                                          @volatile var failAt: Option[Int] = None)
    extends DelegatingStore(underlying) {
    private var writes = 0
    private var pointerAt = Option.empty[Int]

    def pointerWrite: Option[Int] = synchronized(pointerAt)

    override def write(mutations: Seq[KeyValueMutation], durability: WriteDurability):
      Either[StoreError, Unit] = synchronized {
      writes += 1
      if (mutations.exists {
        case KeyValueMutation.Put(key, _) => text(key) == "sync/snapshot/current"
        case _ => false
      }) pointerAt = Some(writes)
      if (failAt.contains(writes))
        Left(StoreError.WriteFailed(path,
          new IOException(s"injected failure at physical write $writes")))
      else super.write(mutations, durability)
    }
  }
}
