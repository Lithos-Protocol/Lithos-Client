package state.synchronization

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import node.NodeApi
import node.model.{BlockchainIndexHeight, IndexedBlock, NodeBox}
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{doAnswer, when}
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
 * Blocks and input boxes come from the extra index, which trails the chain while it catches up, and a
 * block whose input boxes arrive without scripts authenticates no genesis.
 *
 * Neither is visible from the chain height alone, and a committed block is never re-read — so a rollup
 * missed for either reason is missed permanently.
 */
class IndexedBlockGatingSpec extends TestKit(ActorSystem("indexed-block-gating"))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val startHeight = 500

  "CanonicalBlockSource" should "follow the index rather than the chain when the index is behind" in {
    val nodeApi = ChainFixtures.nodeAt(startHeight + 40)
    when(nodeApi.indexedHeight()).thenReturn(
      Success(BlockchainIndexHeight(startHeight + 10, startHeight + 40)))

    new CanonicalBlockSource(nodeApi).tipHeight.get shouldEqual startHeight + 10
  }

  it should "follow the chain when the index has caught up past it" in {
    val nodeApi = ChainFixtures.nodeAt(startHeight + 5)
    when(nodeApi.indexedHeight()).thenReturn(
      Success(BlockchainIndexHeight(startHeight + 40, startHeight + 5)))

    new CanonicalBlockSource(nodeApi).tipHeight.get shouldEqual startHeight + 5
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

  /**
   * Refetching is bounded: the shape is a strong signal, not an established invariant, so refusing
   * forever would stall the cursor on an unproven claim.
   */
  it should "refetch a block whose input boxes carry no script, then accept it" in {
    val nodeApi = ChainFixtures.nodeAt(startHeight)
    // `doAnswer`, because `when` calls the method to record it and the fixture's existing answer
    // would then run against null arguments.
    doAnswer(new Answer[Try[Seq[IndexedBlock]]] {
      override def answer(invocation: InvocationOnMock): Try[Seq[IndexedBlock]] = {
        val ids = Option(invocation.getArgument[Seq[String]](0)).getOrElse(Seq.empty)
        Success(ids.map(_ => stripScripts(ChainFixtures.block(startHeight))))
      }
    }).when(nodeApi).indexedBlocksByHeaderIds(any[Seq[String]])
    val (frame, sync) = started(nodeApi, retries = 2)

    // Two refetches are refused before the block is taken as it is.
    (1 to 2).foreach { _ =>
      sync.expectMsgType[BeginCatchUp]
      sync.expectMsgType[MarkStale].reason should include("resolved no input scripts")
      frame ! CheckBlock
    }

    sync.expectMsgType[BeginCatchUp]
    sync.expectMsgType[ApplyBlock].blockInfo.height shouldEqual startHeight
    frame ! akka.actor.PoisonPill
  }

  private def stripScripts(block: IndexedBlock): IndexedBlock =
    block.copy(blockTransactions = block.blockTransactions.map(tx =>
      tx.copy(inputs = tx.inputs.map(in => in.copy(box = in.box.copy(ergoTree = ""))))))

  private def started(nodeApi: NodeApi, retries: Int = 12): (akka.actor.ActorRef, TestProbe) = {
    val sync = TestProbe()
    val snapshots = TestProbe()
    val (nodeContext, _, wallet) = FakeNodeContext(nodeApi, numAddresses = 1)
    val protocol = ReducerFixtures.protocol(rollupStartHeight = startHeight)
      .copy(localMinerHash = wallet.contract.hashedPropBytes)
    val config = Configuration(ConfigFactory.parseString(
      s"""sync.startHeight = $startHeight
         |sync.catchUpBatchBlocks = 4
         |sync.minerDictionary.bootstrap = false
         |sync.incompleteBlockRetries = $retries
         |""".stripMargin))
    val frame = system.actorOf(Props(
      new StateFrame(config, nodeContext, protocol, sync.ref, snapshots.ref)))

    frame ! StartSynchronization
    snapshots.expectMsg(RestoreLatest)
    snapshots.reply(SnapshotLoaded(None))
    val seed = sync.expectMsgType[RestoreCommittedState]
    sync.reply(BlockCommitted(seed.state.cursor, 0L, 0, 0))
    (frame, sync)
  }
}
