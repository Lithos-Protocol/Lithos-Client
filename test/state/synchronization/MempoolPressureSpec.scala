package state.synchronization

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import node.NodeApi
import node.model.{NodeAsset, NodeBox, NodeInput, NodeRegisters, NodeSpendingProof, NodeTransaction, Paging}
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.Configuration
import state.messages.MempoolMessages.{MempoolSnapshot, MempoolUnavailable}
import state.messages.SyncMessages.{Ready, SyncCursor}
import state.messages.{Capability, SyncView}
import lfsm.LFSMPhase
import lfsm.states.{Rollup, PlasmaDictionary}
import support.{FakeNodeContext, ReducerFixtures, SyncFixtures}
import utils.Globals

import scala.concurrent.duration._
import scala.util.Success

/** Covers bounded mempool revisions without coupling projection health to confirmed-state readiness. */
class MempoolPressureSpec extends TestKit(ActorSystem("mempool-pressure"))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  override def afterAll(): Unit = {
    Globals.setSyncView(SyncView.initial)
    TestKit.shutdownActorSystem(system)
  }

  "MempoolView" should "report a mempool over its bound as unavailable, not as an empty one" in {
    val sync = TestProbe()
    ready()
    val view = viewOver(transactions = 12, bound = 4, sync)

    val unavailable = sync.expectMsgType[MempoolUnavailable]
    unavailable.reason should include("maxTransactions")
    view ! akka.actor.PoisonPill
  }

  /** Publishes only revisions that fit within the configured transaction bound. */
  it should "publish a complete revision when the mempool is within its bound" in {
    val sync = TestProbe()
    ready()
    val view = viewOver(transactions = 3, bound = 100, sync)

    val snapshot = sync.expectMsgType[MempoolSnapshot]
    snapshot.revision shouldEqual 1L
    snapshot.chains.keySet shouldEqual (0 until 3).map(root).toSet
    view ! akka.actor.PoisonPill
  }

  it should "count one transaction returned under two scripts only once" in {
    val sync = TestProbe()
    ready(tracked = 1)
    val view = viewOver(transactions = 1, bound = 1, sync, duplicateAcrossTrees = true)

    val snapshot = sync.expectMsgType[MempoolSnapshot]
    snapshot.chains.keySet shouldEqual Set(root(0))
    snapshot.chains(root(0)).transforms.map(_.tx.id).toSet shouldEqual Set(SyncFixtures.id(400000))
    view ! akka.actor.PoisonPill
  }

  /** Following an untracked root would let anyone paying a rollup script cost this client work. */
  it should "build no chain for a root this client does not track" in {
    val sync = TestProbe()
    ready(tracked = 1)
    val view = viewOver(transactions = 3, bound = 100, sync)

    val snapshot = sync.expectMsgType[MempoolSnapshot]
    snapshot.chains.keySet shouldEqual Set(root(0))
    snapshot.endInputs.keySet shouldEqual Set(root(0))
    view ! akka.actor.PoisonPill
  }

  /** Avoids rebuilding projections that no consumer can use during catch-up. */
  it should "not touch the mempool while synchronization is not ready" in {
    val sync = TestProbe()
    Globals.setSyncView(SyncView.initial)
    val view = viewOver(transactions = 3, bound = 100, sync)

    view ! state.messages.StateFrameMessages.NewBlock(
      state.messages.BlockInfo(SyncFixtures.id(1), 1, Seq.empty, SyncFixtures.id(0)))

    sync.expectNoMessage(500.millis)
    view ! akka.actor.PoisonPill
  }

  /**
   * A submission arriving between the two reads cannot cause a paged skip, so it must not fail one.
   *
   * Requiring the reads to be identical rejects most refreshes once submissions are frequent, and
   * every rejection drops projections and logs at error — so the check would defeat itself exactly
   * when mempool chaining is most useful.
   */
  it should "accept a revision when the second read only gained transactions" in {
    val sync = TestProbe()
    ready()
    // Over one page, so the walk could have skipped and the consistency read is actually taken.
    val view = viewOver(transactions = PageSize + 1, bound = 5000, sync, growBy = 2)

    val snapshot = sync.expectMsgType[MempoolSnapshot](10.seconds)
    // The second read is the one kept, so its arrivals are in the published revision.
    snapshot.chains.size shouldEqual PageSize + 3
    view ! akka.actor.PoisonPill
  }

  /** A single page cannot skip an entry, so paying for a second read would be waste. */
  it should "read once when everything fits in one page" in {
    val sync = TestProbe()
    ready()
    val counted = new java.util.concurrent.atomic.AtomicInteger(0)
    val view = viewOver(transactions = 3, bound = 100, sync, reads = Some(counted))

    sync.expectMsgType[MempoolSnapshot]
    // Three scripts, one page each, and no consistency pass over them.
    counted.get() shouldEqual 3
    view ! akka.actor.PoisonPill
  }

  it should "treat an exact full page as complete only after the consistency pass" in {
    val sync = TestProbe()
    ready(tracked = PageSize)
    val counted = new java.util.concurrent.atomic.AtomicInteger(0)
    val view = viewOver(transactions = PageSize, bound = PageSize, sync,
      reads = Some(counted))

    sync.expectMsgType[MempoolSnapshot](10.seconds).chains should have size PageSize
    // Holding: full page + empty terminator. Evaluation and payout: one empty page each.
    // A full page makes the entire three-tree view repeat once for consistency.
    counted.get() shouldEqual 8
    view ! akka.actor.PoisonPill
  }

  it should "reject the first distinct transaction beyond the bound on the next page" in {
    val sync = TestProbe()
    ready(tracked = PageSize + 1)
    val counted = new java.util.concurrent.atomic.AtomicInteger(0)
    val view = viewOver(transactions = PageSize + 1, bound = PageSize, sync,
      reads = Some(counted))

    sync.expectMsgType[MempoolUnavailable](10.seconds).reason should include("maxTransactions")
    // Failure happens on the holding script's second page, before another script or pass is read.
    counted.get() shouldEqual 2
    view ! akka.actor.PoisonPill
  }

  /** A disappearance is the shape that can skip an entry, so it retries rather than publishing. */
  it should "retry without withdrawing projections when a transaction left mid-read" in {
    val sync = TestProbe()
    ready()
    val view = viewOver(transactions = PageSize + 2, bound = 5000, sync, growBy = -2)

    // Neither a revision nor an unavailability: the last good answer stands and the next tick re-reads.
    sync.expectNoMessage(500.millis)
    view ! akka.actor.PoisonPill
  }

  private val PageSize = 500

  /** The first input of the fixture transaction at `index`, which is also its chain root. */
  private def root(index: Int): String = SyncFixtures.id(410000 + index)

  /** Publishes a view tracking the first `tracked` fixture roots, since only those are followed. */
  private def ready(tracked: Int = PageSize + 10): Unit = {
    val tree = Rollup(PlasmaDictionary.empty(), 0, BigInt(0), Some(100L), 0L, 100,
      hasMiner = false, LFSMPhase.HOLDING, evaluated = false, "rollup", "utxo")
    Globals.setSyncView(SyncView.initial.copy(
      status = Ready(SyncCursor(100, SyncFixtures.id(100), SyncFixtures.id(99))),
      rollups = (0 until tracked).map(index => root(index) -> tree.metadata),
      canonical = Capability.Available))
  }

  /**
   * @param growBy how the mempool changes between the consistency read and the one after it:
   *               positive adds transactions, negative removes them, zero holds it still.
   */
  private def viewOver(transactions: Int,
                       bound: Int,
                       sync: TestProbe,
                       growBy: Int = 0,
                       reads: Option[java.util.concurrent.atomic.AtomicInteger] = None,
                       duplicateAcrossTrees: Boolean = false): akka.actor.ActorRef = {
    val api = mock[NodeApi]
    val (nodeContext, _, wallet) = FakeNodeContext(api, numAddresses = 1)
    val protocol = ReducerFixtures.protocol(rollupStartHeight = 100)
    val tree = wallet.contract.ergoTreeHex
    // The view reads the relevant mempool twice per refresh; this answers the second read differently.
    val passes = new java.util.concurrent.atomic.AtomicInteger(0)
    // Only the holding script answers, so the dedup across the three rollup scripts is exercised too.
    when(api.unconfirmedTransactionsByErgoTree(any[String], any[Paging])).thenAnswer { invocation =>
      val requested = invocation.getArgument[String](0)
      val paging = invocation.getArgument[Paging](1)
      reads.foreach(_.incrementAndGet())
      val answers = requested == protocol.holdingErgoTree ||
        (duplicateAcrossTrees && requested == protocol.evaluationErgoTree)
      if (!answers) Success(Seq.empty)
      else {
        // A pass is one walk of this script; the second pass answers with the changed mempool.
        if (paging.offset == 0) passes.incrementAndGet()
        val count = if (passes.get() >= 2) transactions + growBy else transactions
        Success((paging.offset until math.min(paging.offset + paging.limit, count))
          .map(unconfirmed(tree)))
      }
    }
    val config = Configuration(ConfigFactory.parseString(
      s"sync.startHeight = 100\nsync.mempool.maxTransactions = $bound\n"))
    system.actorOf(Props(new MempoolView(config, nodeContext, protocol, TestProbe().ref, sync.ref)))
  }

  /** Builds an independent transaction with a valid ErgoTree for Appkit conversion. */
  private def unconfirmed(ergoTree: String)(index: Int): NodeTransaction = {
    val id = SyncFixtures.id(400000 + index)
    NodeTransaction(
      id = id,
      inputs = Seq(NodeInput(SyncFixtures.id(410000 + index), NodeSpendingProof("", Map.empty))),
      dataInputs = Seq.empty,
      outputs = Seq(NodeBox(SyncFixtures.id(420000 + index), id, 1000000L, 0, 1, ergoTree,
        Seq.empty[NodeAsset], NodeRegisters.empty)))
  }
}
