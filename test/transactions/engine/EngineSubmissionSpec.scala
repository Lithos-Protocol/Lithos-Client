package transactions.engine

import akka.actor.{Actor, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import lfsm.LFSMPhase.HOLDING
import lfsm.states.Rollup
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
import state.messages.RollupMessages._
import state.synchronization.CompleteMempool
import support.{ChainFixtures, FakeNodeContext}
import transactions.engine.TransactionEngine._
import transactions.engine.EngineWalletMessages._
import work.lithos.mutations.{InputUTXO, UTXO}
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

class EngineSubmissionSpec extends TestKit(ActorSystem("engine-submission-spec"))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {
  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)
  private implicit val ec = system.dispatcher
  private val intent = HoldingTransform("ab" * 32, 100L, transactions.rollups.TransactionMessages.RollupTxStub.ROLLUP_FEE)
  private val protocolId = "cd" * 32
  private val txId = "ef" * 32
  private val anchor = "aa" * 32

  private def exercise(result: Try[String], expected: Outcome, pin: Boolean = true, retry: Boolean = false): Unit = {
    val api = mock[NodeApi]
    val (ctx, _, prover) = FakeNodeContext(api)
    val input = ctx.getClient.execute(c => UTXO(prover.contract, 10000000L)
      .toInput(c, ErgoId.create("bb" * 32), 0.toShort))
    val inputId = input.id.toString
    val rollup = support.SyncFixtures.emptyRollup(intent.blockId, protocolId, 100)
    when(api.info()).thenReturn(Success(ChainFixtures.infoAt(100000).copy(bestFullHeaderId = Some(anchor))))
    when(api.boxById(inputId)).thenReturn(Success(Some(NodeBox(inputId, "", 10000000L, 0, 1, ""))))
    when(api.sendTransaction(anyString())).thenReturn(result)
    val wallet = TestProbe()
    val responder = system.actorOf(Props(new Actor {
      def receive: Receive = {
        case CompleteMempool.Refresh => sender() ! CompleteMempool.Observation(1L,
          Some(CompleteMempool.Snapshot(anchor, Set.empty, Set.empty, System.nanoTime())), None)
        // Metadata, not the full rollup: a transform needs the box and its phase, never a dictionary.
        case _: GetRollupMetadata => sender() ! CurrentRollupMetadata(protocolId, rollup.metadata, None)
      }
    }))
    val signed = mock[SignedTransaction]
    when(signed.getId).thenReturn(txId)
    when(signed.toJson(false)).thenReturn(NodeCodecs.encodeTransaction(NodeTransaction(txId,
      Seq(NodeInput(protocolId, NodeSpendingProof.empty), NodeInput(inputId, NodeSpendingProof.empty)),
      Seq.empty, Seq.empty)).toString)
    val execution = new HoldingTransformExecution(ctx, wallet.ref, responder, responder) {
      override protected lazy val node: NodeApi = api
      override protected def build(i: HoldingTransform, p: String, selected: Option[Seq[InputUTXO]],
                                   retained: Option[Set[String]]): SignedTransaction = {
        if (retry) { selected shouldBe None; retained shouldBe Some(Set(inputId)) }
        else selected.get.map(_.id.toString) shouldBe Seq(inputId)
        signed
      }
    }
    val old = EngineHold(intent.key, "old-lease", "dd" * 32, Set(protocolId), Set(inputId), sendFinished = true)
    try {
      val future = Future(execution.execute(intent, () => true))
      wallet.expectMsg(GetEngineHolds)
      wallet.reply(EngineHolds(if (retry) Vector(old) else Vector.empty))
      if (!retry) {
        val selection = wallet.expectMsgType[SelectInputs]
        wallet.reply(WalletInputs(Seq(input), selection.reservationId))
      }
      val pinned = wallet.expectMsgType[PinEngineInputs]
      pinned.hold.walletInputIds shouldBe Set(inputId)
      pinned.previousTxId shouldBe (if (retry) Some(old.txId) else None)
      verify(api, never()).sendTransaction(anyString())
      wallet.reply(pin)
      Await.result(future, 5.seconds) shouldBe expected
      if (pin) wallet.expectMsg(EngineSendFinished(pinned.hold.reservationId, txId, result == Success(txId)))
      else {
        wallet.expectMsg(CancelEngineInputs(pinned.hold.reservationId, txId, if (retry) Some(old) else None))
        if (!retry) wallet.expectMsg(ReleaseInputs(pinned.hold.reservationId))
        verify(api, never()).sendTransaction(anyString())
      }
    } finally system.stop(responder)
  }

  "Engine submission" should "return the signed id on acceptance only after the input pin is acknowledged" in {
    exercise(Success(txId), Accepted(intent.key, txId))
  }
  it should "retain the signed id and ownership after a lost node response" in {
    val error = NodeError.Transport("/transactions", new RuntimeException("response lost"))
    exercise(Failure(error), Uncertain(intent.key, txId, error.getMessage))
  }
  it should "distinguish an explicit refusal without declaring its inputs free" in {
    val error = NodeError.Rejected("script refused")
    exercise(Failure(error), Rejected(intent.key, error.getMessage, Some(txId)))
  }
  it should "stop before the node when the exact input pin is refused" in {
    // Refused at the shared send boundary, which is what reports it now.
    exercise(Success(txId), Deferred(intent.key, "requirement failed: engine funding ownership changed before send"), pin = false)
  }
  it should "rebuild with retained inputs without selecting another wallet input" in {
    exercise(Success(txId), Accepted(intent.key, txId), retry = true)
  }
}