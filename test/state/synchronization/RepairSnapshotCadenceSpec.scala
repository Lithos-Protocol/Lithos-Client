package state.synchronization

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import lfsm.states.PlasmaDictionary
import org.bouncycastle.util.encoders.Hex
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import play.api.Configuration
import state.messages.BlockInfo
import state.messages.SyncMessages._
import state.persistence.StateSnapshotActor.{SnapshotCandidate, SnapshotSaved}
import state.persistence.SnapshotDictionaryBase
import support.{FakeNodeContext, ReducerFixtures, SyncFixtures}

import scala.concurrent.duration._

/** Repair attempts and deterministic retention deadlines use cadence; rebuilt state is durable now. */
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
      RollupRepairResult.Failed("index still catching up", retryable = true),
      permit(requester, handler)))
    requester.expectMsgType[BlockCommitted]
    snapshots.expectNoMessage(250.millis)
    system.stop(handler)
  }

  "The final failed repair" should "use cadence because its removal deadline is deterministic" in {
    val (handler, snapshots) = newHandler()
    val requester = TestProbe()
    val seeded = quarantinedSeed(attempts = 2)
    restore(requester, handler, seeded)

    requester.send(handler, RepairRollup(rollupId, seeded.cursor,
      RollupRepairResult.Failed("retry budget exhausted", retryable = true),
      permit(requester, handler)))
    requester.expectMsgType[BlockCommitted]
    snapshots.expectNoMessage(250.millis)
    system.stop(handler)
  }

  "A successful or terminal repair" should "force its state transition into a snapshot" in {
    val rebuiltTree = SyncFixtures.emptyRollup(rollupId, SyncFixtures.id(780010), 90)
    Seq[RollupRepairResult](RollupRepairResult.Rebuilt(rebuiltTree), RollupRepairResult.Terminated)
      .foreach { outcome =>
        val (handler, snapshots) = newHandler()
        val requester = TestProbe()
        val seeded = quarantinedSeed()
        restore(requester, handler, seeded)

        requester.send(handler, RepairRollup(rollupId, seeded.cursor, outcome,
          permit(requester, handler)))
        requester.expectMsgType[BlockCommitted]
        snapshots.expectMsgType[SnapshotCandidate].cursor shouldEqual seeded.cursor
        system.stop(handler)
      }
  }

  it should "replace a pending checkpoint when repair changes state at the same cursor" in {
    val (handler, snapshots) = newHandler()
    val requester = TestProbe()
    val seeded = quarantinedSeed()
    restore(requester, handler, seeded)

    requester.send(handler, MarkReady)
    requester.expectMsgType[Ready]
    val stale = snapshots.expectMsgType[SnapshotCandidate]

    val (key, value) = SyncFixtures.plasmaEntries(1, 16).head
    val dictionary = PlasmaDictionary.empty()
    dictionary.insert(key -> value)
    val rebuilt = SyncFixtures.emptyRollup(rollupId, SyncFixtures.id(780011), 90)
      .copy(dictionary = dictionary, numMiners = 1)
    requester.send(handler, RepairRollup(rollupId, seeded.cursor,
      RollupRepairResult.Rebuilt(rebuilt), permit(requester, handler)))
    requester.expectMsgType[BlockCommitted]

    snapshots.send(handler, SnapshotSaved(stale.cursor, stale.checkpointToken))
    val replacement = snapshots.expectMsgType[SnapshotCandidate]
    replacement.cursor shouldEqual seeded.cursor
    replacement.snapshot.metadata.quarantined shouldBe empty
    replacement.snapshot.metadata.rollups.keySet shouldEqual Set(rollupId)
    replacement.snapshot.dictionaries(DictionaryId.Rollup(rollupId)).base match {
      case SnapshotDictionaryBase.Materialized(saved) =>
        Hex.toHexString(saved.digest) shouldEqual Hex.toHexString(dictionary.digest)
      case other => fail(s"repaired dictionary was not retained for the replacement checkpoint: $other")
    }
  }

  "Block maintenance" should "not force a snapshot for a deterministic terminal deadline" in {
    val (handler, snapshots) = newHandler()
    val requester = TestProbe()
    val seeded = quarantinedSeed().copy(quarantined = Map(rollupId ->
      quarantinedSeed().quarantined(rollupId).copy(retryable = false)))
    restore(requester, handler, seeded)

    val block = BlockInfo(SyncFixtures.id(startHeight), startHeight, Seq.empty, seeded.cursor.blockId)
    requester.send(handler, ApplyBlock(block))
    requester.expectMsgType[BlockCommitted]
    snapshots.expectNoMessage(250.millis)
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

  private def permit(requester: TestProbe, handler: akka.actor.ActorRef): String = {
    requester.send(handler, GetRepairableQuarantines)
    requester.expectMsgType[RepairableQuarantines].repairPermit
  }

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
