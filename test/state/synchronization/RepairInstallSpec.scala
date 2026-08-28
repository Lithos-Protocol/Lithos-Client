package state.synchronization

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import lfsm.states.{MinerTree, PlasmaDictionary}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import play.api.Configuration
import state.messages.SyncMessages._
import support.{FakeNodeContext, ReducerFixtures, SyncFixtures}

import scala.concurrent.duration._

/**
 * A rebuild is only installable at the height it was built for. The producer walks the chain while
 * blocks keep committing, so an install has to prove it is not one block stale.
 */
class RepairInstallSpec extends TestKit(ActorSystem("repair-install"))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val startHeight = 100
  private val rollupId = SyncFixtures.id(700001)

  "SyncHandler" should "install a rebuilt Miner Dictionary and clear its fault" in {
    val (handler, _) = newHandler()
    val requester = TestProbe()
    val seeded = faultedSeed
    requester.send(handler, RestoreCommittedState(seeded, Vector(seeded.cursor)))
    requester.expectMsgType[BlockCommitted]

    val rebuilt = MinerTree.initialState.copy(numMiners = 2)
    requester.send(handler, RepairMinerDictionary(seeded.cursor.height, rebuilt, None))
    requester.expectMsgType[BlockCommitted]

    requester.send(handler, GetCommittedState)
    val state = requester.expectMsgType[CommittedState].state
    state.minerDictionaryFault shouldBe empty
    state.minerTree.numMiners shouldEqual 2
    utils.Globals.syncView.minerDictionary.available shouldBe false // still catching up, not faulted
  }

  /**
   * The height guard is the whole reason the rebuild carries one. Without it an install could replace
   * current dictionary state with a reconstruction that predates the newest commit.
   */
  it should "refuse a rebuilt dictionary once the cursor has moved past it" in {
    val (handler, _) = newHandler()
    val requester = TestProbe()
    val seeded = faultedSeed
    requester.send(handler, RestoreCommittedState(seeded, Vector(seeded.cursor)))
    requester.expectMsgType[BlockCommitted]

    val stale = seeded.cursor.height - 1
    requester.send(handler, RepairMinerDictionary(stale, MinerTree.initialState.copy(numMiners = 9), None))
    val rejected = requester.expectMsgType[BlockRejected]
    rejected.reason should include(s"rebuilt at height $stale")

    requester.send(handler, GetCommittedState)
    requester.expectMsgType[CommittedState].state.minerDictionaryFault should not be empty
  }

  "A rebuilt rollup" should "be tracked again and its fault cleared" in {
    val (handler, _) = newHandler()
    val requester = TestProbe()
    val seeded = quarantinedSeed
    requester.send(handler, RestoreCommittedState(seeded, Vector(seeded.cursor)))
    requester.expectMsgType[BlockCommitted]

    val tree = SyncFixtures.emptyNispTree(rollupId, SyncFixtures.id(700002), 90)
    requester.send(handler, RepairRollup(rollupId, seeded.cursor.height,
      RollupRepairResult.Rebuilt(tree)))

    requester.send(handler, GetCommittedState)
    val state = requester.expectMsgType[CommittedState].state
    state.rollups.keySet shouldEqual Set(rollupId)
    state.routes shouldEqual Map(tree.utxoId -> rollupId)
    state.quarantined shouldBe empty
  }

  /** A rollup that ended on chain needs no tracking, and its fault must not be retried forever. */
  it should "drop the fault when the rollup had already terminated" in {
    val (handler, _) = newHandler()
    val requester = TestProbe()
    val seeded = quarantinedSeed
    requester.send(handler, RestoreCommittedState(seeded, Vector(seeded.cursor)))
    requester.expectMsgType[BlockCommitted]

    requester.send(handler, RepairRollup(rollupId, seeded.cursor.height, RollupRepairResult.Terminated))

    requester.send(handler, GetCommittedState)
    val state = requester.expectMsgType[CommittedState].state
    state.quarantined shouldBe empty
    state.rollups shouldBe empty
  }

  /** A failed rebuild has to count, or a permanently broken rollup is walked on every interval. */
  it should "record a failed rebuild rather than retrying it unchanged" in {
    val (handler, _) = newHandler()
    val requester = TestProbe()
    val seeded = quarantinedSeed
    requester.send(handler, RestoreCommittedState(seeded, Vector(seeded.cursor)))
    requester.expectMsgType[BlockCommitted]

    requester.send(handler, RepairRollup(rollupId, seeded.cursor.height,
      RollupRepairResult.Failed("indexed node has no rollup box", retryable = true)))

    requester.send(handler, GetCommittedState)
    val fault = requester.expectMsgType[CommittedState].state.quarantined(rollupId)
    fault.attempts shouldEqual 1
    fault.reason should include("no rollup box")
  }

  /** An install built against a cursor the owner has moved past is the same hazard as the dictionary. */
  it should "ignore a rollup rebuild aimed at a height the cursor has left" in {
    val (handler, _) = newHandler()
    val requester = TestProbe()
    val seeded = quarantinedSeed
    requester.send(handler, RestoreCommittedState(seeded, Vector(seeded.cursor)))
    requester.expectMsgType[BlockCommitted]

    val tree = SyncFixtures.emptyNispTree(rollupId, SyncFixtures.id(700002), 90)
    requester.send(handler, RepairRollup(rollupId, seeded.cursor.height - 5,
      RollupRepairResult.Rebuilt(tree)))
    requester.expectNoMessage(200.millis)

    requester.send(handler, GetCommittedState)
    val state = requester.expectMsgType[CommittedState].state
    state.rollups shouldBe empty
    state.quarantined.keySet shouldEqual Set(rollupId)
  }

  private def faultedSeed: CommittedSyncState =
    ReducerFixtures.emptyState(height = startHeight - 1,
      blockId = SyncFixtures.id(startHeight - 1)).copy(
      minerTree = MinerTree.initialState.copy(dictionary = PlasmaDictionary.empty()),
      minerDictionaryFault = Some("dictionary advanced during bootstrap"))

  private def quarantinedSeed: CommittedSyncState =
    ReducerFixtures.emptyState(height = startHeight - 1,
      blockId = SyncFixtures.id(startHeight - 1)).copy(
      quarantined = Map(rollupId ->
        QuarantineFault(rollupId, 90, "missing context variable 2", retryable = true)))

  private def newHandler() = {
    val snapshots = TestProbe()
    val (_, _, wallet) = FakeNodeContext(numAddresses = 1)
    val protocol = ReducerFixtures.protocol(rollupStartHeight = startHeight)
      .copy(localMinerHash = wallet.contract.hashedPropBytes)
    val config = Configuration(ConfigFactory.parseString(
      s"""sync.startHeight = $startHeight
         |sync.quarantine.repairAttempts = 3
         |sync.snapshots.enabled = false
         |""".stripMargin))
    (system.actorOf(Props(new SyncHandler(config, protocol, snapshots.ref))), snapshots)
  }
}
