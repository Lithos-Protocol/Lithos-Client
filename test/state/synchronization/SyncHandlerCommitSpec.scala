package state.synchronization

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import play.api.Configuration
import state.messages.SyncMessages._
import state.messages.MempoolMessages.{MempoolSnapshot, MempoolUnavailable}
import state.messages.BlockInfo
import state.messages.RollupMessages.GetCurrentRollup
import state.persistence.StateSnapshotActor.SnapshotCandidate
import state.persistence.{StateSnapshotCodec, StateSnapshotIdentity}
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

  /**
   * A candidate is only offered when a write will follow, because each one costs a deep copy of every
   * dictionary.
   */
  it should "offer a snapshot only when one is due, and hand over a detached copy" in {
    val (handler, snapshots, protocol) = newHandler()
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

    // The writer is handed bytes, not the dictionaries the reducer is still reading, so there is no
    // shared object to copy defensively and no second getManifest on the database thread.
    requester.send(handler, GetCommittedState)
    val live = requester.expectMsgType[CommittedState].state
    forced.cursor shouldEqual live.cursor
    forced.encoded.meta.length should be > 0
    val meta = StateSnapshotCodec.decodeMeta(forced.encoded.meta, StateSnapshotIdentity(protocol)).toOption.get
    meta.cursor shouldEqual live.cursor
    meta.minerTree.dictionary.digest should
      contain theSameElementsInOrderAs live.minerTree.dictionary.digest

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
    val (handler, _, _) = newHandler()
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

    val (key, value) = SyncFixtures.plasmaEntries(1, 16).head
    val bad = ReducerFixtures.submissionTx(2, utxoId, SyncFixtures.id(300003), 101,
      key, value, ReducerFixtures.proofHex(Array[Byte](7)), dictionary,
      numMiners = 1, totalScore = BigInt(0), period = 100L)
    val second = BlockInfo(SyncFixtures.id(101), 101, Seq(bad), first.id)

    requester.send(handler, ApplyBlock(second))
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

  private def newHandler() = {
    val snapshots = TestProbe()
    val (nodeContext, _, wallet) = FakeNodeContext(numAddresses = 1)
    val protocol = ReducerFixtures.protocol(rollupStartHeight = 100)
      .copy(localMinerHash = wallet.contract.hashedPropBytes)
    val config = Configuration(ConfigFactory.parseString(
      """sync.startHeight = 100
        |sync.reorgWindow = 4
        |sync.snapshots.intervalBlocks = 720
        |""".stripMargin))
    val handler = system.actorOf(Props(new SyncHandler(config, protocol, snapshots.ref)))
    (handler, snapshots, protocol)
  }
}
