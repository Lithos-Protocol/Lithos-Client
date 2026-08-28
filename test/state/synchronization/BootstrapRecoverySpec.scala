package state.synchronization

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import lfsm.states.MinerTree
import node.NodeApi
import node.model._
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.Configuration
import state.messages.StateFrameMessages.{CheckBlock, StartSynchronization}
import state.messages.SyncMessages._
import state.messages.{Capability, SyncView}
import state.persistence.StateSnapshotActor.{RestoreLatest, SnapshotLoaded}
import support.{ChainFixtures, FakeNodeContext, ReducerFixtures, SyncFixtures}
import utils.Globals

import scala.concurrent.duration._
import scala.util.{Failure, Success}

/**
 * A dictionary fault has to be recoverable without restarting the process.
 *
 * The bootstrap runs at startup and after a full rescan.
 */
class BootstrapRecoverySpec extends TestKit(ActorSystem("bootstrap-recovery"))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach
  with MockitoSugar {

  override def afterAll(): Unit = {
    Globals.setSyncView(SyncView.initial)
    TestKit.shutdownActorSystem(system)
  }

  // The published view is global, so a case that leaves the dictionary unavailable would otherwise
  // make the next producer retry before its own view is in place.
  override def beforeEach(): Unit = Globals.setSyncView(SyncView.initial)

  private val startHeight = 500
  private val genesisId = SyncFixtures.id(70000)

  /** The seed carries the reason, and block synchronization proceeds without the dictionary. */
  "StateFrame" should "seed with a fault rather than refusing to start when the bootstrap fails" in {
    val nodeApi = ChainFixtures.nodeAt(startHeight)
    failingDictionary(nodeApi)
    val (frame, sync, _) = started(nodeApi)

    val seed = sync.expectMsgType[RestoreCommittedState]
    seed.state.minerDictionaryFault should not be empty
    seed.state.minerDictionaryFault.get should include("dictionary box")
    frame ! akka.actor.PoisonPill
  }

  /**
   * The retry runs against the committed height, so it repairs in place rather than re-seeding.
   *
   * Asserted through the repair message the producer sends, because that is what clears the fault
   * for every consumer that reads the published capability.
   */
  it should "retry the bootstrap while the dictionary capability is unavailable" in {
    val nodeApi = ChainFixtures.nodeAt(startHeight)
    failingDictionary(nodeApi)
    val (frame, sync, _) = started(nodeApi)

    val seed = sync.expectMsgType[RestoreCommittedState]
    sync.reply(BlockCommitted(seed.state.cursor, 0L, 0, 0))
    sync.expectMsgType[BeginCatchUp]
    val applied = sync.expectMsgType[ApplyBlock].blockInfo
    val committed = SyncCursor(applied.height, applied.id, applied.parentId)
    sync.reply(BlockCommitted(committed, 1L, 0, 0))
    sync.expectMsg(MarkReady)

    // Canonical state is fine; the dictionary is not. That is the state a retry is for.
    Globals.setSyncView(SyncView.initial.copy(
      status = Ready(committed),
      canonical = Capability.Available,
      minerDictionary = Capability.Unavailable("bootstrap failed")))

    // The dictionary now reads cleanly, as it would once the registration that raced had landed.
    workingDictionary(nodeApi)
    frame ! CheckBlock

    val repair = sync.fishForMessage(5.seconds) {
      case _: RepairMinerDictionary => true
      case _ => false
    }.asInstanceOf[RepairMinerDictionary]
    repair.atCursor shouldEqual committed
    repair.minerTree.utxoId shouldEqual genesisId
    frame ! akka.actor.PoisonPill
  }

  /** Nothing is retried while the capability is healthy, so a working client pays nothing for this. */
  it should "not retry while the dictionary is available" in {
    val nodeApi = ChainFixtures.nodeAt(startHeight)
    workingDictionary(nodeApi)
    val (frame, sync, _) = started(nodeApi)

    val seed = sync.expectMsgType[RestoreCommittedState]
    sync.reply(BlockCommitted(seed.state.cursor, 0L, 0, 0))
    sync.expectMsgType[BeginCatchUp]
    val applied = sync.expectMsgType[ApplyBlock].blockInfo
    sync.reply(BlockCommitted(SyncCursor(applied.height, applied.id, applied.parentId), 1L, 0, 0))
    sync.expectMsg(MarkReady)

    Globals.setSyncView(SyncView.initial.copy(
      status = Ready(SyncCursor(applied.height, applied.id, applied.parentId)),
      canonical = Capability.Available,
      minerDictionary = Capability.Available))

    frame ! CheckBlock
    // Ordinary polling continues and re-marks readiness, so the assertion is about the repair alone.
    val seen = sync.receiveWhile(800.millis) { case message => message }
    seen.collect { case repair: RepairMinerDictionary => repair } shouldBe empty
    frame ! akka.actor.PoisonPill
  }

  /** The genesis box cannot be read, which is the shape an indexer gap takes. */
  private def failingDictionary(nodeApi: NodeApi): Unit =
    when(nodeApi.indexedBoxById(any[String]))
      .thenReturn(Failure(new java.net.ConnectException("index unavailable")))

  /** An unspent genesis box with an empty dictionary: a chain with no registrations yet. */
  private def workingDictionary(nodeApi: NodeApi): Unit = {
    val empty = MinerTree.initialState.dictionary
    val token = ReducerFixtures.protocol().minerDictionaryToken
    val box = IndexedBox(
      NodeBox(genesisId, SyncFixtures.id(72000), 1000000L, 0, 1, "miner-dictionary",
        Seq(NodeAsset(token.toString, 1L)), NodeRegisters(Map("R4" -> empty.ergoValue.toHex))),
      "", 1, 0L, None, None, None)

    when(nodeApi.indexedBoxById(any[String])).thenReturn(Success(Some(box)))
    when(nodeApi.unspentBoxesByTokenId(any[String], any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenReturn(Success(Seq(box)))
  }

  private def started(nodeApi: NodeApi): (akka.actor.ActorRef, TestProbe, TestProbe) = {
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
         |""".stripMargin))
    val frame = system.actorOf(Props(
      new StateFrame(config, nodeContext, protocol, sync.ref, snapshots.ref)))

    frame ! StartSynchronization
    ChainFixtures.unseeded(sync)
    snapshots.expectMsg(RestoreLatest)
    snapshots.reply(SnapshotLoaded(None))
    (frame, sync, snapshots)
  }
}
