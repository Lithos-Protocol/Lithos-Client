package state.synchronization

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import node.NodeApi
import node.model.{BlockchainIndexHeight, NodeHeader, NodeInfo}
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
 * A node that cannot serve the committed height is not the same event as a chain that no longer
 * contains it, and the two need opposite responses.
 *
 * This Spec checks different conditions between the indexed and header height.
 */
class TipRegressionRecoverySpec extends TestKit(ActorSystem("tip-regression-recovery"))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val startHeight = 500

  /**
   * The chain is intact and only the index trails, so the answer is to wait. Attempting recovery
   * here would discard good state for a condition that clears itself.
   */
  "StateFrame" should "wait, not reorganize, when only the index falls below the committed cursor" in {
    val (frame, sync, _, nodeApi, committed) = caughtUp()

    // Headers still cover the cursor; the index has rolled back below it.
    respondWith(nodeApi, chain = committed.height + 3, indexed = committed.height - 2)

    frame ! CheckBlock
    val stale = sync.expectMsgType[MarkStale]
    stale.reason should include("node index is at height")
    stale.reason should include("waiting for indexing to catch up")

    // The assertion that matters: no reorganization is attempted over an intact chain.
    sync.expectNoMessage(400.millis)
    frame ! akka.actor.PoisonPill
  }

  /**
   * The header chain really is shorter, so this is a fork. It must reach a recovery tier rather than
   * re-detecting itself: every retained cursor sits above the chain's end, so none can be canonical.
   */
  it should "reach snapshot recovery when the header chain falls below every retained cursor" in {
    val (frame, sync, snapshots, nodeApi, committed) = caughtUp()

    respondWith(nodeApi, chain = committed.height - 2, indexed = committed.height - 2)

    frame ! CheckBlock
    sync.expectMsgType[MarkStale].reason should include("below the committed height")
    sync.expectMsg(GetRetainedCursors)
    // Every cursor this client holds is above where the chain now ends.
    sync.reply(RetainedCursors(Seq(committed)))

    // Before the fix this failed the header-count check and fell back into the same stale loop,
    // because the range asked for reached above the chain's end.
    snapshots.expectMsg(2.seconds, RestoreLatest)
    frame ! akka.actor.PoisonPill
  }

  /** With a cursor the shortened chain still contains, the rollback target is that cursor. */
  it should "roll back to the newest retained cursor the shortened chain still contains" in {
    val (frame, sync, _, nodeApi, committed) = caughtUp()
    val survivor = SyncCursor(committed.height - 2, ChainFixtures.headerId(committed.height - 2),
      ChainFixtures.headerId(committed.height - 3))

    respondWith(nodeApi, chain = committed.height - 2, indexed = committed.height - 2)

    frame ! CheckBlock
    sync.expectMsgType[MarkStale]
    sync.expectMsg(GetRetainedCursors)
    sync.reply(RetainedCursors(Seq(survivor, committed)))

    sync.expectMsg(RollbackTo(survivor.blockId))
    frame ! akka.actor.PoisonPill
  }

  /** Reports the two heights independently, which one number could not express. */
  private def respondWith(nodeApi: NodeApi, chain: Int, indexed: Int): Unit = {
    doAnswer(new Answer[Try[NodeInfo]] {
      override def answer(invocation: InvocationOnMock): Try[NodeInfo] = Success(ChainFixtures.infoAt(chain))
    }).when(nodeApi).info()

    doAnswer(new Answer[Try[BlockchainIndexHeight]] {
      override def answer(invocation: InvocationOnMock): Try[BlockchainIndexHeight] =
        Success(BlockchainIndexHeight(indexed, chain))
    }).when(nodeApi).indexedHeight()

    doAnswer(new Answer[Try[Seq[NodeHeader]]] {
      override def answer(invocation: InvocationOnMock): Try[Seq[NodeHeader]] = {
        val from = Option(invocation.getArgument[Option[Int]](0)).flatten.getOrElse(0)
        val to = Option(invocation.getArgument[Option[Int]](1)).flatten.getOrElse(chain)
        val lowest = math.max(from + 1, 1)
        // The node walks back from its best header, so a request above the chain's end stops there.
        val highest = math.min(to, chain)
        Success(if (highest < lowest) Seq.empty else (lowest to highest).map(ChainFixtures.header))
      }
    }).when(nodeApi).chainSlice(any[Option[Int]], any[Option[Int]])
  }

  private def caughtUp(): (akka.actor.ActorRef, TestProbe, TestProbe, NodeApi, SyncCursor) = {
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
         |sync.revalidationChecks = 0 seconds
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
    (frame, sync, snapshots, nodeApi, committed)
  }
}
