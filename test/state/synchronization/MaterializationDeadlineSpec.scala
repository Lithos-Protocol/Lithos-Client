package state.synchronization

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import lfsm.states.{DeferredDictionary, PlasmaDictionary}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import play.api.Configuration
import state.messages.RollupMessages.{GetCurrentRollup, RollupUnavailable}
import state.messages.SyncMessages._
import state.persistence.StateSnapshotActor.MaterializeDictionary
import support.{FakeNodeContext, ReducerFixtures, SyncFixtures}

import scala.concurrent.duration._

/**
 * Every materialization barrier must end, including one nothing will ever answer.
 *
 * The persistence actor gives each accepted request its own deadline, so this covers the case where
 * no reply can come at all: a store that would not open leaves the actor alive but answering
 * nothing, and a stopped actor sends its messages to dead letters. Without a deadline here the
 * caller waits out its own ask while `operations` and the two load maps grow by one entry per
 * request for the life of the process.
 */
class MaterializationDeadlineSpec extends TestKit(ActorSystem("materialization-deadline"))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll(): Unit = {
    utils.Globals.setSyncView(state.messages.SyncView.initial)
    TestKit.shutdownActorSystem(system)
  }

  private val startHeight = 100
  private val rollupId = SyncFixtures.id(700001)
  private val utxoId = SyncFixtures.id(700002)

  "SyncHandler" should "end a materialization the persistence actor never answers" in {
    val (handler, snapshots) = readyHandler()
    val requester = TestProbe()

    requester.send(handler, GetCurrentRollup(rollupId))
    // The request reaches the persistence actor, which is what makes this a hang and not a refusal.
    snapshots.expectMsgType[MaterializeDictionary](3.seconds)

    val unavailable = requester.expectMsgType[RollupUnavailable](5.seconds)
    unavailable.reason should include("exceeded")
  }

  /**
   * Every caller is answered, not only the first. A barrier that expired without releasing its load
   * bookkeeping would leave the second request deduplicated onto a token nothing owns any more.
   */
  it should "keep answering callers after an expired materialization" in {
    val (handler, snapshots) = readyHandler()
    val first = TestProbe()
    val second = TestProbe()

    first.send(handler, GetCurrentRollup(rollupId))
    snapshots.expectMsgType[MaterializeDictionary](3.seconds)
    first.expectMsgType[RollupUnavailable](5.seconds)

    second.send(handler, GetCurrentRollup(rollupId))
    // A fresh load rather than a silent join onto the abandoned one.
    snapshots.expectMsgType[MaterializeDictionary](3.seconds)
    second.expectMsgType[RollupUnavailable](5.seconds)
  }

  /** A restore replaces the state a barrier was waiting for, so it must end rather than be dropped. */
  it should "end pending materializations when committed state is replaced" in {
    val (handler, snapshots) = readyHandler()
    val requester = TestProbe()
    val control = TestProbe()

    requester.send(handler, GetCurrentRollup(rollupId))
    snapshots.expectMsgType[MaterializeDictionary](3.seconds)

    val replacement = ReducerFixtures.emptyState(height = startHeight, blockId = SyncFixtures.id(startHeight))
    control.send(handler, RestoreCommittedState(replacement, Vector(replacement.cursor)))
    control.expectMsgType[BlockCommitted]

    // Arrives well inside the deadline, so it is the restore that ended it and not the timer.
    requester.expectMsgType[RollupUnavailable](2.seconds)
  }

  /** Deferred so the request cannot be served from the always-resident state and has to be loaded. */
  private def readyHandler(): (akka.actor.ActorRef, TestProbe) = {
    val snapshots = TestProbe()
    val (_, _, wallet) = FakeNodeContext(numAddresses = 1)
    val protocol = ReducerFixtures.protocol(rollupStartHeight = startHeight)
      .copy(localMinerHash = wallet.contract.hashedPropBytes)
    val config = Configuration(ConfigFactory.parseString(
      s"""sync.startHeight = $startHeight
         |sync.snapshots.enabled = false
         |""".stripMargin))
    val handler = system.actorOf(Props(new SyncHandler(config, protocol, snapshots.ref) {
      override protected def materializationDeadline: FiniteDuration = 1.second
    }))

    val empty = PlasmaDictionary.empty()
    val deferred = DeferredDictionary(empty.digest, empty.flags, empty.parameters)
    val seeded = ReducerFixtures.stateWithRollup(startHeight - 1, SyncFixtures.id(startHeight - 1),
      rollupId, utxoId, deferred)

    val setup = TestProbe()
    setup.send(handler, RestoreCommittedState(seeded, Vector(seeded.cursor)))
    setup.expectMsgType[BlockCommitted]
    setup.send(handler, MarkReady)
    setup.expectMsgType[SyncStatus]
    (handler, snapshots)
  }
}
