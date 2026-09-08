package state.synchronization

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import node.NodeApi
import node.model.{NodeAsset, NodeBox, NodeInput, NodeRegisters, NodeSpendingProof, NodeTransaction}
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.when
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.Configuration
import state.messages.MempoolMessages.{MempoolSnapshot, MempoolUnavailable}
import state.messages.SyncMessages.{Ready, SyncCursor}
import state.messages.{Capability, SyncView}
import lfsm.states.{PlasmaDictionary, Rollup, RollupInfoState}
import support.{ChainFixtures, FakeNodeContext, ReducerFixtures, SyncFixtures}
import utils.Globals

import scala.concurrent.duration._
import scala.util.Success

/**
 * Covers bounded mempool revisions without coupling projection health to confirmed-state readiness.
 *
 * The rollup view is derived from the one complete observation, so these drive the inventory and
 * body endpoints that observation reads rather than a rollup-script scan.
 */
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

    val unavailable = sync.expectMsgType[MempoolUnavailable](10.seconds)
    unavailable.reason should include("maxTransactions")
    view ! akka.actor.PoisonPill
  }

  /** Publishes only revisions that fit within the configured transaction bound. */
  it should "publish a complete revision when the mempool is within its bound" in {
    val sync = TestProbe()
    ready()
    val view = viewOver(transactions = 3, bound = 100, sync)

    val snapshot = sync.expectMsgType[MempoolSnapshot](10.seconds)
    snapshot.revision shouldEqual 1L
    snapshot.chains.keySet shouldEqual (0 until 3).map(root).toSet
    view ! akka.actor.PoisonPill
  }

  /** One body per member, however many views read it, so a transaction is never counted twice. */
  it should "retain each mempool transaction exactly once" in {
    val sync = TestProbe()
    ready()
    val view = viewOver(transactions = 3, bound = 100, sync)

    val snapshot = sync.expectMsgType[MempoolSnapshot](10.seconds)
    snapshot.chains.values.flatMap(_.transforms.map(_.tx.id)).toSeq.distinct should have size 3
    view ! akka.actor.PoisonPill
  }

  /** Following an untracked root would let anyone paying a rollup script cost this client work. */
  it should "build no chain for a root this client does not track" in {
    val sync = TestProbe()
    ready(tracked = 1)
    val view = viewOver(transactions = 3, bound = 100, sync)

    val snapshot = sync.expectMsgType[MempoolSnapshot](10.seconds)
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
   * Ordinary churn, not a fault. Withdrawing projections every time a transaction arrived or
   * confirmed mid-walk would drop them exactly when the mempool is busiest.
   */
  it should "retry without withdrawing projections when the mempool changed mid-walk" in {
    val sync = TestProbe()
    ready()
    val view = viewOver(transactions = 3, bound = 100, sync, growBy = 2)

    sync.expectNoMessage(1.second)
    view ! akka.actor.PoisonPill
  }

  /** The first input of the fixture transaction at `index`, which is also its chain root. */
  private def root(index: Int): String = SyncFixtures.id(410000 + index)

  /** Publishes a view tracking the first `tracked` fixture roots, since only those are followed. */
  private def ready(tracked: Int = 512): Unit = {
    val tree = Rollup(PlasmaDictionary.empty(), 0, BigInt(0),
      RollupInfoState.holding(100L, 100L, 0L), 0L, 100,
      hasMiner = false, evaluated = false, blockId = "rollup", utxoId = "utxo")
    Globals.setSyncView(SyncView.initial.copy(
      status = Ready(SyncCursor(100, SyncFixtures.id(100), SyncFixtures.id(99))),
      rollups = (0 until tracked).map(index => root(index) -> tree.metadata),
      canonical = Capability.Available))
  }

  /**
   * @param growBy how the inventory changes between the walk's first and second read: the walk
   *               rejects any difference as churn, so a nonzero value exercises that retry.
   */
  private def viewOver(transactions: Int,
                       bound: Int,
                       sync: TestProbe,
                       growBy: Int = 0): akka.actor.ActorRef = {
    val api = mock[NodeApi]
    val (nodeContext, _, wallet) = FakeNodeContext(api, numAddresses = 1)
    val protocol = ReducerFixtures.protocol(rollupStartHeight = 100)
    val tree = wallet.contract.ergoTreeHex
    val bodies = (0 until transactions + math.max(0, growBy)).map(unconfirmed(tree))

    when(api.info()).thenReturn(Success(
      ChainFixtures.infoAt(100).copy(bestFullHeaderId = Some("ab" * 32))))
    // The walk reads the inventory twice and requires both to agree; the second read answers with
    // the changed mempool so the churn path is exercised.
    val reads = new java.util.concurrent.atomic.AtomicInteger(0)
    when(api.unconfirmedTransactionIds()).thenAnswer { _ =>
      val count = if (reads.incrementAndGet() > 1) transactions + growBy else transactions
      Success(bodies.take(count).map(_.id))
    }
    when(api.unconfirmedTransactionById(anyString())).thenAnswer { invocation =>
      val requested = invocation.getArgument[String](0)
      Success(bodies.find(_.id == requested))
    }

    val config = Configuration(ConfigFactory.parseString(
      s"sync.startHeight = 100\nsync.mempool.maxTransactions = $bound\n"))
    system.actorOf(Props(new MempoolView(config, nodeContext, protocol, TestProbe().ref, sync.ref) {
      override protected lazy val completeNode: NodeApi = api
    }))
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
