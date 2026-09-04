package transactions.wallet

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import mutations.NodeWallet
import node.MutationConversions._
import node.NodeApi
import node.model._
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.when
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import support.FakeNodeContext
import transactions.wallet.WalletMessages._
import work.lithos.mutations.InputUTXO

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Success, Try}

/**
 * The candidate hold, which is the one lease transition with no way back on its own.
 *
 * A transaction offered to a block candidate may still land, so Candidate has no TTL and `release`
 * cannot leave it. That makes a lost acknowledgement different from every other ask here: the local
 * handle has already moved, nothing will move it again, and the wallet box is withheld for the life
 * of the process with no candidate ever having received the transaction.
 */
object CandidateHoldSpec {
  val config: com.typesafe.config.Config =
    com.typesafe.config.ConfigFactory.parseString("akka.test.single-expect-default = 20s")
}

class CandidateHoldSpec extends TestKit(ActorSystem("candidate-hold-spec", CandidateHoldSpec.config))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private implicit val ec: ExecutionContext = system.dispatcher
  private val erg = 1000000000L

  private def anInput: InputUTXO = {
    val (ctx, _, wallet) = FakeNodeContext()
    ctx.getClient.execute { c =>
      NodeBox("dd" * 32, "aa" * 32, 5 * erg, 0, 100, wallet.contract.ergoTreeHex).toInputUTXO(c)
    }
  }

  private def walletBox(wallet: NodeWallet, value: Long): WalletBox =
    WalletBox(
      box = NodeBox(boxId = f"$value%064x", transactionId = "aa" * 32, value = value, index = 0,
        creationHeight = 100, ergoTree = wallet.contract.ergoTreeHex),
      address = wallet.p2pk.toString, confirmationsNum = Some(10), creationTransaction = "aa" * 32,
      creationOutIndex = 0, inclusionHeight = Some(100), spendingTransaction = None,
      spendingHeight = None, spent = false, onchain = true, scans = Seq(10))

  /** A started manager whose node keeps reporting one box, however this client leases it. */
  private def manager(): (ActorRef, TestProbe) = {
    val api = mock[NodeApi]
    val (ctx, _, wallet) = FakeNodeContext(api, numAddresses = 1)
    when(api.indexerEnabled).thenReturn(true)
    when(api.walletUnspentBoxes(any[ConfirmationRange], any[Paging])).thenAnswer { inv =>
      if (inv.getArgument[Paging](1).offset == 0) Success(Seq(walletBox(wallet, 5 * erg)))
      else Success(Seq.empty[WalletBox])
    }
    when(api.unspentBoxesByErgoTree(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenReturn(Success(Seq.empty[IndexedBox]))

    val mgr = system.actorOf(Props(new WalletManager(ctx)))
    mgr ! RefreshBoxes
    Thread.sleep(1200)
    (mgr, TestProbe())
  }

  private def covering(mgr: ActorRef, probe: TestProbe, id: String): Seq[InputUTXO] = {
    probe.send(mgr, RetrieveCoveringInput(erg, trackUsed = true, reservationId = id))
    probe.expectMsgType[WalletInputs].inputs
  }

  // ─── the lost acknowledgement ─────────────────────────────────────────────

  "A hold whose acknowledgement never arrives" should "be made uncertain, not released" in {
    // Release is a no-op once the handle is Candidate, so without this the lease has no ending at
    // all. Uncertain is the only one that can be reconciled by an authoritative read.
    val probe = TestProbe()
    val selector = WalletSelector(probe.ref, 3.seconds, ec)

    val pending = Future(selector.reserveCovering(erg))
    val ask = probe.expectMsgType[RetrieveCoveringInput](5.seconds)
    probe.reply(WalletInputs(Seq(anInput), ask.reservationId))
    val reservation = Await.result(pending, 5.seconds)

    val holding = Future(Try(reservation.holdForCandidate()))
    probe.expectMsgType[HoldReservationForCandidate](5.seconds) // received, deliberately unanswered
    Await.result(holding, 20.seconds).isFailure shouldBe true

    withClue("ReleaseInputs here would be a no-op and the box would never come back: ") {
      probe.expectMsg(5.seconds, MarkReservationUncertain(reservation.id))
    }
  }

  // ─── both orderings of the race ───────────────────────────────────────────
  //
  // A timeout does not say whether the manager applied the hold, so the uncertain message has to be
  // accepted from either state and end the same way: the lease dropped by a complete refresh, and
  // the box selectable again because the node still reports it.

  "A box left uncertain after the manager applied the hold" should "come back on a complete refresh" in {
    val (mgr, probe) = manager()
    covering(mgr, probe, "held-then-lost").map(_.value) shouldEqual Seq(5 * erg)

    probe.send(mgr, HoldReservationForCandidate("held-then-lost"))
    probe.expectMsg(5.seconds, ReservationHeldForCandidate("held-then-lost", accepted = true))

    probe.send(mgr, MarkReservationUncertain("held-then-lost"))
    awaitAssert(covering(mgr, probe, "after-1").map(_.value) shouldEqual Seq(5 * erg),
      10.seconds, 300.millis)
  }

  "A box left uncertain before the manager saw the hold" should "come back the same way" in {
    val (mgr, probe) = manager()
    covering(mgr, probe, "lost-before-hold").map(_.value) shouldEqual Seq(5 * erg)

    probe.send(mgr, MarkReservationUncertain("lost-before-hold"))
    awaitAssert(covering(mgr, probe, "after-2").map(_.value) shouldEqual Seq(5 * erg),
      10.seconds, 300.millis)

    withClue("a hold arriving after the lease went uncertain must be refused: ") {
      probe.send(mgr, HoldReservationForCandidate("lost-before-hold"))
      probe.expectMsg(5.seconds, ReservationHeldForCandidate("lost-before-hold", accepted = false))
    }
  }

  // ─── the refusal is knowledge, not a gap ──────────────────────────────────

  "A hold the manager explicitly refuses" should "end the lease without an uncertain message" in {
    // An answered `false` says the manager still owns the lease and did not move it, so there is
    // nothing for a refresh to reconcile.
    val probe = TestProbe()
    val selector = WalletSelector(probe.ref, 2.seconds, ec)

    val pending = Future(selector.reserveCovering(erg))
    val ask = probe.expectMsgType[RetrieveCoveringInput](5.seconds)
    probe.reply(WalletInputs(Seq(anInput), ask.reservationId))
    val reservation = Await.result(pending, 5.seconds)

    val holding = Future(Try(reservation.holdForCandidate()))
    val hold = probe.expectMsgType[HoldReservationForCandidate](5.seconds)
    probe.reply(ReservationHeldForCandidate(hold.reservationId, accepted = false))
    Await.result(holding, 20.seconds).isFailure shouldBe true

    probe.expectNoMessage(2.seconds)
  }
}
