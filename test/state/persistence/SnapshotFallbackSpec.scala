package state.persistence

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import lfsm.states.MinerTree
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
import state.persistence.StateSnapshotActor.{RestoreLatest, SnapshotLoaded}
import state.synchronization.{CommittedSyncState, SyncProtocolContext}
import support.{ChainFixtures, FakeNodeContext, ReducerFixtures, SyncFixtures}

import java.nio.file.Files
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
    val (actor, requester) = actorOver(saved = Seq(600), headerAnswer = _ =>
      Failure(new java.net.ConnectException("node refused the connection")))

    requester.send(actor, RestoreLatest)
    val loaded = requester.expectMsgType[SnapshotLoaded]

    loaded.snapshot shouldBe empty
    // The distinction that matters: not "none is canonical", which is a claim this could not make.
    loaded.warning.get should include("Could not verify")
    loaded.warning.get should not include "is canonical"
  }

  it should "restore a generation the node confirms" in {
    val (actor, requester) = actorOver(saved = Seq(600), headerAnswer = height =>
      Success(ChainFixtures.header(height)))

    requester.send(actor, RestoreLatest)
    requester.expectMsgType[SnapshotLoaded].snapshot.get.state.cursor.height shouldEqual 600
  }

  it should "fall past a disproved generation to an older confirmed one" in {
    // Only the newest was replaced by a fork, so the older generation is still the chain's.
    val (actor, requester) = actorOver(saved = Seq(500, 600), headerAnswer = height =>
      if (height == 600) Success(ChainFixtures.header(600).copy(id = "forked-600"))
      else Success(ChainFixtures.header(height)))

    requester.send(actor, RestoreLatest)
    requester.expectMsgType[SnapshotLoaded].snapshot.get.state.cursor.height shouldEqual 500
  }

  it should "report no canonical snapshot only when every generation was checked" in {
    val (actor, requester) = actorOver(saved = Seq(500, 600), headerAnswer = height =>
      Success(ChainFixtures.header(height).copy(id = "forked-" + height)))

    requester.send(actor, RestoreLatest)
    val loaded = requester.expectMsgType[SnapshotLoaded]

    loaded.snapshot shouldBe empty
    loaded.warning.get should include("not")
  }

  /**
   * Cross-field checks the checksum cannot make: bytes can be intact while the combination is one the
   * reducer would never have produced.
   */
  it should "refuse a byte-valid generation whose fields contradict each other" in {
    val header = ChainFixtures.header(600)
    val cursor = SyncCursor(600, header.id, header.parentId)
    val contradictory = CommittedSyncState(cursor, 600L, Map.empty, Map.empty,
      // A stored data-box token says this miner registered; the dictionary says it holds nobody.
      MinerTree.initialState, Some(org.ergoplatform.sdk.ErgoId.create(SyncFixtures.id(7))))

    val (actor, requester) = actorOver(saved = Seq.empty, headerAnswer = h =>
      Success(ChainFixtures.header(h)))
    requester.send(actor, StateSnapshotActor.SnapshotCandidate(contradictory, Vector(cursor), force = true))
    requester.expectNoMessage(scala.concurrent.duration.DurationInt(300).millis)

    requester.send(actor, RestoreLatest)
    val loaded = requester.expectMsgType[SnapshotLoaded]

    loaded.snapshot shouldBe empty
    loaded.warning.get should include("data-box token")
  }

  it should "refuse a generation whose retained cursors do not reach its own" in {
    val header = ChainFixtures.header(600)
    val cursor = SyncCursor(600, header.id, header.parentId)
    val stale = SyncCursor(598, ChainFixtures.header(598).id, ChainFixtures.header(597).id)

    val (actor, requester) = actorOver(saved = Seq.empty, headerAnswer = h =>
      Success(ChainFixtures.header(h)))
    requester.send(actor, StateSnapshotActor.SnapshotCandidate(stateAt(600), Vector(stale), force = true))
    requester.expectNoMessage(scala.concurrent.duration.DurationInt(300).millis)

    requester.send(actor, RestoreLatest)
    requester.expectMsgType[SnapshotLoaded].warning.get should include("retained cursors end at 598")
  }

  private def stateAt(height: Int): CommittedSyncState = {
    val header = ChainFixtures.header(height)
    CommittedSyncState(SyncCursor(height, header.id, header.parentId), height.toLong,
      Map.empty, Map.empty, MinerTree.initialState, None)
  }

  private def actorOver(saved: Seq[Int],
                        headerAnswer: Int => Try[NodeHeader]): (akka.actor.ActorRef, TestProbe) = {
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

    val actor = system.actorOf(Props(new StateSnapshotActor(config, nodeContext, protocol)))
    val requester = TestProbe()
    saved.foreach { height =>
      requester.send(actor, StateSnapshotActor.SnapshotCandidate(
        stateAt(height), Vector(stateAt(height).cursor), force = true))
    }
    // Each save is acknowledged internally; give them room before the restore reads generations.
    requester.expectNoMessage(scala.concurrent.duration.DurationInt(300).millis)
    (actor, requester)
  }
}
