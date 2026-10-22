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
import transactions.engine.wallet.EngineWalletMessages._
import transactions.engine.wallet.EngineFunding
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

  private def exercise(response: Try[String], outcome: String, pin: Boolean = true,
                       quoted: Boolean = false): Unit = {
    val api = mock[NodeApi]
    val (ctx, _, wallet) = FakeNodeContext(api)
    val input = ctx.getClient.execute(c => UTXO(wallet.contract, 10000000L)
      .toInput(c, ErgoId.create("dd" * 32), 0.toShort))
    val owner = TestProbe()
    val allocationFuture = Future(EngineFunding(owner.ref, 5.seconds, ec).reserveCovering(1000000L))
    val request = owner.expectMsgType[SelectInputs]
    owner.reply(WalletInputs(Seq(input), request.reservationId))
    val allocation = Await.result(allocationFuture, 5.seconds)
    val signed = mock[SignedTransaction]
    when(signed.getId).thenReturn(if (quoted) "\"" + txId + "\"" else txId)
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
      // Identity and outcome are the contract; `reason` is diagnostic text and is not asserted.
      val broadcast = Await.result(result, 5.seconds)
      broadcast.txId shouldBe txId
      broadcast.outcome shouldBe outcome
      owner.expectMsg(EngineSendFinished(allocation.reservationId, txId, outcome == "accepted"))
      owner.expectMsg(RefreshBoxes)
      if (outcome == "rejected") owner.expectMsg(EngineSendRefused(allocation.reservationId, txId))
      else owner.expectNoMessage(200.millis)
    } else {
      intercept[IllegalArgumentException](Await.result(result, 5.seconds))
      owner.expectMsg(CancelEngineInputs(allocation.reservationId, txId))
      owner.expectMsg(ReleaseInputs(allocation.reservationId))
      verify(api, never()).sendTransaction(anyString())
    }
  }

  "A refused send" should "carry the node's reason, so the caller can say why in one line" in {
    val refused = intercept[EngineBroadcast.SubmissionOutcomeException] {
      EngineBroadcast.Result("t1", EngineBroadcast.Rejected,
        Some("Malformed transaction: Every input of the transaction should be in UTXO")).requireAccepted()
    }
    refused.reason shouldBe Some("Malformed transaction: Every input of the transaction should be in UTXO")
    refused.getMessage should include("Every input of the transaction should be in UTXO")
  }

  "A node refusal" should "be told apart by what the node said, so each recovery branch can run" in {
    // Every send now fails as a SubmissionOutcomeException, so the branches that read the node's
    // words off an ErgoClientException (mempool reset, chain rebuild, a lost emission box) never ran.
    def refused(reason: String, outcome: String = EngineBroadcast.Rejected) =
      new EngineBroadcast.SubmissionOutcomeException("t1", outcome, Some(reason))
    EngineBroadcast.refusal(refused("Malformed transaction: Every input of the transaction should be in UTXO: 3a")) shouldBe
      Some(EngineBroadcast.InputMissing)
    EngineBroadcast.refusal(refused("Double spending attempt: 3a")) shouldBe Some(EngineBroadcast.DoubleSpend)
    EngineBroadcast.refusal(refused("Malformed transaction: script reduced to false")) shouldBe None
    // An uncertain outcome carries no node verdict, whatever its text.
    EngineBroadcast.refusal(refused("Double spending", EngineBroadcast.Uncertain)) shouldBe None
    EngineBroadcast.refusal(new org.ergoplatform.appkit.ErgoClientException("Double spending", null)) shouldBe
      Some(EngineBroadcast.DoubleSpend)
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
  it should "strip the quotes appkit can leave around a transaction id" in {
    // A quoted id never equals the id in the signed JSON, so every such send failed its identity
    // check, and the hold, acceptance check and reconciler would all have keyed on the wrong id.
    exercise(Success(txId), "accepted", quoted = true)
    val signed = mock[SignedTransaction]
    when(signed.getId).thenReturn("\"" + txId + "\"")
    transactions.candidate.BlockTxMessages.CandidateTx.txId(signed) shouldBe txId
  }
  it should "hand back the inputs of a send the node refused" in {
    exercise(Failure(NodeError.Rejected("Malformed transaction: script reduced to false")), "rejected")
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

  private val owned = "de" * 32
  private val phantom = "df" * 32
  private val stale = EngineHold("join", "stale", "01" * 32, Set(owned, phantom, protocol), Set(owned, phantom),
    sendFinished = true, accepted = true, outputIds = Set("0a" * 32))

  private def spending(inputs: Seq[String]): SignedTransaction = {
    val signed = mock[SignedTransaction]
    when(signed.getId).thenReturn(txId)
    when(signed.toJson(false)).thenReturn(NodeCodecs.encodeTransaction(NodeTransaction(txId,
      inputs.map(id => NodeInput(id, NodeSpendingProof.empty)), Seq.empty,
      Seq(NodeBox("0b" * 32, txId, 1000000L, 0, 1, "")))).toString)
    signed
  }

  "A cancellation" should "refuse to spend anything the stale send does not own, before pinning" in {
    val api = mock[NodeApi]
    val owner = TestProbe()
    intercept[IllegalArgumentException](new EngineBroadcast(owner.ref, api)
      .cancel(spending(Seq(owned, protocol)), Seq(stale), () => true))
    owner.expectMsg(CancelEngineInputs(stale.reservationId, txId, Some(stale)))
    owner.expectMsg(ReleaseInputs(stale.reservationId))
    owner.expectNoMessage(200.millis)
    verify(api, never()).sendTransaction(anyString())
  }

  it should "take over every box the stale send owned, and hand them back if the node refuses it" in {
    val api = mock[NodeApi]
    val owner = TestProbe()
    when(api.info()).thenReturn(Success(ChainFixtures.infoAt(100000).copy(bestFullHeaderId = Some(anchor))))
    when(api.sendTransaction(anyString())).thenReturn(Failure(NodeError.Rejected("Double spending attempt")))
    val result = Future(new EngineBroadcast(owner.ref, api).cancel(spending(Seq(owned)), Seq(stale), () => true))
    owner.expectMsg(CompleteMempool.Refresh)
    owner.reply(CompleteMempool.Observation(1L,
      Some(CompleteMempool.Snapshot(anchor, Set.empty, Set.empty, System.nanoTime())), None))
    val pin = owner.expectMsgType[PinEngineInputs]
    pin.cancelling shouldBe true
    pin.previousTxId shouldBe Some(stale.txId)
    pin.hold shouldBe EngineHold("cancel:" + stale.txId, stale.reservationId, txId, Set(owned), Set(owned, phantom),
      outputIds = Set("0b" * 32), supersedes = Map(stale.txId -> stale.outputIds))
    owner.reply(true)
    Await.result(result, 5.seconds).outcome shouldBe EngineBroadcast.Rejected
    owner.expectMsg(EngineSendFinished(stale.reservationId, txId, accepted = false))
    owner.expectMsg(RefreshBoxes)
    owner.expectMsg(EngineSendRefused(stale.reservationId, txId, Some(stale)))
  }

  it should "pin every reservation of the stale send, and restore each to its own hold if one pin fails" in {
    val api = mock[NodeApi]
    val owner = TestProbe()
    when(api.info()).thenReturn(Success(ChainFixtures.infoAt(100000).copy(bestFullHeaderId = Some(anchor))))
    val fresh = stale.copy(reservationId = "fresh", walletInputIds = Set(owned))
    val chained = stale.copy(reservationId = "chained", walletInputIds = Set(phantom))
    val result = Future(new EngineBroadcast(owner.ref, api, 500.millis)
      .cancel(spending(Seq(owned)), Seq(fresh, chained), () => true))
    owner.expectMsg(CompleteMempool.Refresh)
    owner.reply(CompleteMempool.Observation(1L,
      Some(CompleteMempool.Snapshot(anchor, Set.empty, Set.empty, System.nanoTime())), None))
    Seq(fresh -> true, chained -> false).foreach { case (previous, reply) =>
      val pin = owner.expectMsgType[PinEngineInputs]
      (pin.hold.reservationId, pin.hold.walletInputIds, pin.previousTxId, pin.cancelling) shouldBe
        ((previous.reservationId, previous.walletInputIds, Some(stale.txId), true))
      owner.reply(reply)
    }
    intercept[IllegalArgumentException](Await.result(result, 5.seconds))
    Seq(fresh, chained).foreach { previous =>
      owner.expectMsg(CancelEngineInputs(previous.reservationId, txId, Some(previous)))
      owner.expectMsg(ReleaseInputs(previous.reservationId))
    }
    verify(api, never()).sendTransaction(anyString())
  }
}
