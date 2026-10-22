package transactions.engine

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import configs.NodeContext
import node.MutationConversions._
import node.NodeApi
import node.model._
import node.rest.NodeCodecs
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.{never, verify, when}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import state.synchronization.CompleteMempool
import support.{CanonicalNodeBox, ChainFixtures, FakeNodeContext}
import transactions.engine.wallet.EngineWalletState
import transactions.engine.wallet.EngineWalletMessages._

import java.util.concurrent.atomic.{AtomicInteger, AtomicLong, AtomicReference}
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
  private val hour = 3600000L
  private val settings = configs.ReconcileConfig.Default

  private def box(id: String, tree: String = ""): NodeBox = NodeBox(id, "", 1000000L, 0, 1, tree)

  private def observed(ids: Set[String] = Set.empty, spent: Set[String] = Set.empty): CompleteMempool.Observation =
    CompleteMempool.Observation(1L, Some(CompleteMempool.Snapshot(anchor, ids, spent, System.nanoTime())), None)

  /** A reconciler reading `api`, at the height `height` holds and the time `clock` holds. */
  private def reconciler(api: NodeApi, wallet: TestProbe, mempool: TestProbe, height: AtomicInteger,
                         clock: AtomicLong = new AtomicLong(0L),
                         reconcile: configs.ReconcileConfig = settings): EngineReconciler = {
    val (ctx, _, _) = FakeNodeContext(api)
    when(api.info()).thenAnswer(_ =>
      Success(ChainFixtures.infoAt(height.get).copy(bestFullHeaderId = Some(anchor))))
    new EngineReconciler(ctx, wallet.ref, mempool.ref, settings = reconcile) {
      override protected lazy val node: NodeApi = api
      override protected def now(): Long = clock.get()
    }
  }

  /** `api` reports every one of `ids` spent by `tx`, mined at `height` on the current chain. */
  private def minedSpends(api: NodeApi, tx: String, height: Int, ids: Seq[String]): Unit = {
    ids.foreach { id =>
      when(api.indexedBoxById(id)).thenReturn(Success(Some(IndexedBox(box(id), "", height, 1L, spentTransactionId = Some(tx)))))
      when(api.boxById(id)).thenReturn(Success(None))
    }
    when(api.indexedTransactionById(tx)).thenReturn(Success(Some(IndexedTransaction(tx,
      ids.map(id => IndexedBox(box(id), "", height, 1L)), Seq.empty, Seq.empty, height, 1, ChainFixtures.headerId(height),
      0L, 0, 1L, 100))))
    when(api.chainSlice(Some(height - 1), Some(height + 1))).thenReturn(Success(Seq(ChainFixtures.header(height))))
  }

  private def minedSpend(api: NodeApi, id: String, tx: String, height: Int): Unit = minedSpends(api, tx, height, Seq(id))

  /**
   * One pass. A closing observation is taken only when something settles, and every expected
   * resolution is acknowledged; anything further the pass sends fails the test.
   */
  private def pass(reconciler: EngineReconciler, wallet: TestProbe, mempool: TestProbe, holds: Vector[EngineHold],
                   opening: CompleteMempool.Observation, closing: Option[CompleteMempool.Observation] = None,
                   resolves: Seq[ResolveEngineInputs] = Seq.empty): Unit = {
    val result = Future(reconciler.reconcile())
    wallet.expectMsg(GetEngineHolds)
    wallet.reply(EngineHolds(holds))
    mempool.expectMsg(CompleteMempool.Refresh)
    mempool.reply(opening)
    closing.foreach { observation =>
      mempool.expectMsg(CompleteMempool.Refresh)
      mempool.reply(observation)
    }
    resolves.foreach { resolve =>
      wallet.expectMsg(resolve)
      wallet.reply(true)
    }
    Await.result(result, 5.seconds)
    wallet.expectNoMessage(150.millis)
    mempool.expectNoMessage(50.millis)
  }

  private def exercise(consumed: Boolean, raced: Boolean, otherProtocolConsumed: Boolean = false): Unit = {
    val api = mock[NodeApi]
    when(api.indexedBoxById(anyString())).thenReturn(Success(None))
    val otherProtocol = "ee" * 32
    if (otherProtocolConsumed) minedSpend(api, otherProtocol, spender, 10)
    if (consumed) minedSpend(api, first, spender, 10)
    else when(api.boxById(first)).thenReturn(Success(Some(box(first))))
    when(api.boxById(second)).thenReturn(Success(Some(box(second))))
    val wallet = TestProbe()
    val mempool = TestProbe()
    val settles = consumed || otherProtocolConsumed
    val resolve =
      if (consumed) Seq(ResolveEngineInputs(hold.reservationId, hold.txId, Set(first), Set(second)))
      else if (otherProtocolConsumed) Seq(ResolveEngineInputs(hold.reservationId, hold.txId, Set.empty, Set(first, second)))
      else Seq.empty
    pass(reconciler(api, wallet, mempool, new AtomicInteger(100)), wallet, mempool,
      Vector(hold.copy(signedInputIds = Set(protocol, otherProtocol, first, second))), observed(),
      if (settles) Some(observed(spent = if (raced) Set(second) else Set.empty)) else None,
      if (raced) Seq.empty else resolve)
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

  it should "walk the mempool again when a walk races, rather than give up the pass" in {
    // On a busy node most walks race. Each one used to defer the pass or a hold, so a send could
    // stay owned for as long as the mempool kept moving.
    val api = mock[NodeApi]
    when(api.indexedBoxById(anyString())).thenReturn(Success(None))
    minedSpend(api, first, spender, 10)
    when(api.boxById(second)).thenReturn(Success(Some(box(second))))
    val wallet = TestProbe()
    val mempool = TestProbe()
    val raced = CompleteMempool.Observation(1L, None, Some("mempool membership or chain anchor changed"))
    val result = Future(reconciler(api, wallet, mempool, new AtomicInteger(100)).reconcile())
    wallet.expectMsg(GetEngineHolds)
    wallet.reply(EngineHolds(Vector(hold.copy(signedInputIds = Set(protocol, first, second)))))
    Seq(raced, observed(), raced, observed()).foreach { reply =>
      mempool.expectMsg(CompleteMempool.Refresh)
      mempool.reply(reply)
    }
    wallet.expectMsg(ResolveEngineInputs(hold.reservationId, hold.txId, Set(first), Set(second)))
    wallet.reply(true)
    Await.result(result, 5.seconds)
  }

  it should "settle every transaction built on one that can never be mined, in one pass" in {
    // Joins chain on the emission box. A competing spend of it leaves only the first join with a
    // spent input; the rest spend outputs that will never exist, and no spend could ever end them.
    val api = mock[NodeApi]
    when(api.indexedBoxById(anyString())).thenReturn(Success(None))
    when(api.boxById(anyString())).thenReturn(Success(None))
    val Seq(emission, nextEmission, change, lastEmission) = Seq("10", "11", "12", "13").map(_ * 32)
    val Seq(parentFunding, childFunding, grandchildFunding) = Seq("20", "21", "22").map(_ * 32)
    minedSpend(api, emission, spender, 99)
    Seq(parentFunding, childFunding, grandchildFunding).foreach(id =>
      when(api.boxById(id)).thenReturn(Success(Some(box(id)))))
    val parent = EngineHold("collateral:a", "parent", "01" * 32, Set(emission, parentFunding), Set(parentFunding),
      sendFinished = true, outputIds = Set(nextEmission, change))
    val child = EngineHold("collateral:b", "child", "02" * 32, Set(nextEmission, change, childFunding),
      Set(change, childFunding), sendFinished = true, outputIds = Set(lastEmission))
    val grandchild = EngineHold("collateral:c", "grandchild", "03" * 32, Set(lastEmission, grandchildFunding),
      Set(grandchildFunding), sendFinished = true)
    val wallet = TestProbe()
    val mempool = TestProbe()
    pass(reconciler(api, wallet, mempool, new AtomicInteger(100)), wallet, mempool,
      Vector(grandchild, child, parent), observed(), Some(observed()), Seq(
        ResolveEngineInputs("parent", parent.txId, Set.empty, Set(parentFunding)),
        ResolveEngineInputs("child", child.txId, Set.empty, Set(childFunding), Set(change)),
        ResolveEngineInputs("grandchild", grandchild.txId, Set.empty, Set(grandchildFunding))))
  }

  it should "settle a cancellation that lost the race without killing what the original created" in {
    // The original was mined after all. Its outputs exist, so a send built on them is still live.
    val api = mock[NodeApi]
    when(api.indexedBoxById(anyString())).thenReturn(Success(None))
    when(api.boxById(anyString())).thenReturn(Success(None))
    val Seq(cancelled, original, created, childFunding) = Seq("70", "71", "72", "73").map(_ * 32)
    minedSpends(api, original, 50, Seq(first, second))
    when(api.boxById(childFunding)).thenReturn(Success(Some(box(childFunding))))
    val cancellation = EngineHold("cancel:" + original, "lease", cancelled, Set(first), Set(first, second),
      sendFinished = true, outputIds = Set("74" * 32), supersedes = Map(original -> Set(created)))
    val child = EngineHold("dex", "child", "75" * 32, Set(created, childFunding), Set(childFunding), sendFinished = true)
    val wallet = TestProbe()
    val mempool = TestProbe()
    val subject = reconciler(api, wallet, mempool, new AtomicInteger(100))
    pass(subject, wallet, mempool, Vector(cancellation, child), observed(), Some(observed()),
      Seq(ResolveEngineInputs("lease", cancelled, Set(first, second), Set.empty)))
    pass(subject, wallet, mempool, Vector(child), observed())
  }

  it should "record nothing from a pass during which a block arrived" in {
    val api = mock[NodeApi]
    when(api.indexedBoxById(anyString())).thenReturn(Success(None))
    when(api.boxById(anyString())).thenReturn(Success(None))
    val Seq(emission, output, parentFunding, childFunding) = Seq("80", "81", "82", "83").map(_ * 32)
    minedSpend(api, emission, spender, 99)
    Seq(parentFunding, childFunding).foreach(id => when(api.boxById(id)).thenReturn(Success(Some(box(id)))))
    val parent = EngineHold("join", "parent", "84" * 32, Set(emission, parentFunding), Set(parentFunding),
      sendFinished = true, outputIds = Set(output))
    val child = EngineHold("join", "child", "85" * 32, Set(output, childFunding), Set(childFunding), sendFinished = true)
    val wallet = TestProbe()
    val mempool = TestProbe()
    val subject = reconciler(api, wallet, mempool, new AtomicInteger(100))
    val moved = CompleteMempool.Observation(1L,
      Some(CompleteMempool.Snapshot("ab" * 32, Set.empty, Set.empty, System.nanoTime())), None)
    pass(subject, wallet, mempool, Vector(parent, child), observed(), Some(moved))
    // Without the parent in view, only a recorded death could settle the child, and none was recorded.
    pass(subject, wallet, mempool, Vector(child), observed())
  }

  it should "settle a send out of the mempool past the hold ceiling from its inputs' chain state" in {
    // Nothing here can be cancelled: one input never reached the chain, and the other is under a
    // script this wallet cannot sign. Before the ceiling it is held; at it, the chain decides.
    val api = mock[NodeApi]
    val phantom = "30" * 32
    val unsignable = "31" * 32
    when(api.indexedBoxById(anyString())).thenReturn(Success(None))
    when(api.boxById(phantom)).thenReturn(Success(None))
    when(api.boxById(unsignable)).thenReturn(Success(Some(box(unsignable))))
    val stuck = EngineHold("dex", "stuck", "04" * 32, Set(protocol, phantom, unsignable), Set(phantom, unsignable),
      sendFinished = true)
    val wallet = TestProbe()
    val mempool = TestProbe()
    val height = new AtomicInteger(100)
    val clock = new AtomicLong(0L)
    val subject = reconciler(api, wallet, mempool, height, clock)
    pass(subject, wallet, mempool, Vector(stuck), observed())
    height.set(100 + settings.cancelAfterBlocks)
    clock.set(hour)
    pass(subject, wallet, mempool, Vector(stuck), observed())
    clock.set(settings.holdCeilingMs)
    pass(subject, wallet, mempool, Vector(stuck), observed(), Some(observed()),
      Seq(ResolveEngineInputs("stuck", stuck.txId, Set.empty, Set(unsignable), Set(phantom))))
  }

  it should "never cancel a NISP submission or fraud proof, however long it is out of the mempool" in {
    val api = mock[NodeApi]
    val (_, _, keys) = FakeNodeContext(api)
    // Signable and canonical, so anything but the operation would carry it to a cancellation.
    val funding = CanonicalNodeBox("60" * 32, "63" * 32, 1000000000L, 0, 90, keys.contract.ergoTreeHex)
    when(api.indexedBoxById(anyString())).thenReturn(Success(None))
    when(api.boxById(funding.boxId)).thenReturn(Success(Some(funding)))
    val submission = EngineHold("rollup:" + "61" * 32, "nisp", "62" * 32, Set("61" * 32, funding.boxId),
      Set(funding.boxId), sendFinished = true)
    val wallet = TestProbe()
    val mempool = TestProbe()
    val height = new AtomicInteger(100)
    val subject = reconciler(api, wallet, mempool, height)
    pass(subject, wallet, mempool, Vector(submission), observed())
    height.set(100 + settings.cancelAfterBlocks)
    pass(subject, wallet, mempool, Vector(submission), observed())
    verify(api, never()).sendTransaction(anyString())
  }

  it should "leave a stale send alone with cancellation off, and settle it at the ceiling" in {
    val api = mock[NodeApi]
    val (_, _, keys) = FakeNodeContext(api)
    // Signable and canonical, so with cancellation on this send would be cancelled.
    val funding = CanonicalNodeBox("64" * 32, "65" * 32, 1000000000L, 0, 90, keys.contract.ergoTreeHex)
    when(api.indexedBoxById(anyString())).thenReturn(Success(None))
    when(api.boxById(funding.boxId)).thenReturn(Success(Some(funding)))
    val swap = EngineHold("dex", "swap", "66" * 32, Set("67" * 32, funding.boxId), Set(funding.boxId),
      sendFinished = true)
    val wallet = TestProbe()
    val mempool = TestProbe()
    val height = new AtomicInteger(100)
    val clock = new AtomicLong(0L)
    val subject = reconciler(api, wallet, mempool, height, clock, settings.copy(cancelStaleSends = false))
    pass(subject, wallet, mempool, Vector(swap), observed())
    height.set(100 + settings.cancelAfterBlocks)
    pass(subject, wallet, mempool, Vector(swap), observed())
    clock.set(settings.holdCeilingMs)
    pass(subject, wallet, mempool, Vector(swap), observed(), Some(observed()),
      Seq(ResolveEngineInputs("swap", swap.txId, Set.empty, Set(funding.boxId))))
    verify(api, never()).sendTransaction(anyString())
  }

  it should "keep the inputs of a send the node still holds, however old" in {
    val api = mock[NodeApi]
    val wallet = TestProbe()
    val mempool = TestProbe()
    val clock = new AtomicLong(0L)
    val subject = reconciler(api, wallet, mempool, new AtomicInteger(100), clock)
    pass(subject, wallet, mempool, Vector(hold), observed(ids = Set(hold.txId)))
    clock.set(settings.holdCeilingMs + hour)
    pass(subject, wallet, mempool, Vector(hold), observed(ids = Set(hold.txId)))
    verify(api, never()).boxById(anyString())
  }

  /** The engine's wallet state, answering mempool observations the way the engine forwards them. */
  private class ObservedWallet(ctx: NodeContext) extends EngineWalletState(ctx,
    transactions.engine.wallet.EngineWalletStateSpec.NoAutoSweep) {
    override def receive: Receive = ({
      case CompleteMempool.Refresh => sender() ! observed()
    }: Receive).orElse(super.receive)
  }

  "A send out of the mempool long enough" should
    "be cancelled on chain, then settle with its lender key once the cancellation is mined" in {
    val api = mock[NodeApi]
    val (ctx, _, keys) = FakeNodeContext(api, numAddresses = 1)
    val height = new AtomicInteger(100)
    when(api.info()).thenAnswer(_ =>
      Success(ChainFixtures.infoAt(height.get).copy(bestFullHeaderId = Some(anchor))))
    when(api.indexerEnabled).thenReturn(false)
    when(api.walletUnspentBoxes(any[ConfirmationRange], any[Paging])).thenReturn(Success(Seq.empty[WalletBox]))
    when(api.unspentBoxesByErgoTree(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenReturn(Success(Seq.empty[IndexedBox]))
    def walletBox(value: Long, tx: String): NodeBox =
      CanonicalNodeBox(f"$value%064x", tx, value, 0, 90, keys.contract.ergoTreeHex)
    val small = walletBox(1000000000L, "40" * 32)
    val large = walletBox(2000000000L, "41" * 32)
    // Change from a parent that never confirmed: owned, but not on chain.
    val phantom = walletBox(500000000L, "42" * 32)
    val onChain = new AtomicReference(Map(small.boxId -> small, large.boxId -> large))
    when(api.boxById(anyString())).thenAnswer(inv => Success(onChain.get.get(inv.getArgument[String](0))))
    when(api.indexedBoxById(anyString())).thenReturn(Success(None))
    val sent = new AtomicReference[String]()
    when(api.sendTransaction(anyString())).thenAnswer { inv =>
      sent.set(inv.getArgument[String](0))
      Success(NodeCodecs.transaction(new com.google.gson.JsonParser().parse(sent.get).getAsJsonObject).id)
    }

    val engine = system.actorOf(Props(new ObservedWallet(ctx)))
    val probe = TestProbe()
    val staleTx = "50" * 32
    val staleOutput = "51" * 32
    val emission = "52" * 32
    val key = "53" * 32
    val inputs = ctx.getClient.execute(c => Seq(small, large, phantom).map(_.toInputUTXO(c)))
    val ids = inputs.map(_.id.toString).toSet
    ids shouldBe Set(small.boxId, large.boxId, phantom.boxId)
    probe.send(engine, ReserveKnownInputs(inputs, "join-funding", Long.MaxValue))
    probe.expectMsgType[WalletInputs].inputs should have size 3
    probe.send(engine, EngineJoinGuard.Acquire(key, "lease")); probe.expectMsg(true)
    probe.send(engine, EngineJoinGuard.Pin(key, "lease", staleTx, Set("join-funding"))); probe.expectMsg(true)
    probe.send(engine, PinEngineInputs(EngineHold("collateral:x", "join-funding", staleTx, ids + emission, ids,
      outputIds = Set(staleOutput))))
    probe.expectMsg(true)
    probe.send(engine, EngineSendFinished("join-funding", staleTx, accepted = true))
    probe.send(engine, EngineJoinGuard.Cancel(key, "lease"))

    val subject = new EngineReconciler(ctx, engine, engine) {
      override protected lazy val node: NodeApi = api
    }
    subject.reconcile()
    height.set(100 + settings.cancelAfterBlocks)
    subject.reconcile()

    // One input is enough to conflict, and the smallest is the one spent.
    val cancellation = NodeCodecs.transaction(new com.google.gson.JsonParser().parse(sent.get).getAsJsonObject)
    cancellation.inputs.map(_.boxId) shouldBe Seq(small.boxId)
    probe.send(engine, GetEngineHolds)
    val held = probe.expectMsgType[EngineHolds].holds
    held.map(_.txId) shouldBe Vector(cancellation.id)
    held.head.walletInputIds shouldBe ids
    held.head.supersedes shouldBe Map(staleTx -> Set(staleOutput))
    probe.send(engine, EngineJoinGuard.Keys)
    probe.expectMsg(Set(key))

    onChain.set(Map(large.boxId -> large))
    minedSpend(api, small.boxId, cancellation.id, 111)
    height.set(112)
    subject.reconcile()
    probe.send(engine, GetOwnedInputIds)
    probe.expectMsg(Set.empty[String])
    probe.send(engine, EngineJoinGuard.Keys)
    probe.expectMsg(Set.empty[String])
    system.stop(engine)
  }
}
