package state.synchronization

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import lfsm.states.MinerDictionary
import node.NodeApi
import node.model._
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{doAnswer, when}
import org.mockito.invocation.InvocationOnMock
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import play.api.Configuration
import state.messages.StateFrameMessages.{CheckBlock, StartSynchronization}
import state.messages.SyncMessages._
import state.messages.{Capability, SyncView}
import state.persistence.StateSnapshotActor.{RestoreLatest, SnapshotLoaded}
import support.{ChainFixtures, FakeNodeContext, ReducerFixtures, SyncFixtures}
import utils.Globals

import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

/**
 * A dictionary retry and a quarantine checkpoint must never share completion state.
 *
 * They are gated separately: a dictionary walk suppresses the maintenance checkpoints that would
 * freeze catch-up, and nothing else.
 */
class CheckpointIsolationSpec extends TestKit(ActorSystem("checkpoint-isolation"))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  override def beforeEach(): Unit = Globals.setSyncView(SyncView.initial)

  override def afterAll(): Unit = {
    Globals.setSyncView(SyncView.initial)
    TestKit.shutdownActorSystem(system)
  }

  private val startHeight = 500
  private val genesisId = SyncFixtures.id(790000)

  "A Miner Dictionary repair running beside catch-up" should
    "prevent maintenance checkpoints until its own publication completes" in {
    val nodeApi = ChainFixtures.nodeAt(startHeight)
    when(nodeApi.indexedBoxById(any[String]))
      .thenReturn(Failure(new java.net.ConnectException("index unavailable")))
    val (frame, sync) = started(nodeApi)
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)

    try {
      val seed = sync.expectMsgType[RestoreCommittedState]
      seed.state.minerDictionaryFault should not be empty
      sync.reply(BlockCommitted(seed.state.cursor, 0L, 0, 0))
      sync.expectMsgType[BeginCatchUp]
      val initial = sync.expectMsgType[ApplyBlock].blockInfo
      val readyAt = SyncCursor(initial.height, initial.id, initial.parentId)
      sync.reply(BlockCommitted(readyAt, 1L, 0, 0))
      sync.expectMsg(MarkReady)

      Globals.setSyncView(SyncView.initial.copy(status = Ready(readyAt),
        canonical = Capability.Available,
        minerDictionary = Capability.Unavailable("bootstrap failed")))
      advanceCanonical(nodeApi, startHeight + 3)
      blockingDictionary(nodeApi, entered, release)

      // This Tick starts the dictionary worker and canonical polling together while the old tip is
      // Ready. The worker remains blocked as three new blocks commit.
      frame ! CheckBlock
      sync.expectMsg(GetMinerDictionaryRepairPermit)
      sync.reply(MinerDictionaryRepairPermit(readyAt, "dictionary-permit"))

      // A blocked dictionary walk does not suppress the quarantine query. Sharing one gate meant a
      // faulted dictionary silently stopped every rollup rebuild — the repair that recovers a
      // payout — for as long as the fault lasted.
      sync.expectMsg(GetRepairableQuarantines)
      sync.reply(RepairableQuarantines(Seq.empty, readyAt, "quarantine-permit"))

      sync.expectMsgType[BeginCatchUp]
      val committed = (1 to 3).map { _ =>
        val block = sync.expectMsgType[ApplyBlock](2.seconds).blockInfo
        sync.reply(BlockCommitted(SyncCursor(block.height, block.id, block.parentId),
          block.height.toLong, 0, 0))
        block.height
      }
      // checkpointIntervalBlocks is 1, so every one of these commits would freeze the producer for a
      // maintenance checkpoint if the dictionary walk did not suppress them. Reaching the tip is
      // what proves it still does: the query above freezes nothing, a checkpoint would.
      committed shouldEqual Seq(startHeight + 1, startHeight + 2, startHeight + 3)
      sync.expectMsg(MarkReady)
      entered.await(2, TimeUnit.SECONDS) shouldBe true

      release.countDown()
      val repair = sync.expectMsgType[RepairMinerDictionary](2.seconds)
      repair.atCursor shouldEqual readyAt
      sync.reply(BlockRejected(Some(SyncCursor(startHeight + 3,
        ChainFixtures.headerId(startHeight + 3), ChainFixtures.headerId(startHeight + 2))), "",
        "dictionary repair cursor is stale"))
      sync.expectNoMessage(250.millis)
    } finally {
      release.countDown()
      frame ! akka.actor.PoisonPill
    }
  }

  private def advanceCanonical(nodeApi: NodeApi, tip: Int): Unit = {
    when(nodeApi.info()).thenReturn(Success(ChainFixtures.infoAt(tip)))
    when(nodeApi.indexedHeight()).thenReturn(Success(BlockchainIndexHeight(tip, tip)))
    doAnswer((invocation: InvocationOnMock) => {
      val from = invocation.getArgument[Option[Int]](0).getOrElse(0)
      val to = invocation.getArgument[Option[Int]](1).getOrElse(tip)
      val lowest = math.max(from + 1, 1)
      val highest = math.min(to, tip)
      (Success(if (highest < lowest) Seq.empty else (lowest to highest).map(ChainFixtures.header)):
        Try[Seq[NodeHeader]])
    }).when(nodeApi).chainSlice(any[Option[Int]], any[Option[Int]])
    doAnswer((invocation: InvocationOnMock) => {
      val ids = invocation.getArgument[Seq[String]](0)
      val blocks = (1 to tip).map(h => ChainFixtures.headerId(h) -> ChainFixtures.block(h)).toMap
      Success(ids.flatMap(blocks.get)): Try[Seq[IndexedBlock]]
    }).when(nodeApi).indexedBlocksByHeaderIds(any[Seq[String]])
  }

  private def blockingDictionary(nodeApi: NodeApi,
                                 entered: CountDownLatch,
                                 release: CountDownLatch): Unit = {
    val empty = MinerDictionary.initialState.dictionary
    val token = ReducerFixtures.protocol().minerDictionaryToken
    val box = IndexedBox(
      NodeBox(genesisId, SyncFixtures.id(790001), 1000000L, 0, 1, "miner-dictionary",
        Seq(NodeAsset(token.toString, 1L)), NodeRegisters(Map("R4" -> empty.ergoValue.toHex))),
      "", 1, 0L, None, None, None)
    when(nodeApi.indexedBoxById(any[String])).thenAnswer { _ =>
      entered.countDown()
      release.await(5, TimeUnit.SECONDS)
      Success(Some(box)): Try[Option[IndexedBox]]
    }
    when(nodeApi.unspentBoxesByTokenId(any[String], any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenReturn(Success(Seq(box)))
  }

  private def started(nodeApi: NodeApi): (akka.actor.ActorRef, TestProbe) = {
    val sync = TestProbe()
    val snapshots = TestProbe()
    val (nodeContext, _, wallet) = FakeNodeContext(nodeApi, numAddresses = 1)
    val protocol = ReducerFixtures.protocol(rollupStartHeight = startHeight)
      .copy(localMinerHash = wallet.contract.hashedPropBytes, minerDictionaryGenesisId = genesisId)
    val config = Configuration(ConfigFactory.parseString(
      s"""sync.startHeight = $startHeight
         |sync.catchUpBatchBlocks = 4
         |sync.minerDictionary.bootstrap = true
         |sync.minerDictionary.repairInterval = 0 seconds
         |sync.quarantine.maintenanceCheckpoints = true
         |sync.quarantine.checkpointIntervalBlocks = 1
         |""".stripMargin))
    val frame = system.actorOf(Props(new StateFrame(config, nodeContext, protocol,
      sync.ref, snapshots.ref)))

    frame ! StartSynchronization
    ChainFixtures.unseeded(sync)
    snapshots.expectMsg(RestoreLatest)
    snapshots.reply(SnapshotLoaded(None))
    frame -> sync
  }
}
