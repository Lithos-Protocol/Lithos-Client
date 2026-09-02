package state.persistence

import lfsm.LFSMPhase
import lfsm.states.{MinerDictionary, PlasmaDictionary, Rollup, RollupInfoState}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import state.messages.SyncMessages.SyncCursor
import state.synchronization.{CommittedSyncMetadata, CommittedSyncState, DictionaryId}
import storage._
import support.{ReducerFixtures, SyncFixtures}

import java.nio.file.Path
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}

/** Proves generation metadata and every referenced dictionary header use one database view. */
class SnapshotReadConsistencySpec extends AnyFlatSpec with Matchers {

  private val identity = StateSnapshotIdentity(ReducerFixtures.protocol())
  private val MaxEntry = 64 * 1024 * 1024

  "LevelDbStateSnapshotStore" should "finish an old generation while concurrent saves prune it" in {
    implicit val executionContext: ExecutionContext = ExecutionContext.global
    val keyValues = new InterleavingStore
    val snapshots = new LevelDbStateSnapshotStore(keyValues, retention = 2, identity)
    val first = persisted(100)
    save(snapshots, first) shouldEqual Right(())
    val firstKey = snapshots.generationKeys().toOption.get.head
    val savesStarted = new CountDownLatch(1)
    val savesFinished = Promise[Either[SnapshotError, Unit]]()

    // The callback runs after metadata was obtained from the read view and before its headers are
    // read. Start the saves on another thread: they wait for this load's read lock instead of trying
    // to upgrade that lock re-entrantly on the callback thread, which ReentrantReadWriteLock forbids.
    keyValues.afterNextMetaRead {
      savesFinished.completeWith(Future {
        savesStarted.countDown()
        for {
          _ <- save(snapshots, persisted(101))
          _ <- save(snapshots, persisted(102))
        } yield ()
      })
      savesStarted.await(5, TimeUnit.SECONDS) shouldBe true
    }

    val loaded = snapshots.load(firstKey).toOption.get
    Await.result(savesFinished.future, 10.seconds) shouldEqual Right(())

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
    val tree = Rollup(PlasmaDictionary.empty(), 0, BigInt(0),
      RollupInfoState.holding(90L, 90L, 0L), 1000000L, 90,
      hasMiner = false, evaluated = false, blockId = rollupId, utxoId = utxoId)
    val cursor = SyncCursor(height, SyncFixtures.id(height), SyncFixtures.id(height - 1))
    PersistedSyncState(
      CommittedSyncState(cursor, height.toLong, Map(rollupId -> tree), Map(utxoId -> rollupId),
        Map(rollupId -> origin), MinerDictionary.initialState, None),
      Vector(cursor))
  }

  /**
   * Its read view is immutable. The hook starts live writes exactly between metadata and header reads;
   * the snapshot-store read lock lets the load finish before those writes can prune its generation.
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
