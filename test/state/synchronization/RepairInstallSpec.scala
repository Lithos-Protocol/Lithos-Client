package state.synchronization

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import lfsm.states.{MinerDictionary, PlasmaDictionary}
import org.bouncycastle.util.encoders.Hex
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import play.api.Configuration
import state.messages.{BlockInfo, BlockTx, TxInput, TxOutput}
import state.messages.SyncMessages._
import state.persistence.SnapshotDictionaryBase
import state.persistence.StateSnapshotActor.{SnapshotCandidate, SnapshotSaveFailed}
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
   * A dictionary walk takes minutes and blocks arrive every couple of minutes, so pinning its permit
   * to the global state epoch meant any walk outlasting one block could never install — which, once
   * the registration chain is long enough for a walk to exceed one block, is every walk.
   *
   * The rebuild is the dictionary as of the height it was built for, and no block in between touched
   * the dictionary, so it is still exact at the cursor the client has since reached.
   */
  it should "install a rebuilt Miner Dictionary after ordinary blocks moved the cursor" in {
    val (handler, _) = newHandler()
    val requester = TestProbe()
    val seeded = faultedSeed
    requester.send(handler, RestoreCommittedState(seeded, Vector(seeded.cursor)))
    requester.expectMsgType[BlockCommitted]

    // Taken before the walk starts, exactly as the producer takes it.
    val permit = minerPermit(requester, handler)

    requester.send(handler, ApplyBlock(BlockInfo(SyncFixtures.id(startHeight), startHeight,
      Seq.empty, seeded.cursor.blockId)))
    val moved = requester.expectMsgType[BlockCommitted].cursor
    moved.height shouldEqual startHeight

    val rebuilt = MinerDictionary.initialState.copy(syncHeight = seeded.cursor.height)
    requester.send(handler, RepairMinerDictionary(seeded.cursor, rebuilt, None, permit))
    requester.expectMsgType[BlockCommitted]

    requester.send(handler, GetCommittedState)
    val state = requester.expectMsgType[CommittedState].state
    state.minerDictionaryFault shouldBe empty
    // Installed at the cursor the client is on now, not the one the walk was built for.
    state.cursor shouldEqual moved
  }

  /**
   * The relaxation above must still refuse a permit issued before dictionary state actually moved,
   * or a stale rebuild could overwrite a newer one.
   */
  it should "refuse a rebuilt Miner Dictionary whose permit predates a dictionary change" in {
    val (handler, _) = newHandler()
    val requester = TestProbe()
    val seeded = faultedSeed
    requester.send(handler, RestoreCommittedState(seeded, Vector(seeded.cursor)))
    requester.expectMsgType[BlockCommitted]

    val stalePermit = minerPermit(requester, handler)
    val currentPermit = minerPermit(requester, handler)
    val rebuilt = MinerDictionary.initialState.copy(syncHeight = seeded.cursor.height)

    requester.send(handler, RepairMinerDictionary(seeded.cursor, rebuilt, None, currentPermit))
    requester.expectMsgType[BlockCommitted]

    // That install moved the dictionary, which is exactly what the first permit was issued against.
    requester.send(handler, RepairMinerDictionary(seeded.cursor, rebuilt, None, stalePermit))
    requester.expectMsgType[BlockRejected].reason should
      include("no longer names the exact committed state")
  }

  /**
   * The dictionary half of the same hazard.
   *
   * While faulted, `applyTransaction` skips the dictionary branch entirely, so a spend of the
   * singleton is not applied — and that is exactly why "the dictionary epoch did not move" would
   * otherwise mean "we were not watching". A rebuild installed across such a block would name a
   * spent box and be published as available while already behind the chain.
   */
  it should "refuse a rebuilt Miner Dictionary after the chain spent the dictionary box" in {
    val (handler, _) = newHandler()
    val requester = TestProbe()
    val seeded = faultedSeed
    requester.send(handler, RestoreCommittedState(seeded, Vector(seeded.cursor)))
    requester.expectMsgType[BlockCommitted]

    val permit = minerPermit(requester, handler)

    val protocol = ReducerFixtures.protocol(rollupStartHeight = startHeight)
    val moved = BlockTx(SyncFixtures.id(700300),
      Seq(TxInput(SyncFixtures.id(700301), None)), Seq.empty,
      Seq(ReducerFixtures.dictionaryOutput(SyncFixtures.id(700302), SyncFixtures.id(700300),
        startHeight, PlasmaDictionary.empty(), protocol.minerDictionaryToken)))
    requester.send(handler, ApplyBlock(BlockInfo(SyncFixtures.id(startHeight), startHeight,
      Seq(moved), seeded.cursor.blockId)))
    requester.expectMsgType[BlockCommitted]

    val rebuilt = MinerDictionary.initialState.copy(syncHeight = seeded.cursor.height)
    requester.send(handler, RepairMinerDictionary(seeded.cursor, rebuilt, None, permit))
    requester.expectMsgType[BlockRejected].reason should
      include("no longer names the exact committed state")

    requester.send(handler, GetCommittedState)
    requester.expectMsgType[CommittedState].state.minerDictionaryFault should not be empty
  }

  /**
   * A rollup rebuild must survive the blocks its walk runs beside.
   *
   * At the live tip nothing freezes the producer, so pinning the permit to the global epoch meant
   * any walk outlasting one block returned to a dead permit — and because the rejection happens
   * before `applyRepair`, no attempt was ever charged, so the client re-walked the rollup's whole
   * chain forever without ever installing.
   */
  it should "install a rebuilt rollup after ordinary blocks moved the cursor" in {
    val (handler, _) = newHandler()
    val requester = TestProbe()
    val seeded = quarantinedSeed
    requester.send(handler, RestoreCommittedState(seeded, Vector(seeded.cursor)))
    requester.expectMsgType[BlockCommitted]

    val permit = rollupPermit(requester, handler)

    requester.send(handler, ApplyBlock(BlockInfo(SyncFixtures.id(startHeight), startHeight,
      Seq.empty, seeded.cursor.blockId)))
    val moved = requester.expectMsgType[BlockCommitted].cursor

    val rebuilt = SyncFixtures.emptyRollup(rollupId, SyncFixtures.id(700123), 90)
    requester.send(handler, RepairRollup(rollupId, seeded.cursor,
      RollupRepairResult.Rebuilt(rebuilt), permit))
    requester.expectMsgType[BlockCommitted]

    requester.send(handler, GetCommittedState)
    val state = requester.expectMsgType[CommittedState].state
    state.quarantined should not contain key(rollupId)
    state.rollups should contain key rollupId
    state.cursor shouldEqual moved
  }

  /**
   * The relaxation must still refuse a rebuild the chain has overtaken.
   *
   * A quarantined rollup has no route, so the reducer cannot see its spends — which is exactly why
   * "the quarantine epoch did not move" would otherwise mean "we were not watching" rather than
   * "nothing happened". The fault retains the UTXO so that spend is still noticed.
   */
  it should "refuse a rebuilt rollup after the chain spent the quarantined rollup" in {
    val (handler, _) = newHandler()
    val requester = TestProbe()
    val seeded = quarantinedSeed
    requester.send(handler, RestoreCommittedState(seeded, Vector(seeded.cursor)))
    requester.expectMsgType[BlockCommitted]

    val permit = rollupPermit(requester, handler)

    // A block spending the quarantined rollup's retained UTXO: untracked, but not unnoticed.
    val spend = BlockTx(SyncFixtures.id(700200),
      Seq(TxInput(quarantinedUtxo, None)), Seq.empty,
      Seq(TxOutput(SyncFixtures.id(700201), 1000000L, "", Seq.empty, Seq.empty,
        SyncFixtures.id(700200), startHeight, 0)))
    requester.send(handler, ApplyBlock(BlockInfo(SyncFixtures.id(startHeight), startHeight,
      Seq(spend), seeded.cursor.blockId)))
    requester.expectMsgType[BlockCommitted]

    val rebuilt = SyncFixtures.emptyRollup(rollupId, SyncFixtures.id(700123), 90)
    requester.send(handler, RepairRollup(rollupId, seeded.cursor,
      RollupRepairResult.Rebuilt(rebuilt), permit))
    requester.expectMsgType[BlockRejected].reason should
      include("no longer names the exact committed state")

    requester.send(handler, GetCommittedState)
    requester.expectMsgType[CommittedState].state.quarantined should contain key rollupId
  }

  /**
   * The dictionary override belongs to the cursor the repair was installed at, not the one it was
   * rebuilt for.
   *
   * A rollback drops overrides above its target. Stamped with the rebuild cursor, an override
   * survives a rollback that undoes the install itself — leaving the repaired dictionary as the
   * reconstruction base for a boundary whose metadata still expects the pre-repair digest, which
   * fails every checkpoint and materialization from it.
   */
  it should "drop a repaired dictionary override when a rollback undoes its install" in {
    val (handler, snapshots) = newHandler(snapshotsEnabled = true, snapshotInterval = 2,
      retryDelay = 200.millis)
    val requester = TestProbe()
    val seeded = faultedSeed
    requester.send(handler, RestoreCommittedState(seeded, Vector(seeded.cursor)))
    requester.expectMsgType[BlockCommitted]

    def commit(height: Int): SyncCursor = {
      requester.send(handler, ApplyBlock(BlockInfo(SyncFixtures.id(height), height, Seq.empty,
        if (height == startHeight) seeded.cursor.blockId else SyncFixtures.id(height - 1))))
      requester.expectMsgType[BlockCommitted].cursor
    }

    // The permit is taken before the walk, so the install lands two blocks above the rebuild cursor.
    // That gap is what the override height has to follow.
    val builtAt = commit(startHeight)
    requester.send(handler, GetMinerDictionaryRepairPermit)
    val permit = requester.expectMsgType[MinerDictionaryRepairPermit]
    permit.committedCursor shouldEqual builtAt

    val midpoint = commit(startHeight + 1)
    val installedAt = commit(startHeight + 2)

    // A distinct digest, or the repaired and pre-repair dictionaries would be indistinguishable.
    val populated = PlasmaDictionary.empty()
    populated.insert(SyncFixtures.plasmaEntries(1, 32): _*)
    val rebuilt = MinerDictionary.initialState.copy(dictionary = populated, numMiners = 1)

    requester.send(handler, RepairMinerDictionary(builtAt, rebuilt, None, permit.repairPermit))
    requester.expectMsgType[BlockCommitted].cursor shouldEqual installedAt

    // Fail the forced checkpoint so the override is still live across the rollback.
    val forced = snapshots.expectMsgType[SnapshotCandidate]
    snapshots.send(handler, SnapshotSaveFailed(forced.cursor, forced.checkpointToken, "held open"))

    // Back below the install, so the repair no longer describes committed state at all.
    requester.send(handler, RollbackTo(midpoint.blockId))
    requester.expectMsgType[RollbackCompleted].cursor shouldEqual midpoint

    // The boundary's metadata still carries the pre-repair digest, so a surviving override would
    // hand the checkpoint a base that cannot produce it.
    val retried = snapshots.expectMsgType[SnapshotCandidate](5.seconds)
    val minerSource = retried.snapshot.dictionaries(DictionaryId.Miner)
    minerSource.base match {
      case SnapshotDictionaryBase.Materialized(dictionary) =>
        Hex.toHexString(dictionary.digest) shouldEqual minerSource.expectedDigest
      case _ => succeed
    }
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
    requester.send(handler, GetSyncStatus)
    requester.expectMsgType[CatchingUp]
  }

  it should "leave the Miner Dictionary faulted when its repair exceeds the journal budget" in {
    val (handler, _) = newHandler(maxJournalBytes = 1L)
    val requester = TestProbe()
    val seeded = faultedSeed
    requester.send(handler, RestoreCommittedState(seeded, Vector(seeded.cursor)))
    requester.expectMsgType[BlockCommitted]

    val rebuilt = MinerDictionary.initialState.copy(syncHeight = seeded.cursor.height)
    requester.send(handler, RepairMinerDictionary(seeded.cursor, rebuilt, None,
      minerPermit(requester, handler)))
    requester.expectMsgType[BlockRejected].reason should include("volatile synchronization bytes")

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

  it should "stay quarantined when its repair exceeds the journal budget" in {
    val (handler, _) = newHandler(maxJournalBytes = 1L)
    val requester = TestProbe()
    val seeded = quarantinedSeed
    requester.send(handler, RestoreCommittedState(seeded, Vector(seeded.cursor)))
    requester.expectMsgType[BlockCommitted]

    val tree = SyncFixtures.emptyRollup(rollupId, SyncFixtures.id(700002), 90)
    requester.send(handler, RepairRollup(rollupId, seeded.cursor,
      RollupRepairResult.Rebuilt(tree), rollupPermit(requester, handler)))
    requester.expectMsgType[BlockRejected].reason should include("volatile synchronization bytes")

    requester.send(handler, GetCommittedState)
    val state = requester.expectMsgType[CommittedState].state
    state.rollups shouldBe empty
    state.quarantined.keySet shouldEqual Set(rollupId)
    requester.send(handler, GetSyncStatus)
    requester.expectMsgType[CatchingUp]
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

  private val quarantinedUtxo = SyncFixtures.id(700100)

  private def quarantinedSeed: CommittedSyncState =
    ReducerFixtures.emptyState(height = startHeight - 1,
      blockId = SyncFixtures.id(startHeight - 1)).copy(
      quarantined = Map(rollupId ->
        QuarantineFault(rollupId, 90, SyncFixtures.id(700000),
          "missing context variable 2", retryable = true, utxoId = quarantinedUtxo)))

  private def rollupPermit(requester: TestProbe, handler: akka.actor.ActorRef): String = {
    requester.send(handler, GetRepairableQuarantines)
    requester.expectMsgType[RepairableQuarantines].repairPermit
  }

  private def minerPermit(requester: TestProbe, handler: akka.actor.ActorRef): String = {
    requester.send(handler, GetMinerDictionaryRepairPermit)
    requester.expectMsgType[MinerDictionaryRepairPermit].repairPermit
  }

  private def newHandler(maxJournalBytes: Long = SyncHandler.MaxTransformJournalBytes,
                         snapshotsEnabled: Boolean = false,
                         snapshotInterval: Int = 90,
                         retryDelay: FiniteDuration = SyncHandler.CheckpointRetryDelay) = {
    val snapshots = TestProbe()
    val (_, _, wallet) = FakeNodeContext(numAddresses = 1)
    val protocol = ReducerFixtures.protocol(rollupStartHeight = startHeight)
      .copy(localMinerHash = wallet.contract.hashedPropBytes)
    val config = Configuration(ConfigFactory.parseString(
      s"""sync.startHeight = $startHeight
         |sync.quarantine.repairAttempts = 3
         |sync.snapshots.enabled = $snapshotsEnabled
         |sync.snapshots.intervalBlocks = $snapshotInterval
         |""".stripMargin))
    (system.actorOf(Props(new SyncHandler(config, protocol, snapshots.ref) {
      override protected def maxTransformJournalBytes: Long = maxJournalBytes
      override protected def checkpointRetryDelay: FiniteDuration = retryDelay
    })), snapshots)
  }
}
