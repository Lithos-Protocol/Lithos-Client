package api

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.testkit.TestKit
import api.models._
import configs.NodeContext
import lfsm.{CollateralParams, LFSMHelpers}
import node.NodeApi
import node.model._
import org.ergoplatform.appkit.{Address, BlockchainContext}
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.when
import org.mockito.invocation.InvocationOnMock
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.Configuration
import support.{CollateralNodeFixtures => Fx, FakeNodeContext}
import transactions.ProtocolContracts.{hex, lenderEntry}
import transactions.wallet.WalletMessages._

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._
import scala.util.{Success, Try}

/**
 * The collateral-market endpoints above the box scans: what a caller is told before it commits
 * funds, and what the client does to the node to answer
 *
 *  - a quote derives the taken-key set ONCE, not once per wallet address;
 *  - a key a join will refuse is never reported as one a join could use;
 *  - a wallet reply of an unexpected type is a status a caller can act on, not a 500;
 *  - the snapshot behind the wallet view does not survive a join.
 */
object CollateralMarketApiImplSpec {
  val config: com.typesafe.config.Config =
    com.typesafe.config.ConfigFactory.parseString("akka.test.single-expect-default = 20s")
}

class CollateralMarketApiImplSpec
  extends TestKit(ActorSystem("collateral-market-api-spec", CollateralMarketApiImplSpec.config))
    with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  /** Emission defaults are what `EmissionConfig` falls back to; only the TTL is overridden. */
  private val emptyConfig = Configuration.from(Map.empty[String, Any])

  // ─── a wallet that answers ────────────────────────────────────────────────

  /**
   * Stands in for `WalletManager`. `claimReply` is a function so a test can make the actor answer
   * `RewardClaimFailed`, which the manager really does send and which the API used to cast away.
   */
  private class StubWallet(spendable: Long,
                           rewards: RewardSummary,
                           claimReply: () => Any) extends Actor {
    override def receive: Receive = {
      case GetSpendableBalance => sender() ! SpendableBalance(spendable)
      case GetUnlockedRewards => sender() ! rewards
      case ClaimUnlockedRewards => sender() ! claimReply()
    }
  }

  private val noRewards = RewardSummary(0, 0, 0L, 0L, None)

  // ─── the node ─────────────────────────────────────────────────────────────

  /**
   * Counts every token scan, so a test can assert on the WORK a request costs rather than only on
   * its answer. `checkJoin` ran the whole duplicate-key derivation once per wallet address, which
   * no assertion on the response body could have caught.
   */
  private class CountingIndex(boxesFor: String => Seq[IndexedBox]) {
    val calls = new AtomicInteger(0)

    def answer(inv: InvocationOnMock): Try[Seq[IndexedBox]] = {
      calls.incrementAndGet()
      val tokenId = inv.getArgument[String](0)
      val paging = inv.getArgument[Paging](1)
      // One page of everything, then empty: every caller here stops on a short page.
      Success(if (paging.offset == 0) boxesFor(tokenId) else Seq.empty[IndexedBox])
    }
  }

  private case class Fixture(api: CollateralMarketApiImpl,
                             nodeApi: NodeApi,
                             ctxOf: NodeContext,
                             addresses: Seq[Address],
                             index: CountingIndex)

  /**
   * @param numAddresses how many wallet addresses the node reports; the shipped `maxLenderKeys` is 32
   * @param boxes        node boxes per token id, built inside a context so the ergoTrees are real
   * @param ttlMs        snapshot lifetime; 0 makes every read rebuild
   */
  private def fixture(numAddresses: Int = 4,
                      boxes: (BlockchainContext, Seq[Address]) => Map[String, Seq[IndexedBox]] =
                        (_, _) => Map.empty,
                      spendable: Long = 1000L * 1000000000L,
                      rewards: RewardSummary = noRewards,
                      claimReply: () => Any = () => RewardsClaimed(Seq.empty),
                      ttlMs: Long = 10000L,
                      config: Configuration = emptyConfig): Fixture = {
    val nodeApi = mock[NodeApi]
    val (nodeCtx, _, wallet) = FakeNodeContext(nodeApi, numAddresses)
    val addresses = wallet.addresses

    val byToken = nodeCtx.getClient.execute(ctx => boxes(ctx, addresses))
    val index = new CountingIndex(id => byToken.getOrElse(id, Seq.empty))

    when(nodeApi.unspentBoxesByTokenId(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenAnswer((inv: InvocationOnMock) => index.answer(inv))
    when(nodeApi.unconfirmedInputByBoxId(anyString())).thenReturn(Success(None))
    when(nodeApi.walletAddresses()).thenReturn(Success(addresses.map(_.toString)))
    when(nodeApi.indexerEnabled).thenReturn(true)

    val walletRef: ActorRef =
      system.actorOf(Props(new StubWallet(spendable, rewards, claimReply)))

    val api = new CollateralMarketApiImpl(nodeCtx, config, system, walletRef) {
      override protected val snapshotTtlMs: Long = ttlMs
    }
    Fixture(api, nodeApi, nodeCtx, addresses, index)
  }

  /** The minimum chain state every endpoint needs: an emission box and a config box. */
  private def baseBoxes(ctx: BlockchainContext,
                        lenderSet: Seq[Array[Byte]] = Seq.empty,
                        head: Long = 0L,
                        tail: Long = 0L): Map[String, Seq[IndexedBox]] =
    Map(
      LFSMHelpers.EMISSION_NFT.toString ->
        Seq(Fx.indexed(Fx.emissionBox(ctx, lenderSet = lenderSet, head = head, tail = tail))),
      LFSMHelpers.EMCONFIG_NFT.toString -> Seq(Fx.indexed(Fx.configBox(ctx))))

  private def quoteFor(f: Fixture, count: Int): CollateralJoinQuote =
    f.api.checkJoin(CollateralJoinCheckRequest(Some(count)))

  /**
   * A proof of spend recent enough to still hold its key, against the MOCKED chain height.
   *
   * `takenLenderKeys` keeps a retiring key only while `ctx.getHeight - inclusionHeight <= 50`. The
   * offline context reports height 123,413, so a fixture written at a plausible-looking 490 is a
   * box 122,000 confirmations deep and is correctly ignored — which reads as the code failing.
   */
  private def recentProofOfSpend(ctx: BlockchainContext, retiring: Array[Byte]): IndexedBox =
    Fx.indexed(Fx.proofOfSpendBox(ctx, retiring, createdAt = ctx.getHeight - 10),
      height = ctx.getHeight - 10)

  // ─── 1. the work a quote costs ────────────────────────────────────────────

  "A join quote" should "derive the taken-key set once, not once per wallet address" in {
    // The regression that matters most here. `takenLenderKeys` sat INSIDE `mine.count(...)`, so it
    // ran once per element: two token scans paged to exhaustion per wallet address. At the shipped
    // maxLenderKeys of 32 that is 64 exhaustive scans for one unauthenticated request, against the
    // miner's own node — which by the client rules is the most severe class of defect there is.
    //
    // Asserted on the CALL COUNT, because the response body is identical either way.
    val f = fixture(numAddresses = 8, boxes = (ctx, _) => baseBoxes(ctx))
    val quote = quoteFor(f, 1)

    quote.count shouldBe 1
    withClue(s"token scans for one quote across ${f.addresses.size} addresses: ") {
      // Emission tip, config, and one pass each over the queue and collateral tokens. Generous
      // enough not to pin the exact shape, tight enough that a per-address derivation fails it:
      // eight addresses would put this above thirty.
      f.index.calls.get() should be <= 12
    }
  }

  it should "cost no scans at all on a second read inside the snapshot's lifetime" in {
    val f = fixture(numAddresses = 4, boxes = (ctx, _) => baseBoxes(ctx))
    f.api.getWalletStatus
    val afterFirst = f.index.calls.get()
    f.api.getWalletStatus

    withClue(s"first read cost $afterFirst scans, second cost ${f.index.calls.get() - afterFirst}: ") {
      f.index.calls.get() shouldBe afterFirst
    }
  }

  // ─── 2. free means what the join means by free ────────────────────────────

  "A key named by a recent proof of spend" should "be RETIRING rather than FREE" in {
    // It has no queue box and no live collateral box, so the old derivation called it FREE. `join`
    // filters on `takenLenderKeys`, which holds it for 50 confirmations because a reorg could make
    // that collateral box live again — so the panel offered a join the endpoint then refused.
    val f = fixture(numAddresses = 4, boxes = (ctx, addrs) =>
      baseBoxes(ctx) + (LFSMHelpers.COLLAT_TOKEN.toString ->
        Seq(recentProofOfSpend(ctx, Fx.entryOf(addrs.head)))))

    val status = f.api.getWalletStatus
    val retiring = status.lenderKeys.find(_.address == f.addresses.head.toString).get

    retiring.status shouldBe CollateralLenderKeyStatus.Retiring
    status.freeLenderKeys shouldBe f.addresses.size - 1
    withClue("a RETIRING key must not be counted as a position that can be opened: ") {
      status.lenderKeys.count(_.status == CollateralLenderKeyStatus.Free) shouldBe status.freeLenderKeys
    }
  }

  "A key holding a live collateral box" should "be ACTIVE and carry a position" in {
    // The other status a key can be spoken for under, and the one where the LIT figure means
    // something different: a live box carries its block's emission AND the lender's permit
    // together, indistinguishable on-chain, so it reports `carriedLit` and no permit.
    val f = fixture(numAddresses = 4, boxes = (ctx, addrs) =>
      baseBoxes(ctx) + (LFSMHelpers.COLLAT_TOKEN.toString ->
        Seq(Fx.indexed(Fx.collateralBox(ctx, addrs.head, carriedLit = 4200L,
          createdAt = ctx.getHeight - 5), height = ctx.getHeight - 5))))

    val status = f.api.getWalletStatus
    status.lenderKeys.find(_.address == f.addresses.head.toString).get.status shouldBe
      CollateralLenderKeyStatus.Active
    status.ownPositionsLive shouldBe 1
    status.ownPositionsQueued shouldBe 0
    status.freeLenderKeys shouldBe f.addresses.size - 1

    val position = status.positions.find(_.kind == "LIVE").get
    position.carriedLit shouldBe Some("4200")
    withClue("a live box's LIT is emission plus permit, so it must not be reported as a permit: ") {
      position.permitLit shouldBe None
    }
  }

  it should "not be offered by a quote that needs every key" in {
    // The other half, and the one that names the moment the two behaviours differ: the count alone
    // could be right while the quote still hands the key out.
    val f = fixture(numAddresses = 4, boxes = (ctx, addrs) =>
      baseBoxes(ctx) + (LFSMHelpers.COLLAT_TOKEN.toString ->
        Seq(recentProofOfSpend(ctx, Fx.entryOf(addrs.head)))))

    val quote = quoteFor(f, 3)
    quote.freeLenderKeys shouldBe 3
    quote.lenderAddresses should not contain f.addresses.head.toString
    quote.keysRequired shouldBe 3
  }

  // ─── 3. what blocks a join, and in which order ────────────────────────────

  "A quote" should "report AUTO_COLLATERALIZE ahead of any other reason" in {
    // Ordered by what the reader has to change first. A wallet with no ERG and autoCollateralize on
    // must not send somebody to top up a wallet that was never the reason.
    val f = fixture(numAddresses = 2, boxes = (ctx, _) => baseBoxes(ctx), spendable = 0L,
      config = Configuration.from(Map("emission.autoCollateralize" -> true)))

    val quote = quoteFor(f, 1)
    quote.manualJoinAllowed shouldBe false
    quote.blockedReason shouldBe Some(CollateralJoinBlock.AutoCollateralize)
    withClue("the wallet is empty too, and that must not be the reason reported: ") {
      quote.affordNow shouldBe false
    }
  }

  it should "report INSUFFICIENT_FUNDS when nothing else blocks it" in {
    val f = fixture(numAddresses = 4, boxes = (ctx, _) => baseBoxes(ctx), spendable = 0L)
    val quote = quoteFor(f, 1)
    quote.manualJoinAllowed shouldBe false
    quote.blockedReason shouldBe Some(CollateralJoinBlock.InsufficientFunds)
    quote.shortfallNanoErgs shouldBe
      (CollateralParams.PRINCIPAL_FLOOR + org.ergoplatform.appkit.Parameters.MinFee).toString
  }

  it should "allow the join when nothing blocks it" in {
    val f = fixture(numAddresses = 4, boxes = (ctx, _) => baseBoxes(ctx))
    val quote = quoteFor(f, 2)
    quote.manualJoinAllowed shouldBe true
    quote.blockedReason shouldBe None
    quote.lenderAddresses should have size 2
    withClue("the addresses must be the ones the join would use, in order: ") {
      quote.lenderAddresses shouldEqual f.addresses.take(2).map(_.toString)
    }
    quote.permitsLit should have size 2
  }

  // ─── 4. a join that cannot go ahead ───────────────────────────────────────

  "A join without the acknowledgement" should "be refused before anything is read" in {
    // Cheapest check first, and it must not depend on chain state: no boxes are stubbed here at all,
    // so reaching the node would fail the test with a different error.
    val f = fixture(numAddresses = 2)
    val thrown = the[LithosApiErrors.LithosBadRequest] thrownBy
      f.api.join(CollateralJoinExecuteRequest(1, None, None, None))
    thrown.getMessage should include("acknowledgeNoWithdrawal")

    the[LithosApiErrors.LithosBadRequest] thrownBy
      f.api.join(CollateralJoinExecuteRequest(1, Some(false), None, None))
  }

  "A join while autoCollateralize is set" should "be refused with a 409-mapped error" in {
    // No boxes are stubbed, so reaching the node would fail with a different error entirely — which
    // is how this asserts the gate runs BEFORE anything is read.
    val f = fixture(numAddresses = 2,
      config = Configuration.from(Map("emission.autoCollateralize" -> true)))

    // The acknowledgement is present, so this is the autoCollateralize gate and not the one above.
    val thrown = the[LithosApiErrors.LithosStateChanged] thrownBy
      f.api.join(CollateralJoinExecuteRequest(1, Some(true), None, None))
    thrown.getMessage should include("autoCollateralize")
  }

  // ─── 5. a wallet reply the API did not expect ─────────────────────────────

  "A sweep that failed" should "become a status carrying the reason, not a 500" in {
    // `WalletManager` really does answer `RewardClaimFailed` — `runRewardSweep` catches Throwable
    // and completes its promise with it. The API cast the reply to `RewardsClaimed`, so the one
    // message carrying WHY the sweep failed became a ClassCastException naming two class names.
    val f = fixture(
      rewards = RewardSummary(0, 2, 0L, 6000000000L, None),
      claimReply = () => RewardClaimFailed("node refused the sweep"))

    val thrown = the[LithosApiErrors.LithosUnprocessable] thrownBy f.api.claimUnlockedRewards
    thrown.getMessage should include("node refused the sweep")
  }

  it should "still report nothing-to-claim when the wallet holds no unlocked boxes" in {
    val f = fixture(rewards = noRewards)
    a[CollateralMarketApi.NothingToClaim] should be thrownBy f.api.claimUnlockedRewards
  }

  it should "report a concurrent sweep rather than success with an empty list" in {
    // The pre-check passed, so an empty result means another caller holds the leases. A 200 with no
    // claims cannot be told from a deliberate no-op.
    val f = fixture(
      rewards = RewardSummary(0, 2, 0L, 6000000000L, None),
      claimReply = () => RewardsClaimed(Seq.empty))
    val thrown = the[CollateralMarketApi.NothingToClaim] thrownBy f.api.claimUnlockedRewards
    thrown.getMessage should include("already sweeping")
  }

  // ─── 6. the market snapshot ───────────────────────────────────────────────

  "The market snapshot" should "sum the permits on queue boxes rather than the emission box's LIT" in {
    val f = fixture(numAddresses = 2, boxes = (ctx, addrs) =>
      baseBoxes(ctx, head = 0L, tail = 2L) + (LFSMHelpers.QUEUE_TOKEN.toString -> Seq(
        Fx.indexed(Fx.queueBox(ctx, addrs.head, position = 0L, permit = 1000L, txId = "d1" * 32)),
        Fx.indexed(Fx.queueBox(ctx, addrs(1), position = 1L, permit = 2500L, txId = "d2" * 32)))))

    val info = f.api.getMarketInfo
    info.permitLitLocked shouldBe "3500"
    info.queueLength shouldBe 2L
    info.queuedNanoErgs shouldBe (2L * CollateralParams.PRINCIPAL_FLOOR).toString
  }

  it should "be served from the snapshot on a second read" in {
    val f = fixture(numAddresses = 2, boxes = (ctx, _) => baseBoxes(ctx))
    f.api.getMarketInfo
    val afterFirst = f.index.calls.get()
    f.api.getMarketInfo
    f.index.calls.get() shouldBe afterFirst
  }

  it should "rebuild once the snapshot has expired" in {
    // The other side of the pair. Without this, a cache that never refreshed would pass the test
    // above and be a permanent freeze rather than a ten-second one.
    val f = fixture(numAddresses = 2, boxes = (ctx, _) => baseBoxes(ctx), ttlMs = 0L)
    f.api.getMarketInfo
    val afterFirst = f.index.calls.get()
    f.api.getMarketInfo
    f.index.calls.get() should be > afterFirst
  }
}
