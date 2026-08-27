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
import support.{FakeCache, FakeNodeContext, ReducerFixtures, SyncFixtures}

class SyncHandlerCommitSpec extends TestKit(ActorSystem("sync-handler-commit"))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  "SyncHandler" should "publish readiness only after an exact block commit" in {
    val (handler, snapshots) = newHandler()
    val requester = TestProbe()
    val block = BlockInfo(SyncFixtures.id(100), 100, Seq.empty, SyncFixtures.id(99))

    requester.send(handler, BeginCatchUp(100))
    requester.send(handler, GetSyncStatus)
    requester.expectMsg(Starting)

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
    requester.expectMsg(FullSync(Seq.empty))

    handler ! MempoolSnapshot(1L, Map.empty, Map.empty, Set.empty)
    requester.send(handler, GetSyncStatus)
    requester.expectMsg(Ready(committed.cursor))

    handler ! MempoolUnavailable("temporary node failure")
    requester.send(handler, GetSyncStatus)
    requester.expectMsg(Ready(committed.cursor))
    requester.send(handler, GetSynced)
    requester.expectMsg(FullSync(Seq.empty))

    handler ! MempoolSnapshot(2L, Map.empty, Map.empty, Set.empty)
    requester.send(handler, GetSynced)
    requester.expectMsg(FullSync(Seq.empty))
    snapshots.expectMsgType[state.persistence.StateSnapshotActor.SnapshotCandidate]
  }

  it should "leave the cursor unchanged on rejection and continue from the last commit" in {
    val (handler, _) = newHandler()
    val requester = TestProbe()
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
    val (handler, _) = newHandler()
    val requester = TestProbe()
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
    retained.map(_.blockId) shouldEqual Seq(first.id, replacement.id)
    retained.map(_.blockId) should not contain orphan.id
  }

  it should "force only the first ready snapshot and then respect the configured interval" in {
    val (handler, snapshots) = newHandler()
    val requester = TestProbe()
    val first = BlockInfo(SyncFixtures.id(100), 100, Seq.empty, SyncFixtures.id(99))

    requester.send(handler, ApplyBlock(first))
    requester.expectMsgType[BlockCommitted]
    snapshots.expectMsgType[SnapshotCandidate].force shouldBe false

    handler ! MempoolSnapshot(1L, Map.empty, Map.empty, Set.empty)
    requester.send(handler, MarkReady)
    requester.expectMsgType[Ready]
    snapshots.expectMsgType[SnapshotCandidate].force shouldBe true

    val second = BlockInfo(SyncFixtures.id(101), 101, Seq.empty, first.id)
    requester.send(handler, BeginCatchUp(101))
    requester.send(handler, ApplyBlock(second))
    requester.expectMsgType[BlockCommitted]
    snapshots.expectMsgType[SnapshotCandidate].force shouldBe false
    requester.send(handler, MarkReady)
    requester.expectMsgType[Ready]
    snapshots.expectNoMessage()
  }

  /** Verifies that a rollup-scoped transform failure does not reject the canonical block. */
  it should "commit a block whose only recognized transform belongs to one rollup that fails" in {
    val (handler, _) = newHandler()
    val requester = TestProbe()
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
    val (handler, _) = newHandler()
    val requester = TestProbe()
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

  private def newHandler() = {
    val cache = new FakeCache
    val snapshots = TestProbe()
    val (nodeContext, _, wallet) = FakeNodeContext(numAddresses = 1)
    val protocol = ReducerFixtures.protocol(rollupStartHeight = 100)
      .copy(localMinerHash = wallet.contract.hashedPropBytes)
    val config = Configuration(ConfigFactory.parseString(
      """sync.startHeight = 100
        |sync.reorgWindow = 4
        |sync.snapshots.intervalBlocks = 720
        |""".stripMargin))
    val handler = system.actorOf(Props(new SyncHandler(config, protocol, cache, snapshots.ref)))
    handler -> snapshots
  }
}
