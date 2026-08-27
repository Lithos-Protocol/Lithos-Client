package state.synchronization

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import node.NodeApi
import node.model.NodeHeader
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{doAnswer, when}
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.Configuration
import state.messages.StateFrameMessages.{CheckBlock, StartSynchronization}
import state.messages.SyncMessages._
import state.persistence.StateSnapshotActor.{RestoreLatest, SnapshotLoaded}
import support.{ChainFixtures, FakeNodeContext, ReducerFixtures}
import tasks.RollupSyncTask

import scala.concurrent.duration._
import scala.util.{Success, Try}

/**
 * Covers the recovery paths a clean run never enters: an empty canonical range, an exception escaping
 * the producer's receive, and the deepest reorg tier.
 *
 * An earlier build restarted this actor on any exception, which reset the flag that the single scheduled
 * start message sets — leaving a live ticker, an inactive producer, and no way back.
 */
class StateFrameRecoverySpec extends TestKit(ActorSystem("state-frame-recovery"))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val startHeight = 500

  "CanonicalBlockSource" should "reject an empty answer to a non-empty header range" in {
    val nodeApi = mock[NodeApi]
    when(nodeApi.chainSlice(any[Option[Int]], any[Option[Int]]))
      .thenReturn(Success(Seq.empty[NodeHeader]): Try[Seq[NodeHeader]])

    val failure = new CanonicalBlockSource(nodeApi).headerSlice(startHeight, startHeight + 3).failed.get

    failure.getMessage should include("returned 0 of 4 header(s)")
  }

  it should "reject a range that stops short of the requested end" in {
    val nodeApi = mock[NodeApi]
    // Correct from the start but truncated. A position-only check passes this, because it inspects
    // only the elements that are present.
    when(nodeApi.chainSlice(any[Option[Int]], any[Option[Int]])).thenReturn(
      Success(Seq(ChainFixtures.header(startHeight), ChainFixtures.header(startHeight + 1))))

    val failure = new CanonicalBlockSource(nodeApi).headerSlice(startHeight, startHeight + 3).failed.get

    failure.getMessage should include("returned 2 of 4 header(s)")
  }

  "StateFrame" should "keep polling after an unexpected response would have thrown" in {
    val (frame, sync, _) = started(tip = startHeight + 1)
    sync.expectMsgType[BeginCatchUp]
    val first = sync.expectMsgType[ApplyBlock].blockInfo

    // A commit response with no matching case. The producer must degrade to a stall, not die.
    sync.reply("not a commit response")
    sync.expectMsgType[MarkStale]

    frame ! CheckBlock
    sync.expectMsgType[BeginCatchUp]
    sync.expectMsgType[ApplyBlock].blockInfo.height shouldEqual first.height
    frame ! akka.actor.PoisonPill
  }

  /**
   * The deepest tier clears dictionary state, and catch-up starts above the dictionary genesis, so
   * resuming the poll directly would commit against an empty dictionary the client treats as correct.
   */
  it should "re-seed the Miner Dictionary after a full rescan instead of resuming the poll" in {
    val sync = TestProbe()
    val snapshots = TestProbe()
    val nodeApi = ChainFixtures.nodeAt(startHeight + 2)
    val (nodeContext, _, wallet) = FakeNodeContext(nodeApi, numAddresses = 1)
    val protocol = ReducerFixtures.protocol(rollupStartHeight = startHeight)
      .copy(localMinerHash = wallet.contract.hashedPropBytes)
    val frame = system.actorOf(Props(
      new StateFrame(configWith(startHeight), nodeContext, protocol, sync.ref, snapshots.ref)))

    frame ! StartSynchronization
    snapshots.expectMsg(RestoreLatest)
    snapshots.reply(SnapshotLoaded(None))
    val seed = sync.expectMsgType[RestoreCommittedState]
    sync.reply(BlockCommitted(seed.state.cursor, 0L, 0, 0))
    sync.expectMsgType[BeginCatchUp]
    val applied = sync.expectMsgType[ApplyBlock].blockInfo
    val committed = SyncCursor(applied.height, applied.id, applied.parentId)
    sync.reply(BlockCommitted(committed, 1L, 0, 0))
    sync.expectMsgType[ApplyBlock]
    sync.reply(BlockRejected(Some(committed), "", "stop here"))
    sync.expectMsgType[MarkStale]

    // Replace the chain under the committed cursor so the next poll sees a fork it cannot resolve.
    // `doAnswer` rather than `when`, because `when` calls the method to record it and the answer
    // already installed by the fixture would run against null arguments.
    doAnswer(new Answer[Try[Seq[NodeHeader]]] {
      override def answer(invocation: InvocationOnMock): Try[Seq[NodeHeader]] = {
        val from = Option(invocation.getArgument[Option[Int]](0)).flatten.getOrElse(0)
        val to = Option(invocation.getArgument[Option[Int]](1)).flatten.getOrElse(startHeight + 2)
        Success((math.max(from + 1, 1) to math.min(to, startHeight + 2)).map(forked))
      }
    }).when(nodeApi).chainSlice(any[Option[Int]], any[Option[Int]])

    frame ! CheckBlock
    sync.expectMsgType[MarkStale]
    sync.expectMsg(GetRetainedCursors)
    sync.reply(RetainedCursors(Seq(committed)))

    // No retained cursor is canonical and no snapshot exists, so the producer resets.
    snapshots.expectMsg(RestoreLatest)
    snapshots.reply(SnapshotLoaded(None))
    sync.expectMsg(ResetSyncState)
    sync.reply(SyncStateReset)

    // The assertion: a fresh seed, not a poll. Before the fix this went straight to BeginCatchUp.
    val reseed = sync.expectMsgType[RestoreCommittedState]
    reseed.state.rollups shouldBe empty
    reseed.state.minerDictionaryFault should not be empty
    frame ! akka.actor.PoisonPill
  }

  /**
   * A restart resets the producer's started flag and only this message sets it, so it has to repeat.
   * Asserted through the task rather than by forcing a restart, because the receive is now total.
   */
  "RollupSyncTask" should "keep offering to start synchronization rather than sending once" in {
    val frame = TestProbe()
    val config = Configuration(ConfigFactory.parseString(
      """lithos-tasks.rollup-sync-task.enabled = true
        |lithos-tasks.rollup-sync-task.startup = 150 millis
        |lithos-tasks.rollup-sync-task.interval = 150 millis
        |""".stripMargin).withFallback(ConfigFactory.load()))

    new RollupSyncTask(system, config, frame.ref)

    frame.expectMsg(2.seconds, StartSynchronization)
    frame.expectMsg(2.seconds, StartSynchronization)
  }

  private def forked(height: Int): NodeHeader =
    ChainFixtures.header(height).copy(id = "forked-" + height, parentId = "forked-" + (height - 1))

  private def configWith(start: Int, retriesBeforeAlarm: Int = 12): Configuration =
    Configuration(ConfigFactory.parseString(
      s"""sync.startHeight = $start
         |sync.catchUpBatchBlocks = 4
         |sync.minerDictionary.bootstrap = false
         |sync.retriesBeforeAlarm = $retriesBeforeAlarm
         |""".stripMargin))

  private def started(tip: Int): (akka.actor.ActorRef, TestProbe, TestProbe) = {
    val sync = TestProbe()
    val snapshots = TestProbe()
    val (nodeContext, _, wallet) = FakeNodeContext(ChainFixtures.nodeAt(tip), numAddresses = 1)
    val protocol = ReducerFixtures.protocol(rollupStartHeight = startHeight)
      .copy(localMinerHash = wallet.contract.hashedPropBytes)
    val frame = system.actorOf(Props(
      new StateFrame(configWith(startHeight), nodeContext, protocol, sync.ref, snapshots.ref)))
    frame ! StartSynchronization

    snapshots.expectMsg(RestoreLatest)
    snapshots.reply(SnapshotLoaded(None))
    val restore = sync.expectMsgType[RestoreCommittedState]
    sync.reply(BlockCommitted(restore.state.cursor, 0L, 0, 0))
    (frame, sync, snapshots)
  }
}
