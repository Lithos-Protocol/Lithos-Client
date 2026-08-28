package state.synchronization

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import play.api.Configuration
import state.messages.BlockInfo
import state.messages.SyncMessages._
import state.persistence.StateSnapshotActor.SnapshotCandidate
import support.{FakeNodeContext, ReducerFixtures, SyncFixtures}

import scala.concurrent.duration._

/** Repair attempts are cheap metadata; successful outcomes and retention deadlines are durable now. */
class RepairSnapshotCadenceSpec extends TestKit(ActorSystem("repair-snapshot-cadence"))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val startHeight = 100
  private val rollupId = SyncFixtures.id(780001)

  "An intermediate failed repair" should "use the periodic snapshot cadence" in {
    val (handler, snapshots) = newHandler()
    val requester = TestProbe()
    val seeded = quarantinedSeed()
    restore(requester, handler, seeded)

    requester.send(handler, RepairRollup(rollupId, seeded.cursor,
      RollupRepairResult.Failed("index still catching up", retryable = true)))
    requester.expectMsgType[BlockCommitted]
    snapshots.expectNoMessage(250.millis)
    system.stop(handler)
  }

  "The final failed repair" should "force a snapshot when it first assigns a removal deadline" in {
    val (handler, snapshots) = newHandler()
    val requester = TestProbe()
    val seeded = quarantinedSeed(attempts = 2)
    restore(requester, handler, seeded)

    requester.send(handler, RepairRollup(rollupId, seeded.cursor,
      RollupRepairResult.Failed("retry budget exhausted", retryable = true)))
    requester.expectMsgType[BlockCommitted]
    snapshots.expectMsgType[SnapshotCandidate].cursor shouldEqual seeded.cursor
    system.stop(handler)
  }

  "A successful or terminal repair" should "force its state transition into a snapshot" in {
    val rebuiltTree = SyncFixtures.emptyNispTree(rollupId, SyncFixtures.id(780010), 90)
    Seq[RollupRepairResult](RollupRepairResult.Rebuilt(rebuiltTree), RollupRepairResult.Terminated)
      .foreach { outcome =>
        val (handler, snapshots) = newHandler()
        val requester = TestProbe()
        val seeded = quarantinedSeed()
        restore(requester, handler, seeded)

        requester.send(handler, RepairRollup(rollupId, seeded.cursor, outcome))
        requester.expectMsgType[BlockCommitted]
        snapshots.expectMsgType[SnapshotCandidate].cursor shouldEqual seeded.cursor
        system.stop(handler)
      }
  }

  "Block maintenance" should "force a snapshot when it assigns a terminal fault's deadline" in {
    val (handler, snapshots) = newHandler()
    val requester = TestProbe()
    val seeded = quarantinedSeed().copy(quarantined = Map(rollupId ->
      quarantinedSeed().quarantined(rollupId).copy(retryable = false)))
    restore(requester, handler, seeded)

    val block = BlockInfo(SyncFixtures.id(startHeight), startHeight, Seq.empty, seeded.cursor.blockId)
    requester.send(handler, ApplyBlock(block))
    requester.expectMsgType[BlockCommitted]
    snapshots.expectMsgType[SnapshotCandidate].cursor.height shouldEqual startHeight
    system.stop(handler)
  }

  private def restore(requester: TestProbe,
                      handler: akka.actor.ActorRef,
                      state: CommittedSyncState): Unit = {
    requester.send(handler, RestoreCommittedState(state, Vector(state.cursor)))
    requester.expectMsgType[BlockCommitted]
  }

  private def quarantinedSeed(attempts: Int = 0): CommittedSyncState =
    ReducerFixtures.emptyState(height = startHeight - 1,
      blockId = SyncFixtures.id(startHeight - 1)).copy(
      quarantined = Map(rollupId -> QuarantineFault(rollupId, 90, SyncFixtures.id(780000),
        "missing indexed context", retryable = true, attempts = attempts)))

  private def newHandler(): (akka.actor.ActorRef, TestProbe) = {
    val snapshots = TestProbe()
    val (_, _, wallet) = FakeNodeContext(numAddresses = 1)
    val protocol = ReducerFixtures.protocol(rollupStartHeight = startHeight)
      .copy(localMinerHash = wallet.contract.hashedPropBytes)
    val config = Configuration(ConfigFactory.parseString(
      s"""sync.startHeight = $startHeight
         |sync.quarantine.repairAttempts = 3
         |sync.snapshots.enabled = true
         |sync.snapshots.intervalBlocks = 720
         |""".stripMargin))
    system.actorOf(Props(new SyncHandler(config, protocol, snapshots.ref))) -> snapshots
  }
}
