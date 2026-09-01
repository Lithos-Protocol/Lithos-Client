package state.persistence

import akka.actor.{ActorSystem, Kill, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import lfsm.states.{AuthenticatedDictionaryView, PlasmaDictionary}
import org.bouncycastle.util.encoders.Hex
import org.scalatest.BeforeAndAfterAll
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import play.api.Configuration
import state.persistence.StateSnapshotActor._
import state.synchronization.{CommittedSyncMetadata, CommittedSyncState, DictionaryId, SyncProtocolContext}
import support.{FakeNodeContext, ReducerFixtures, RestartingSupervisor, SyncFixtures}

import java.nio.file.Files
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.{Success, Try}

/** A restarted persistence worker must terminate every request accepted by its old incarnation. */
class SnapshotActorRestartRecoverySpec extends TestKit(ActorSystem("snapshot-actor-restart-recovery"))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with OptionValues {

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
    system.settings.config.getInt(
      "lithos-contexts.snapshot-io-dispatcher.thread-pool-executor.fixed-pool-size") shouldEqual 4
    system.settings.config.getInt(
      "lithos-contexts.snapshot-io-dispatcher.thread-pool-executor.task-queue-size") shouldEqual 4
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

  it should "terminate active and queued work from both priority classes on restart" in {
    val source = materializedSource(99)
    val dictionary = source.base.asInstanceOf[SnapshotDictionaryBase.Materialized].dictionary
    val entered = new CountDownLatch(2)
    val release = new CountDownLatch(1)
    val fixture = actorWithOverrides(reconstructFn = _ => {
      entered.countDown()
      release.await(10, TimeUnit.SECONDS)
      Right(dictionary)
    })

    fixture.requester.send(fixture.actor, MaterializeDictionary(source, "active-normal"))
    fixture.requester.send(fixture.actor, MaterializeDictionary(source, "active-critical",
      MaterializationPriority.Critical))
    entered.await(5, TimeUnit.SECONDS) shouldBe true
    fixture.requester.send(fixture.actor, MaterializeDictionary(source, "queued-normal"))
    fixture.requester.send(fixture.actor, MaterializeDictionary(source, "queued-critical",
      MaterializationPriority.Critical))

    fixture.actor ! Kill
    val failed = fixture.requester.receiveN(4, 5.seconds)
      .map(_.asInstanceOf[DictionaryLoadFailed])
    failed.map(_.requestToken).toSet shouldEqual
      Set("active-normal", "active-critical", "queued-normal", "queued-critical")
    all(failed.map(_.reason)) should include("restarted")
    release.countDown()
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

  it should "run queued critical loads first without preempting either active load" in {
    val sources = (1 to 5).map(number => materializedSource(number)).toVector
    val entered = sources.map(source => source.expectedDigest -> new CountDownLatch(1)).toMap
    val release = sources.map(source => source.expectedDigest -> new CountDownLatch(1)).toMap
    val fixture = actorWithOverrides(reconstructFn = source => {
      entered(source.expectedDigest).countDown()
      release.get(source.expectedDigest).foreach(_.await(10, TimeUnit.SECONDS))
      source.base match {
        case SnapshotDictionaryBase.Materialized(dictionary) => Right(dictionary)
        case _ => Left(SnapshotError.Corrupt("test source was not materialized"))
      }
    })

    fixture.requester.send(fixture.actor, MaterializeDictionary(sources(0), "active-1"))
    fixture.requester.send(fixture.actor, MaterializeDictionary(sources(1), "active-2"))
    entered(sources(0).expectedDigest).await(5, TimeUnit.SECONDS) shouldBe true
    entered(sources(1).expectedDigest).await(5, TimeUnit.SECONDS) shouldBe true

    fixture.requester.send(fixture.actor, MaterializeDictionary(sources(2), "normal"))
    fixture.requester.send(fixture.actor, MaterializeDictionary(sources(3), "critical-1",
      MaterializationPriority.Critical))
    fixture.requester.send(fixture.actor, MaterializeDictionary(sources(4), "critical-2",
      MaterializationPriority.Critical))
    entered(sources(3).expectedDigest).await(150, TimeUnit.MILLISECONDS) shouldBe false
    entered(sources(4).expectedDigest).getCount shouldEqual 1L
    entered(sources(2).expectedDigest).getCount shouldEqual 1L

    release(sources(0).expectedDigest).countDown()
    entered(sources(3).expectedDigest).await(5, TimeUnit.SECONDS) shouldBe true
    entered(sources(4).expectedDigest).getCount shouldEqual 1L
    entered(sources(2).expectedDigest).getCount shouldEqual 1L

    release(sources(3).expectedDigest).countDown()
    entered(sources(4).expectedDigest).await(5, TimeUnit.SECONDS) shouldBe true
    entered(sources(2).expectedDigest).getCount shouldEqual 1L

    entered(sources(4).expectedDigest).getCount shouldEqual 0L
    entered(sources(2).expectedDigest).getCount shouldEqual 1L
    release(sources(1).expectedDigest).countDown()
    release(sources(2).expectedDigest).countDown()
    release(sources(4).expectedDigest).countDown()

    fixture.requester.receiveN(5, 5.seconds).map(_.asInstanceOf[DictionaryLoaded].requestToken).toSet shouldEqual
      Set("active-1", "active-2", "normal", "critical-1", "critical-2")
  }

  it should "promote a queued normal token once without duplicating physical work" in {
    val sources = (11 to 14).map(number => materializedSource(number)).toVector
    val entered = sources.map(source => source.expectedDigest -> new CountDownLatch(1)).toMap
    val release = sources.map(source => source.expectedDigest -> new CountDownLatch(1)).toMap
    val calls = new AtomicInteger(0)
    val fixture = actorWithOverrides(reconstructFn = source => {
      calls.incrementAndGet()
      entered(source.expectedDigest).countDown()
      release(source.expectedDigest).await(10, TimeUnit.SECONDS)
      source.base match {
        case SnapshotDictionaryBase.Materialized(dictionary) => Right(dictionary)
        case _ => Left(SnapshotError.Corrupt("test source was not materialized"))
      }
    })

    fixture.requester.send(fixture.actor, MaterializeDictionary(sources(0), "active-1"))
    fixture.requester.send(fixture.actor, MaterializeDictionary(sources(1), "active-2"))
    entered(sources(0).expectedDigest).await(5, TimeUnit.SECONDS) shouldBe true
    entered(sources(1).expectedDigest).await(5, TimeUnit.SECONDS) shouldBe true
    fixture.requester.send(fixture.actor, MaterializeDictionary(sources(2), "normal-first"))
    fixture.requester.send(fixture.actor, MaterializeDictionary(sources(3), "normal-promoted"))
    fixture.requester.send(fixture.actor, PromoteMaterialization("normal-promoted"))
    fixture.requester.send(fixture.actor, PromoteMaterialization("normal-promoted"))

    release(sources(0).expectedDigest).countDown()
    entered(sources(3).expectedDigest).await(5, TimeUnit.SECONDS) shouldBe true
    entered(sources(2).expectedDigest).getCount shouldEqual 1L
    release(sources(3).expectedDigest).countDown()
    entered(sources(2).expectedDigest).await(5, TimeUnit.SECONDS) shouldBe true
    release(sources(2).expectedDigest).countDown()
    release(sources(1).expectedDigest).countDown()

    fixture.requester.receiveN(4, 5.seconds).map(_.asInstanceOf[DictionaryLoaded].requestToken).toSet shouldEqual
      Set("active-1", "active-2", "normal-first", "normal-promoted")
    calls.get() shouldEqual 4
  }

  it should "fail a timed-out save without starting replacement I/O over it" in {
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
      timeout = 500.millis)
    val first = candidate(fixture.protocol, "save-timeout")

    fixture.requester.send(fixture.actor, first)
    entered.await(5, TimeUnit.SECONDS) shouldBe true
    val failed = fixture.requester.expectMsgType[SnapshotSaveFailed](5.seconds)
    failed.checkpointToken shouldEqual first.checkpointToken
    failed.reason should include("exceeded")

    val retry = first.copy(checkpointToken = "save-after-timeout")
    fixture.requester.send(fixture.actor, retry)
    Thread.sleep(100)
    calls.get() shouldEqual 1

    release.countDown()
    fixture.requester.expectMsgType[SnapshotSaved](5.seconds).checkpointToken shouldEqual
      retry.checkpointToken
    calls.get() shouldEqual 2
  }

  it should "fail expired dictionary requests without exceeding the two-worker physical limit" in {
    val dictionary = PlasmaDictionary.empty()
    val source = SnapshotDictionarySource(Hex.toHexString(dictionary.digest), dictionary.flags,
      dictionary.parameters, SnapshotDictionaryBase.Materialized(dictionary), Vector.empty)
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val calls = new AtomicInteger(0)
    val fixture = actorWithOverrides(
      reconstructFn = _ => {
        calls.incrementAndGet()
        entered.countDown()
        release.await(10, TimeUnit.SECONDS)
        Right(dictionary)
      },
      timeout = 150.millis)

    fixture.requester.send(fixture.actor, MaterializeDictionary(source, "active-timeout"))
    fixture.requester.send(fixture.actor, MaterializeDictionary(source, "accepted-peer"))
    fixture.requester.send(fixture.actor, MaterializeDictionary(source, "queued-normal"))
    fixture.requester.send(fixture.actor, MaterializeDictionary(source, "queued-critical",
      MaterializationPriority.Critical))
    entered.await(5, TimeUnit.SECONDS) shouldBe true

    val failures = (1 to 4).map(_ => fixture.requester.expectMsgType[DictionaryLoadFailed](5.seconds))
    failures.map(_.requestToken).toSet shouldEqual
      Set("active-timeout", "accepted-peer", "queued-normal", "queued-critical")
    failures.foreach(_.reason should include("exceeded"))
    calls.get() shouldEqual 2
    release.countDown()
  }

  it should "coalesce a blocked restore and let later callers continue without it" in {
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val calls = new AtomicInteger(0)
    val fixture = actorWithOverrides(
      restoreFn = Some(_ => {
        calls.incrementAndGet()
        entered.countDown()
        release.await(10, TimeUnit.SECONDS)
        Success(Left(NoSnapshot("no retained snapshot in test", retryable = false)))
      }))

    fixture.requester.send(fixture.actor, RestoreLatest)
    entered.await(5, TimeUnit.SECONDS) shouldBe true
    val retry = TestProbe()
    retry.send(fixture.actor, RestoreLatest)
    val fallback = retry.expectMsgType[SnapshotLoaded](5.seconds)
    fallback.snapshot shouldBe empty
    fallback.warning.value should include("already in progress")
    calls.get() shouldEqual 1

    release.countDown()
    fixture.requester.expectMsgType[SnapshotLoaded](5.seconds).warning.value should include(
      "no retained snapshot")
  }

  it should "retain a timed-out restore slot until its underlying read returns" in {
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val calls = new AtomicInteger(0)
    val fixture = actorWithOverrides(
      restoreFn = Some(_ => {
        val call = calls.incrementAndGet()
        if (call == 1) {
          entered.countDown()
          release.await(10, TimeUnit.SECONDS)
        }
        Success(Left(NoSnapshot(s"restore call $call", retryable = false)))
      }),
      timeout = 150.millis)

    fixture.requester.send(fixture.actor, RestoreLatest)
    entered.await(5, TimeUnit.SECONDS) shouldBe true
    val expired = fixture.requester.expectMsgType[SnapshotLoaded](5.seconds)
    expired.snapshot shouldBe empty
    expired.warning.value should include("exceeded")

    val retry = TestProbe()
    retry.send(fixture.actor, RestoreLatest)
    retry.expectMsgType[SnapshotLoaded](5.seconds).warning.value should include("already in progress")
    calls.get() shouldEqual 1

    release.countDown()
    awaitAssert({
      val afterRelease = TestProbe()
      afterRelease.send(fixture.actor, RestoreLatest)
      afterRelease.expectMsgType[SnapshotLoaded](1.second).warning.value should include("restore call 2")
    }, 5.seconds, 100.millis)
    calls.get() shouldEqual 2
  }

  it should "keep four blocked snapshot operations off the canonical polling dispatcher" in {
    val dictionary = PlasmaDictionary.empty()
    val source = SnapshotDictionarySource(Hex.toHexString(dictionary.digest), dictionary.flags,
      dictionary.parameters, SnapshotDictionaryBase.Materialized(dictionary), Vector.empty)
    val entered = new CountDownLatch(4)
    val release = new CountDownLatch(1)
    def block(): Unit = {
      entered.countDown()
      release.await(10, TimeUnit.SECONDS)
    }
    val fixture = actorWithOverrides(
      persistFn = _ => { block(); Right(()) },
      reconstructFn = _ => { block(); Right(dictionary) },
      restoreFn = Some(_ => { block(); Success(Left(NoSnapshot("isolation test", retryable = false))) }))

    fixture.requester.send(fixture.actor, candidate(fixture.protocol, "isolated-save"))
    fixture.requester.send(fixture.actor, MaterializeDictionary(source, "isolated-load-1"))
    fixture.requester.send(fixture.actor, MaterializeDictionary(source, "isolated-load-2"))
    fixture.requester.send(fixture.actor, RestoreLatest)
    entered.await(5, TimeUnit.SECONDS) shouldBe true

    val polling = system.dispatchers.lookup(configs.Contexts.key(configs.Contexts.Polling))
    Await.result(Future("polling-remains-live")(polling), 2.seconds) shouldEqual "polling-remains-live"
    release.countDown()
    fixture.requester.receiveN(4, 5.seconds)
  }

  it should "execute snapshot work only on the isolated snapshot dispatcher" in {
    val dictionary = PlasmaDictionary.empty()
    val source = SnapshotDictionarySource(Hex.toHexString(dictionary.digest), dictionary.flags,
      dictionary.parameters, SnapshotDictionaryBase.Materialized(dictionary), Vector.empty)
    val observed = new AtomicReference[String]("")
    val fixture = actorWithOverrides(reconstructFn = _ => {
      observed.set(Thread.currentThread().getName)
      Right(dictionary)
    })

    fixture.requester.send(fixture.actor, MaterializeDictionary(source, "dispatcher-check"))
    fixture.requester.expectMsgType[DictionaryLoaded](5.seconds)
    observed.get() should include("snapshot-io-dispatcher")
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
    restoreFn: Option[StateSnapshotStore => Try[Either[NoSnapshot, PersistedSyncState]]] = None,
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

      override protected def findCanonical(store: StateSnapshotStore):
        Try[Either[NoSnapshot, PersistedSyncState]] = restoreFn.fold(super.findCanonical(store))(_(store))

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

  private def materializedSource(marker: Int): SnapshotDictionarySource = {
    val dictionary = PlasmaDictionary.empty()
    dictionary.insert(SyncFixtures.plasmaEntries(1, 32 + marker).head)
    SnapshotDictionarySource(Hex.toHexString(dictionary.digest), dictionary.flags,
      dictionary.parameters, SnapshotDictionaryBase.Materialized(dictionary), Vector.empty)
  }
}
