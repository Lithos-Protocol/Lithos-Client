package transactions.engine

import akka.actor.ActorSystem
import akka.testkit.{TestKit, TestProbe}
import node.{NodeApi, NodeError}
import node.model._
import node.rest.NodeCodecs
import org.ergoplatform.appkit.SignedTransaction
import org.ergoplatform.sdk.ErgoId
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.{never, verify, when}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import state.synchronization.CompleteMempool
import support.{ChainFixtures, FakeNodeContext}
import transactions.engine.EngineWalletMessages._
import transactions.engine.EngineFunding
import work.lithos.mutations.UTXO
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

class EngineBroadcastSpec extends TestKit(ActorSystem("engine-broadcast-spec"))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {
  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)
  private implicit val ec = system.dispatcher
  private val txId = "aa" * 32
  private val protocol = "bb" * 32
  private val anchor = "cc" * 32

  private def exercise(response: Try[String], outcome: String, pin: Boolean = true): Unit = {
    val api = mock[NodeApi]
    val (ctx, _, wallet) = FakeNodeContext(api)
    val input = ctx.getClient.execute(c => UTXO(wallet.contract, 10000000L)
      .toInput(c, ErgoId.create("dd" * 32), 0.toShort))
    val owner = TestProbe()
    val allocationFuture = Future(EngineFunding(owner.ref, 5.seconds, ec).reserveCovering(1000000L))
    val request = owner.expectMsgType[RetrieveCoveringInput]
    owner.reply(WalletInputs(Seq(input), request.reservationId))
    val allocation = Await.result(allocationFuture, 5.seconds)
    val signed = mock[SignedTransaction]
    when(signed.getId).thenReturn(txId)
    when(signed.toJson(false)).thenReturn(NodeCodecs.encodeTransaction(NodeTransaction(txId,
      Seq(NodeInput(protocol, NodeSpendingProof.empty), NodeInput(input.id.toString, NodeSpendingProof.empty)),
      Seq.empty, Seq.empty)).toString)
    when(api.info()).thenReturn(Success(ChainFixtures.infoAt(100000).copy(bestFullHeaderId = Some(anchor))))
    when(api.sendTransaction(anyString())).thenReturn(response)
    val result = Future(new EngineBroadcast(owner.ref, api).send(signed, Seq(allocation), "operation", () => true))
    owner.expectMsg(CompleteMempool.Refresh)
    owner.reply(CompleteMempool.Observation(1L,
      Some(CompleteMempool.Snapshot(anchor, Set.empty, Set.empty, System.nanoTime())), None))
    val hold = owner.expectMsgType[PinEngineInputs]
    hold.hold.signedInputIds shouldBe Set(protocol, input.id.toString)
    verify(api, never()).sendTransaction(anyString())
    owner.reply(pin)
    if (pin) {
      Await.result(result, 5.seconds) shouldBe EngineBroadcast.Result(txId, outcome)
      owner.expectMsg(EngineSendFinished(allocation.reservationId, txId, outcome == "accepted"))
      owner.expectMsg(RefreshBoxes)
    } else {
      intercept[IllegalArgumentException](Await.result(result, 5.seconds))
      owner.expectMsg(CancelEngineInputs(allocation.reservationId, txId))
      owner.expectMsg(ReleaseInputs(allocation.reservationId))
      verify(api, never()).sendTransaction(anyString())
    }
  }

  "The engine broadcast boundary" should "report acceptance only for the signed transaction ID" in {
    exercise(Success(txId), "accepted")
  }
  it should "report a different node transaction ID as uncertain" in {
    exercise(Success("ef" * 32), "uncertain")
  }
  it should "retain the known ID when the node response is lost" in {
    exercise(Failure(new RuntimeException("connection reset")), "uncertain")
  }
  it should "never send after ownership pinning is refused" in {
    exercise(Success(txId), "accepted", pin = false)
  }

  private def grouped(secondReply: Option[Boolean]): Unit = {
    val api = mock[NodeApi]
    val owner = TestProbe()
    val funding = Seq(EngineBroadcast.Funding("first", Set("de" * 32)),
      EngineBroadcast.Funding("second", Set("ef" * 32)))
    val signed = mock[SignedTransaction]
    when(signed.getId).thenReturn(txId)
    when(signed.toJson(false)).thenReturn(NodeCodecs.encodeTransaction(NodeTransaction(txId,
      (funding.flatMap(_.walletInputIds) :+ protocol).map(id => NodeInput(id, NodeSpendingProof.empty)),
      Seq.empty, Seq.empty)).toString)
    when(api.info()).thenReturn(Success(ChainFixtures.infoAt(100000).copy(bestFullHeaderId = Some(anchor))))
    val result = Future(new EngineBroadcast(owner.ref, api, 500.millis)
      .sendOwned(signed, funding, "group", () => true))
    owner.expectMsg(CompleteMempool.Refresh)
    owner.reply(CompleteMempool.Observation(1L,
      Some(CompleteMempool.Snapshot(anchor, Set.empty, Set.empty, System.nanoTime())), None))
    owner.expectMsgType[PinEngineInputs].hold.reservationId shouldBe "first"
    owner.reply(true)
    owner.expectMsgType[PinEngineInputs].hold.reservationId shouldBe "second"
    secondReply.foreach(owner.reply)
    intercept[Exception](Await.result(result, 5.seconds))
    funding.foreach { allocation =>
      owner.expectMsg(CancelEngineInputs(allocation.reservationId, txId))
      owner.expectMsg(ReleaseInputs(allocation.reservationId))
    }
    verify(api, never()).sendTransaction(anyString())
  }

  it should "cancel an acknowledged prefix when another allocation cannot be pinned" in {
    grouped(Some(false))
  }
  it should "cancel exact pins when an acknowledgement is lost before any node call" in {
    grouped(None)
  }
}
