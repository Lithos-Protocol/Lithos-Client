package state.persistence

import lfsm.LFSMPhase
import lfsm.states.{MinerDictionary, Rollup, PlasmaDictionary}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import state.messages.SyncMessages.SyncCursor
import state.synchronization.{CommittedSyncMetadata, CommittedSyncState, DictionaryId}
import storage._
import support.{ReducerFixtures, SyncFixtures}

import java.nio.file.Path

/** Proves generation metadata and every referenced dictionary header use one database view. */
class SnapshotReadConsistencySpec extends AnyFlatSpec with Matchers {

  private val identity = StateSnapshotIdentity(ReducerFixtures.protocol())
  private val MaxEntry = 64 * 1024 * 1024

  "LevelDbStateSnapshotStore" should "finish an old generation while concurrent saves prune it" in {
    val keyValues = new InterleavingStore
    val snapshots = new LevelDbStateSnapshotStore(keyValues, retention = 2, identity)
    val first = persisted(100)
    save(snapshots, first) shouldEqual Right(())
    val firstKey = snapshots.generationKeys().toOption.get.head

    // The callback runs after metadata was obtained from the read view and before its headers are read.
    // Two later saves prune the first generation from the live database.
    keyValues.afterNextMetaRead {
      save(snapshots, persisted(101)) shouldEqual Right(())
      save(snapshots, persisted(102)) shouldEqual Right(())
    }

    val loaded = snapshots.load(firstKey).toOption.get

    loaded.state.cursor shouldEqual first.state.cursor
    loaded.state.rollups.keySet shouldEqual first.state.rollups.keySet
    snapshots.generationKeys().toOption.get.exists(java.util.Arrays.equals(_, firstKey)) shouldBe false
  }

  private def save(store: LevelDbStateSnapshotStore,
                   value: PersistedSyncState): Either[SnapshotError, Unit] =
    store.save(plan(value), MaxEntry)

  private def plan(value: PersistedSyncState): SnapshotCheckpoint = {
    val state = value.state
    def source(dictionary: lfsm.states.AuthenticatedDictionaryView) =
      SnapshotDictionarySource(org.bouncycastle.util.encoders.Hex.toHexString(dictionary.digest),
        dictionary.flags, dictionary.parameters, SnapshotDictionaryBase.Materialized(dictionary), Vector.empty)
    val dictionaries: Map[DictionaryId, SnapshotDictionarySource] = state.rollups.map { case (id, rollup) =>
      (DictionaryId.Rollup(id): DictionaryId) -> source(rollup.dictionary)
    } + (DictionaryId.Miner -> source(state.minerTree.dictionary))
    SnapshotCheckpoint(CommittedSyncMetadata.from(state), value.recentCursors, dictionaries)
  }

  private def persisted(height: Int): PersistedSyncState = {
    val rollupId = SyncFixtures.id(5500)
    val utxoId = SyncFixtures.id(5600 + height)
    val origin = SyncFixtures.id(5700)
    val tree = Rollup(PlasmaDictionary.empty(), 0, BigInt(0), Some(90L), 1000000L, 90,
      hasMiner = false, LFSMPhase.HOLDING, evaluated = false, rollupId, utxoId)
    val cursor = SyncCursor(height, SyncFixtures.id(height), SyncFixtures.id(height - 1))
    PersistedSyncState(
      CommittedSyncState(cursor, height.toLong, Map(rollupId -> tree), Map(utxoId -> rollupId),
        Map(rollupId -> origin), MinerDictionary.initialState, None),
      Vector(cursor))
  }

  /**
   * Its read view is immutable, while the hook mutates the live delegate exactly between metadata and
   * header reads. An implementation that falls back to KeyValueStore.get observes the prune and fails.
   */
  private final class InterleavingStore extends KeyValueStore {
    private val delegate = new InMemoryKeyValueStore
    private var hook = Option.empty[() => Unit]

    override def path: Path = delegate.path
    override def backend: StorageBackend = delegate.backend

    def afterNextMetaRead(action: => Unit): Unit = synchronized {
      hook = Some(() => action)
    }

    override def get(key: Array[Byte]): Either[StoreError, Option[Array[Byte]]] = delegate.get(key)
    override def scanPrefix(prefix: Array[Byte]) = delegate.scanPrefix(prefix)
    override def scanKeys(prefix: Array[Byte]) = delegate.scanKeys(prefix)
    override def write(mutations: Seq[KeyValueMutation], durability: WriteDurability) =
      delegate.write(mutations, durability)
    override def close(): Either[StoreError, Unit] = delegate.close()

    override def readSnapshot[A](
      f: KeyValueReadView => Either[StoreError, A]): Either[StoreError, A] =
      delegate.readSnapshot { view =>
        f(new KeyValueReadView {
          override def get(key: Array[Byte]): Either[StoreError, Option[Array[Byte]]] = {
            val result = view.get(key)
            if (new String(key, "UTF-8").endsWith("/meta")) fire()
            result
          }
          override def scanPrefix(prefix: Array[Byte]) = view.scanPrefix(prefix)
          override def scanKeys(prefix: Array[Byte]) = view.scanKeys(prefix)
        })
      }

    private def fire(): Unit = {
      val next = synchronized {
        val value = hook
        hook = None
        value
      }
      next.foreach(_())
    }
  }
}
