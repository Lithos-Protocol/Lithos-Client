package state.synchronization

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import node.NodeApi
import node.model.NodeHeader
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.Configuration
import state.messages.StateFrameMessages.{CheckBlock, StartSynchronization}
import state.messages.SyncMessages._
import state.persistence.StateSnapshotActor.{RestoreLatest, SnapshotLoaded}
import support.{ChainFixtures, FakeNodeContext, ReducerFixtures}

import scala.concurrent.duration._
import scala.util.{Success, Try}

/**
 * At tip there is nothing to fetch, so nothing re-reads the committed header unless this does.
 *
 * Without it a fork that replaces the tip block at the same height stays undetected until the chain
 * grows past the local cursor, and a node whose tip fell below that cursor reads as caught up.
 */
class TipRevalidationSpec extends TestKit(ActorSystem("tip-revalidation"))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val startHeight = 500

  "StateFrame" should "detect a fork that replaces the tip block at the same height" in {
    val (frame, sync, nodeApi, committed) = caughtUp()

    // The chain keeps its height and changes its identity — the case a height comparison misses.
    respondWith(nodeApi, tip = committed.height, forkFrom = committed.height)

    frame ! CheckBlock
    sync.expectMsgType[MarkStale]
    sync.expectMsg(GetRetainedCursors)
    frame ! akka.actor.PoisonPill
  }

  it should "enter recovery when the node tip falls below the committed cursor" in {
    val (frame, sync, nodeApi, committed) = caughtUp()

    respondWith(nodeApi, tip = committed.height - 2, forkFrom = Int.MaxValue)

    frame ! CheckBlock
    sync.expectMsgType[MarkStale].reason should include("below the committed height")
    sync.expectMsg(GetRetainedCursors)
    frame ! akka.actor.PoisonPill
  }

  /** The re-read is interval-bounded, so an idle client does not pay a header call every poll. */
  it should "not re-read the committed header again inside the revalidation interval" in {
    val (frame, sync, nodeApi, committed) = caughtUp(revalidation = "1 hour")

    // The first poll at tip consumes the interval, so the fork below arrives inside it.
    frame ! CheckBlock
    sync.expectMsg(MarkReady)

    respondWith(nodeApi, tip = committed.height, forkFrom = committed.height)
    frame ! CheckBlock
    sync.expectMsg(MarkReady)
    sync.expectNoMessage(300.millis)
    frame ! akka.actor.PoisonPill
  }

  private def respondWith(nodeApi: NodeApi, tip: Int, forkFrom: Int): Unit = {
    doAnswer(new Answer[Try[node.model.NodeInfo]] {
      override def answer(invocation: InvocationOnMock): Try[node.model.NodeInfo] =
        Success(ChainFixtures.infoAt(tip))
    }).when(nodeApi).info()

    doAnswer(new Answer[Try[Seq[NodeHeader]]] {
      override def answer(invocation: InvocationOnMock): Try[Seq[NodeHeader]] = {
        val from = Option(invocation.getArgument[Option[Int]](0)).flatten.getOrElse(0)
        val to = Option(invocation.getArgument[Option[Int]](1)).flatten.getOrElse(tip)
        Success((math.max(from + 1, 1) to math.min(to, tip)).map { height =>
          val header = ChainFixtures.header(height)
          if (height >= forkFrom) header.copy(id = "forked-" + height) else header
        })
      }
    }).when(nodeApi).chainSlice(any[Option[Int]], any[Option[Int]])
  }

  /** A producer caught up at the tip, which is the only state where the re-read matters. */
  private def caughtUp(revalidation: String = "0 seconds"): (akka.actor.ActorRef, TestProbe, NodeApi, SyncCursor) = {
    val sync = TestProbe()
    val snapshots = TestProbe()
    val nodeApi = ChainFixtures.nodeAt(startHeight)
    val (nodeContext, _, wallet) = FakeNodeContext(nodeApi, numAddresses = 1)
    val protocol = ReducerFixtures.protocol(rollupStartHeight = startHeight)
      .copy(localMinerHash = wallet.contract.hashedPropBytes)
    val config = Configuration(ConfigFactory.parseString(
      s"""sync.startHeight = $startHeight
         |sync.catchUpBatchBlocks = 4
         |sync.minerDictionary.bootstrap = false
         |sync.tipRevalidation = $revalidation
         |""".stripMargin))
    val frame = system.actorOf(Props(
      new StateFrame(config, nodeContext, protocol, sync.ref, snapshots.ref)))

    frame ! StartSynchronization
    snapshots.expectMsg(RestoreLatest)
    snapshots.reply(SnapshotLoaded(None))
    val seed = sync.expectMsgType[RestoreCommittedState]
    sync.reply(BlockCommitted(seed.state.cursor, 0L, 0, 0))
    sync.expectMsgType[BeginCatchUp]
    val applied = sync.expectMsgType[ApplyBlock].blockInfo
    val committed = SyncCursor(applied.height, applied.id, applied.parentId)
    sync.reply(BlockCommitted(committed, 1L, 0, 0))
    sync.expectMsg(MarkReady)
    (frame, sync, nodeApi, committed)
  }
}
