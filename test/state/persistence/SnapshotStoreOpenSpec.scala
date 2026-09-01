package state.persistence

import akka.actor.{ActorSystem, Kill, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import lfsm.states.{MinerDictionary, PlasmaDictionary}
import org.bouncycastle.util.encoders.Hex
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import play.api.Configuration
import state.persistence.StateSnapshotActor.{DictionaryLoadFailed, DictionaryLoaded, MaterializeDictionary}
import state.synchronization.{CommittedSyncMetadata, DictionaryId}
import storage.LevelDbKeyValueStore
import support.{FakeNodeContext, ReducerFixtures, RestartingSupervisor, SyncFixtures}

import java.nio.file.{Files, Path}
import scala.collection.JavaConverters._
import scala.concurrent.duration._

/**
 * The snapshot database is opened on first use, not in the constructor.
 *
 * Opening in the constructor turned an ordinary lock conflict into an ActorInitializationException
 * and a permanently stopped actor. Making it lazy removed that, but it also removed the guarantee
 * that the store is open by the time reconstruction runs: reconstruction executes on the I/O
 * dispatcher and reads an already-open handle, so a request that never triggers the open fails every
 * persisted read. That regression shipped in one build pass and was caught only incidentally, by a
 * storage-fault drill whose fixture happened to reach a persisted base.
 */
class SnapshotStoreOpenSpec extends TestKit(ActorSystem("snapshot-store-open"))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {

  private var directories = Vector.empty[Path]

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    directories.foreach { path =>
      if (Files.exists(path)) {
        val paths = Files.walk(path)
        try paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists)
        finally paths.close()
      }
    }
  }

  private val startHeight = 100

  /**
   * A materialization is the first message the actor sees, so nothing else has opened the store.
   * That is the real startup order after a restart in which the producer still holds its own state
   * and therefore never asks for a snapshot restore.
   */
  "StateSnapshotActor" should "open the snapshot database for a persisted materialization" in {
    val protocol = ReducerFixtures.protocol(rollupStartHeight = startHeight)
    val (directory, digest, dictionary) = persistedDictionary(protocol)
    val requester = TestProbe()
    val worker = system.actorOf(Props(new StateSnapshotActor(configFor(directory),
      FakeNodeContext(numAddresses = 1)._1, protocol)))

    val source = SnapshotDictionarySource(digest, dictionary.flags, dictionary.parameters,
      SnapshotDictionaryBase.Persisted(digest), Vector.empty)
    requester.send(worker, MaterializeDictionary(source, "first-message"))

    val loaded = requester.expectMsgType[DictionaryLoaded](10.seconds)
    loaded.requestToken shouldEqual "first-message"
    Hex.toHexString(loaded.dictionary.digest) shouldEqual digest
  }

  /**
   * The window the regression was actually reachable through.
   *
   * `preRestart` closes the store, so a replacement instance starts with no handle — while
   * `SyncHandler` keeps a checkpoint-backed journal base whose dictionaries are digest-only, so its
   * next cold load carries a persisted base. Nothing reopens the store in between until the next
   * checkpoint, and a keyed materialization failure quarantines the rollup that asked for it.
   */
  it should "reopen the database for a persisted materialization after an actor restart" in {
    val protocol = ReducerFixtures.protocol(rollupStartHeight = startHeight)
    val (directory, digest, dictionary) = persistedDictionary(protocol)
    val requester = TestProbe()
    val supervisor = system.actorOf(RestartingSupervisor.props(Props(
      new StateSnapshotActor(configFor(directory), FakeNodeContext(numAddresses = 1)._1, protocol))))
    requester.send(supervisor, RestartingSupervisor.GetChild)
    val worker = requester.expectMsgType[RestartingSupervisor.Child].ref

    val source = SnapshotDictionarySource(digest, dictionary.flags, dictionary.parameters,
      SnapshotDictionaryBase.Persisted(digest), Vector.empty)

    requester.send(worker, MaterializeDictionary(source, "before-restart"))
    requester.expectMsgType[DictionaryLoaded](10.seconds)

    worker ! Kill

    requester.send(worker, MaterializeDictionary(source, "after-restart"))
    requester.expectMsgType[DictionaryLoaded](10.seconds).requestToken shouldEqual "after-restart"
  }

  /** A database that cannot be opened is a per-request failure, not a stopped actor. */
  it should "answer with a typed failure rather than dying when the database cannot open" in {
    val protocol = ReducerFixtures.protocol(rollupStartHeight = startHeight)
    val directory = Files.createTempDirectory("lithos-sync-open-blocked-")
    directories :+= directory
    // A regular file where the database directory belongs: openable targets are directories.
    val target = directory.resolve("occupied")
    Files.write(target, Array[Byte](1, 2, 3))

    val requester = TestProbe()
    val worker = system.actorOf(Props(new StateSnapshotActor(configFor(target),
      FakeNodeContext(numAddresses = 1)._1, protocol)))

    val empty = PlasmaDictionary.empty()
    val source = SnapshotDictionarySource(Hex.toHexString(empty.digest), empty.flags,
      empty.parameters, SnapshotDictionaryBase.Persisted(Hex.toHexString(empty.digest)),
      Vector.empty)
    requester.send(worker, MaterializeDictionary(source, "unopenable"))
    requester.expectMsgType[DictionaryLoadFailed](10.seconds).requestToken shouldEqual "unopenable"

    // Still answering, which is the whole point of not opening in the constructor.
    requester.send(worker, MaterializeDictionary(source, "second-request"))
    requester.expectMsgType[DictionaryLoadFailed](10.seconds).requestToken shouldEqual "second-request"
  }

  /** One persisted checkpoint holding a single non-empty Miner Dictionary. */
  private def persistedDictionary(protocol: state.synchronization.SyncProtocolContext):
    (Path, String, PlasmaDictionary) = {
    val directory = Files.createTempDirectory("lithos-sync-open-")
    directories :+= directory
    val dictionary = PlasmaDictionary.empty()
    dictionary.insert(SyncFixtures.plasmaEntries(1, 32): _*)
    val digest = Hex.toHexString(dictionary.digest)

    val state = ReducerFixtures.emptyState(height = startHeight, blockId = SyncFixtures.id(startHeight))
      .copy(minerTree = MinerDictionary.initialState.copy(dictionary = dictionary, numMiners = 1,
        syncHeight = startHeight, savedHeight = startHeight))
    val checkpoint = SnapshotCheckpoint(CommittedSyncMetadata.from(state), Vector(state.cursor),
      Map(DictionaryId.Miner -> SnapshotDictionarySource(digest, dictionary.flags,
        dictionary.parameters, SnapshotDictionaryBase.Materialized(dictionary), Vector.empty)))

    val store = new LevelDbStateSnapshotStore(LevelDbKeyValueStore.openOrThrow(directory), 2,
      StateSnapshotIdentity(protocol))
    store.save(checkpoint, 64 * 1024 * 1024) shouldEqual Right(())
    store.close() shouldEqual Right(())
    (directory, digest, dictionary)
  }

  private def configFor(directory: Path): Configuration =
    Configuration(ConfigFactory.parseString(
      s"""sync.startHeight = $startHeight
         |sync.snapshots.enabled = true
         |sync.snapshots.retention = 2
         |sync.storage.backend = "leveldb"
         |sync.storage.path = "${directory.toString.replace("\\", "/")}"
         |""".stripMargin))
}
