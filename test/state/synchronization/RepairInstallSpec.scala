package state.synchronization

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import lfsm.states.{MinerDictionary, PlasmaDictionary}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import play.api.Configuration
import state.messages.BlockInfo
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

    val rebuilt = MinerDictionary.initialState.copy(syncHeight = seeded.cursor.height)
    requester.send(handler, RepairMinerDictionary(seeded.cursor, rebuilt, None,
      minerPermit(requester, handler)))
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
    requester.send(handler, RepairMinerDictionary(stale, MinerDictionary.initialState, None,
      minerPermit(requester, handler)))
    val rejected = requester.expectMsgType[BlockRejected]
    rejected.reason should include("no longer names the exact committed state")

    requester.send(handler, GetCommittedState)
    requester.expectMsgType[CommittedState].state.minerDictionaryFault should not be empty
  }

  "A rebuilt rollup" should "be tracked again and its fault cleared" in {
    val (handler, _) = newHandler()
    val requester = TestProbe()
    val seeded = quarantinedSeed
    requester.send(handler, RestoreCommittedState(seeded, Vector(seeded.cursor)))
    requester.expectMsgType[BlockCommitted]

    val tree = SyncFixtures.emptyRollup(rollupId, SyncFixtures.id(700002), 90)
    requester.send(handler, RepairRollup(rollupId, seeded.cursor,
      RollupRepairResult.Rebuilt(tree), rollupPermit(requester, handler)))
    requester.expectMsgType[BlockCommitted]

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

    requester.send(handler, RepairRollup(rollupId, seeded.cursor, RollupRepairResult.Terminated,
      rollupPermit(requester, handler)))
    requester.expectMsgType[BlockCommitted]

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
      RollupRepairResult.Failed("indexed node has no rollup box", retryable = true),
      rollupPermit(requester, handler)))
    requester.expectMsgType[BlockCommitted]

    requester.send(handler, GetCommittedState)
    val fault = requester.expectMsgType[CommittedState].state.quarantined(rollupId)
    fault.attempts shouldEqual 1
    fault.reason should include("no rollup box")
  }

  /** A same-height fork must not accept a rollup reconstructed from the replaced block. */
  it should "refuse a rollup rebuild from another block at the same height without charging an attempt" in {
    val (handler, _) = newHandler()
    val requester = TestProbe()
    val seeded = quarantinedSeed
    requester.send(handler, RestoreCommittedState(seeded, Vector(seeded.cursor)))
    requester.expectMsgType[BlockCommitted]

    val tree = SyncFixtures.emptyRollup(rollupId, SyncFixtures.id(700002), 90)
    val stale = seeded.cursor.copy(blockId = SyncFixtures.id(799996), parentId = SyncFixtures.id(799995))
    requester.send(handler, RepairRollup(rollupId, stale,
      RollupRepairResult.Rebuilt(tree), rollupPermit(requester, handler)))
    val rejected = requester.expectMsgType[BlockRejected]
    rejected.reason should include("no longer names the exact committed state")

    requester.send(handler, GetCommittedState)
    val state = requester.expectMsgType[CommittedState].state
    state.rollups shouldBe empty
    state.quarantined.keySet shouldEqual Set(rollupId)
    state.quarantined(rollupId).attempts shouldEqual 0
  }

  it should "start diagnostic retention when the final consecutive repair attempt fails" in {
    val (handler, _) = newHandler()
    val requester = TestProbe()
    val seeded = quarantinedSeed.copy(quarantined = quarantinedSeed.quarantined.map {
      case (id, fault) => id -> fault.copy(attempts = 2)
    })
    requester.send(handler, RestoreCommittedState(seeded, Vector(seeded.cursor)))
    requester.expectMsgType[BlockCommitted]

    requester.send(handler, RepairRollup(rollupId, seeded.cursor,
      RollupRepairResult.Failed("still absent", retryable = true), rollupPermit(requester, handler)))
    requester.expectMsgType[BlockCommitted]

    requester.send(handler, GetCommittedState)
    val fault = requester.expectMsgType[CommittedState].state.quarantined(rollupId)
    fault.attempts shouldEqual 3
    fault.removalHeight shouldEqual Some(fault.genesisHeight + 720)
    fault.removalWarningLogged shouldBe false
  }

  it should "start a later quarantine episode at zero after a successful repair" in {
    val (handler, _) = newHandler()
    val requester = TestProbe()
    val seeded = quarantinedSeed.copy(quarantined = quarantinedSeed.quarantined.map {
      case (id, fault) => id -> fault.copy(attempts = 2)
    })
    requester.send(handler, RestoreCommittedState(seeded, Vector(seeded.cursor)))
    requester.expectMsgType[BlockCommitted]

    val inputId = SyncFixtures.id(700010)
    val tree = SyncFixtures.emptyRollup(rollupId, inputId, 90)
    requester.send(handler, RepairRollup(rollupId, seeded.cursor,
      RollupRepairResult.Rebuilt(tree), rollupPermit(requester, handler)))
    requester.expectMsgType[BlockCommitted]

    val (key, value) = SyncFixtures.plasmaEntries(1, 16).head
    val output = PlasmaDictionary.empty()
    output.insert(key -> value)
    val shaped = ReducerFixtures.submissionTx(95, inputId, SyncFixtures.id(700011), startHeight,
      key, value, ReducerFixtures.proofHex(Array[Byte](9)), output,
      numMiners = 1, totalScore = BigInt(0), period = 90L)
    val broken = shaped.copy(inputs = shaped.inputs.map(input => input.copy(
      spendingProof = input.spendingProof.map(proof => proof.copy(ext = proof.ext - "2")))))
    requester.send(handler, ApplyBlock(BlockInfo(SyncFixtures.id(startHeight), startHeight,
      Seq(broken), seeded.cursor.blockId)))
    requester.expectMsgType[BlockCommitted]

    requester.send(handler, GetCommittedState)
    val nextEpisode = requester.expectMsgType[CommittedState].state.quarantined(rollupId)
    nextEpisode.attempts shouldEqual 0
    nextEpisode.removalHeight shouldBe empty
  }

  private def faultedSeed: CommittedSyncState =
    ReducerFixtures.emptyState(height = startHeight - 1,
      blockId = SyncFixtures.id(startHeight - 1)).copy(
      minerTree = MinerDictionary.initialState.copy(dictionary = PlasmaDictionary.empty()),
      minerDictionaryFault = Some("dictionary advanced during bootstrap"))

  private def quarantinedSeed: CommittedSyncState =
    ReducerFixtures.emptyState(height = startHeight - 1,
      blockId = SyncFixtures.id(startHeight - 1)).copy(
      quarantined = Map(rollupId ->
        QuarantineFault(rollupId, 90, SyncFixtures.id(700000),
          "missing context variable 2", retryable = true)))

  private def rollupPermit(requester: TestProbe, handler: akka.actor.ActorRef): String = {
    requester.send(handler, GetRepairableQuarantines)
    requester.expectMsgType[RepairableQuarantines].repairPermit
  }

  private def minerPermit(requester: TestProbe, handler: akka.actor.ActorRef): String = {
    requester.send(handler, GetMinerDictionaryRepairPermit)
    requester.expectMsgType[MinerDictionaryRepairPermit].repairPermit
  }

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
