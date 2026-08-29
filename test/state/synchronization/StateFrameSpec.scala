package state.synchronization

import akka.actor.{ActorIdentity, ActorSystem, Identify, Kill, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import play.api.Configuration
import state.messages.StateFrameMessages.{NewBlock, StartSynchronization}
import state.messages.SyncMessages._
import state.persistence.StateSnapshotActor.{RestoreLatest, SnapshotLoaded}
import support.{ChainFixtures, FakeNodeContext, ReducerFixtures, RestartingSupervisor}

import scala.concurrent.duration._

/**
 * Covers canonical producer ordering, retry, and start-height behavior through the production block source.
 * Dictionary bootstrap is disabled so these tests isolate block production.
 */
class StateFrameSpec extends TestKit(ActorSystem("state-frame"))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val startHeight = 500

  /** Verifies the first emitted block, not only the configured start-height value. */
  "StateFrame" should "begin at the configured start height, not below it" in {
    val (frame, sync, _) = started(tip = startHeight + 3)

    sync.expectMsgType[BeginCatchUp]
    sync.expectMsgType[ApplyBlock].blockInfo.height shouldEqual startHeight
    frame ! akka.actor.PoisonPill
  }

  // Batching changes fetches only; commits remain ordered and one at a time.
  it should "apply a fetched batch one block at a time, in chain order" in {
    val (frame, sync, _) = started(tip = startHeight + 3)
    sync.expectMsgType[BeginCatchUp]

    val heights = (0 to 3).map { _ =>
      val applied = sync.expectMsgType[ApplyBlock].blockInfo
      sync.reply(commit(applied.height, applied.id, applied.parentId))
      applied.height
    }

    heights shouldEqual Seq(startHeight, startHeight + 1, startHeight + 2, startHeight + 3)
    frame ! akka.actor.PoisonPill
  }

  it should "publish a committed block to its subscribers only after the commit" in {
    val (frame, sync, _) = started(tip = startHeight)
    val subscriber = TestProbe()
    frame ! AutoSubscribable.AutoSubscribe(subscriber.ref)

    sync.expectMsgType[BeginCatchUp]
    val applied = sync.expectMsgType[ApplyBlock].blockInfo
    subscriber.expectNoMessage(200.millis)

    sync.reply(commit(applied.height, applied.id, applied.parentId))
    subscriber.expectMsgType[NewBlock].blockInfo.id shouldEqual applied.id
    frame ! akka.actor.PoisonPill
  }

  it should "mark ready once the committed cursor reaches the tip" in {
    val (frame, sync, _) = started(tip = startHeight)
    sync.expectMsgType[BeginCatchUp]
    val applied = sync.expectMsgType[ApplyBlock].blockInfo
    sync.reply(commit(applied.height, applied.id, applied.parentId))

    sync.expectMsg(MarkReady)
    frame ! akka.actor.PoisonPill
  }

  /**
   * A rejected block invalidates the later blocks fetched with it, so the retry must re-derive the batch
   * rather than replay what it already held. Asserted by the height the retry asks for: a retained batch
   * would offer the block after the rejected one.
   */
  it should "abandon the rest of a batch when one block is rejected" in {
    val (frame, sync, _) = started(tip = startHeight + 3)
    sync.expectMsgType[BeginCatchUp]
    val first = sync.expectMsgType[ApplyBlock].blockInfo
    first.height shouldEqual startHeight

    sync.reply(BlockRejected(None, first.id, "state invariant failed"))
    sync.expectMsgType[MarkStale].reason should include("state invariant")

    frame ! state.messages.StateFrameMessages.CheckBlock
    sync.expectMsgType[BeginCatchUp]
    sync.expectMsgType[ApplyBlock].blockInfo.height shouldEqual startHeight
    frame ! akka.actor.PoisonPill
  }

  /** Re-derives from the last committed cursor after a block-wide rejection. */
  it should "re-derive from the committed cursor after a rejection rather than skipping ahead" in {
    val (frame, sync, _) = started(tip = startHeight + 1)
    sync.expectMsgType[BeginCatchUp]
    val first = sync.expectMsgType[ApplyBlock].blockInfo
    sync.reply(commit(first.height, first.id, first.parentId))

    val second = sync.expectMsgType[ApplyBlock].blockInfo
    second.height shouldEqual startHeight + 1
    sync.reply(BlockRejected(None, second.id, "unattributable failure"))
    sync.expectMsgType[MarkStale]

    frame ! state.messages.StateFrameMessages.CheckBlock
    sync.expectMsgType[BeginCatchUp]
    sync.expectMsgType[ApplyBlock].blockInfo.height shouldEqual startHeight + 1
    frame ! akka.actor.PoisonPill
  }

  /** Keeps retrying a stalled height while reporting the configured failure threshold. */
  it should "keep retrying a rejected height rather than stopping" in {
    val (frame, sync, _) = started(tip = startHeight + 1, retriesBeforeAlarm = 2)
    sync.expectMsgType[BeginCatchUp]

    val heights = (1 to 3).map { _ =>
      val applied = sync.expectMsgType[ApplyBlock].blockInfo
      sync.reply(BlockRejected(None, applied.id, "unattributable failure"))
      sync.expectMsgType[MarkStale]
      frame ! state.messages.StateFrameMessages.CheckBlock
      sync.expectMsgType[BeginCatchUp]
      applied.height
    }

    // Rejection leaves the cursor at the unapplied height.
    heights.distinct shouldEqual Seq(startHeight)
    frame ! akka.actor.PoisonPill
  }

  it should "clear a stall once the cursor moves again" in {
    val (frame, sync, _) = started(tip = startHeight + 1, retriesBeforeAlarm = 2)
    sync.expectMsgType[BeginCatchUp]

    val first = sync.expectMsgType[ApplyBlock].blockInfo
    sync.reply(BlockRejected(None, first.id, "transient failure"))
    sync.expectMsgType[MarkStale]

    frame ! state.messages.StateFrameMessages.CheckBlock
    sync.expectMsgType[BeginCatchUp]
    val retried = sync.expectMsgType[ApplyBlock].blockInfo
    retried.height shouldEqual startHeight
    sync.reply(commit(retried.height, retried.id, retried.parentId))

    sync.expectMsgType[ApplyBlock].blockInfo.height shouldEqual startHeight + 1
    frame ! akka.actor.PoisonPill
  }

  it should "ignore an ask completion created by an actor incarnation that restarted" in {
    val (frame, sync, _) = started(tip = startHeight + 1, restartable = true)
    sync.expectMsgType[BeginCatchUp]
    val staleApply = sync.expectMsgType[ApplyBlock]

    // Kill restarts the actor without changing its ActorRef. The outstanding ask still completes and
    // pipes its result to that ref, but it belongs to the stopped instance.
    frame ! Kill
    frame.tell(Identify("restarted"), testActor)
    expectMsg(ActorIdentity("restarted", Some(frame)))
    sync.reply(commit(staleApply.blockInfo.height, staleApply.blockInfo.id,
      staleApply.blockInfo.parentId))

    // Processing the stale completion would continue the old retained batch with height start + 1.
    sync.expectNoMessage(300.millis)
    frame ! StartSynchronization
    sync.expectMsg(GetCommittedState)
    frame ! akka.actor.PoisonPill
  }

  it should "freeze catch-up at the configured maintenance checkpoint until a repair is published" in {
    val (frame, sync, _) = started(tip = startHeight + 3, checkpointInterval = 2)
    sync.expectMsgType[BeginCatchUp]

    val first = sync.expectMsgType[ApplyBlock].blockInfo
    sync.reply(commit(first.height, first.id, first.parentId))
    val second = sync.expectMsgType[ApplyBlock].blockInfo
    sync.reply(commit(second.height, second.id, second.parentId))

    sync.expectMsg(GetRepairableQuarantines)
    val at = SyncCursor(second.height, second.id, second.parentId)
    val fault = QuarantineFault(ChainFixtures.headerId(100), 100, "missing-collateral",
      "indexed decode gap", retryable = true)
    sync.reply(RepairableQuarantines(Seq(fault), at, "repair-permit"))

    val repair = sync.expectMsgType[RepairRollup]
    repair.rollupId shouldEqual fault.rollupId
    repair.atCursor shouldEqual at
    sync.expectNoMessage(200.millis)

    sync.reply(BlockRejected(Some(at), "", "repair cursor moved before publication"))
    sync.expectMsgType[ApplyBlock].blockInfo.height shouldEqual startHeight + 2
    frame ! akka.actor.PoisonPill
  }

  it should "continue catch-up without checkpoints when maintenance is disabled" in {
    val (frame, sync, _) = started(tip = startHeight + 2, checkpoints = false,
      checkpointInterval = 1)
    sync.expectMsgType[BeginCatchUp]

    val first = sync.expectMsgType[ApplyBlock].blockInfo
    sync.reply(commit(first.height, first.id, first.parentId))

    sync.expectMsgType[ApplyBlock].blockInfo.height shouldEqual startHeight + 1
    frame ! akka.actor.PoisonPill
  }

  /**
   * Waits without committing while the chain tip is below the configured start height.
   *
   * The seed is established first, so the producer reaches its polling loop.
   */
  it should "commit nothing while the tip is below the configured start height" in {
    val sync = TestProbe()
    val snapshots = TestProbe()
    // The tip serves the seed header at startHeight - 1 but nothing at startHeight or above.
    val (nodeContext, _, wallet) = FakeNodeContext(
      ChainFixtures.nodeAt(startHeight - 1), numAddresses = 1)
    val protocol = ReducerFixtures.protocol(rollupStartHeight = startHeight)
      .copy(localMinerHash = wallet.contract.hashedPropBytes)
    val frame = system.actorOf(Props(
      new StateFrame(configWith(startHeight), nodeContext, protocol, sync.ref, snapshots.ref)))

    frame ! StartSynchronization
    ChainFixtures.unseeded(sync)
    snapshots.expectMsg(RestoreLatest)
    snapshots.reply(SnapshotLoaded(None))

    // The seed commits at startHeight - 1; nothing above it may follow.
    val restore = sync.expectMsgType[RestoreCommittedState]
    restore.state.cursor.height shouldEqual startHeight - 1
    sync.reply(BlockCommitted(restore.state.cursor, 0L, 0, 0))

    sync.expectMsg(MarkReady)
    sync.expectNoMessage(500.millis)
    frame ! akka.actor.PoisonPill
  }

  private def configWith(start: Int,
                         retriesBeforeAlarm: Int = 12,
                         checkpoints: Boolean = true,
                         checkpointInterval: Int = 10): Configuration =
    Configuration(ConfigFactory.parseString(
      s"""sync.startHeight = $start
         |sync.catchUpBatchBlocks = 4
         |sync.minerDictionary.bootstrap = false
         |sync.retriesBeforeAlarm = $retriesBeforeAlarm
         |sync.quarantine.maintenanceCheckpoints = $checkpoints
         |sync.quarantine.checkpointIntervalBlocks = $checkpointInterval
         |""".stripMargin))

  private def commit(height: Int, blockId: String, parentId: String): BlockCommitted =
    BlockCommitted(SyncCursor(height, blockId, parentId), height.toLong, 0, 0)

  /** Starts a producer on the first-run path with no snapshot or committed state. */
  private def started(tip: Int,
                      retriesBeforeAlarm: Int = 12,
                      checkpoints: Boolean = true,
                      checkpointInterval: Int = 10,
                      restartable: Boolean = false): (akka.actor.ActorRef, TestProbe, TestProbe) = {
    val sync = TestProbe()
    val snapshots = TestProbe()
    val (nodeContext, _, wallet) = FakeNodeContext(ChainFixtures.nodeAt(tip), numAddresses = 1)
    val protocol = ReducerFixtures.protocol(rollupStartHeight = startHeight)
      .copy(localMinerHash = wallet.contract.hashedPropBytes)
    val childProps = Props(
      new StateFrame(configWith(startHeight, retriesBeforeAlarm, checkpoints, checkpointInterval),
        nodeContext, protocol,
        sync.ref, snapshots.ref))
    val frame = if (restartable) {
      val supervisor = system.actorOf(RestartingSupervisor.props(childProps))
      val requester = TestProbe()
      requester.send(supervisor, RestartingSupervisor.GetChild)
      requester.expectMsgType[RestartingSupervisor.Child].ref
    } else system.actorOf(childProps)
    frame ! StartSynchronization

    ChainFixtures.unseeded(sync)
    snapshots.expectMsg(RestoreLatest)
    snapshots.reply(SnapshotLoaded(None))
    val restore = sync.expectMsgType[RestoreCommittedState]
    sync.reply(BlockCommitted(restore.state.cursor, 0L, 0, 0))
    (frame, sync, snapshots)
  }
}
