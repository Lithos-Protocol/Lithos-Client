package transactions.wallet

import akka.actor.ActorSystem
import akka.testkit.{TestKit, TestProbe}
import mutations.NotEnoughInputsException
import node.MutationConversions._
import node.NodeApi
import node.model.{NodeAsset, NodeBox}
import org.ergoplatform.sdk.ErgoId
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import support.FakeNodeContext
import transactions.wallet.WalletMessages._
import work.lithos.mutations.{InputUTXO, Token}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try

class WalletSelectorSpec extends TestKit(ActorSystem("wallet-selector-spec"))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  private implicit val ec: ExecutionContext = system.dispatcher

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private def input(value: Long, token: Option[Token] = None): InputUTXO = {
    val (nodeContext, _, wallet) = FakeNodeContext(mock[NodeApi], numAddresses = 1)
    val assets = token.toSeq.map(t => NodeAsset(t.id.toString, t.amount))
    val box = NodeBox(f"$value%064x", "aa" * 32, value, 0, 100,
      wallet.contract.ergoTreeHex, assets)
    nodeContext.getClient.execute(ctx => box.toInputUTXO(ctx))
  }

  "WalletSelector" should "provide the shared synchronous selection interface" in {
    val manager = TestProbe()
    val selector = WalletSelector(manager.ref, 2.seconds, ec)
    val selected = input(1000L)

    val result = Future(selector.reserve(1000L))
    val request = manager.expectMsgType[RetrieveInputs]
    request.erg shouldEqual 1000L
    request.tokens shouldBe empty
    request.trackUsed shouldBe true
    manager.reply(WalletInputs(Seq(selected), request.reservationId))

    Await.result(result, 2.seconds).inputs shouldEqual Seq(selected)
  }

  it should "stop before submission when the manager denies the lease" in {
    val manager = TestProbe()
    val selector = WalletSelector(manager.ref, 2.seconds, ec)
    val selected = input(1000L)

    val reserved = Future(selector.reserve(1000L))
    val request = manager.expectMsgType[RetrieveInputs]
    manager.reply(WalletInputs(Seq(selected), request.reservationId))
    val reservation = Await.result(reserved, 2.seconds)

    val begun = Future(Try(reservation.beginSubmission()))
    val begin = manager.expectMsgType[BeginReservationSubmission]
    begin.reservationId shouldEqual request.reservationId
    begin.expectedInputIds shouldEqual Set(selected.id.toString)
    manager.reply(ReservationSubmissionStarted(begin.reservationId, accepted = false))

    val cancel = manager.expectMsgType[CancelReservationSubmission]
    cancel.reservationId shouldEqual request.reservationId
    cancel.expectedInputIds shouldEqual Set(selected.id.toString)
    manager.reply(ReservationSubmissionCancelled(cancel.reservationId, accepted = false))
    Await.result(begun, 2.seconds).isFailure shouldBe true
    manager.expectNoMessage(200.millis)
  }

  it should "cancel the exact lease when the submission acknowledgement times out" in {
    val manager = TestProbe()
    val selector = WalletSelector(manager.ref, 200.millis, ec)
    val selected = input(1000L)

    val reserved = Future(selector.reserve(1000L))
    val request = manager.expectMsgType[RetrieveInputs]
    manager.reply(WalletInputs(Seq(selected), request.reservationId))
    val reservation = Await.result(reserved, 2.seconds)

    val begun = Future(Try(reservation.beginSubmission()))
    val begin = manager.expectMsgType[BeginReservationSubmission]
    begin.expectedInputIds shouldEqual Set(selected.id.toString)
    // No acknowledgement: the caller must never proceed to the node on an ambiguous local ask.
    val cancel = manager.expectMsgType[CancelReservationSubmission](2.seconds)
    cancel.reservationId shouldEqual request.reservationId
    cancel.expectedInputIds shouldEqual Set(selected.id.toString)
    manager.reply(ReservationSubmissionCancelled(cancel.reservationId, accepted = true))

    Await.result(begun, 2.seconds).isFailure shouldBe true
    manager.expectNoMessage(200.millis)
  }

  it should "release a partial reservation before reporting insufficient tokens" in {
    val manager = TestProbe()
    val selector = WalletSelector(manager.ref, 2.seconds, ec)
    val tokenId = ErgoId.create("bb" * 32)
    val selected = input(1000L, Some(Token(tokenId, 1L)))

    val result = Future(selector.reserve(1000L, Seq(Token(tokenId, 2L))))
    val request = manager.expectMsgType[RetrieveInputs]
    request.tokens shouldEqual Seq(Token(tokenId, 2L))
    manager.reply(WalletInputs(Seq(selected), request.reservationId))
    manager.expectMsg(ReleaseInputs(request.reservationId))

    intercept[NotEnoughInputsException](Await.result(result, 2.seconds))
  }

  it should "expose covering selection, release, and returned change" in {
    val manager = TestProbe()
    val selector = WalletSelector(manager.ref, 2.seconds, ec)
    val selected = input(2000L)

    val covering = Future(selector.reserveCovering(1000L))
    val request = manager.expectMsgType[RetrieveCoveringInput]
    request.erg shouldEqual 1000L
    manager.reply(WalletInputs(Seq(selected), request.reservationId))
    val reservation = Await.result(covering, 2.seconds)
    reservation.inputs shouldEqual Seq(selected)

    reservation.release()
    manager.expectMsg(ReleaseInputs(request.reservationId))
    selector.giveBack(Seq(selected))
    manager.expectMsg(ReturnInputs(Seq(selected)))
  }
}
