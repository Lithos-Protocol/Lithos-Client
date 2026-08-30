package state.synchronization

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import node.NodeApi
import node.model.BlockchainIndexHeight
import org.mockito.Mockito.when
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.Configuration
import state.messages.StateFrameMessages.StartSynchronization
import state.messages.SyncMessages._
import state.persistence.StateSnapshotActor.{RestoreLatest, SnapshotLoaded}
import support.{ChainFixtures, FakeNodeContext, ReducerFixtures}

import scala.concurrent.duration._
import scala.util.Success

/**
 * Blocks come from the extra index, which trails the chain while it catches up.
 *
 * The two heights answer different questions and must not be collapsed: the index says how far this
 * client can read, and only the header chain says whether a fork happened.
 */
class IndexedBlockGatingSpec extends TestKit(ActorSystem("indexed-block-gating"))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val startHeight = 500

  "CanonicalBlockSource" should "read no further than the index while reporting the real chain height" in {
    val nodeApi = ChainFixtures.nodeAt(startHeight + 40)
    when(nodeApi.indexedHeight()).thenReturn(
      Success(BlockchainIndexHeight(startHeight + 10, startHeight + 40)))

    val heights = new CanonicalBlockSource(nodeApi).heights.get

    heights.usable shouldEqual startHeight + 10
    // The chain height must survive the minimum, or an index that is merely behind reads as a fork.
    heights.chain shouldEqual startHeight + 40
  }

  it should "follow the chain when the index has caught up past it" in {
    val nodeApi = ChainFixtures.nodeAt(startHeight + 5)
    when(nodeApi.indexedHeight()).thenReturn(
      Success(BlockchainIndexHeight(startHeight + 40, startHeight + 5)))

    val heights = new CanonicalBlockSource(nodeApi).heights.get

    heights.usable shouldEqual startHeight + 5
    heights.chain shouldEqual startHeight + 5
  }

  "StateFrame" should "commit nothing above the indexed height" in {
    val nodeApi = ChainFixtures.nodeAt(startHeight + 20)
    // The chain is well ahead, but only the start height itself is queryable.
    when(nodeApi.indexedHeight()).thenReturn(
      Success(BlockchainIndexHeight(startHeight, startHeight + 20)))
    val (frame, sync) = started(nodeApi)

    sync.expectMsgType[BeginCatchUp]
    val applied = sync.expectMsgType[ApplyBlock].blockInfo
    applied.height shouldEqual startHeight
    sync.reply(BlockCommitted(SyncCursor(applied.height, applied.id, applied.parentId), 1L, 0, 0))

    // Nothing above the index may follow, even though the chain has twenty more blocks.
    sync.expectMsg(MarkReady)
    sync.expectNoMessage(400.millis)
    frame ! akka.actor.PoisonPill
  }

  private def started(nodeApi: NodeApi): (akka.actor.ActorRef, TestProbe) = {
    val sync = TestProbe()
    val snapshots = TestProbe()
    val (nodeContext, _, wallet) = FakeNodeContext(nodeApi, numAddresses = 1)
    val protocol = ReducerFixtures.protocol(rollupStartHeight = startHeight)
      .copy(localMinerHash = wallet.contract.hashedPropBytes)
    val config = Configuration(ConfigFactory.parseString(
      s"""sync.startHeight = $startHeight
         |sync.catchUpBatchBlocks = 4
         |sync.minerDictionary.bootstrap = false
         |sync.quarantine.repairAttempts = 0
         |""".stripMargin))
    val frame = system.actorOf(Props(
      new StateFrame(config, nodeContext, protocol, sync.ref, snapshots.ref)))

    frame ! StartSynchronization
    ChainFixtures.unseeded(sync)
    snapshots.expectMsg(RestoreLatest)
    snapshots.reply(SnapshotLoaded(None))
    val seed = sync.expectMsgType[RestoreCommittedState]
    sync.reply(BlockCommitted(seed.state.cursor, 0L, 0, 0))
    (frame, sync)
  }
}
