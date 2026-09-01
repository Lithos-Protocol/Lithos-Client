package state.synchronization

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import node.NodeApi
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import play.api.Configuration
import state.messages.StateFrameMessages.StartSynchronization
import state.messages.SyncMessages._
import state.messages.SyncView
import state.persistence.PersistedSyncState
import state.persistence.StateSnapshotActor.{RestoreLatest, SnapshotLoaded}
import support.{ChainFixtures, FakeNodeContext, ReducerFixtures, SyncFixtures}
import utils.Globals

import scala.concurrent.duration._

/**
 * A snapshot restore that could not be answered must not be read as "there is no snapshot".
 *
 * Descending to a re-seed discards every tracked rollup, rebuilds the whole Miner Dictionary and
 * rescans from `sync.startHeight`. The conditions that leave a restore unanswered are ordinary — a
 * node whose header chain is still catching up, a database that will not open, a read that blocked
 * past its deadline — so reading them as absence costs that rescan for no reason.
 */
class SnapshotDeferralSpec extends TestKit(ActorSystem("snapshot-deferral"))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  override def beforeEach(): Unit = Globals.setSyncView(SyncView.initial)

  override def afterAll(): Unit = {
    Globals.setSyncView(SyncView.initial)
    TestKit.shutdownActorSystem(system)
  }

  private val startHeight = 500

  /**
   * The re-seed arrives as a `RestoreCommittedState` carrying a cursor at `startHeight - 1`, so its
   * absence is the only thing separating the two behaviours: both leave the client not yet
   * synchronized, and both keep polling.
   */
  "StateFrame" should "hold its recovery rung when a snapshot restore could not be answered" in {
    val (frame, sync, snapshots) = started()
    try {
      snapshots.expectMsg(RestoreLatest)
      snapshots.reply(SnapshotLoaded(None, Some("could not verify against the node"),
        retryable = true))

      val seen = sync.receiveWhile(2.seconds) {
        case GetCommittedState =>
          sync.reply(SyncUnavailable(Starting))
          GetCommittedState
        case other => other
      }
      seen.collect { case restore: RestoreCommittedState => restore } shouldBe empty

      // Holding the rung is only safe because the producer keeps asking, so the retry is half of
      // the behaviour and not an implementation detail.
      snapshots.expectMsg(5.seconds, RestoreLatest)
    } finally frame ! akka.actor.PoisonPill
  }

  it should "restore the retained generation once the store can answer" in {
    val (frame, sync, snapshots) = started()
    try {
      snapshots.expectMsg(RestoreLatest)
      snapshots.reply(SnapshotLoaded(None, Some("could not verify against the node"),
        retryable = true))

      sync.receiveWhile(2.seconds) {
        case GetCommittedState =>
          sync.reply(SyncUnavailable(Starting))
          GetCommittedState
        case other => other
      }

      snapshots.expectMsg(5.seconds, RestoreLatest)
      val retained = ReducerFixtures.emptyState(height = 640, blockId = SyncFixtures.id(640))
      snapshots.reply(SnapshotLoaded(Some(PersistedSyncState(retained, Vector(retained.cursor)))))

      // The retained cursor, not a seed at startHeight - 1: nothing was discarded while waiting.
      val restored = sync.fishForMessage(5.seconds) {
        case _: RestoreCommittedState => true
        case _ => false
      }.asInstanceOf[RestoreCommittedState]
      restored.state.cursor.height shouldEqual 640
    } finally frame ! akka.actor.PoisonPill
  }

  /**
   * The definitive answer still seeds. Without this the deferral above would be indistinguishable
   * from a producer that had simply stopped making decisions.
   */
  it should "seed when the store reports that no usable snapshot exists" in {
    val (frame, sync, snapshots) = started()
    try {
      snapshots.expectMsg(RestoreLatest)
      snapshots.reply(SnapshotLoaded(None, Some("No retained synchronization snapshot")))

      val seeded = sync.fishForMessage(5.seconds) {
        case _: RestoreCommittedState => true
        case _ => false
      }.asInstanceOf[RestoreCommittedState]
      seeded.state.cursor.height shouldEqual startHeight - 1
    } finally frame ! akka.actor.PoisonPill
  }

  private def started(): (akka.actor.ActorRef, TestProbe, TestProbe) = {
    val sync = TestProbe()
    val snapshots = TestProbe()
    val nodeApi: NodeApi = ChainFixtures.nodeAt(startHeight)
    val (nodeContext, _, wallet) = FakeNodeContext(nodeApi, numAddresses = 1)
    val protocol = ReducerFixtures.protocol(rollupStartHeight = startHeight)
      .copy(localMinerHash = wallet.contract.hashedPropBytes)
    // The dictionary bootstrap is off so the seed path needs no box reads: this is about which
    // rung the producer chooses, not about what the seed contains.
    val config = Configuration(ConfigFactory.parseString(
      s"""sync.startHeight = $startHeight
         |sync.catchUpBatchBlocks = 4
         |sync.minerDictionary.bootstrap = false
         |sync.pollInterval = 500 millis
         |""".stripMargin))
    val frame = system.actorOf(Props(
      new StateFrame(config, nodeContext, protocol, sync.ref, snapshots.ref)))

    frame ! StartSynchronization
    ChainFixtures.unseeded(sync)
    (frame, sync, snapshots)
  }
}
