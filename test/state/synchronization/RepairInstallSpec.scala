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
 * A rebuild is only installable at the exact cursor it was built for. The producer walks the chain
 * while blocks and reorgs continue, so an install has to prove it still belongs to that chain.
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

    val rebuilt = MinerTree.initialState.copy(syncHeight = seeded.cursor.height)
    requester.send(handler, RepairMinerDictionary(seeded.cursor, rebuilt, None))
    requester.expectMsgType[BlockCommitted]

    requester.send(handler, GetCommittedState)
    val state = requester.expectMsgType[CommittedState].state
    state.minerDictionaryFault shouldBe empty
    state.minerTree.syncHeight shouldEqual seeded.cursor.height
    utils.Globals.syncView.minerDictionary.available shouldBe false // still catching up, not faulted
  }

  /**
   * The cursor guard rejects a reconstruction from a replaced tip even when the height is unchanged.
   */
  it should "refuse a rebuilt dictionary from another block at the same height" in {
    val (handler, _) = newHandler()
    val requester = TestProbe()
    val seeded = faultedSeed
    requester.send(handler, RestoreCommittedState(seeded, Vector(seeded.cursor)))
    requester.expectMsgType[BlockCommitted]

    val stale = seeded.cursor.copy(blockId = SyncFixtures.id(799998), parentId = SyncFixtures.id(799997))
    requester.send(handler, RepairMinerDictionary(stale, MinerTree.initialState, None))
    val rejected = requester.expectMsgType[BlockRejected]
    rejected.reason should include(s"rebuilt at ${stale.blockId}@${stale.height}")

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
    requester.send(handler, RepairRollup(rollupId, seeded.cursor,
      RollupRepairResult.Rebuilt(tree)))

    requester.send(handler, GetCommittedState)
    val state = requester.expectMsgType[CommittedState].state
    state.rollups.keySet shouldEqual Set(rollupId)
    state.routes shouldEqual Map(tree.utxoId -> rollupId)
    state.rollupOrigins shouldEqual Map(rollupId -> seeded.quarantined(rollupId).collateralBoxId)
    state.quarantined shouldBe empty
  }

  /** A rollup that ended on chain needs no tracking, and its fault must not be retried forever. */
  it should "drop the fault when the rollup had already terminated" in {
    val (handler, _) = newHandler()
    val requester = TestProbe()
    val seeded = quarantinedSeed
    requester.send(handler, RestoreCommittedState(seeded, Vector(seeded.cursor)))
    requester.expectMsgType[BlockCommitted]

    requester.send(handler, RepairRollup(rollupId, seeded.cursor, RollupRepairResult.Terminated))

    requester.send(handler, GetCommittedState)
    val state = requester.expectMsgType[CommittedState].state
    state.quarantined shouldBe empty
    state.rollups shouldBe empty
    state.rollupOrigins shouldBe empty
  }

  /** A failed rebuild has to count, or a permanently broken rollup is walked on every interval. */
  it should "record a failed rebuild rather than retrying it unchanged" in {
    val (handler, _) = newHandler()
    val requester = TestProbe()
    val seeded = quarantinedSeed
    requester.send(handler, RestoreCommittedState(seeded, Vector(seeded.cursor)))
    requester.expectMsgType[BlockCommitted]

    requester.send(handler, RepairRollup(rollupId, seeded.cursor,
      RollupRepairResult.Failed("indexed node has no rollup box", retryable = true)))

    requester.send(handler, GetCommittedState)
    val fault = requester.expectMsgType[CommittedState].state.quarantined(rollupId)
    fault.attempts shouldEqual 1
    fault.reason should include("no rollup box")
  }

  /** A same-height fork must not accept a rollup reconstructed from the replaced block. */
  it should "ignore a rollup rebuild from another block at the same height" in {
    val (handler, _) = newHandler()
    val requester = TestProbe()
    val seeded = quarantinedSeed
    requester.send(handler, RestoreCommittedState(seeded, Vector(seeded.cursor)))
    requester.expectMsgType[BlockCommitted]

    val tree = SyncFixtures.emptyNispTree(rollupId, SyncFixtures.id(700002), 90)
    val stale = seeded.cursor.copy(blockId = SyncFixtures.id(799996), parentId = SyncFixtures.id(799995))
    requester.send(handler, RepairRollup(rollupId, stale,
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
        QuarantineFault(rollupId, 90, SyncFixtures.id(700000),
          "missing context variable 2", retryable = true)))

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
