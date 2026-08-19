package transactions.wallet

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import configs.NodeContext
import mutations.NodeWallet
import node.MutationConversions._
import node.NodeApi
import node.model._
import org.ergoplatform.appkit.BlockchainContext
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.when
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import support.FakeNodeContext
import transactions.wallet.WalletMessages._
import work.lithos.mutations.InputUTXO

import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Success, Try}

/**
 * KNOWN reservations: the exact holds a transaction chain puts on outputs it has built but the node
 * may not have seen.
 *
 * They exist because a chained run spends its own change. Before them, the emission queue excluded
 * those boxes by raw id inside its own funding source and the rollup batch excluded its fee
 * allocations the same way — two private exclusion sets, invisible to the actor that owns the
 * wallet. Anything else selecting through [[WalletManager]] in that window (a LithosDex request, a
 * second batch) could hand the same box to a second transaction.
 *
 * A Known hold differs from an ordinary reservation in the one way that matters to the refresh:
 * absence from a COMPLETE read is expected rather than proof of spending, so the collector must not
 * treat it the way it treats a selection. That asymmetry is what these pin.
 *
 * Boxes are identified by VALUE, not by the id they are constructed with: `toInputUTXO` recomputes
 * the id from the box's contents, so a synthetic one does not survive the conversion.
 */
object KnownReservationSpec {
  val config: com.typesafe.config.Config =
    com.typesafe.config.ConfigFactory.parseString("akka.test.single-expect-default = 20s")
}

class KnownReservationSpec
  extends TestKit(ActorSystem("known-reservation-spec", KnownReservationSpec.config))
    with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  private implicit val ec: ExecutionContext = system.dispatcher

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val erg = 1000000000L

  private class TestableWalletManager(ctx: NodeContext, clock: () => Long) extends WalletManager(ctx) {
    override protected def now(): Long = clock()
  }

  private def walletBox(wallet: NodeWallet, value: Long): WalletBox =
    WalletBox(
      box = NodeBox(boxId = f"$value%064x", transactionId = "aa" * 32, value = value, index = 0,
        creationHeight = 100, ergoTree = wallet.contract.ergoTreeHex),
      address = wallet.p2pk.toString, confirmationsNum = Some(10), creationTransaction = "aa" * 32,
      creationOutIndex = 0, inclusionHeight = Some(100), spendingTransaction = None,
      spendingHeight = None, spent = false, onchain = true, scans = Seq(10))

  /** A box under a tree the prover does NOT hold, standing in for anything not ours. */
  private def foreignBox(value: Long): NodeBox =
    NodeBox(boxId = f"$value%064x", transactionId = "cc" * 32, value = value, index = 0,
      creationHeight = 100,
      // A trivially-true script this client has no secret for.
      ergoTree = "10010101d17300")

  private case class Fixture(mgr: ActorRef,
                             wallet: NodeWallet,
                             probe: TestProbe,
                             ctx: NodeContext,
                             reported: AtomicReference[Seq[WalletBox]])

  /**
   * A started WalletManager whose reported wallet a test can change between refreshes, so a Known
   * output can be made to appear the way a mempool-aware read would show it.
   */
  private def fixture(initial: NodeWallet => Seq[WalletBox] = _ => Seq.empty,
                      time: () => Long = () => System.currentTimeMillis()): Fixture = {
    val api = mock[NodeApi]
    val (ctx, _, wallet) = FakeNodeContext(api, numAddresses = 1)
    val reported = new AtomicReference[Seq[WalletBox]](initial(wallet))

    when(api.indexerEnabled).thenReturn(true)
    when(api.walletUnspentBoxes(any[ConfirmationRange], any[Paging])).thenAnswer { inv =>
      if (inv.getArgument[Paging](1).offset == 0) Success(reported.get())
      else Success(Seq.empty[WalletBox])
    }
    when(api.unspentBoxesByErgoTree(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenReturn(Success(Seq.empty[IndexedBox]))

    val mgr = system.actorOf(Props(new TestableWalletManager(ctx, time)))
    val probe = TestProbe()
    mgr ! RefreshBoxes
    Thread.sleep(1200)
    Fixture(mgr, wallet, probe, ctx, reported)
  }

  // `ctx => body(ctx)` rather than `body`: appkit's `execute` takes a java.util.function.Function,
  // and scalac SAM-converts a lambda literal where IntelliJ will not convert a Function1 value.
  private def withCtx[A](f: Fixture)(body: BlockchainContext => A): A =
    f.ctx.getClient.execute(ctx => body(ctx))

  /** A signable box that exists only as a built output, exactly as a chained change box does. */
  private def knownOutput(f: Fixture, value: Long): InputUTXO =
    withCtx(f)(ctx => walletBox(f.wallet, value).toInputUTXO(ctx))

  private def offered(f: Fixture, need: Long): Seq[InputUTXO] = {
    f.probe.send(f.mgr, RetrieveInputs(need, Seq.empty, trackUsed = false))
    f.probe.expectMsgType[WalletInputs].inputs
  }

  private def reserveKnown(f: Fixture, id: String, boxes: Seq[InputUTXO]): Seq[InputUTXO] = {
    f.probe.send(f.mgr, ReserveKnownInputs(boxes, id, Long.MaxValue))
    f.probe.expectMsgType[WalletInputs].inputs
  }

  private def refresh(f: Fixture): Unit = {
    f.mgr ! RefreshBoxes
    Thread.sleep(1200)
  }

  // ─── the property the mechanism exists for ────────────────────────────────

  "A Known output" should "not be offered to a second selector once the node reports it" in {
    // The whole point. The emission chain holds its own intermediate change; a LithosDex request
    // selecting through the same manager must not be handed it. Without the Known hold the
    // mempool-aware refresh publishes that box the moment the parent reaches the mempool, and the
    // two transactions spend the same input.
    val f = fixture()
    val child = knownOutput(f, 3 * erg)
    reserveKnown(f, "emission-chain", Seq(child)) should have size 1

    // What a mempool-aware read does once the parent is broadcast: the child is now unspent.
    f.reported.set(Seq(walletBox(f.wallet, 3 * erg)))
    refresh(f)

    offered(f, erg) shouldBe empty
  }

  it should "become selectable only after its holder releases it" in {
    val f = fixture()
    val child = knownOutput(f, 3 * erg)
    reserveKnown(f, "emission-chain", Seq(child)) should have size 1
    f.reported.set(Seq(walletBox(f.wallet, 3 * erg)))
    refresh(f)
    offered(f, erg) shouldBe empty

    f.probe.send(f.mgr, ReleaseInputs("emission-chain"))
    f.probe.awaitAssert(offered(f, erg).map(_.value) shouldEqual Seq(3 * erg), 5.seconds, 100.millis)
  }

  it should "survive a complete refresh that does not report it" in {
    // The asymmetry against an ordinary selection. A complete read that omits a RESERVED box proves
    // it was spent and collects it; a complete read that omits a KNOWN box proves nothing, because
    // its parent has not been sent yet. Collecting it here would hand the chain's own input to
    // something else in the window between building and broadcasting.
    val f = fixture()
    val child = knownOutput(f, 3 * erg)
    reserveKnown(f, "emission-chain", Seq(child)) should have size 1

    refresh(f)
    refresh(f)

    // Still held: a second holder cannot take it.
    reserveKnown(f, "someone-else", Seq(child)) shouldBe empty
  }

  // ─── what the manager will and will not hold ──────────────────────────────

  it should "refuse a box the prover cannot sign for" in {
    val f = fixture()
    val foreign = withCtx(f)(ctx => foreignBox(2 * erg).toInputUTXO(ctx))
    reserveKnown(f, "not-ours", Seq(foreign)) shouldBe empty
  }

  it should "refuse a duplicate box id inside one hold" in {
    val f = fixture()
    val child = knownOutput(f, 3 * erg)
    reserveKnown(f, "duplicated", Seq(child, child)) shouldBe empty
  }

  it should "refuse a box another lease already holds" in {
    val f = fixture()
    val child = knownOutput(f, 3 * erg)
    reserveKnown(f, "first", Seq(child)) should have size 1
    reserveKnown(f, "second", Seq(child)) shouldBe empty
  }

  it should "refuse a reservation id already in use" in {
    val f = fixture()
    reserveKnown(f, "reused", Seq(knownOutput(f, 3 * erg))) should have size 1
    reserveKnown(f, "reused", Seq(knownOutput(f, 4 * erg))) shouldBe empty
  }

  it should "refuse a hold that would exceed the known-input ceiling" in {
    // The ceiling is what stops a chain that never releases from growing the reservation map without
    // bound. Nothing else caps it: a Known hold is not collected by the refresh.
    val f = fixture()
    val many = (1 to WalletManager.MAX_KNOWN_INPUTS).map(i => knownOutput(f, erg + i))
    // Held in chunks, since one request is also capped at MAX_TX_INPUTS.
    many.grouped(WalletManager.MAX_TX_INPUTS).zipWithIndex.foreach { case (chunk, idx) =>
      reserveKnown(f, s"chunk-$idx", chunk) should have size chunk.size
    }
    reserveKnown(f, "one-too-many", Seq(knownOutput(f, erg - 1))) shouldBe empty
  }

  it should "age out on the same TTL as a selection" in {
    // Deliberately NOT exempt from the sweep. A Known hold is released by its owner or it is a leak,
    // and the refresh cannot collect it, so the TTL is the only thing that ever ends an abandoned
    // one. Fifteen minutes is late enough that a live chain is never caught by it.
    val clock = new AtomicLong(1000000L)
    val f = fixture(time = () => clock.get())
    val child = knownOutput(f, 3 * erg)
    reserveKnown(f, "abandoned", Seq(child)) should have size 1

    clock.addAndGet(WalletManager.ReservationTtlMs + 1L)
    f.probe.send(f.mgr, ResetUsedInputs)
    f.probe.awaitAssert(
      reserveKnown(f, "after-ttl", Seq(child)) should have size 1, 5.seconds, 100.millis)
  }

  // ─── crossing the node boundary ───────────────────────────────────────────

  it should "keep an ambiguous child held through cleanup and a complete refresh" in {
    // An ambiguous send is the one outcome a release must never follow: the node may have taken the
    // transaction. The refresh resolves it in whichever direction a COMPLETE read shows — and until
    // one arrives, neither cleanup nor the TTL may hand the box back.
    val clock = new AtomicLong(1000000L)
    val f = fixture(time = () => clock.get())
    val child = knownOutput(f, 3 * erg)
    reserveKnown(f, "ambiguous", Seq(child)) should have size 1

    f.probe.send(f.mgr, BeginReservationSubmission(
      "ambiguous", Set(child.id.toString), Long.MaxValue))
    f.probe.expectMsg(ReservationSubmissionStarted("ambiguous", accepted = true))
    f.probe.send(f.mgr, MarkReservationUncertain("ambiguous"))

    // Cleanup at the end of a batch: a terminal handle must ignore it.
    f.probe.send(f.mgr, ReleaseInputs("ambiguous"))
    clock.addAndGet(WalletManager.ReservationTtlMs + 1L)
    f.probe.send(f.mgr, ResetUsedInputs)

    // The node still reports nothing, so nothing is resolved and the box stays out of reach.
    reserveKnown(f, "opportunist", Seq(child)) shouldBe empty
  }

  it should "release an uncertain child once a complete read shows the send never landed" in {
    val f = fixture()
    val child = knownOutput(f, 3 * erg)
    reserveKnown(f, "ambiguous", Seq(child)) should have size 1
    f.probe.send(f.mgr, BeginReservationSubmission(
      "ambiguous", Set(child.id.toString), Long.MaxValue))
    f.probe.expectMsgType[ReservationSubmissionStarted]
    f.probe.send(f.mgr, MarkReservationUncertain("ambiguous"))

    // Present in a complete mempool-aware read means the spending transaction never took: safe again.
    f.reported.set(Seq(walletBox(f.wallet, 3 * erg)))
    refresh(f)

    f.probe.awaitAssert(offered(f, erg).map(_.value) shouldEqual Seq(3 * erg), 5.seconds, 100.millis)
  }

  // ─── the multi-lease group a chained spend needs ──────────────────────────

  "A grouped submission" should "cancel the acknowledged prefix when a later lease cannot begin" in {
    // A queue spend owns both ordinary wallet funding and its parent's chained change. Both permits
    // have to be in hand before the node is contacted, because a send that turns out to be missing
    // one is a transaction built on a box something else may already have taken. When the group
    // cannot be completed, nothing may be left Submitting — that state has no TTL.
    val f = fixture(w => Seq(walletBox(w, 5 * erg)))
    val selector = WalletSelector(f.mgr, 5.seconds, ec)

    val funding = selector.reserve(erg)
    funding.inputs should have size 1

    val child = knownOutput(f, 3 * erg)
    val chained = selector.reserveKnown(Seq(child))

    // The child's lease is retired behind the group's back, exactly as an expiry would retire it.
    f.probe.send(f.mgr, ReleaseInputs(chained.id))
    f.probe.awaitAssert({
      f.probe.send(f.mgr, ReserveKnownInputs(Seq(child), "probe-free", Long.MaxValue))
      f.probe.expectMsgType[WalletInputs].inputs should have size 1
    }, 5.seconds, 100.millis)
    f.probe.send(f.mgr, ReleaseInputs("probe-free"))

    Try(WalletReservation.beginAll(Seq(funding, chained))).isFailure shouldBe true

    // The funding box is back in the pool rather than stranded in Submitting.
    f.probe.awaitAssert(offered(f, erg).map(_.value) shouldEqual Seq(5 * erg), 5.seconds, 100.millis)
  }

  it should "leave nothing held when the whole group begins and commits" in {
    val f = fixture(w => Seq(walletBox(w, 5 * erg)))
    val selector = WalletSelector(f.mgr, 5.seconds, ec)

    val funding = selector.reserve(erg)
    val child = knownOutput(f, 3 * erg)
    val chained = selector.reserveKnown(Seq(child))

    WalletReservation.beginAll(Seq(funding, chained))
    val settled = knownOutput(f, 4 * erg)
    funding.commit(Seq(settled))
    chained.commit()

    // The parents are gone and only the committed output is selectable.
    f.probe.awaitAssert(offered(f, erg).map(_.value) shouldEqual Seq(4 * erg), 5.seconds, 100.millis)
  }

  // ─── two selectors, one owner ─────────────────────────────────────────────

  "Two independent selectors" should "never both receive a chained output" in {
    // The reason the exclusion sets had to move into the actor. These are the DEX controller's
    // selector and the rollup handler's selector: different objects, different timeouts, one owner.
    val f = fixture(w => Seq(walletBox(w, 5 * erg)))
    val emissionSide = WalletSelector(f.mgr, 5.seconds, ec)
    val dexSide = WalletSelector(f.mgr, 5.seconds, ec)

    val child = knownOutput(f, 3 * erg)
    emissionSide.reserveKnown(Seq(child)).inputs should have size 1
    f.reported.set(Seq(walletBox(f.wallet, 5 * erg), walletBox(f.wallet, 3 * erg)))
    refresh(f)

    // The DEX side asks for an amount only the chained box could cover on its own; it must be told
    // no rather than handed the emission chain's input.
    val taken = Await.result(Future(Try(dexSide.reserve(6 * erg))), 10.seconds)
    taken.isFailure shouldBe true
    dexSide.reserve(5 * erg).inputs.map(_.value) shouldEqual Seq(5 * erg)
  }
}
