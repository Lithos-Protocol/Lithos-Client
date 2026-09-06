package transactions.engine

import akka.actor.ActorSystem
import akka.testkit.{TestKit, TestProbe}
import node.NodeApi
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import state.messages.RollupMessages._
import state.synchronization.CompleteMempool
import support.FakeNodeContext
import transactions.engine.TransactionEngine._
import transactions.engine.EngineWalletMessages._
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

class HoldingTransformExecutionSpec extends TestKit(ActorSystem("holding-engine-execution"))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {
  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)
  private implicit val ec = system.dispatcher
  private val intent = HoldingTransform("ab" * 32, 100L,
    transactions.rollups.TransactionMessages.RollupTxStub.ROLLUP_FEE)

  "A rediscovered holding transform" should "skip completed contract work after recreating execution state" in {
    val api = mock[NodeApi]
    val (ctx, _, _) = FakeNodeContext(api)
    val wallet = TestProbe()
    val sync = TestProbe()
    val mempool = TestProbe()
    (1 to 2).foreach { _ =>
      val execution = new HoldingTransformExecution(ctx, wallet.ref, sync.ref, mempool.ref) {
        override protected lazy val node: NodeApi = api
      }
      val result = Future(execution.execute(intent, () => true))
      wallet.expectMsg(GetEngineHolds)
      wallet.reply(EngineHolds(Vector.empty))
      mempool.expectMsg(CompleteMempool.Refresh)
      mempool.reply(CompleteMempool.Observation(1L, Some(CompleteMempool.Snapshot(
        "cd" * 32, Set.empty, Set.empty, System.nanoTime())), None))
      sync.expectMsg(GetCurrentRollupCritical(intent.blockId))
      sync.reply(NoRollupFound())
      Await.result(result, 3.seconds) shouldBe Completed(intent.key)
      wallet.expectNoMessage(100.millis)
      org.mockito.Mockito.verifyNoInteractions(api)
    }
  }

  it should "return the known id while an earlier worker still owns the send" in {
    val (ctx, _, _) = FakeNodeContext()
    val wallet = TestProbe()
    val sync = TestProbe()
    val mempool = TestProbe()
    val execution = new HoldingTransformExecution(ctx, wallet.ref, sync.ref, mempool.ref)
    val result = Future(execution.execute(intent, () => true))
    wallet.expectMsg(GetEngineHolds)
    wallet.reply(EngineHolds(Vector(EngineHold(intent.key, "lease", "ef" * 32, Set("cd" * 32), Set("aa" * 32)))))
    Await.result(result, 3.seconds) shouldBe Uncertain(intent.key, "ef" * 32, "previous send has not completed")
    sync.expectNoMessage(100.millis)
    mempool.expectNoMessage(100.millis)
  }
}