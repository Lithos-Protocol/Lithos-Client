package support

import lfsm.states.PlasmaDictionary
import org.bouncycastle.util.encoders.Hex
import state.persistence._
import state.synchronization.{CommittedSyncMetadata, DictionaryId}
import storage._

import java.nio.file.Path

/** Abrupt-process target used by the production-budget snapshot crash drill. */
object SyncSnapshotCrashWorker {
  final val HaltExitCode = 77

  def main(arguments: Array[String]): Unit = {
    require(arguments.length == 4,
      "expected <store-path> <before|after> <write-number> <entry-count>")
    val path = java.nio.file.Paths.get(arguments(0))
    val phase = arguments(1)
    val writeNumber = arguments(2).toInt
    val entries = arguments(3).toInt
    val underlying = LevelDbKeyValueStore.openOrThrow(path)
    val crashing = new CrashAtWriteStore(underlying, phase, writeNumber)
    val snapshots = new LevelDbStateSnapshotStore(crashing, 2, SnapshotCrashFixtures.identity)
    snapshots.save(SnapshotCrashFixtures.checkpoint(entries), 64 * 1024 * 1024)
    // Reaching this means the requested physical phase was not exercised.
    snapshots.close()
    System.exit(78)
  }

  private final class CrashAtWriteStore(underlying: KeyValueStore,
                                        phase: String,
                                        target: Int) extends KeyValueStore {
    private var writes = 0
    override def path: Path = underlying.path
    override def backend: StorageBackend = underlying.backend
    override def get(key: Array[Byte]) = underlying.get(key)
    override def scanPrefix(prefix: Array[Byte]) = underlying.scanPrefix(prefix)
    override def scanKeys(prefix: Array[Byte]) = underlying.scanKeys(prefix)
    override def readSnapshot[A](f: KeyValueReadView => Either[StoreError, A]) =
      underlying.readSnapshot(f)
    override def close() = underlying.close()

    override def write(mutations: Seq[KeyValueMutation], durability: WriteDurability) = {
      writes += 1
      if (writes == target && phase == "before") Runtime.getRuntime.halt(HaltExitCode)
      val result = underlying.write(mutations, durability)
      if (writes == target && phase == "after") Runtime.getRuntime.halt(HaltExitCode)
      result
    }
  }
}

object SnapshotCrashFixtures {
  val identity: StateSnapshotIdentity = StateSnapshotIdentity(ReducerFixtures.protocol())

  def checkpoint(entries: Int): SnapshotCheckpoint = {
    val dictionary = PlasmaDictionary.empty()
    dictionary.insert(SyncFixtures.plasmaEntries(entries, 32): _*)
    val base = ReducerFixtures.emptyState(100, SyncFixtures.id(100), 1L)
    val state = base.copy(minerTree = base.minerTree.copy(dictionary = dictionary,
      numMiners = entries, syncHeight = 100, savedHeight = 100))
    val source = SnapshotDictionarySource(Hex.toHexString(dictionary.digest), dictionary.flags,
      dictionary.parameters, SnapshotDictionaryBase.Materialized(dictionary), Vector.empty)
    SnapshotCheckpoint(CommittedSyncMetadata.from(state), Vector(state.cursor),
      Map(DictionaryId.Miner -> source))
  }
}
