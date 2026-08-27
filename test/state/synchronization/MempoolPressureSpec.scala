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

  private def ready(): Unit =
    Globals.setSyncView(SyncView.initial.copy(
      status = Ready(SyncCursor(100, SyncFixtures.id(100), SyncFixtures.id(99))),
      canonical = Capability.Available))

  private def viewOver(transactions: Int, bound: Int, sync: TestProbe): akka.actor.ActorRef = {
    val api = mock[NodeApi]
    val (nodeContext, _, wallet) = FakeNodeContext(api, numAddresses = 1)
    val protocol = ReducerFixtures.protocol(rollupStartHeight = 100)
    val tree = wallet.contract.ergoTreeHex
    // Only the holding script answers, so the dedup across the three rollup scripts is exercised too.
    when(api.unconfirmedTransactionsByErgoTree(any[String], any[Paging])).thenAnswer { invocation =>
      val requested = invocation.getArgument[String](0)
      val paging = invocation.getArgument[Paging](1)
      Success(
        if (requested != protocol.holdingErgoTree || paging.offset > 0) Seq.empty
        else (0 until transactions).map(unconfirmed(tree)))
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
