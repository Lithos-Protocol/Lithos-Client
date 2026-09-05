package state.synchronization

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import lfsm.{CollateralParams, LFSMHelpers}
import lfsm.states.PlasmaDictionary
import org.ergoplatform.appkit.Parameters
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import play.api.Configuration
import state.messages.SyncMessages._
import state.messages.MempoolMessages.{MempoolSnapshot, MempoolUnavailable}
import state.messages.BlockInfo
import state.messages.RollupMessages.{GetCurrentRollup, UpdateEvaluation}
import state.persistence.StateSnapshotActor.SnapshotCandidate
import support.{FakeCache, FakeNodeContext, ReducerFixtures, SyncFixtures}

import scala.concurrent.duration._

class SyncHandlerCommitSpec extends TestKit(ActorSystem("sync-handler-commit"))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  "SyncHandler" should "publish readiness only after an exact block commit" in {
    val (handler, snapshots, _) = newHandler()
    val requester = TestProbe()
    seed(handler, requester)
    val block = BlockInfo(SyncFixtures.id(100), 100, Seq.empty, SyncFixtures.id(99))

    requester.send(handler, BeginCatchUp(100))
    requester.send(handler, GetSyncStatus)
    requester.expectMsg(CatchingUp(SyncCursor(99, SyncFixtures.id(99), SyncFixtures.id(98)), 100))

    requester.send(handler, ApplyBlock(block))
    val committed = requester.expectMsgType[BlockCommitted]
    committed.cursor shouldEqual SyncCursor(100, block.id, block.parentId)

    requester.send(handler, GetSyncStatus)
    requester.expectMsg(CatchingUp(committed.cursor, 100))
    requester.send(handler, GetSynced)
    requester.expectMsgType[SyncUnavailable]

    // Confirmed-state readiness is independent of mempool projection health.
    requester.send(handler, MarkReady)
    requester.expectMsg(Ready(committed.cursor))
    requester.send(handler, GetSynced)
    requester.expectMsg(FullSync(Seq.empty, Map.empty))

    handler ! MempoolSnapshot(1L, Map.empty, Map.empty, Set.empty)
    requester.send(handler, GetSyncStatus)
    requester.expectMsg(Ready(committed.cursor))

    handler ! MempoolUnavailable("temporary node failure")
    requester.send(handler, GetSyncStatus)
    requester.expectMsg(Ready(committed.cursor))
    requester.send(handler, GetSynced)
    requester.expectMsg(FullSync(Seq.empty, Map.empty))

    handler ! MempoolSnapshot(2L, Map.empty, Map.empty, Set.empty)
    requester.send(handler, GetSynced)
    requester.expectMsg(FullSync(Seq.empty, Map.empty))
    snapshots.expectMsgType[state.persistence.StateSnapshotActor.SnapshotCandidate]
  }

  it should "leave the cursor unchanged on rejection and continue from the last commit" in {
    val (handler, _, _) = newHandler()
    val requester = TestProbe()
    seed(handler, requester)
    val first = BlockInfo(SyncFixtures.id(100), 100, Seq.empty, SyncFixtures.id(99))
    requester.send(handler, ApplyBlock(first))
    requester.expectMsgType[BlockCommitted]
    handler ! MempoolSnapshot(1L, Map.empty, Map.empty, Set.empty)
    requester.send(handler, MarkReady)
    requester.expectMsgType[Ready]

    val gap = BlockInfo(SyncFixtures.id(102), 102, Seq.empty, first.id)
    requester.send(handler, ApplyBlock(gap))
    requester.expectMsgType[BlockRejected]
    requester.send(handler, GetCommittedState)
    requester.expectMsgType[CommittedState].state.cursor.blockId shouldEqual first.id

    val second = BlockInfo(SyncFixtures.id(101), 101, Seq.empty, first.id)
    requester.send(handler, ApplyBlock(second))
    requester.expectMsgType[BlockCommitted].cursor.blockId shouldEqual second.id
  }

  it should "restore an exact retained state before applying a replacement branch" in {
    val (handler, _, _) = newHandler()
    val requester = TestProbe()
    seed(handler, requester)
    val first = BlockInfo(SyncFixtures.id(100), 100, Seq.empty, SyncFixtures.id(99))
    val orphan = BlockInfo(SyncFixtures.id(101), 101, Seq.empty, first.id)
    requester.send(handler, ApplyBlock(first))
    requester.expectMsgType[BlockCommitted]
    requester.send(handler, ApplyBlock(orphan))
    requester.expectMsgType[BlockCommitted]

    requester.send(handler, RollbackTo(first.id))
    requester.expectMsg(RollbackCompleted(SyncCursor(100, first.id, first.parentId)))
    val replacement = BlockInfo(SyncFixtures.id(201), 101, Seq.empty, first.id)
    requester.send(handler, ApplyBlock(replacement))
    requester.expectMsgType[BlockCommitted].cursor.blockId shouldEqual replacement.id

    requester.send(handler, GetRetainedCursors)
    val retained = requester.expectMsgType[RetainedCursors].cursors
    retained.map(_.blockId) shouldEqual Seq(SyncFixtures.id(99), first.id, replacement.id)
    retained.map(_.blockId) should not contain orphan.id
  }

  it should "pin a 384 MiB cache and separate 256 MiB volatile-journal budget" in {
    SyncHandler.MaxMaterializedDictionaryCacheBytes shouldEqual 384 * 1024L * 1024L
    SyncHandler.MaxTransformJournalBytes shouldEqual 256L * 1024L * 1024L
  }

  it should "put the cheapest max-size NISP cache-saturation path above one block reward" in {
    val maxContractNispBytes = LFSMHelpers.NISP_MAX - 1
    val dictionary = PlasmaDictionary.empty()
    val emptyWeight = dictionary.estimatedHeapBytes
    val key = Array.fill[Byte](dictionary.parameters.keySize)(1)

    dictionary.insert(key -> Array.fill[Byte](maxContractNispBytes)(2))
    val maxNispEntryWeight = dictionary.estimatedHeapBytes - emptyWeight
    val firstEntryOverBudget =
      ((SyncHandler.MaxMaterializedDictionaryCacheBytes - emptyWeight) / maxNispEntryWeight) + 1L

    // This invariant is coupled to LFSMHelpers.NISP_MAX, which nothing else records.
    info(s"NISP_MAX=${LFSMHelpers.NISP_MAX} entryWeight=$maxNispEntryWeight " +
      s"entriesToSaturate=$firstEntryOverBudget " +
      s"cost=${firstEntryOverBudget * Parameters.MinFee} vs blockReward=${CollateralParams.BLOCK_REWARD}")
    maxNispEntryWeight shouldEqual 118896L // drift guard
    firstEntryOverBudget * Parameters.MinFee should be > CollateralParams.BLOCK_REWARD
  }

  it should "restore the exact rollup origin map on rollback" in {
    val (handler, _, _) = newHandler()
    val requester = TestProbe()
    seed(handler, requester)
    val collateralInput = SyncFixtures.id(290001)
    val holdingOutput = SyncFixtures.id(290002)
    val rollupId = SyncFixtures.id(100)
    val genesis = ReducerFixtures.genesisTx(29, collateralInput, holdingOutput, height = 100)
    val first = BlockInfo(rollupId, 100, Seq(genesis), SyncFixtures.id(99),
      resolvedInputs = Map(collateralInput -> ReducerFixtures.resolvedInput(
        collateralInput, Some(ReducerFixtures.CollateralToken), 100)))
    val second = BlockInfo(SyncFixtures.id(101), 101, Seq.empty, first.id)

    requester.send(handler, ApplyBlock(first))
    requester.expectMsgType[BlockCommitted]
    requester.send(handler, ApplyBlock(second))
    requester.expectMsgType[BlockCommitted]
    requester.send(handler, RollbackTo(first.id))
    requester.expectMsg(RollbackCompleted(SyncCursor(100, first.id, first.parentId)))
    requester.send(handler, GetCommittedState)

    val restored = requester.expectMsgType[CommittedState].state
    restored.rollupOrigins shouldEqual Map(rollupId -> collateralInput)
    restored.routes shouldEqual Map(holdingOutput -> rollupId)
  }

  it should "offer a replayable checkpoint plan only when one is due" in {
    val (handler, snapshots, _) = newHandler()
    val requester = TestProbe()
    seed(handler, requester)
    val first = BlockInfo(SyncFixtures.id(100), 100, Seq.empty, SyncFixtures.id(99))

    // Height 100 is not a multiple of the configured interval, so nothing is offered.
    requester.send(handler, ApplyBlock(first))
    requester.expectMsgType[BlockCommitted]
    snapshots.expectNoMessage(200.millis)

    handler ! MempoolSnapshot(1L, Map.empty, Map.empty, Set.empty)
    requester.send(handler, MarkReady)
    requester.expectMsgType[Ready]
    val forced = snapshots.expectMsgType[SnapshotCandidate]

    requester.send(handler, GetCommittedState)
    val live = requester.expectMsgType[CommittedState].state
    forced.cursor shouldEqual live.cursor
    forced.snapshot.metadata shouldEqual CommittedSyncMetadata.from(live)
    forced.snapshot.dictionaries.keySet shouldEqual Set(DictionaryId.Miner)

    val second = BlockInfo(SyncFixtures.id(101), 101, Seq.empty, first.id)
    requester.send(handler, BeginCatchUp(101))
    requester.send(handler, ApplyBlock(second))
    requester.expectMsgType[BlockCommitted]
    requester.send(handler, MarkReady)
    requester.expectMsgType[Ready]
    snapshots.expectNoMessage(200.millis)
  }

  /** Verifies that a rollup-scoped transform failure does not reject the canonical block. */
  it should "commit a block whose only recognized transform belongs to one rollup that fails" in {
    val (handler, snapshots, _) = newHandler()
    val requester = TestProbe()
    seed(handler, requester)
    val dictionary = lfsm.states.PlasmaDictionary.empty()
    val rollupId = SyncFixtures.id(100)
    val utxoId = SyncFixtures.id(300001)

    // The second block carries a digest mismatch attributable to the tracked rollup.
    val genesisInput = SyncFixtures.id(300002)
    val genesis = ReducerFixtures.genesisTx(1, genesisInput, utxoId, height = 100)
    val first = BlockInfo(rollupId, 100, Seq(genesis), SyncFixtures.id(99),
      resolvedInputs = Map(genesisInput ->
        ReducerFixtures.resolvedInput(genesisInput, Some(ReducerFixtures.CollateralToken), 100)))
    requester.send(handler, ApplyBlock(first))
    requester.expectMsgType[BlockCommitted]
    requester.send(handler, MarkReady)
    requester.expectMsgType[Ready]
    // Readiness forces one checkpoint even when the normal interval is not due.
    snapshots.expectMsgType[SnapshotCandidate]

    val (key, value) = SyncFixtures.plasmaEntries(1, 16).head
    val bad = ReducerFixtures.submissionTx(2, utxoId, SyncFixtures.id(300003), 101,
      key, value, ReducerFixtures.proofHex(Array[Byte](7)), dictionary,
      numMiners = 1, totalScore = BigInt(0), period = 100L)
    val second = BlockInfo(SyncFixtures.id(101), 101, Seq(bad), first.id)

    requester.send(handler, ApplyBlock(second))
    val load = snapshots.expectMsgType[state.persistence.StateSnapshotActor.MaterializeDictionary]
    snapshots.reply(state.persistence.StateSnapshotActor.DictionaryLoaded(
      load.source.expectedDigest, load.requestToken, dictionary))
    val committed = requester.expectMsgType[BlockCommitted]

    committed.cursor.blockId shouldEqual second.id
    requester.send(handler, GetCurrentRollup(rollupId))
    requester.expectMsgType[state.messages.RollupMessages.NoRollupFound]
  }

  /** A parent mismatch is block-wide and must leave the cursor unchanged. */
  it should "refuse to advance the cursor when a block does not follow the committed one" in {
    val (handler, _, _) = newHandler()
    val requester = TestProbe()
    seed(handler, requester)
    val first = BlockInfo(SyncFixtures.id(100), 100, Seq.empty, SyncFixtures.id(99))
    requester.send(handler, ApplyBlock(first))
    requester.expectMsgType[BlockCommitted]

    val forked = BlockInfo(SyncFixtures.id(201), 101, Seq.empty, SyncFixtures.id(999))
    requester.send(handler, ApplyBlock(forked))
    requester.expectMsgType[BlockRejected].reason should include("Expected parent")

    requester.send(handler, GetCommittedState)
    requester.expectMsgType[CommittedState].state.cursor.blockId shouldEqual first.id
  }

  it should "force checkpoint compaction before a block can cross the volatile journal bound" in {
    val base = ReducerFixtures.emptyState(height = 99, blockId = SyncFixtures.id(99), version = 0L)
    val first = BlockInfo(SyncFixtures.id(100), 100, Seq.empty, SyncFixtures.id(99))
    val firstState = BlockReducer.applyBlock(base, first, ReducerFixtures.protocol(100))
      .toOption.get.state
    val oneEntry = TransformJournal(base.cursor).append(TransformJournalEntry(firstState.cursor,
      CommittedSyncMetadata.from(firstState), Vector.empty)).accountedBytes
    val (handler, snapshots, _) = newHandler(maxJournalBytes = oneEntry)
    val requester = TestProbe()
    seed(handler, requester)

    requester.send(handler, ApplyBlock(first))
    requester.expectMsgType[BlockCommitted]
    val second = BlockInfo(SyncFixtures.id(101), 101, Seq.empty, first.id)
    requester.send(handler, ApplyBlock(second))
    requester.expectMsgType[BlockRejected].reason should include("journal would retain")
    val compaction = snapshots.expectMsgType[SnapshotCandidate]
    compaction.cursor shouldEqual SyncCursor(100, first.id, first.parentId)

    requester.send(handler, GetCommittedState)
    requester.expectMsgType[CommittedState].state.cursor.height shouldEqual 100

    snapshots.send(handler, state.persistence.StateSnapshotActor.SnapshotSaved(
      compaction.cursor, compaction.checkpointToken))
    requester.send(handler, ApplyBlock(second))
    requester.expectMsgType[BlockCommitted].cursor.height shouldEqual 101
  }

  it should "stop at the journal bound without growing when snapshots are disabled" in {
    val base = ReducerFixtures.emptyState(height = 99, blockId = SyncFixtures.id(99), version = 0L)
    val first = BlockInfo(SyncFixtures.id(100), 100, Seq.empty, base.cursor.blockId)
    val firstState = BlockReducer.applyBlock(base, first, ReducerFixtures.protocol(100))
      .toOption.get.state
    val oneEntry = TransformJournal(base.cursor).append(TransformJournalEntry(firstState.cursor,
      CommittedSyncMetadata.from(firstState), Vector.empty)).accountedBytes
    val (handler, snapshots, _) = newHandler(maxJournalBytes = oneEntry, snapshotsEnabled = false)
    val requester = TestProbe()
    seed(handler, requester)

    requester.send(handler, ApplyBlock(first))
    requester.expectMsgType[BlockCommitted]
    val second = BlockInfo(SyncFixtures.id(101), 101, Seq.empty, first.id)

    (1 to 3).foreach { _ =>
      requester.send(handler, ApplyBlock(second))
      requester.expectMsgType[BlockRejected].reason should include("journal would retain")
    }
    snapshots.expectNoMessage(200.millis)
    requester.send(handler, GetCommittedState)
    requester.expectMsgType[CommittedState].state.cursor shouldEqual firstState.cursor
  }

  it should "ignore evaluation completion for a superseded rollup box" in {
    val (handler, _, _) = newHandler()
    val requester = TestProbe()
    seed(handler, requester)
    val origin = SyncFixtures.id(301001)
    val utxoId = SyncFixtures.id(301002)
    val genesis = ReducerFixtures.genesisTx(1, origin, utxoId, height = 100)
    val block = BlockInfo(SyncFixtures.id(100), 100, Seq(genesis), SyncFixtures.id(99),
      resolvedInputs = Map(origin ->
        ReducerFixtures.resolvedInput(origin, Some(ReducerFixtures.CollateralToken), 100)))
    requester.send(handler, ApplyBlock(block))
    requester.expectMsgType[BlockCommitted]

    requester.send(handler, UpdateEvaluation(block.id, Some(SyncFixtures.id(301003))))
    firstState(requester, handler).rollups(block.id).evaluated shouldBe false
    requester.send(handler, UpdateEvaluation(block.id, Some(utxoId)))
    firstState(requester, handler).rollups(block.id).evaluated shouldBe true
  }

  private def firstState(requester: TestProbe, handler: akka.actor.ActorRef): CommittedSyncState = {
    requester.send(handler, GetCommittedState)
    requester.expectMsgType[CommittedState].state
  }

  /**
   * The producer seeds this owner before any block, so no committed state means the owner lost its own.
   */
  it should "refuse a block rather than invent state when it has never been seeded" in {
    val (handler, _, _) = newHandler()
    val requester = TestProbe()
    val block = BlockInfo(SyncFixtures.id(100), 100, Seq.empty, SyncFixtures.id(99))

    requester.send(handler, ApplyBlock(block))
    requester.expectMsg(SyncUnseeded)

    requester.send(handler, GetCommittedState)
    requester.expectMsgType[SyncUnavailable]
  }

  /**
   * Seeds the owner the way the producer does. A block arriving before this is refused, because an
   * owner with no committed state has lost it rather than started fresh.
   */
  private def seed(handler: akka.actor.ActorRef, requester: TestProbe): Unit = {
    val base = ReducerFixtures.emptyState(height = 99, blockId = SyncFixtures.id(99), version = 0L)
    requester.send(handler, RestoreCommittedState(base, Vector(base.cursor)))
    requester.expectMsgType[BlockCommitted]
  }

  private def newHandler(maxJournalBytes: Long = SyncHandler.MaxTransformJournalBytes,
                         snapshotsEnabled: Boolean = true) = {
    val snapshots = TestProbe()
    val (nodeContext, _, wallet) = FakeNodeContext(numAddresses = 1)
    val protocol = ReducerFixtures.protocol(rollupStartHeight = 100)
      .copy(localMinerHash = wallet.contract.hashedPropBytes)
    val config = Configuration(ConfigFactory.parseString(
      s"""sync.startHeight = 100
         |sync.snapshots.enabled = $snapshotsEnabled
         |sync.snapshots.intervalBlocks = 720
         |""".stripMargin))
    val handler = system.actorOf(Props(new SyncHandler(config, protocol, snapshots.ref) {
      override protected def maxTransformJournalBytes: Long = maxJournalBytes
    }))
    (handler, snapshots, protocol)
  }
}
