package state.synchronization

import akka.actor.{ActorIdentity, ActorSystem, Identify, Kill, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import lfsm.states.PlasmaDictionary
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import play.api.Configuration
import state.messages.MempoolMessages.{MempoolChain, MempoolSnapshot}
import state.messages.RollupMessages.{CurrentRollup, GetCurrentRollup, NoRollupFound, RemoveRollup}
import state.messages.SyncMessages._
import state.persistence.StateSnapshotActor.{DictionaryLoaded, MaterializeDictionary, SnapshotCandidate, SnapshotSaveFailed, SnapshotSaved}
import support.{FakeCache, FakeNodeContext, ReducerFixtures, RestartingSupervisor, SyncFixtures}

import scala.concurrent.duration._

/**
 * What the state owner keeps, and what it lets go of.
 */
class SyncStateLifecycleSpec extends TestKit(ActorSystem("sync-state-lifecycle"))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val startHeight = 100

  /**
   * A rollup removed for an unsubmittable NISP must leave state, not merely be hidden.
   *
   * The NISP window closes at the rollup's own creation height, so the miss is permanent — and while
   * the rollup stays tracked the reducer keeps copying its tree for every other miner's submission.
   */
  "SyncHandler" should "stop tracking a rollup it can never submit to, rather than hiding it" in {
    val (handler, _) = newHandler()
    val requester = TestProbe()
    val rollupId = SyncFixtures.id(600001)
    val utxoId = SyncFixtures.id(600002)
    val seeded = ReducerFixtures.stateWithRollup(startHeight - 1, SyncFixtures.id(startHeight - 1),
      rollupId, utxoId, PlasmaDictionary.empty())

    requester.send(handler, RestoreCommittedState(seeded, Vector(seeded.cursor)))
    requester.expectMsgType[BlockCommitted]
    requester.send(handler, MarkReady)
    requester.expectMsg(Ready(seeded.cursor))

    requester.send(handler, GetCommittedState)
    requester.expectMsgType[CommittedState].state.rollups.keySet should contain(rollupId)

    handler ! RemoveRollup(rollupId, "Unable to submit valid NISP")

    requester.send(handler, GetCommittedState)
    val after = requester.expectMsgType[CommittedState].state
    withClue("the rollup and its route must both leave committed state: ") {
      after.rollups.keySet should not contain rollupId
      after.routes.keySet should not contain utxoId
    }

    // And it is gone for consumers too, which is what hiding it already achieved.
    requester.send(handler, GetCurrentRollup(rollupId))
    requester.expectMsgType[NoRollupFound]
  }

  it should "hand the writer a replay plan while keeping committed state digest-only" in {
    val (handler, snapshots) = newHandler(snapshotsEnabled = true)
    val requester = TestProbe()
    val seeded = ReducerFixtures.emptyState().copy(
      cursor = SyncCursor(startHeight - 1, SyncFixtures.id(startHeight - 1), SyncFixtures.id(startHeight - 2)))

    requester.send(handler, RestoreCommittedState(seeded, Vector(seeded.cursor)))
    requester.expectMsgType[BlockCommitted]
    requester.send(handler, GetCommittedState)
    val before = requester.expectMsgType[CommittedState].state.minerTree.dictionary
    before.materialized shouldBe false

    handler ! MempoolSnapshot(1L, Map.empty, Map.empty, Set.empty)
    requester.send(handler, MarkReady)
    requester.expectMsgType[Ready]

    val candidate = snapshots.expectMsgType[SnapshotCandidate]
    candidate.cursor shouldEqual seeded.cursor
    candidate.snapshot.metadata.minerDictionary shouldEqual seeded.minerTree.metadata
    candidate.snapshot.dictionaries.keySet shouldEqual Set(DictionaryId.Miner)

    // The materialized base lives only in the checkpoint source while the owner publishes metadata.
    requester.send(handler, GetCommittedState)
    val after = requester.expectMsgType[CommittedState].state.minerTree.dictionary
    after should be theSameInstanceAs before
  }

  it should "roll back anywhere in the volatile transform journal without retained full states" in {
    val (handler, _) = newHandler()
    val requester = TestProbe()
    val seeded = ReducerFixtures.emptyState().copy(
      cursor = SyncCursor(startHeight - 1, SyncFixtures.id(startHeight - 1), SyncFixtures.id(startHeight - 2)))

    requester.send(handler, RestoreCommittedState(seeded, Vector(seeded.cursor)))
    requester.expectMsgType[BlockCommitted]

    val committed = (startHeight to startHeight + 9).map { height =>
      val block = state.messages.BlockInfo(SyncFixtures.id(height), height, Seq.empty,
        SyncFixtures.id(height - 1))
      requester.send(handler, ApplyBlock(block))
      requester.expectMsgType[BlockCommitted].cursor
    }

    // Both a recent and an old boundary are represented by metadata plus journal operations.
    val inWindow = committed(committed.size - 2)
    requester.send(handler, RollbackTo(inWindow.blockId))
    requester.expectMsgType[RollbackCompleted].cursor shouldEqual inWindow

    requester.send(handler, RollbackTo(committed.head.blockId))
    requester.expectMsgType[RollbackCompleted].cursor shouldEqual committed.head
  }

  it should "compact only an acknowledged lagging prefix and retain commits made while it saved" in {
    val (handler, snapshots) = newHandler(snapshotsEnabled = true, snapshotInterval = 3)
    val requester = TestProbe()
    val seeded = ReducerFixtures.emptyState().copy(
      cursor = SyncCursor(startHeight - 1, SyncFixtures.id(startHeight - 1), SyncFixtures.id(startHeight - 2)))
    requester.send(handler, RestoreCommittedState(seeded, Vector(seeded.cursor)))
    requester.expectMsgType[BlockCommitted]

    var pendingCheckpoint = Option.empty[SnapshotCandidate]
    val cursors = (startHeight to startHeight + 4).map { height =>
      requester.send(handler, ApplyBlock(state.messages.BlockInfo(SyncFixtures.id(height), height,
        Seq.empty, SyncFixtures.id(height - 1))))
      val committed = requester.expectMsgType[BlockCommitted].cursor
      if (height == startHeight + 2) {
        val candidate = snapshots.expectMsgType[SnapshotCandidate]
        // Interval 3 retains one volatile entry, so height 101 is the immutable boundary.
        candidate.cursor.height shouldEqual startHeight + 1
        pendingCheckpoint = Some(candidate)
      }
      committed
    }

    val candidate = pendingCheckpoint.get
    snapshots.send(handler, SnapshotSaved(candidate.cursor, candidate.checkpointToken))
    snapshots.send(handler, GetCommittedState)
    snapshots.expectMsgType[CommittedState].state.cursor shouldEqual cursors.last
    snapshots.send(handler, RollbackTo(cursors(2).blockId))
    snapshots.expectMsgType[RollbackCompleted].cursor shouldEqual cursors(2)
    snapshots.send(handler, RollbackTo(cursors(1).blockId))
    snapshots.expectMsgType[RollbackCompleted].cursor shouldEqual cursors(1)
  }

  it should "retain every volatile boundary when a lagging checkpoint fails" in {
    val (handler, snapshots) = newHandler(snapshotsEnabled = true, snapshotInterval = 3)
    val requester = TestProbe()
    val seeded = ReducerFixtures.emptyState().copy(
      cursor = SyncCursor(startHeight - 1, SyncFixtures.id(startHeight - 1), SyncFixtures.id(startHeight - 2)))
    requester.send(handler, RestoreCommittedState(seeded, Vector(seeded.cursor)))
    requester.expectMsgType[BlockCommitted]

    val cursors = (startHeight to startHeight + 2).map { height =>
      requester.send(handler, ApplyBlock(state.messages.BlockInfo(SyncFixtures.id(height), height,
        Seq.empty, SyncFixtures.id(height - 1))))
      requester.expectMsgType[BlockCommitted].cursor
    }
    val candidate = snapshots.expectMsgType[SnapshotCandidate]
    candidate.cursor shouldEqual cursors(1)
    snapshots.send(handler, SnapshotSaveFailed(candidate.cursor, candidate.checkpointToken, "disk full"))

    requester.send(handler, RollbackTo(cursors.head.blockId))
    requester.expectMsgType[RollbackCompleted].cursor shouldEqual cursors.head
  }

  it should "retry a failed checkpoint with backoff instead of hot-looping persistence" in {
    val (handler, snapshots) = newHandler(snapshotsEnabled = true, snapshotInterval = 3,
      retryDelay = 200.millis)
    val requester = TestProbe()
    val seeded = ReducerFixtures.emptyState().copy(
      cursor = SyncCursor(startHeight - 1, SyncFixtures.id(startHeight - 1),
        SyncFixtures.id(startHeight - 2)))
    requester.send(handler, RestoreCommittedState(seeded, Vector(seeded.cursor)))
    requester.expectMsgType[BlockCommitted]

    (startHeight to startHeight + 2).foreach { height =>
      requester.send(handler, ApplyBlock(state.messages.BlockInfo(SyncFixtures.id(height), height,
        Seq.empty, SyncFixtures.id(height - 1))))
      requester.expectMsgType[BlockCommitted]
    }
    val first = snapshots.expectMsgType[SnapshotCandidate]
    snapshots.send(handler, SnapshotSaveFailed(first.cursor, first.checkpointToken, "disk full"))
    snapshots.expectNoMessage(100.millis)
    val retry = snapshots.expectMsgType[SnapshotCandidate](2.seconds)
    retry.cursor shouldEqual first.cursor
    retry.checkpointToken should not equal first.checkpointToken
  }

  it should "refuse to compact a lagging acknowledgement from a replaced same-height branch" in {
    val (handler, snapshots) = newHandler(snapshotsEnabled = true, snapshotInterval = 3)
    val requester = TestProbe()
    val seeded = ReducerFixtures.emptyState().copy(
      cursor = SyncCursor(startHeight - 1, SyncFixtures.id(startHeight - 1), SyncFixtures.id(startHeight - 2)))
    requester.send(handler, RestoreCommittedState(seeded, Vector(seeded.cursor)))
    requester.expectMsgType[BlockCommitted]

    val original = (startHeight to startHeight + 2).map { height =>
      requester.send(handler, ApplyBlock(state.messages.BlockInfo(SyncFixtures.id(height), height,
        Seq.empty, SyncFixtures.id(height - 1))))
      requester.expectMsgType[BlockCommitted].cursor
    }
    val stale = snapshots.expectMsgType[SnapshotCandidate]
    stale.cursor shouldEqual original(1)

    requester.send(handler, RollbackTo(original.head.blockId))
    requester.expectMsgType[RollbackCompleted]
    val replacement101 = SyncCursor(startHeight + 1, SyncFixtures.id(7101), original.head.blockId)
    val replacement102 = SyncCursor(startHeight + 2, SyncFixtures.id(7102), replacement101.blockId)
    Seq(replacement101, replacement102).foreach { cursor =>
      requester.send(handler, ApplyBlock(state.messages.BlockInfo(cursor.blockId, cursor.height,
        Seq.empty, cursor.parentId)))
      requester.expectMsgType[BlockCommitted].cursor shouldEqual cursor
    }

    snapshots.send(handler, SnapshotSaved(stale.cursor, stale.checkpointToken))
    val replacementCheckpoint = snapshots.expectMsgType[SnapshotCandidate]
    replacementCheckpoint.cursor shouldEqual replacement101
    replacementCheckpoint.cursor.blockId should not equal stale.cursor.blockId
  }

  it should "lose its volatile journal on restart and require a checkpoint restore" in {
    val snapshots = TestProbe()
    val childProps = handlerProps(snapshots.ref)
    val supervisor = system.actorOf(RestartingSupervisor.props(childProps))
    val requester = TestProbe()
    requester.send(supervisor, RestartingSupervisor.GetChild)
    val handler = requester.expectMsgType[RestartingSupervisor.Child].ref
    val seeded = ReducerFixtures.emptyState().copy(
      cursor = SyncCursor(startHeight - 1, SyncFixtures.id(startHeight - 1), SyncFixtures.id(startHeight - 2)))
    requester.send(handler, RestoreCommittedState(seeded, Vector(seeded.cursor)))
    requester.expectMsgType[BlockCommitted]
    val next = state.messages.BlockInfo(SyncFixtures.id(startHeight), startHeight, Seq.empty,
      seeded.cursor.blockId)
    requester.send(handler, ApplyBlock(next))
    requester.expectMsgType[BlockCommitted]

    handler ! Kill
    handler.tell(Identify("sync-restarted"), testActor)
    expectMsg(ActorIdentity("sync-restarted", Some(handler)))
    requester.send(handler, RollbackTo(next.id))
    requester.expectMsgType[RollbackRejected].reason should include("no transform journal")

    requester.send(handler, RestoreCommittedState(seeded, Vector(seeded.cursor)))
    requester.expectMsgType[BlockCommitted].cursor shouldEqual seeded.cursor
  }

  it should "publish all metadata without materializing rollups and load only the requested root" in {
    val (handler, snapshots) = newHandler()
    val requester = TestProbe()
    val firstId = SyncFixtures.id(610001)
    val secondId = SyncFixtures.id(610002)
    val first = SyncFixtures.emptyRollup(firstId, SyncFixtures.id(620001), startHeight - 1)
    val second = SyncFixtures.emptyRollup(secondId, SyncFixtures.id(620002), startHeight - 1)
    val seeded = ReducerFixtures.emptyState(startHeight - 1, SyncFixtures.id(startHeight - 1)).copy(
      rollups = Map(firstId -> first, secondId -> second),
      routes = Map(first.utxoId -> firstId, second.utxoId -> secondId),
      rollupOrigins = Map(firstId -> SyncFixtures.id(630001), secondId -> SyncFixtures.id(630002)))

    requester.send(handler, RestoreCommittedState(seeded, Vector(seeded.cursor)))
    requester.expectMsgType[BlockCommitted]
    handler ! MempoolSnapshot(1L, Map.empty, Map.empty, Set.empty)
    requester.send(handler, MarkReady)
    requester.expectMsgType[Ready]

    requester.send(handler, GetSynced)
    requester.expectMsgType[FullSync].rollups.map(_._2.blockId).toSet shouldEqual Set(firstId, secondId)
    snapshots.expectNoMessage(150.millis)

    requester.send(handler, GetCurrentRollup(firstId))
    val load = snapshots.expectMsgType[MaterializeDictionary]
    load.source.expectedDigest shouldEqual first.metadata.dictionaryDigest
    snapshots.reply(DictionaryLoaded(load.source.expectedDigest, load.requestToken, first.dictionary))
    requester.expectMsgType[CurrentRollup].rollup.blockId shouldEqual firstId
    snapshots.expectNoMessage(150.millis)
  }

  it should "materialize only roots with active mempool chains when publishing global projections" in {
    val (handler, snapshots) = newHandler()
    val requester = TestProbe()
    val firstId = SyncFixtures.id(640001)
    val secondId = SyncFixtures.id(640002)
    val first = SyncFixtures.emptyRollup(firstId, SyncFixtures.id(640011), startHeight - 1)
    val second = SyncFixtures.emptyRollup(secondId, SyncFixtures.id(640012), startHeight - 1)
    val seeded = ReducerFixtures.emptyState(startHeight - 1, SyncFixtures.id(startHeight - 1)).copy(
      rollups = Map(firstId -> first, secondId -> second),
      routes = Map(first.utxoId -> firstId, second.utxoId -> secondId),
      rollupOrigins = Map(firstId -> SyncFixtures.id(640021), secondId -> SyncFixtures.id(640022)))

    requester.send(handler, RestoreCommittedState(seeded, Vector(seeded.cursor)))
    requester.expectMsgType[BlockCommitted]
    handler ! MempoolSnapshot(1L, Map(first.utxoId -> MempoolChain(Seq.empty)), Map.empty, Set.empty)
    requester.send(handler, MarkReady)
    requester.expectMsgType[Ready]
    requester.send(handler, GetSynced)

    val load = snapshots.expectMsgType[MaterializeDictionary]
    load.source.expectedDigest shouldEqual first.metadata.dictionaryDigest
    snapshots.reply(DictionaryLoaded(load.source.expectedDigest, load.requestToken, first.dictionary))
    requester.expectMsgType[FullSync].rollups.map(_._2.blockId).toSet shouldEqual Set(firstId, secondId)
    snapshots.expectNoMessage(150.millis)
  }

  /** Cursors are cheap, so their window reaches past the states and locates deeper forks. */
  it should "keep more cursors than retained states" in {
    val (handler, _) = newHandler(cursorWindow = 50)
    val requester = TestProbe()
    val seeded = ReducerFixtures.emptyState().copy(
      cursor = SyncCursor(startHeight - 1, SyncFixtures.id(startHeight - 1), SyncFixtures.id(startHeight - 2)))

    requester.send(handler, RestoreCommittedState(seeded, Vector(seeded.cursor)))
    requester.expectMsgType[BlockCommitted]

    (startHeight to startHeight + 9).foreach { height =>
      requester.send(handler, ApplyBlock(state.messages.BlockInfo(SyncFixtures.id(height), height,
        Seq.empty, SyncFixtures.id(height - 1))))
      requester.expectMsgType[BlockCommitted]
    }

    requester.send(handler, GetRetainedCursors)
    val cursors = requester.expectMsgType[RetainedCursors].cursors
    withClue("a fork deeper than the state window must still be locatable: ") {
      cursors.size should be > 3
    }
  }

  private def newHandler(cursorWindow: Int = 720,
                         snapshotsEnabled: Boolean = false,
                         snapshotInterval: Int = 720,
                         retryDelay: FiniteDuration = SyncHandler.CheckpointRetryDelay) = {
    val snapshots = TestProbe()
    val handler = system.actorOf(handlerProps(snapshots.ref, cursorWindow, snapshotsEnabled,
      snapshotInterval, retryDelay))
    (handler, snapshots)
  }

  private def handlerProps(snapshotActor: akka.actor.ActorRef,
                           cursorWindow: Int = 720,
                           snapshotsEnabled: Boolean = false,
                           snapshotInterval: Int = 720,
                           retryDelay: FiniteDuration = SyncHandler.CheckpointRetryDelay): Props = {
    val (_, _, wallet) = FakeNodeContext(numAddresses = 1)
    val protocol = ReducerFixtures.protocol(rollupStartHeight = startHeight)
      .copy(localMinerHash = wallet.contract.hashedPropBytes)
    val config = Configuration(ConfigFactory.parseString(
      s"""sync.startHeight = $startHeight
         |sync.cursorWindow = $cursorWindow
         |sync.snapshots.enabled = $snapshotsEnabled
          |sync.snapshots.intervalBlocks = $snapshotInterval
         |""".stripMargin))
    Props(new SyncHandler(config, protocol, snapshotActor) {
      override protected def checkpointRetryDelay: FiniteDuration = retryDelay
    })
  }
}
