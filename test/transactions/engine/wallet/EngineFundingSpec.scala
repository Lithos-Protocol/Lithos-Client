package transactions.engine.wallet

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
import transactions.engine.wallet.EngineWalletMessages._
import work.lithos.mutations.{InputUTXO, Token}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try

class EngineFundingSpec extends TestKit(ActorSystem("wallet-selector-spec"))
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

  "EngineFunding" should "provide the shared synchronous selection interface" in {
    val manager = TestProbe()
    val selector = EngineFunding(manager.ref, 2.seconds, ec)
    val selected = input(1000L)

    val result = Future(selector.reserve(1000L))
    val request = manager.expectMsgType[SelectInputs]
    request.erg shouldEqual 1000L
    request.tokens shouldBe empty
    request.trackUsed shouldBe true
    manager.reply(WalletInputs(Seq(selected), request.reservationId))

    Await.result(result, 2.seconds).inputs shouldEqual Seq(selected)
  }

  it should "stop candidate publication when the engine refuses its allocation" in {
    val manager = TestProbe()
    val funding = EngineFunding(manager.ref, 2.seconds, ec)
    val reserved = Future(funding.reserve(1000L))
    val request = manager.expectMsgType[SelectInputs]
    manager.reply(WalletInputs(Seq(input(1000L)), request.reservationId))
    val allocation = Await.result(reserved, 2.seconds)
    val held = Future(Try(allocation.holdForCandidate()))
    manager.expectMsg(HoldReservationForCandidate(request.reservationId))
    manager.reply(ReservationHeldForCandidate(request.reservationId, accepted = false))
    Await.result(held, 2.seconds).isFailure shouldBe true
    manager.expectNoMessage(100.millis)
  }

  it should "retain candidate ownership when its acknowledgement is lost" in {
    val manager = TestProbe()
    val funding = EngineFunding(manager.ref, 200.millis, ec)
    val selected = input(1000L)
    val reserved = Future(funding.reserve(1000L))
    val request = manager.expectMsgType[SelectInputs]
    manager.reply(WalletInputs(Seq(selected), request.reservationId))
    val allocation = Await.result(reserved, 2.seconds)
    val held = Future(Try(allocation.holdForCandidate()))
    manager.expectMsg(HoldReservationForCandidate(request.reservationId))
    manager.expectMsg(MarkReservationUncertain(request.reservationId))
    Await.result(held, 2.seconds).isFailure shouldBe true
    allocation.release()
    manager.expectNoMessage(100.millis)
  }
  it should "release a partial reservation before reporting insufficient tokens" in {
    val manager = TestProbe()
    val selector = EngineFunding(manager.ref, 2.seconds, ec)
    val tokenId = ErgoId.create("bb" * 32)
    val selected = input(1000L, Some(Token(tokenId, 1L)))

    val result = Future(selector.reserve(1000L, Seq(Token(tokenId, 2L))))
    val request = manager.expectMsgType[SelectInputs]
    request.tokens shouldEqual Seq(Token(tokenId, 2L))
    manager.reply(WalletInputs(Seq(selected), request.reservationId))
    manager.expectMsg(ReleaseInputs(request.reservationId))

    intercept[NotEnoughInputsException](Await.result(result, 2.seconds))
  }

  it should "expose covering selection, release, and returned change" in {
    val manager = TestProbe()
    val selector = EngineFunding(manager.ref, 2.seconds, ec)
    val selected = input(2000L)

    val covering = Future(selector.reserveCovering(1000L))
    val request = manager.expectMsgType[SelectInputs]
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
