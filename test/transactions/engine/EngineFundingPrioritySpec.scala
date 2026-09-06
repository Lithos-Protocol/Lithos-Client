package transactions.engine

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import node.NodeApi
import node.model._
import org.ergoplatform.sdk.ErgoId
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import support.FakeNodeContext
import transactions.engine.EngineWalletMessages._
import work.lithos.mutations.UTXO
import java.util.concurrent.{CompletableFuture, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._
import scala.util.Success

class EngineFundingPrioritySpec extends TestKit(ActorSystem("engine-funding-priority-spec", ConfigFactory.load()))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {
  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  "Critical funding" should "complete while optional selection is blocked on node IO" in {
    val api = mock[NodeApi]
    val (ctx, _, wallet) = FakeNodeContext(api)
    val input = ctx.getClient.execute(c => UTXO(wallet.contract, 10000000L)
      .toInput(c, ErgoId.create("aa" * 32), 0.toShort))
    val box = WalletInventory.nodeBox(input)
    val entry = WalletBox(box, wallet.p2pk.toString, Some(10), box.transactionId,
      0, Some(1), None, None, spent = false, onchain = true, Seq.empty)
    val gate = new CompletableFuture[Unit]()
    val entered = new CompletableFuture[Unit]()
    val calls = new AtomicInteger()
    when(api.indexerEnabled).thenReturn(false)
    when(api.walletUnspentBoxes(any[ConfirmationRange], any[Paging])).thenAnswer { _ =>
      if (calls.incrementAndGet() == 1) { entered.complete(()); gate.get(10, TimeUnit.SECONDS) }
      Success(Seq(entry))
    }
    val engine = system.actorOf(Props(new EngineWalletState(ctx)))
    val optional = TestProbe()
    val critical = TestProbe()
    try {
      optional.send(engine, RetrieveInputs(1000000L, Seq.empty, reservationId = "optional"))
      entered.get(5, TimeUnit.SECONDS)
      critical.send(engine, CriticalWalletRequest(RetrieveInputs(1000000L, Seq.empty, reservationId = "critical")))
      critical.expectMsgType[WalletInputs](2.seconds).inputs.map(_.id) shouldBe Seq(input.id)
      optional.expectNoMessage(100.millis)
      gate.complete(())
      optional.expectMsgType[WalletInputs](3.seconds).inputs shouldBe empty
    } finally { gate.complete(()); system.stop(engine) }
  }

  it should "reserve two input batches of ownership capacity beyond the optional ceiling" in {
    val (ctx, _, wallet) = FakeNodeContext()
    val inputs = ctx.getClient.execute(c => (1 to 513).map(i => UTXO(wallet.contract, 1000000L)
      .toInput(c, ErgoId.create(f"$i%064x"), 0.toShort)))
    val engine = system.actorOf(Props(new EngineWalletState(ctx)))
    val probe = TestProbe()
    def reserve(from: Int, count: Int, critical: Boolean): WalletInputs = {
      val request = ReserveKnownInputs(inputs.slice(from, from + count), s"allocation-$from", Long.MaxValue)
      probe.send(engine, if (critical) CriticalWalletRequest(request) else request)
      probe.expectMsgType[WalletInputs]
    }
    try {
      Seq(0, 75, 150, 225).foreach(i => reserve(i, 75, critical = false).inputs should have size 75)
      reserve(300, 62, critical = false).inputs should have size 62
      reserve(362, 1, critical = false).inputs shouldBe empty
      reserve(362, 75, critical = true).inputs should have size 75
      reserve(437, 75, critical = true).inputs should have size 75
      reserve(512, 1, critical = true).inputs shouldBe empty
    } finally system.stop(engine)
  }
}
