package transactions

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import configs.NodeContext
import mutations.NodeWallet
import mutations.NodeWallet.MINER_REWARD_DELAY
import node.NodeApi
import node.model._
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.when
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import org.ergoplatform.sdk.ErgoId
import support.FakeNodeContext
import transactions.WalletMessages._
import work.lithos.mutations.{InputUTXO, Token}

import scala.concurrent.duration.{Duration, DurationInt}
import scala.util.{Failure, Success}

/**
 * The reservation lifecycle — the launch blocker the 2026-08-11 audit closed, pinned by nothing
 * until now.
 *
 * The defect: `ResetUsedInputs` cleared every reservation on a fifteen-minute tick, while the
 * refresh RETAINED reserved boxes the node had stopped reporting — which is precisely the signal
 * they were spent. A box spent at t=0 survived the refresh because it was reserved, then became
 * selectable again at t=15min. These assert the three named endings that replaced it.
 *
 * Boxes are identified by VALUE, not by the id they are constructed with: `toInputUTXO` recomputes
 * the id from the box's contents, so a synthetic one does not survive the conversion.
 */
object WalletManagerSpec {
  val config: com.typesafe.config.Config =
    com.typesafe.config.ConfigFactory.parseString("akka.test.single-expect-default = 20s")
}

class WalletManagerSpec extends TestKit(ActorSystem("wallet-manager-spec", WalletManagerSpec.config))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val erg = 1000000000L

  /** A WalletManager whose clock the test drives, so a reservation can age without waiting it out. */
  private class TestableWalletManager(ctx: NodeContext, clock: () => Long) extends WalletManager(ctx) {
    override protected def now(): Long = clock()
  }

  private def walletBox(wallet: NodeWallet, value: Long, assets: Seq[NodeAsset] = Seq.empty): WalletBox =
    WalletBox(
      box = NodeBox(boxId = f"$value%064x", transactionId = "aa" * 32, value = value, index = 0,
        creationHeight = 100, ergoTree = wallet.contract.ergoTreeHex, assets = assets),
      address = wallet.p2pk.toString, confirmationsNum = Some(10), creationTransaction = "aa" * 32,
      creationOutIndex = 0, inclusionHeight = Some(100), spendingTransaction = None,
      spendingHeight = None, spent = false, onchain = true, scans = Seq(10))

  private def coinbase(wallet: NodeWallet, value: Long, creationHeight: Int = 1): IndexedBox =
    IndexedBox(
      box = NodeBox(boxId = f"$value%064x", transactionId = "bb" * 32, value = value, index = 0,
        creationHeight = creationHeight, ergoTree = wallet.rewardTrees.keys.head),
      address = "reward", inclusionHeight = creationHeight, globalIndex = 1L)

  // ─── harness ──────────────────────────────────────────────────────────────

  private case class Fixture(mgr: ActorRef, wallet: NodeWallet, probe: TestProbe, height: Int)

  /**
   * A started WalletManager with the node reporting what the builders say, and its first refresh
   * already applied — awaited rather than slept on, except where a test needs to observe an EMPTY
   * result, which cannot be distinguished from "not yet refreshed" by polling.
   */
  private def fixture(mkBoxes: NodeWallet => Seq[WalletBox] = _ => Seq.empty,
                      mkRewards: NodeWallet => Seq[IndexedBox] = _ => Seq.empty,
                      indexer: Boolean = true,
                      rewardsFail: Boolean = false,
                      time: () => Long = () => System.currentTimeMillis()): Fixture = {
    val api = mock[NodeApi]
    val (ctx, _, wallet) = FakeNodeContext(api, numAddresses = 1)
    val boxes = mkBoxes(wallet)
    val rewards = mkRewards(wallet)

    when(api.indexerEnabled).thenReturn(indexer)
    // One page then a short one: `loadAll` pages to exhaustion and stops when a page is not full.
    when(api.walletUnspentBoxes(any[ConfirmationRange], any[Paging])).thenAnswer { inv =>
      if (inv.getArgument[Paging](1).offset == 0) Success(boxes) else Success(Seq.empty[WalletBox])
    }
    if (rewardsFail)
      when(api.unspentBoxesByErgoTree(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
        .thenReturn(Failure(new RuntimeException("indexer refused")))
    else
      when(api.unspentBoxesByErgoTree(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
        .thenAnswer { inv =>
          Success(rewards.filter(_.box.ergoTree == inv.getArgument[String](0)))
        }

    val mgr = system.actorOf(Props(new TestableWalletManager(ctx, time)))
    val probe = TestProbe()
    val height = ctx.getClient.execute(_.getHeight)
    mgr ! RefreshBoxes
    // Settle the refresh before any assertion. It runs in a Future off the mailbox, so an ask that
    // arrives first would see an empty wallet and read as a failure of the thing under test.
    Thread.sleep(1200)
    Fixture(mgr, wallet, probe, height)
  }

  private def offered(f: Fixture, need: Long, tokens: Seq[Token] = Seq.empty,
                      track: Boolean = false): Seq[InputUTXO] = {
    f.probe.send(f.mgr, RetrieveInputs(need, tokens, trackUsed = track))
    f.probe.expectMsgType[WalletInputs].inputs
  }

  private def values(boxes: Seq[InputUTXO]): Seq[Long] = boxes.map(_.value).sorted

  // ─── the property the whole mechanism exists for ──────────────────────────

  "Two selections inside one refresh window" should "never return the same box" in {
    // The highest-value test in the wallet list: the symptom of failure is a double spend that looks
    // like someone else's. Reservations are subtracted at SELECTION, not only at refresh.
    val f = fixture(w => Seq(walletBox(w, erg), walletBox(w, 2 * erg)))

    val first = offered(f, erg / 2, track = true)
    val second = offered(f, erg / 2, track = true)

    first should not be empty
    second should not be empty
    first.map(_.id.toString).intersect(second.map(_.id.toString)) shouldBe empty
  }

  "A reservation" should "hold without an intervening refresh" in {
    val f = fixture(w => Seq(walletBox(w, erg)))
    offered(f, erg / 2, track = true) should have size 1
    // Only one box exists and it is now reserved, so nothing is left to offer.
    offered(f, erg / 2) shouldBe empty
  }

  it should "come back once the caller releases it" in {
    val f = fixture(w => Seq(walletBox(w, erg)))
    val held = offered(f, erg / 2, track = true)
    held should have size 1
    offered(f, erg / 2) shouldBe empty

    f.mgr ! ReleaseInputs(held)
    f.probe.awaitAssert(offered(f, erg / 2) should have size 1, 5.seconds, 100.millis)
  }

  // ─── ending one: spent, detected by a complete refresh ────────────────────

  "A reserved box the node stops reporting" should "be collected with its reservation" in {
    // It was spent. Keeping the box because it is reserved, and then clearing the reservation on a
    // timer, is exactly what handed a spent box back out.
    val api = mock[NodeApi]
    val (ctx, _, wallet) = FakeNodeContext(api, numAddresses = 1)
    val small = walletBox(wallet, erg)
    val large = walletBox(wallet, 5 * erg)
    var reported = Seq(small, large)

    when(api.indexerEnabled).thenReturn(false)
    when(api.unspentBoxesByErgoTree(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenReturn(Success(Seq.empty[IndexedBox]))
    when(api.walletUnspentBoxes(any[ConfirmationRange], any[Paging])).thenAnswer { inv =>
      if (inv.getArgument[Paging](1).offset == 0) Success(reported) else Success(Seq.empty[WalletBox])
    }

    val mgr = system.actorOf(Props(new TestableWalletManager(ctx, () => System.currentTimeMillis())))
    val probe = TestProbe()
    mgr ! RefreshBoxes
    Thread.sleep(1200)

    probe.send(mgr, RetrieveInputs(erg / 2, Seq.empty, trackUsed = true))
    val taken = probe.expectMsgType[WalletInputs].inputs
    taken.map(_.value) shouldEqual Seq(erg) // dust-first takes the small one

    // The node now reports only the large box: the reserved one was spent and confirmed.
    reported = Seq(large)
    mgr ! RefreshBoxes
    Thread.sleep(1200)

    probe.send(mgr, RetrieveInputs(1000L, Seq.empty, trackUsed = false))
    val after = probe.expectMsgType[WalletInputs].inputs
    withClue("the spent box must be gone, and only the survivor offered: ") {
      after.map(_.value) shouldEqual Seq(5 * erg)
    }
  }

  // ─── ending three: stale, by age and only by age ──────────────────────────

  "The sweep" should "leave a young reservation alone" in {
    // The regression for the wholesale wipe. A reservation that is merely young is a box sitting in
    // an unconfirmed transaction, and handing it out again is the double spend.
    val f = fixture(w => Seq(walletBox(w, erg)))
    offered(f, erg / 2, track = true) should have size 1

    f.mgr ! ResetUsedInputs
    Thread.sleep(300)
    offered(f, erg / 2) shouldBe empty
  }

  it should "release one that has aged past the TTL" in {
    var clock = 1000000L
    val f = fixture(w => Seq(walletBox(w, erg)), time = () => clock)
    offered(f, erg / 2, track = true) should have size 1
    offered(f, erg / 2) shouldBe empty

    clock += WalletManager.ReservationTtlMs + 1
    f.mgr ! ResetUsedInputs
    f.probe.awaitAssert(offered(f, erg / 2) should have size 1, 5.seconds, 100.millis)
  }

  // ─── the reward-box regression (audit §4b.1) ──────────────────────────────

  "A reserved coinbase" should "survive a complete refresh" in {
    // Introduced by the FIRST version of the reservation fix: the collector compared reservations
    // against the wallet boxes alone, and coinbases live in a separate map — so every reserved
    // coinbase was declared spent on sight and released while its transaction was still in flight.
    val f = fixture(mkRewards = w => Seq(coinbase(w, 3 * erg)))
    offered(f, erg, track = true).map(_.value) shouldEqual Seq(3 * erg)

    f.mgr ! RefreshBoxes
    Thread.sleep(1200)

    withClue("still reported by the node, so still reserved: ") {
      offered(f, erg) shouldBe empty
    }
  }

  "A failed reward lookup" should "not be read as the coinbase having been spent" in {
    // `loadRewards` used to swallow a per-address failure as an empty answer, which to a collector
    // reasoning from absence is indistinguishable from "spent". It now reports incompleteness, and
    // an incomplete refresh collects nothing.
    //
    // The assertion has to be made AFTER the lookup recovers. Asserting during the failure only
    // shows an empty offer, which the defect also produces — for the opposite reason, because the
    // box had vanished from `rewardBoxes` along with its reservation. An earlier version of this
    // test did exactly that and passed with the fix reverted.
    val api = mock[NodeApi]
    val (ctx, _, wallet) = FakeNodeContext(api, numAddresses = 1)
    val reward = coinbase(wallet, 3 * erg)
    var failing = false

    when(api.indexerEnabled).thenReturn(true)
    when(api.walletUnspentBoxes(any[ConfirmationRange], any[Paging])).thenReturn(Success(Seq.empty[WalletBox]))
    when(api.unspentBoxesByErgoTree(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenAnswer { inv =>
        if (failing) Failure(new RuntimeException("indexer refused"))
        else Success(if (inv.getArgument[String](0) == reward.box.ergoTree) Seq(reward) else Seq.empty[IndexedBox])
      }

    val mgr = system.actorOf(Props(new TestableWalletManager(ctx, () => System.currentTimeMillis())))
    val probe = TestProbe()
    mgr ! RefreshBoxes
    Thread.sleep(1200)

    probe.send(mgr, RetrieveInputs(erg, Seq.empty, trackUsed = true))
    probe.expectMsgType[WalletInputs].inputs.map(_.value) shouldEqual Seq(3 * erg)

    failing = true
    mgr ! RefreshBoxes
    Thread.sleep(1200)

    // The lookup recovers, so the box is reported again. With the reservation intact it stays
    // withheld; released at the failed refresh, it would be handed straight back out — which is the
    // double spend, and the only point at which the two behaviours differ observably.
    failing = false
    mgr ! RefreshBoxes
    Thread.sleep(1200)

    probe.send(mgr, RetrieveInputs(erg, Seq.empty, trackUsed = false))
    withClue("a lookup that failed says nothing about whether the box was spent: ") {
      probe.expectMsgType[WalletInputs].inputs shouldBe empty
    }
  }

  // ─── the other half of the same inference (audit §4d.2) ───────────────────

  "An incomplete wallet read" should "collect nothing" in {
    // `loadAll` pages to exhaustion, and a page that fails part way leaves the read partial. A
    // partial read cannot tell "spent" from "not fetched", and guessing wrong releases a reservation
    // on a box sitting in an unconfirmed transaction — the double spend the whole mechanism exists
    // to stop.
    //
    // Asserted after the read RECOVERS, for the same reason as the failed-lookup case above: while
    // the read is partial the box is absent from the offer either way, so that moment cannot tell
    // the two behaviours apart.
    val api = mock[NodeApi]
    val (ctx, _, wallet) = FakeNodeContext(api, numAddresses = 1)
    val held = walletBox(wallet, erg)
    // A FULL first page is what forces a second request, which is where the blip lands. A read that
    // fails on its first page is partial too, but it would not exercise the paging loop.
    val filler = (1 to WalletManager.MAX_WALLET_BOXES).map(i => walletBox(wallet, erg + i))
    var blip = false

    when(api.indexerEnabled).thenReturn(false)
    when(api.unspentBoxesByErgoTree(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenReturn(Success(Seq.empty[IndexedBox]))
    when(api.walletUnspentBoxes(any[ConfirmationRange], any[Paging])).thenAnswer { inv =>
      val offset = inv.getArgument[Paging](1).offset
      if (!blip) if (offset == 0) Success(Seq(held)) else Success(Seq.empty[WalletBox])
      else if (offset == 0) Success(filler)
      else Failure(new RuntimeException("connection reset mid-paging"))
    }

    val mgr = system.actorOf(Props(new TestableWalletManager(ctx, () => System.currentTimeMillis())))
    val probe = TestProbe()
    mgr ! RefreshBoxes
    Thread.sleep(1200)

    probe.send(mgr, RetrieveInputs(erg / 2, Seq.empty, trackUsed = true))
    probe.expectMsgType[WalletInputs].inputs.map(_.value) shouldEqual Seq(erg)

    blip = true
    mgr ! RefreshBoxes
    Thread.sleep(2000) // 500 boxes to convert, plus the failed second page

    blip = false
    mgr ! RefreshBoxes
    Thread.sleep(1200)

    probe.send(mgr, RetrieveInputs(erg / 2, Seq.empty, trackUsed = false))
    withClue("a page that never arrived says nothing about whether the box was spent: ") {
      probe.expectMsgType[WalletInputs].inputs shouldBe empty
    }
  }

  "A coinbase" should "be preferred when one covers the request outright" in {
    // Spending them here is the only thing that returns that ERG to an address ordinary wallets can
    // see, so a small fee should take the coinbase rather than a wallet box.
    val f = fixture(w => Seq(walletBox(w, erg)), w => Seq(coinbase(w, 3 * erg)))
    offered(f, 1000L).map(_.value) shouldEqual Seq(3 * erg)
  }

  it should "be taken alone, so the selection stays irredundant" in {
    val f = fixture(w => Seq(walletBox(w, erg)), w => Seq(coinbase(w, 3 * erg)))
    offered(f, 1000L) should have size 1
  }

  it should "never be chosen when the request needs tokens" in {
    // A coinbase carries none, so preferring one for a token request returns a set that cannot cover
    // it.
    val tokenId = "cc" * 32
    val f = fixture(
      w => Seq(walletBox(w, erg, Seq(NodeAsset(tokenId, 50L)))),
      w => Seq(coinbase(w, 3 * erg)))

    val chosen = offered(f, 1000L, Seq(Token(ErgoId.create(tokenId), 10L)))
    values(chosen) shouldEqual Seq(erg)
    chosen.flatMap(_.tokens).map(_.id.toString) should contain(tokenId)
  }

  "A locked coinbase" should "never be selected" in {
    // The guard is `>`, not `>=`, and it runs against the height captured at the last refresh — which
    // is lower than the live one, so it errs toward holding a box back rather than offering one that
    // is still locked.
    val api = mock[NodeApi]
    val (ctx, _, wallet) = FakeNodeContext(api, numAddresses = 1)
    val height = ctx.getClient.execute(_.getHeight)
    val exactlyAtBoundary = coinbase(wallet, 3 * erg, creationHeight = height - MINER_REWARD_DELAY)

    when(api.indexerEnabled).thenReturn(true)
    when(api.walletUnspentBoxes(any[ConfirmationRange], any[Paging])).thenReturn(Success(Seq.empty[WalletBox]))
    when(api.unspentBoxesByErgoTree(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenAnswer { inv =>
        Success(if (inv.getArgument[String](0) == exactlyAtBoundary.box.ergoTree) Seq(exactlyAtBoundary)
                else Seq.empty[IndexedBox])
      }

    val mgr = system.actorOf(Props(new TestableWalletManager(ctx, () => System.currentTimeMillis())))
    val probe = TestProbe()
    mgr ! RefreshBoxes
    Thread.sleep(1200)

    probe.send(mgr, RetrieveInputs(1000L, Seq.empty, trackUsed = false))
    withClue(s"creationHeight + $MINER_REWARD_DELAY == chainHeight is still locked: ") {
      probe.expectMsgType[WalletInputs].inputs shouldBe empty
    }
  }

  // ─── signable filter ──────────────────────────────────────────────────────

  "A box this client cannot sign for" should "never be selected" in {
    // Live since the genesis transaction started returning permit change to a derived lender
    // address: a P2PK box the node's wallet does track and the prover could not sign, which
    // surfaced as intermittent unexplained signing failures.
    val api = mock[NodeApi]
    val (ctx, _, wallet) = FakeNodeContext(api, numAddresses = 1)
    val foreign = WalletBox(
      box = NodeBox("ff" * 32, "aa" * 32, erg, 0, 100,
        "0008cd02856c5e4eb4b915005dccb8e0f42bed1777cae48565be13a77cbf31e3c93f4515"),
      address = "foreign", confirmationsNum = Some(1), creationTransaction = "aa" * 32,
      creationOutIndex = 0, inclusionHeight = Some(100), spendingTransaction = None,
      spendingHeight = None, spent = false, onchain = true, scans = Seq(10))

    when(api.indexerEnabled).thenReturn(false)
    when(api.unspentBoxesByErgoTree(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenReturn(Success(Seq.empty[IndexedBox]))
    when(api.walletUnspentBoxes(any[ConfirmationRange], any[Paging])).thenAnswer { inv =>
      if (inv.getArgument[Paging](1).offset == 0) Success(Seq(foreign)) else Success(Seq.empty[WalletBox])
    }

    val mgr = system.actorOf(Props(new TestableWalletManager(ctx, () => System.currentTimeMillis())))
    val probe = TestProbe()
    mgr ! RefreshBoxes
    Thread.sleep(1200)

    probe.send(mgr, RetrieveInputs(1000L, Seq.empty, trackUsed = false))
    probe.expectMsgType[WalletInputs].inputs shouldBe empty
    wallet.signableTrees should not contain foreign.box.ergoTree
  }

  // ─── change handed back mid-run ───────────────────────────────────────────

  "ReturnInputs" should "make unconfirmed change selectable before it confirms" in {
    // A chained run spends its own change before the node can report it. Without this the next
    // transaction in the run has nothing to draw on.
    import node.MutationConversions._

    val f = fixture()
    offered(f, 1000L) shouldBe empty

    val change = NodeBox("dd" * 32, "aa" * 32, 7 * erg, 0, 100, f.wallet.contract.ergoTreeHex)
    val converted = FakeNodeContext.offlineClient().execute(ctx => change.toInputUTXO(ctx))

    f.mgr ! ReturnInputs(Seq(converted))
    f.probe.awaitAssert(offered(f, 1000L).map(_.value) shouldEqual Seq(7 * erg), 5.seconds, 100.millis)
  }

  it should "ignore change the prover cannot sign for" in {
    // Guards the same hazard as the selection filter, on the path that bypasses the node entirely.
    import node.MutationConversions._

    val f = fixture()
    val foreign = NodeBox("dd" * 32, "aa" * 32, 7 * erg, 0, 100,
      "0008cd02856c5e4eb4b915005dccb8e0f42bed1777cae48565be13a77cbf31e3c93f4515")
    val converted = FakeNodeContext.offlineClient().execute(ctx => foreign.toInputUTXO(ctx))

    f.mgr ! ReturnInputs(Seq(converted))
    Thread.sleep(300)
    offered(f, 1000L) shouldBe empty
  }
}
