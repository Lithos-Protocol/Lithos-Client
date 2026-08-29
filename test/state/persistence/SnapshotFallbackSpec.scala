package state.persistence

import akka.actor.{ActorIdentity, ActorSystem, Identify, Kill, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import lfsm.states.MinerDictionary
import node.NodeApi
import node.model.NodeHeader
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{doAnswer, when}
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.Configuration
import state.messages.SyncMessages.SyncCursor
import state.persistence.StateSnapshotActor.{RestoreLatest, SnapshotLoaded, SnapshotSaved}
import state.synchronization.{CommittedSyncMetadata, CommittedSyncState, DictionaryId, SyncProtocolContext}
import support.{ChainFixtures, FakeNodeContext, ReducerFixtures, RestartingSupervisor, SyncFixtures}

import java.nio.file.Files
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

/**
 * A snapshot that cannot be checked is not a snapshot that has been disproved.
 *
 * Collapsing the two discards recoverable state whenever the node is starting, behind, or still
 * indexing — and the caller then falls through to a full rescan from the configured start height.
 */
class SnapshotFallbackSpec extends TestKit(ActorSystem("snapshot-fallback"))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  "StateSnapshotActor" should "keep a generation whose header could not be read" in {
    val (actor, requester, identity) = actorOver(saved = Seq(600), headerAnswer = _ =>
      Failure(new java.net.ConnectException("node refused the connection")))

    requester.send(actor, RestoreLatest)
    val loaded = requester.expectMsgType[SnapshotLoaded]

    loaded.snapshot shouldBe empty
    // The distinction that matters: not "none is canonical", which is a claim this could not make.
    loaded.warning.get should include("Could not verify")
    loaded.warning.get should not include "is canonical"
  }

  it should "restore a generation the node confirms" in {
    val (actor, requester, identity) = actorOver(saved = Seq(600), headerAnswer = height =>
      Success(ChainFixtures.header(height)))

    requester.send(actor, RestoreLatest)
    requester.expectMsgType[SnapshotLoaded].snapshot.get.state.cursor.height shouldEqual 600
  }

  it should "fall past a disproved generation to an older confirmed one" in {
    // Only the newest was replaced by a fork, so the older generation is still the chain's.
    val (actor, requester, identity) = actorOver(saved = Seq(500, 600), headerAnswer = height =>
      if (height == 600) Success(ChainFixtures.header(600).copy(id = "forked-600"))
      else Success(ChainFixtures.header(height)))

    requester.send(actor, RestoreLatest)
    requester.expectMsgType[SnapshotLoaded].snapshot.get.state.cursor.height shouldEqual 500
  }

  it should "report no canonical snapshot only when every generation was checked" in {
    val (actor, requester, identity) = actorOver(saved = Seq(500, 600), headerAnswer = height =>
      Success(ChainFixtures.header(height).copy(id = "forked-" + height)))

    requester.send(actor, RestoreLatest)
    val loaded = requester.expectMsgType[SnapshotLoaded]

    loaded.snapshot shouldBe empty
    loaded.warning.get should include("not")
  }

  it should "terminate a restore request on restart and ignore its stale validation completion" in {
    val calls = new AtomicInteger(0)
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val (actor, requester, _) = actorOver(saved = Seq(600), headerAnswer = height => {
      if (calls.getAndIncrement() == 0) {
        entered.countDown()
        release.await(10, TimeUnit.SECONDS)
      }
      Success(ChainFixtures.header(height))
    }, restartable = true)

    requester.send(actor, RestoreLatest)
    entered.await(5, TimeUnit.SECONDS) shouldBe true
    actor ! Kill
    val interrupted = requester.expectMsgType[SnapshotLoaded](5.seconds)
    interrupted.snapshot shouldBe empty
    interrupted.warning.get should include("restarted")
    release.countDown()
    actor.tell(Identify("snapshot-restarted"), testActor)
    expectMsg(ActorIdentity("snapshot-restarted", Some(actor)))

    requester.expectNoMessage(500.millis)
    requester.send(actor, RestoreLatest)
    requester.expectMsgType[SnapshotLoaded].snapshot.get.state.cursor.height shouldEqual 600
  }

  /**
   * Cross-field checks the checksum cannot make: bytes can be intact while the combination is one the
   * reducer would never have produced.
   */
  it should "refuse a byte-valid generation whose fields contradict each other" in {
    val header = ChainFixtures.header(600)
    val cursor = SyncCursor(600, header.id, header.parentId)
    val contradictory = CommittedSyncState(cursor, 600L, Map.empty, Map.empty, Map.empty,
      // A stored data-box token says this miner registered; the dictionary says it holds nobody.
      MinerDictionary.initialState, Some(org.ergoplatform.sdk.ErgoId.create(SyncFixtures.id(7))))

    val (actor, requester, identity) = actorOver(saved = Seq.empty, headerAnswer = h =>
      Success(ChainFixtures.header(h)))
    requester.send(actor, candidate(contradictory, Vector(cursor), identity))
    requester.expectMsgType[SnapshotSaved]

    requester.send(actor, RestoreLatest)
    val loaded = requester.expectMsgType[SnapshotLoaded]

    loaded.snapshot shouldBe empty
    loaded.warning.get should include("data-box token")
  }

  it should "refuse a generation whose retained cursors do not reach its own" in {
    val header = ChainFixtures.header(600)
    val cursor = SyncCursor(600, header.id, header.parentId)
    val stale = SyncCursor(598, ChainFixtures.header(598).id, ChainFixtures.header(597).id)

    val (actor, requester, identity) = actorOver(saved = Seq.empty, headerAnswer = h =>
      Success(ChainFixtures.header(h)))
    requester.send(actor, candidate(stateAt(600), Vector(stale), identity))
    requester.expectMsgType[SnapshotSaved]

    requester.send(actor, RestoreLatest)
    requester.expectMsgType[SnapshotLoaded].warning.get should include("retained cursors end at 598")
  }

  it should "refuse retained cursor identity from a same-height fork" in {
    val state = stateAt(600)
    val forked = state.cursor.copy(blockId = "aa" * 32, parentId = "bb" * 32)
    val (actor, requester, identity) = actorOver(saved = Seq.empty, headerAnswer = h =>
      Success(ChainFixtures.header(h)))
    requester.send(actor, candidate(state, Vector(forked), identity))
    requester.expectMsgType[SnapshotSaved]

    requester.send(actor, RestoreLatest)
    requester.expectMsgType[SnapshotLoaded].warning.get should include("retained cursors end at 600")
  }

  private def stateAt(height: Int): CommittedSyncState = {
    val header = ChainFixtures.header(height)
    CommittedSyncState(SyncCursor(height, header.id, header.parentId), height.toLong,
      Map.empty, Map.empty, Map.empty, MinerDictionary.initialState, None)
  }

  private def actorOver(saved: Seq[Int],
                        headerAnswer: Int => Try[NodeHeader],
                        restartable: Boolean = false)
  : (akka.actor.ActorRef, TestProbe, StateSnapshotIdentity) = {
    val nodeApi = mock[NodeApi]
    doAnswer(new Answer[Try[Seq[NodeHeader]]] {
      override def answer(invocation: InvocationOnMock): Try[Seq[NodeHeader]] = {
        // headerSlice asks one lower and filters, so answer for the height actually wanted.
        val to = Option(invocation.getArgument[Option[Int]](1)).flatten.getOrElse(0)
        headerAnswer(to).map(Seq(_))
      }
    }).when(nodeApi).chainSlice(any[Option[Int]], any[Option[Int]])

    val (nodeContext, _, wallet) = FakeNodeContext(nodeApi, numAddresses = 1)
    val protocol = ReducerFixtures.protocol(rollupStartHeight = 100)
      .copy(localMinerHash = wallet.contract.hashedPropBytes)
    val path = Files.createTempDirectory("snapshot-fallback").resolve("db")
    val config = Configuration(ConfigFactory.parseString(
      s"""sync.startHeight = 100
         |sync.snapshots.enabled = true
         |sync.storage.path = "${path.toString.replace("\\", "/")}"
         |""".stripMargin))

    val requester = TestProbe()
    val childProps = Props(new StateSnapshotActor(config, nodeContext, protocol))
    val actor = if (restartable) {
      val supervisor = system.actorOf(RestartingSupervisor.props(childProps))
      requester.send(supervisor, RestartingSupervisor.GetChild)
      requester.expectMsgType[RestartingSupervisor.Child].ref
    } else system.actorOf(childProps)
    val identity = StateSnapshotIdentity(protocol)
    saved.foreach { height =>
      requester.send(actor, candidate(stateAt(height), Vector(stateAt(height).cursor), identity))
      requester.expectMsgType[SnapshotSaved]
    }
    (actor, requester, identity)
  }

  /** Encodes as the state owner does, so the actor receives the shape it handles. */
  private def candidate(state: CommittedSyncState,
                        cursors: Vector[SyncCursor],
                        identity: StateSnapshotIdentity): StateSnapshotActor.SnapshotCandidate = {
    def source(dictionary: lfsm.states.AuthenticatedDictionaryView) =
      SnapshotDictionarySource(org.bouncycastle.util.encoders.Hex.toHexString(dictionary.digest),
        dictionary.flags, dictionary.parameters, SnapshotDictionaryBase.Materialized(dictionary), Vector.empty)
    val dictionaries: Map[DictionaryId, SnapshotDictionarySource] = state.rollups.map { case (id, rollup) =>
      (DictionaryId.Rollup(id): DictionaryId) -> source(rollup.dictionary)
    } + (DictionaryId.Miner -> source(state.minerTree.dictionary))
    StateSnapshotActor.SnapshotCandidate(
      SnapshotCheckpoint(CommittedSyncMetadata.from(state), cursors, dictionaries),
      s"test-${state.cursor.height}")
  }
}
