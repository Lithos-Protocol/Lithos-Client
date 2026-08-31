package support

import akka.actor.{ActorSystem, Props}
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import org.bouncycastle.util.encoders.Hex
import play.api.Configuration
import state.persistence.StateSnapshotActor.{DictionaryLoadFailed, MaterializationPriority,
  MaterializeDictionary}
import state.persistence._
import storage.StoreError.{OpenFailed, WriteFailed}
import storage.{LevelDbKeyValueStore, WriteDurability}

import java.nio.charset.StandardCharsets
import java.nio.file.{Path, Paths}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._

/** Process-isolated targets for storage operational-qualification drills. */
object SyncStorageFaultWorker {
  private final val ExternalWriteBytes = 4 * 1024 * 1024

  def main(arguments: Array[String]): Unit = arguments.headOption match {
    case Some("expect-lock-refusal") =>
      require(arguments.length == 2, "expected <store-path>")
      expectLockRefusal(Paths.get(arguments(1)))
    case Some("stall-two-after-real-read") =>
      require(arguments.length == 3, "expected <store-path> <dictionary-digest>")
      stallTwoAfterRealRead(Paths.get(arguments(1)), arguments(2))
    case Some("expect-external-write-failure") =>
      require(arguments.length == 3, "expected <store-path> <read-only|disk-full>")
      expectExternalWriteFailure(Paths.get(arguments(1)), arguments(2))
    case _ => throw new IllegalArgumentException(
      "expected expect-lock-refusal, stall-two-after-real-read, or expect-external-write-failure")
  }

  /** A second process must not open the live LevelDB directory while its owner holds the lock. */
  private def expectLockRefusal(path: Path): Unit = LevelDbKeyValueStore.open(path) match {
    case Left(_: OpenFailed) =>
      println("REAL_LEVELDB_LOCK_REFUSED")
    case Left(other) => throw new IllegalStateException(
      s"LevelDB lock contention returned ${other.getClass.getSimpleName}: ${other.message}")
    case Right(store) =>
      store.close()
      throw new IllegalStateException("a second process opened an already-owned LevelDB directory")
  }

  /**
   * Reads the real persisted dictionary in each production worker slot, then injects a permanent
   * post-read return stall. The actor must time out both active callers and the queued critical
   * caller without starting a third physical reconstruction. The parent deliberately kills this
   * process afterward and verifies that LevelDB reopens without repair.
   */
  private def stallTwoAfterRealRead(path: Path, digest: String): Unit = {
    val protocol = ReducerFixtures.protocol(rollupStartHeight = 100)
    val inspection = LevelDbKeyValueStore.openOrThrow(path)
    val inspectionSnapshots = new LevelDbStateSnapshotStore(inspection, 2,
      StateSnapshotIdentity(protocol))
    val dictionary = inspectionSnapshots.loadDictionary(digest) match {
      case Right(value) => value
      case Left(error) => throw new IllegalStateException(error.message)
    }
    val source = SnapshotDictionarySource(digest, dictionary.flags, dictionary.parameters,
      SnapshotDictionaryBase.Persisted(digest), Vector.empty)
    require(inspectionSnapshots.close() == Right(()))

    val system = ActorSystem("sync-storage-double-stall-worker")
    val neverRelease = new CountDownLatch(1)
    try {
      val requester = TestProbe()(system)
      val (nodeContext, _, _) = FakeNodeContext(numAddresses = 1)
      val config = Configuration(ConfigFactory.parseString(
        s"""sync.startHeight = 100
           |sync.snapshots.enabled = true
           |sync.snapshots.retention = 2
           |sync.storage.backend = "leveldb"
           |sync.storage.path = "${path.toString.replace("\\", "/")}"
           |""".stripMargin))
      val calls = new AtomicInteger(0)
      val readsCompleted = new CountDownLatch(2)
      val worker = system.actorOf(Props(new StateSnapshotActor(config, nodeContext, protocol) {
        override protected def operationTimeout: FiniteDuration = 500.millis

        override protected def reconstruct(source: SnapshotDictionarySource):
          Either[SnapshotError, lfsm.states.AuthenticatedDictionaryView] = {
          calls.incrementAndGet()
          val result = super.reconstruct(source)
          require(result.isRight, result.left.toOption.map(_.message).getOrElse("unknown read failure"))
          readsCompleted.countDown()
          neverRelease.await()
          result
        }
      }))

      requester.send(worker, MaterializeDictionary(source, "stalled-normal-1"))
      requester.send(worker, MaterializeDictionary(source, "stalled-normal-2"))
      require(readsCompleted.await(30, TimeUnit.SECONDS),
        "both real LevelDB reads did not finish before the injected return stall")
      requester.send(worker, MaterializeDictionary(source, "queued-critical",
        MaterializationPriority.Critical))
      val failures = requester.receiveN(3, 10.seconds).collect {
        case failure: DictionaryLoadFailed => failure
      }
      require(failures.size == 3, s"received ${failures.size} logical failures")
      require(failures.map(_.requestToken).toSet ==
        Set("stalled-normal-1", "stalled-normal-2", "queued-critical"))
      require(failures.forall(_.reason.contains("exceeded")))
      require(calls.get() == 2,
        s"the queued critical request started a third physical reconstruction: ${calls.get()}")
      println("POST_READ_DOUBLE_STALL_LOGICAL_TIMEOUTS_COMPLETE")
      System.out.flush()
      neverRelease.await()
    } finally {
      // The parent normally kills this process. This path exists only for an unexpected interrupt.
      system.terminate()
    }
  }

  /**
   * Requires a user-provided disposable path whose filesystem is already read-only or has less
   * than four MiB of writable quota. It never tries to manufacture those host conditions itself.
   */
  private def expectExternalWriteFailure(path: Path, condition: String): Unit = {
    require(condition == "read-only" || condition == "disk-full",
      s"unsupported external condition $condition")
    LevelDbKeyValueStore.open(path) match {
      case Left(_: OpenFailed) =>
        println(s"EXTERNAL_${condition.toUpperCase.replace('-', '_')}_OPEN_FAILED")
      case Left(other) => throw new IllegalStateException(other.message)
      case Right(store) =>
        val key = s"lithos-operational-$condition".getBytes(StandardCharsets.UTF_8)
        val payload = Array.fill[Byte](ExternalWriteBytes)(0x5a.toByte)
        try store.put(key, payload, WriteDurability.Synchronous) match {
          case Left(_: WriteFailed) =>
            println(s"EXTERNAL_${condition.toUpperCase.replace('-', '_')}_WRITE_FAILED")
          case Left(other) => throw new IllegalStateException(other.message)
          case Right(_) =>
            store.delete(key, WriteDurability.Synchronous)
            throw new IllegalStateException(
              s"the supplied $condition path accepted a four-MiB synchronous write")
        } finally store.close()
    }
  }
}
