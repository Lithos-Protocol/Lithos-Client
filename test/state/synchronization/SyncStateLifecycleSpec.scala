package state.synchronization

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import lfsm.states.PlasmaDictionary
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import play.api.Configuration
import state.messages.MempoolMessages.MempoolSnapshot
import state.messages.RollupMessages.{GetCurrentRollup, NoRollupFound, RemoveRollup}
import state.messages.SyncMessages._
import state.persistence.StateSnapshotActor.SnapshotCandidate
import support.{FakeCache, FakeNodeContext, ReducerFixtures, SyncFixtures}

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

  /**
   * The writer receives bytes, so the owner keeps the dictionaries it is still reducing against.
   *
   * Copying instead ran getManifest, loadManifest and generateProof per rollup and left the writer to
   * run getManifest again — three times the work, and a second copy of the rollup set in memory.
   */
  it should "hand the snapshot writer encoded bytes and keep its own dictionaries" in {
    val (handler, snapshots) = newHandler()
    val requester = TestProbe()
    val seeded = ReducerFixtures.emptyState().copy(
      cursor = SyncCursor(startHeight - 1, SyncFixtures.id(startHeight - 1), SyncFixtures.id(startHeight - 2)))

    requester.send(handler, RestoreCommittedState(seeded, Vector(seeded.cursor)))
    requester.expectMsgType[BlockCommitted]
    requester.send(handler, GetCommittedState)
    val before = requester.expectMsgType[CommittedState].state.minerTree.dictionary

    handler ! MempoolSnapshot(1L, Map.empty, Map.empty, Set.empty)
    requester.send(handler, MarkReady)
    requester.expectMsgType[Ready]

    val candidate = snapshots.expectMsgType[SnapshotCandidate]
    candidate.encoded.length should be > 0
    candidate.cursor shouldEqual seeded.cursor

    // The dictionary the owner holds is untouched, because nothing had to be detached for the writer.
    requester.send(handler, GetCommittedState)
    val after = requester.expectMsgType[CommittedState].state.minerTree.dictionary
    after should be theSameInstanceAs before
  }

  /** The retained window is the largest memory term, so its bound is worth pinning. */
  it should "retain no more committed states than the configured window" in {
    val (handler, _) = newHandler(reorgWindow = 3)
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

    // Rolling back to a cursor inside the window succeeds.
    val inWindow = committed(committed.size - 2)
    requester.send(handler, RollbackTo(inWindow.blockId))
    requester.expectMsgType[RollbackCompleted].cursor shouldEqual inWindow

    // One outside it is refused, which is what routes a deeper fork to a snapshot instead.
    requester.send(handler, RollbackTo(committed.head.blockId))
    requester.expectMsgType[RollbackRejected].reason should include("outside the retained")
  }

  /** Cursors are cheap, so their window reaches past the states and locates deeper forks. */
  it should "keep more cursors than retained states" in {
    val (handler, _) = newHandler(reorgWindow = 2, cursorWindow = 50)
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

  private def newHandler(reorgWindow: Int = 5, cursorWindow: Int = 720) = {
    val cache = new FakeCache
    val snapshots = TestProbe()
    val (_, _, wallet) = FakeNodeContext(numAddresses = 1)
    val protocol = ReducerFixtures.protocol(rollupStartHeight = startHeight)
      .copy(localMinerHash = wallet.contract.hashedPropBytes)
    val config = Configuration(ConfigFactory.parseString(
      s"""sync.startHeight = $startHeight
         |sync.reorgWindow = $reorgWindow
         |sync.cursorWindow = $cursorWindow
         |sync.snapshots.intervalBlocks = 720
         |""".stripMargin))
    val handler = system.actorOf(Props(new SyncHandler(config, protocol, cache, snapshots.ref)))
    (handler, snapshots)
  }
}
