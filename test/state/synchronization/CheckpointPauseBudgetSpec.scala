package state.synchronization

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import node.NodeApi
import node.model.IndexedBox
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import play.api.Configuration
import state.messages.StateFrameMessages.StartSynchronization
import state.messages.SyncMessages._
import state.persistence.StateSnapshotActor.{RestoreLatest, SnapshotLoaded}
import support.{ChainFixtures, FakeNodeContext, ReducerFixtures}

import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.duration._
import scala.util.{Success, Try}

/** Pins the configured upper bound on how long a maintenance checkpoint may freeze catch-up. */
class CheckpointPauseBudgetSpec extends TestKit(ActorSystem("checkpoint-pause-budget"))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val startHeight = 500

  "A quarantine maintenance checkpoint" should
    "resume catch-up at its timeout without starting another repair while the worker returns" in {
    val tip = startHeight + 2
    val nodeApi = ChainFixtures.nodeAt(tip)
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    when(nodeApi.indexedBoxById(any[String])).thenAnswer { _ =>
      entered.countDown()
      release.await(5, TimeUnit.SECONDS)
      Success(None): Try[Option[IndexedBox]]
    }

    val (frame, sync) = started(nodeApi, repairTimeout = 150.millis)
    try {
      sync.expectMsgType[BeginCatchUp]
      val first = sync.expectMsgType[ApplyBlock].blockInfo
      sync.reply(commit(first))

      sync.expectMsg(GetRepairableQuarantines)
      val at = SyncCursor(first.height, first.id, first.parentId)
      val fault = QuarantineFault(ChainFixtures.headerId(100), 100,
        ChainFixtures.headerId(99), "indexed repair is deliberately blocked", retryable = true)
      sync.reply(RepairableQuarantines(Seq(fault), at, "repair-permit"))

      entered.await(2, TimeUnit.SECONDS) shouldBe true
      sync.expectNoMessage(50.millis)

      // The timeout releases the frozen producer, but `repairing` remains true until the one worker
      // returns. Crossing another one-block interval therefore cannot launch a second checkpoint.
      val second = sync.expectMsgType[ApplyBlock](2.seconds).blockInfo
      second.height shouldEqual startHeight + 1
      sync.reply(commit(second))
      val third = sync.expectMsgType[ApplyBlock].blockInfo
      third.height shouldEqual startHeight + 2
      sync.reply(commit(third))
      sync.expectMsg(MarkReady)

      release.countDown()
      // The late result is still offered through the exact-cursor guard, which refuses it without
      // consuming an attempt now that catch-up has moved.
      val late = sync.expectMsgType[RepairRollup](2.seconds)
      late.atCursor shouldEqual at
      sync.reply(BlockRejected(Some(SyncCursor(third.height, third.id, third.parentId)), "",
        "repair cursor moved before late publication"))
      sync.expectNoMessage(500.millis)
    } finally {
      release.countDown()
      frame ! akka.actor.PoisonPill
    }
  }

  private def commit(block: state.messages.BlockInfo): BlockCommitted =
    BlockCommitted(SyncCursor(block.height, block.id, block.parentId), block.height.toLong, 0, 0)

  private def started(nodeApi: NodeApi,
                      repairTimeout: FiniteDuration): (akka.actor.ActorRef, TestProbe) = {
    val sync = TestProbe()
    val snapshots = TestProbe()
    val (nodeContext, _, wallet) = FakeNodeContext(nodeApi, numAddresses = 1)
    val protocol = ReducerFixtures.protocol(rollupStartHeight = startHeight)
      .copy(localMinerHash = wallet.contract.hashedPropBytes)
    val config = Configuration(ConfigFactory.parseString(
      s"""sync.startHeight = $startHeight
         |sync.catchUpBatchBlocks = 4
         |sync.minerDictionary.bootstrap = false
         |sync.quarantine.maintenanceCheckpoints = true
         |sync.quarantine.checkpointIntervalBlocks = 1
         |sync.quarantine.repairTimeout = ${repairTimeout.toMillis} milliseconds
         |""".stripMargin))
    val frame = system.actorOf(Props(new StateFrame(config, nodeContext, protocol,
      sync.ref, snapshots.ref)))

    frame ! StartSynchronization
    ChainFixtures.unseeded(sync)
    snapshots.expectMsg(RestoreLatest)
    snapshots.reply(SnapshotLoaded(None))
    val restore = sync.expectMsgType[RestoreCommittedState]
    sync.reply(BlockCommitted(restore.state.cursor, 0L, 0, 0))
    frame -> sync
  }
}
