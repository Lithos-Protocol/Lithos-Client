package state.synchronization

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import play.api.Configuration
import state.messages.StateFrameMessages.StartSynchronization
import state.messages.SyncMessages._
import state.persistence.StateSnapshotActor.{RestoreLatest, SnapshotLoaded}
import support.{ChainFixtures, FakeNodeContext, ReducerFixtures, SyncFixtures}

import scala.concurrent.duration._

/**
 * A producer restart must not undo committed work the state owner still holds.
 * Restoring a snapshot instead discards everything committed since it was written.
 */
class StateFrameRestartSpec extends TestKit(ActorSystem("state-frame-restart"))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val startHeight = 500

  "StateFrame" should "resume from state the owner still holds rather than a snapshot behind it" in {
    val sync = TestProbe()
    val snapshots = TestProbe()
    val frame = producer(sync, snapshots, tip = startHeight + 30)
    val held = ReducerFixtures.emptyState(height = startHeight + 20,
      blockId = ChainFixtures.headerId(startHeight + 20))

    frame ! StartSynchronization
    sync.expectMsg(GetCommittedState)
    sync.reply(CommittedState(held))

    // No snapshot is read, so nothing can roll the owner back to one.
    snapshots.expectNoMessage(300.millis)
    sync.expectMsgType[BeginCatchUp]
    // Polling continues from the height the owner had reached, not from the start height.
    sync.expectMsgType[ApplyBlock].blockInfo.height shouldEqual startHeight + 21
    frame ! akka.actor.PoisonPill
  }

  /** A cold start has no owner state, so the snapshot path is still the one that runs. */
  it should "fall through to the snapshot when the owner holds nothing" in {
    val sync = TestProbe()
    val snapshots = TestProbe()
    val frame = producer(sync, snapshots, tip = startHeight + 3)

    frame ! StartSynchronization
    ChainFixtures.unseeded(sync)

    snapshots.expectMsg(RestoreLatest)
    snapshots.reply(SnapshotLoaded(None))
    sync.expectMsgType[RestoreCommittedState].state.cursor.height shouldEqual startHeight - 1
    frame ! akka.actor.PoisonPill
  }

  /** Adopting a cursor is safe because the next poll revalidates it against the header chain. */
  it should "reorganize from adopted state whose cursor is no longer canonical" in {
    val sync = TestProbe()
    val snapshots = TestProbe()
    val frame = producer(sync, snapshots, tip = startHeight + 30)
    val orphaned = ReducerFixtures.emptyState(height = startHeight + 20,
      blockId = SyncFixtures.id(999999))

    frame ! StartSynchronization
    sync.expectMsg(GetCommittedState)
    sync.reply(CommittedState(orphaned))

    // The fork is found in the poll, before any batch is asked for, so no catch-up is announced.
    sync.expectMsgType[MarkStale].reason should include("diverged")
    sync.expectMsg(GetRetainedCursors)
    frame ! akka.actor.PoisonPill
  }

  private def producer(sync: TestProbe, snapshots: TestProbe, tip: Int): akka.actor.ActorRef = {
    val (nodeContext, _, wallet) = FakeNodeContext(ChainFixtures.nodeAt(tip), numAddresses = 1)
    val protocol = ReducerFixtures.protocol(rollupStartHeight = startHeight)
      .copy(localMinerHash = wallet.contract.hashedPropBytes)
    val config = Configuration(ConfigFactory.parseString(
      s"""sync.startHeight = $startHeight
         |sync.catchUpBatchBlocks = 4
         |sync.minerDictionary.bootstrap = false
         |""".stripMargin))
    system.actorOf(Props(new StateFrame(config, nodeContext, protocol, sync.ref, snapshots.ref)))
  }
}
