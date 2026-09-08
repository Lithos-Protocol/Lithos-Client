package transactions.engine

import akka.actor.ActorSystem
import akka.testkit.{TestKit, TestProbe}
import node.NodeApi
import node.model._
import org.mockito.Mockito.when
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import state.synchronization.CompleteMempool
import support.{ChainFixtures, FakeNodeContext}
import transactions.engine.wallet.EngineWalletMessages._
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.Success

class EngineReconciliationSpec extends TestKit(ActorSystem("engine-reconciliation-spec"))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {
  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)
  private implicit val ec = system.dispatcher
  private val first = "ab" * 32
  private val second = "cd" * 32
  private val protocol = "ef" * 32
  private val anchor = "aa" * 32
  private val spender = "bb" * 32
  private val hold = EngineHold("operation", "lease", "ff" * 32, Set(protocol), Set(first, second), sendFinished = true)

  private def exercise(consumed: Boolean, raced: Boolean, otherProtocolConsumed: Boolean = false): Unit = {
    val api = mock[NodeApi]
    val (ctx, _, _) = FakeNodeContext(api)
    def box(id: String): NodeBox = NodeBox(id, "", 1000000L, 0, 1, "")
    when(api.indexedBoxById(protocol)).thenReturn(Success(None))
    val otherProtocol = "ee" * 32
    when(api.indexedBoxById(otherProtocol)).thenReturn(Success(if (otherProtocolConsumed)
      Some(IndexedBox(box(otherProtocol), "", 1, 1L, spentTransactionId = Some(spender))) else None))
    when(api.boxById(otherProtocol)).thenReturn(Success(None))
    when(api.indexedBoxById(first)).thenReturn(Success(if (consumed)
      Some(IndexedBox(box(first), "", 1, 1L, spentTransactionId = Some(spender))) else None))
    when(api.indexedBoxById(second)).thenReturn(Success(None))
    when(api.boxById(first)).thenReturn(Success(if (consumed) None else Some(box(first))))
    when(api.boxById(second)).thenReturn(Success(Some(box(second))))
    val tx = mock[IndexedTransaction]
    when(tx.inputs).thenReturn(Seq(IndexedBox(box(if (otherProtocolConsumed) otherProtocol else first), "", 1, 1L)))
    when(tx.inclusionHeight).thenReturn(10)
    when(tx.blockId).thenReturn(ChainFixtures.headerId(10))
    when(api.indexedTransactionById(spender)).thenReturn(Success(Some(tx)))
    when(api.chainSlice(Some(9), Some(11))).thenReturn(Success(Seq(ChainFixtures.header(10))))
    val wallet = TestProbe()
    val mempool = TestProbe()
    val reconciler = new EngineReconciler(ctx, wallet.ref, mempool.ref) {
      override protected lazy val node: NodeApi = api
    }
    val result = Future(reconciler.reconcile())
    wallet.expectMsg(GetEngineHolds)
    wallet.reply(EngineHolds(Vector(hold.copy(signedInputIds = Set(protocol, otherProtocol)))))
    mempool.expectMsg(CompleteMempool.Refresh)
    mempool.reply(CompleteMempool.Observation(1L, Some(CompleteMempool.Snapshot(anchor,
      Set.empty, Set.empty, System.nanoTime())), None))
    mempool.expectMsg(CompleteMempool.Refresh)
    mempool.reply(CompleteMempool.Observation(2L, Some(CompleteMempool.Snapshot(anchor,
      Set.empty, if (raced) Set(second) else Set.empty, System.nanoTime())), None))
    if (consumed && !raced) {
      wallet.expectMsg(ResolveEngineInputs(hold.reservationId, hold.txId, Set(first), Set(second)))
      wallet.reply(true)
    }
    if (otherProtocolConsumed && !raced) {
      wallet.expectMsg(ResolveEngineInputs(hold.reservationId, hold.txId, Set.empty, Set(first, second)))
      wallet.reply(true)
    }
    Await.result(result, 3.seconds)
    wallet.expectNoMessage(150.millis)
  }

  "Engine reconciliation" should "retain inputs when only local mempool absence is known" in {
    exercise(consumed = false, raced = false)
  }
  it should "retire the confirmed spent input and separately release the surviving unspent input" in {
    exercise(consumed = true, raced = false)
  }
  it should "retain ownership when a surviving input becomes mempool-spent during the checks" in {
    exercise(consumed = true, raced = true)
  }
  it should "release surviving wallet inputs when another protocol input is confirmed spent" in {
    exercise(consumed = false, raced = false, otherProtocolConsumed = true)
  }
}
