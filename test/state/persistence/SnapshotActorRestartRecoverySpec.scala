package state.persistence

import akka.actor.{ActorSystem, Kill, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import lfsm.states.{AuthenticatedDictionaryView, PlasmaDictionary}
import org.bouncycastle.util.encoders.Hex
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import play.api.Configuration
import state.persistence.StateSnapshotActor._
import state.synchronization.{CommittedSyncMetadata, CommittedSyncState, DictionaryId, SyncProtocolContext}
import support.{FakeNodeContext, ReducerFixtures, RestartingSupervisor, SyncFixtures}

import java.nio.file.Files
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._

/** A restarted persistence worker must terminate every request accepted by its old incarnation. */
class SnapshotActorRestartRecoverySpec extends TestKit(ActorSystem("snapshot-actor-restart-recovery"))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  "StateSnapshotActor" should "fail an in-flight save when its actor incarnation restarts" in {
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val calls = new AtomicInteger(0)
    val fixture = actorWithOverrides(
      persistFn = _ => {
        if (calls.getAndIncrement() == 0) {
          entered.countDown()
          release.await(10, TimeUnit.SECONDS)
        }
        Right(())
      })
    val first = candidate(fixture.protocol, "save-before-restart")

    fixture.requester.send(fixture.actor, first)
    entered.await(5, TimeUnit.SECONDS) shouldBe true
    fixture.actor ! Kill
    val failed = fixture.requester.expectMsgType[SnapshotSaveFailed](5.seconds)
    failed.checkpointToken shouldEqual first.checkpointToken
    failed.reason should include("restarted")

    release.countDown()
    val second = first.copy(checkpointToken = "save-after-restart")
    fixture.requester.send(fixture.actor, second)
    fixture.requester.expectMsgType[SnapshotSaved](5.seconds).checkpointToken shouldEqual second.checkpointToken
  }

  it should "pin the production operation deadline and materialization concurrency" in {
    StateSnapshotActor.OperationTimeout shouldEqual 5.minutes
    StateSnapshotActor.MaxConcurrentMaterializations shouldEqual 2
  }

  it should "fail an in-flight dictionary load and accept a retry after restart" in {
    val dictionary = PlasmaDictionary.empty()
    val source = SnapshotDictionarySource(Hex.toHexString(dictionary.digest), dictionary.flags,
      dictionary.parameters, SnapshotDictionaryBase.Materialized(dictionary), Vector.empty)
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val calls = new AtomicInteger(0)
    val fixture = actorWithOverrides(
      reconstructFn = _ => {
        if (calls.getAndIncrement() == 0) {
          entered.countDown()
          release.await(10, TimeUnit.SECONDS)
        }
        Right(dictionary)
      })

    fixture.requester.send(fixture.actor, MaterializeDictionary(source, "load-before-restart"))
    entered.await(5, TimeUnit.SECONDS) shouldBe true
    fixture.actor ! Kill
    val failed = fixture.requester.expectMsgType[DictionaryLoadFailed](5.seconds)
    failed.requestToken shouldEqual "load-before-restart"
    failed.reason should include("restarted")

    release.countDown()
    fixture.requester.send(fixture.actor, MaterializeDictionary(source, "load-after-restart"))
    val loaded = fixture.requester.expectMsgType[DictionaryLoaded](5.seconds)
    loaded.requestToken shouldEqual "load-after-restart"
    Hex.toHexString(loaded.dictionary.digest) shouldEqual source.expectedDigest
  }

  it should "run at most two dictionary reconstructions concurrently without refusing queued work" in {
    val dictionary = PlasmaDictionary.empty()
    val source = SnapshotDictionarySource(Hex.toHexString(dictionary.digest), dictionary.flags,
      dictionary.parameters, SnapshotDictionaryBase.Materialized(dictionary), Vector.empty)
    val entered = new CountDownLatch(2)
    val release = new CountDownLatch(1)
    val calls = new AtomicInteger(0)
    val fixture = actorWithOverrides(reconstructFn = _ => {
      calls.incrementAndGet()
      entered.countDown()
      release.await(10, TimeUnit.SECONDS)
      Right(dictionary)
    })

    (1 to 3).foreach(index =>
      fixture.requester.send(fixture.actor, MaterializeDictionary(source, s"queued-$index")))
    entered.await(5, TimeUnit.SECONDS) shouldBe true
    calls.get() shouldEqual 2

    release.countDown()
    val loaded = (1 to 3).map(_ => fixture.requester.expectMsgType[DictionaryLoaded](5.seconds))
    loaded.map(_.requestToken).toSet shouldEqual Set("queued-1", "queued-2", "queued-3")
  }

  it should "restart and fail a save that exceeds the persistence deadline" in {
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val calls = new AtomicInteger(0)
    val fixture = actorWithOverrides(
      persistFn = _ => {
        if (calls.getAndIncrement() == 0) {
          entered.countDown()
          release.await(10, TimeUnit.SECONDS)
        }
        Right(())
      },
      timeout = 150.millis)
    val first = candidate(fixture.protocol, "save-timeout")

    fixture.requester.send(fixture.actor, first)
    entered.await(5, TimeUnit.SECONDS) shouldBe true
    val failed = fixture.requester.expectMsgType[SnapshotSaveFailed](5.seconds)
    failed.checkpointToken shouldEqual first.checkpointToken
    failed.reason should include("exceeded")

    release.countDown()
    val retry = first.copy(checkpointToken = "save-after-timeout")
    fixture.requester.send(fixture.actor, retry)
    fixture.requester.expectMsgType[SnapshotSaved](5.seconds).checkpointToken shouldEqual
      retry.checkpointToken
  }

  it should "restart and fail every accepted dictionary request when one exceeds the deadline" in {
    val dictionary = PlasmaDictionary.empty()
    val source = SnapshotDictionarySource(Hex.toHexString(dictionary.digest), dictionary.flags,
      dictionary.parameters, SnapshotDictionaryBase.Materialized(dictionary), Vector.empty)
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val fixture = actorWithOverrides(
      reconstructFn = _ => {
        entered.countDown()
        release.await(10, TimeUnit.SECONDS)
        Right(dictionary)
      },
      timeout = 150.millis)

    fixture.requester.send(fixture.actor, MaterializeDictionary(source, "active-timeout"))
    fixture.requester.send(fixture.actor, MaterializeDictionary(source, "accepted-peer"))
    fixture.requester.send(fixture.actor, MaterializeDictionary(source, "queued-peer"))
    entered.await(5, TimeUnit.SECONDS) shouldBe true

    val failures = (1 to 3).map(_ => fixture.requester.expectMsgType[DictionaryLoadFailed](5.seconds))
    failures.map(_.requestToken).toSet shouldEqual
      Set("active-timeout", "accepted-peer", "queued-peer")
    failures.foreach(_.reason should include("exceeded"))
    release.countDown()
  }

  private final case class Fixture(actor: akka.actor.ActorRef,
                                   requester: TestProbe,
                                   protocol: SyncProtocolContext)

  private def actorWithOverrides(
    persistFn: SnapshotCandidate => Either[SnapshotError, Unit] = _ => Right(()),
    reconstructFn: SnapshotDictionarySource => Either[SnapshotError, AuthenticatedDictionaryView] =
      source => source.base match {
        case SnapshotDictionaryBase.Materialized(dictionary) => Right(dictionary)
        case _ => Left(SnapshotError.Corrupt("test source was not materialized"))
      },
    timeout: FiniteDuration = 5.minutes): Fixture = {
    val (nodeContext, _, wallet) = FakeNodeContext(numAddresses = 1)
    val protocol = ReducerFixtures.protocol(rollupStartHeight = 100)
      .copy(localMinerHash = wallet.contract.hashedPropBytes)
    val path = Files.createTempDirectory("snapshot-actor-restart").resolve("db")
    val config = Configuration(ConfigFactory.parseString(
      s"""sync.startHeight = 100
         |sync.snapshots.enabled = true
         |sync.storage.path = "${path.toString.replace("\\", "/")}"
         |""".stripMargin))
    val childProps = Props(new StateSnapshotActor(config, nodeContext, protocol) {
      override protected def persist(candidate: SnapshotCandidate): Either[SnapshotError, Unit] =
        persistFn(candidate)

      override protected def reconstruct(source: SnapshotDictionarySource):
        Either[SnapshotError, AuthenticatedDictionaryView] = reconstructFn(source)

      override protected def operationTimeout: FiniteDuration = timeout
    })
    val supervisor = system.actorOf(RestartingSupervisor.props(childProps))
    val requester = TestProbe()
    requester.send(supervisor, RestartingSupervisor.GetChild)
    Fixture(requester.expectMsgType[RestartingSupervisor.Child].ref, requester, protocol)
  }

  private def candidate(protocol: SyncProtocolContext, token: String): SnapshotCandidate = {
    val state = ReducerFixtures.emptyState(height = 99, blockId = SyncFixtures.id(99))
    val dictionary = state.minerTree.dictionary
    val source = SnapshotDictionarySource(Hex.toHexString(dictionary.digest), dictionary.flags,
      dictionary.parameters, SnapshotDictionaryBase.Materialized(dictionary), Vector.empty)
    SnapshotCandidate(SnapshotCheckpoint(CommittedSyncMetadata.from(state), Vector(state.cursor),
      Map(DictionaryId.Miner -> source)), token)
  }
}
