package transactions.engine.execution

import akka.actor.ActorSystem
import akka.testkit.{TestKit, TestProbe}
import api.LithosApiErrors.{LithosBadRequest, LithosNotFound, LithosStateChanged}
import api.models._
import cache.LDCache
import configs.NodeContext
import lithosdex.{LDHelpers, LDLiquidityPool}
import lithosdex.contracts.{LDOrderContracts, LDOrderTerms}
import mutations.NodeWallet
import node.MutationConversions._
import node.NodeApi
import node.model._
import org.ergoplatform.appkit.{BlockchainContext, Parameters}
import org.ergoplatform.sdk.ErgoId
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.when
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import state.synchronization.CompleteMempool
import support.{FakeCache, FakeNodeContext, LDNodeFixtures}
import transactions.batching.lithosdex.{DexContracts, LDOrderTransactions}
import transactions.engine.wallet.EngineFunding
import transactions.engine.wallet.EngineWalletMessages.SelectInputs
import work.lithos.mutations.{Contract, Token, UTXO}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Try}

/**
 * The order endpoints above the builders: which orders a wallet is shown and in what state, what a
 * request is refused for before anything is reserved, and what a quote tells a caller about fees it
 * will lose.
 *
 * Statuses are asserted against one mempool observation holding every case at once, so a status read
 * from the wrong transaction shows up as two orders swapping places rather than passing by coincidence.
 */
class DexOrderExecutionSpec extends TestKit(ActorSystem("dex-order-execution-spec"))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  private implicit val ec: ExecutionContext = system.dispatcher

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val erg = 1000000000L
  private val ReservesX = 10L * erg
  private val ReservesY = 10000L * 1000000L
  private val Fee = 3000000L
  private val Cap = 1000000L

  private val emptySnapshot = CompleteMempool.Snapshot("00" * 32, Set.empty, Set.empty, System.nanoTime())

  private case class Fixture(api: DexAPIExecution, nodeApi: NodeApi, node: NodeContext, walletProbe: TestProbe,
                             cache: LDCache) {
    def wallet: NodeWallet = node.getNodeWallet
  }

  private def fixture(snapshot: => CompleteMempool.Snapshot = emptySnapshot,
                      held: => Seq[CompleteMempool.MempoolTx] = Seq.empty): Fixture = {
    val nodeApi = mock[NodeApi]
    val (node, _, _) = FakeNodeContext(nodeApi, numAddresses = 1)
    val probe = TestProbe()
    val api = new DexAPIExecution(node, EngineFunding(probe.ref, 2.seconds, ec), heldPlacements = () => held) {
      override protected def mempoolSnapshot(): CompleteMempool.Snapshot = snapshot
    }
    Fixture(api, nodeApi, node, probe, new LDCache(new FakeCache))
  }

  private def withCtx[A](f: Fixture)(body: BlockchainContext => A): A = f.node.getClient.execute(ctx => body(ctx))

  /** One pool box and one vault box for the singleton lookups. */
  private def serve(f: Fixture, pool: NodeBox, vault: Option[NodeBox] = None): Unit = withCtx(f) { ctx =>
    val n = ctx.getNetworkType
    when(f.nodeApi.unspentBoxesByTokenId(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenAnswer { inv =>
        inv.getArgument[String](0) match {
          case id if id == LDHelpers.getPoolNFT(n).toString => Success(Seq(LDNodeFixtures.indexed(pool)))
          case id if id == LDHelpers.getVaultNFT(n).toString => Success(vault.map(LDNodeFixtures.indexed(_)).toSeq)
          case _ => Success(Seq.empty[IndexedBox])
        }
      }
  }

  /** Template scans answer from `byTemplate`; box reads answer from `live`. */
  private def serveOrders(f: Fixture, byTemplate: Map[String, Seq[IndexedBox]], live: Seq[NodeBox]): Unit = {
    when(f.nodeApi.unspentBoxesByTemplateHash(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenAnswer { inv =>
        if (inv.getArgument[Paging](1).offset > 0) Success(Seq.empty[IndexedBox])
        else Success(byTemplate.getOrElse(inv.getArgument[String](0), Seq.empty[IndexedBox]))
      }
    when(f.nodeApi.boxesWithPoolByIds(any[Seq[String]])).thenAnswer { inv =>
      val wanted = inv.getArgument[Seq[String]](0).toSet
      Success(live.filter(box => wanted.contains(box.boxId)))
    }
  }

  private def poolBox(ctx: BlockchainContext, accX: BigInt = BigInt(0), index: Int = 0, txId: String = "ab" * 32) =
    LDNodeFixtures.poolBox(ctx, ReservesX, ReservesY, accX = accX, index = index, txId = txId)

  private def terms(ctx: BlockchainContext, owner: sigma.data.SigmaBoolean) =
    LDOrderTerms(owner, LDHelpers.getPoolNFT(ctx.getNetworkType), Fee, Cap)

  private def mine(f: Fixture, ctx: BlockchainContext) = terms(ctx, f.wallet.p2pk.getPublicKey)

  private def stranger(ctx: BlockchainContext) =
    terms(ctx, ctx.newProverBuilder().withDLogSecret(java.math.BigInteger.valueOf(7007L)).build().getAddress.getPublicKey)

  private def box(ctx: BlockchainContext, contract: Contract, value: Long, txId: String, tokens: Token*): NodeBox =
    LDNodeFixtures.nodeBox(ctx, UTXO(contract, value, tokens), index = 0, txId = txId)

  private def hash(kind: lithosdex.contracts.LDOrderKind): String = LDOrderContracts.template(kind).templateHash

  private def mempoolTx(id: String, spends: String, outputs: NodeBox*): CompleteMempool.MempoolTx =
    CompleteMempool.MempoolTx(id, NodeTransaction(id, Seq(NodeInput(spends, NodeSpendingProof.empty)), Seq.empty, outputs), 200)

  private def expectNoReservation(f: Fixture): Unit = f.walletProbe.expectNoMessage(200.millis)

  // ══════════════════════════════════════════════════════════════════════════
  //  LISTING
  // ══════════════════════════════════════════════════════════════════════════

  "Listing orders" should "report each order's state from the mempool, unconfirmed placements first" in {
    import lithosdex.contracts.LDOrderKind._
    // Filled in once the boxes exist, which needs the fixture's context
    @volatile var snapshot = emptySnapshot
    @volatile var held = Seq.empty[CompleteMempool.MempoolTx]
    val f = fixture(snapshot, held)

    val (open, waiting, filling, cancelling, pending, evicted, strangers, walletInput, afterFill) = withCtx(f) { ctx =>
      val lit = Token(LDHelpers.getTokenY(ctx.getNetworkType), 500L * 1000000L)
      // The filling order asks for everything the pool paid before its fill, so the pool that fill leaves,
      // which is the mempool tip every order is priced against, can no longer pay it
      val quote = LDLiquidityPool(poolBox(ctx).toInputUTXO(ctx)).simSwap(erg, ergIn = true).amountOut
      (box(ctx, LDOrderContracts.swapSell(mine(f, ctx), erg, 1L), erg + Fee + erg / 1000, "a1" * 32),
        // A floor no pool reaches, so this one waits
        box(ctx, LDOrderContracts.swapSell(mine(f, ctx), erg, Long.MaxValue / 2), erg + Fee + erg / 1000, "a6" * 32),
        box(ctx, LDOrderContracts.swapSell(mine(f, ctx), erg, quote), erg + Fee + erg / 1000, "a2" * 32),
        box(ctx, LDOrderContracts.swapBuy(mine(f, ctx), 1L), erg / 1000, "a3" * 32, lit),
        box(ctx, LDOrderContracts.deposit(mine(f, ctx), erg, 1L), erg + LDHelpers.PROVISION_MIN + Fee + erg / 1000, "d1" * 32, lit),
        box(ctx, LDOrderContracts.deposit(mine(f, ctx), erg, 2L), erg + LDHelpers.PROVISION_MIN + Fee + erg / 1000, "e1" * 32, lit),
        box(ctx, LDOrderContracts.swapSell(stranger(ctx), erg, 1L), erg + Fee + erg / 1000, "a4" * 32),
        NodeBox("22" * 32, "99" * 32, erg, 0, 100, "00"),
        LDNodeFixtures.poolBox(ctx, ReservesX + erg, ReservesY - quote, txId = "f1" * 32))
    }
    withCtx(f) { ctx =>
      serve(f, afterFill)
      serveOrders(f,
        Map(hash(SwapSell) -> Seq(LDNodeFixtures.indexed(open, height = 480), LDNodeFixtures.indexed(filling, height = 481),
          LDNodeFixtures.indexed(strangers, height = 483), LDNodeFixtures.indexed(waiting, height = 479)),
          hash(SwapBuy) -> Seq(LDNodeFixtures.indexed(cancelling, height = 482))),
        Seq(open, waiting, filling, cancelling, strangers, walletInput))
      val fill = mempoolTx("f1" * 32, filling.boxId, afterFill)
      val refund = mempoolTx("f2" * 32, cancelling.boxId, NodeBox("33" * 32, "f2" * 32, erg, 0, 100, "00"))
      val placement = mempoolTx("d1" * 32, "11" * 32, pending)
      snapshot = CompleteMempool.Snapshot("00" * 32, Set(fill.id, refund.id, placement.id),
        Set(filling.boxId, cancelling.boxId, "11" * 32), System.nanoTime(), transactions = Vector(fill, refund, placement))
      // Carried into a candidate, so the node no longer holds it
      held = Seq(mempoolTx("e1" * 32, walletInput.boxId, evicted))
    }

    val orders = f.api.listOrders(f.cache).orders
    orders.map(o => o.boxId -> o.status).toMap shouldEqual Map(
      open.boxId -> "OPEN", waiting.boxId -> "OPEN", filling.boxId -> "FILLING", cancelling.boxId -> "CANCELLING",
      pending.boxId -> "PENDING", evicted.boxId -> "PENDING")
    orders.take(2).map(_.boxId).toSet shouldEqual Set(pending.boxId, evicted.boxId)
    orders.drop(2).map(_.boxId) shouldEqual Seq(cancelling.boxId, filling.boxId, open.boxId, waiting.boxId)

    val byId = orders.map(o => o.boxId -> o).toMap
    byId(filling.boxId).spendingTxId shouldEqual Some("f1" * 32)
    byId(cancelling.boxId).spendingTxId shouldEqual Some("f2" * 32)
    byId(pending.boxId).placementTxId shouldEqual "d1" * 32
    byId(evicted.boxId).placementTxId shouldEqual "e1" * 32
    byId(open.boxId).placedHeight shouldEqual Some(480)
    byId(pending.boxId).placedHeight shouldBe None
    byId(open.boxId).fillableNow shouldBe true
    byId(waiting.boxId).fillableNow shouldBe false
    // Its fill is in the mempool, so it is filling however the pool that fill leaves would price it
    byId(filling.boxId).fillableNow shouldBe true
    // Its floor of 1 prices at the tip, but a refund in the mempool means it will not fill
    byId(cancelling.boxId).fillableNow shouldBe false
    byId(open.boxId).`type` shouldEqual "SWAP"
    byId(open.boxId).ergIn shouldEqual Some(true)
    byId(cancelling.boxId).ergIn shouldEqual Some(false)
    byId(pending.boxId).minShares shouldEqual Some("1")
    byId(open.boxId).owner shouldEqual f.wallet.p2pk.toString
  }

  it should "leave out an evicted placement whose input something else now spends" in {
    import lithosdex.contracts.LDOrderKind._
    val f0 = fixture()
    val (evicted, input) = withCtx(f0) { ctx =>
      val lit = Token(LDHelpers.getTokenY(ctx.getNetworkType), 500L * 1000000L)
      (box(ctx, LDOrderContracts.deposit(mine(f0, ctx), erg, 1L), erg + LDHelpers.PROVISION_MIN + Fee + erg / 1000, "e1" * 32, lit),
        NodeBox("22" * 32, "99" * 32, erg, 0, 100, "00"))
    }
    val other = mempoolTx("c1" * 32, input.boxId)
    val f = fixture(CompleteMempool.Snapshot("00" * 32, Set(other.id), Set(input.boxId), System.nanoTime(),
      transactions = Vector(other)), held = Seq(mempoolTx("e1" * 32, input.boxId, evicted)))
    serveOrders(f, Map.empty, Seq(input))
    withCtx(f)(ctx => serve(f, poolBox(ctx)))

    f.api.listOrders(f.cache).orders shouldBe empty
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  REFUSED BEFORE ANYTHING IS RESERVED
  // ══════════════════════════════════════════════════════════════════════════

  "An order whose miner fee cap is not below its executor fee" should "be refused naming both" in {
    // A cap at the fee lets a broadcast fill spend all of it, so no broadcaster would take the order
    val f = fixture()
    withCtx(f)(ctx => serve(f, poolBox(ctx)))
    val thrown = intercept[LithosBadRequest](f.api.placeSwapOrder(
      LDSwapOrderExecuteRequest("1000000000", ergIn = true, "1", executorFee = Some("1000000"), maxMinerFee = Some("1000000")),
      f.cache))
    thrown.getMessage should include("maxMinerFee")
    thrown.getMessage should include("executorFee")
    expectNoReservation(f)
  }

  it should "be refused when the fault is in the configured defaults, naming where they come from" in {
    val nodeApi = mock[NodeApi]
    val (node, _, _) = FakeNodeContext(nodeApi, numAddresses = 1)
    val api = new DexAPIExecution(node, EngineFunding(TestProbe().ref, 2.seconds, ec),
      orderDefaults = configs.LithosDexOrdersConfig(executorFeeNanoErg = 1000000L, maxMinerFeeNanoErg = 2000000L))
    intercept[LithosBadRequest](api.checkSwapOrder(LDSwapOrderRequest("1000", ergIn = true), new LDCache(new FakeCache)))
      .getMessage should include("lithosdex.orders")
  }

  "A swap order with a zero floor" should "be refused outright" in {
    // `minOutput` is the order's only price protection once it sits in the mempool
    val f = fixture()
    withCtx(f)(ctx => serve(f, poolBox(ctx)))
    intercept[LithosBadRequest](f.api.placeSwapOrder(
      LDSwapOrderExecuteRequest("1000000000", ergIn = true, "0"), f.cache)).getMessage should include("minOutput")
    expectNoReservation(f)
  }

  "A deposit order with a zero share floor" should "be refused outright" in {
    val f = fixture()
    withCtx(f)(ctx => serve(f, poolBox(ctx)))
    intercept[LithosBadRequest](f.api.placeDepositOrder(
      LDDepositOrderExecuteRequest("1000000000", "300000000", "0"), f.cache)).getMessage should include("minShares")
    expectNoReservation(f)
  }

  "A swap order selling ERG" should "reserve the sale, the fee, the return and the network headroom" in {
    val f = fixture()
    withCtx(f)(ctx => serve(f, poolBox(ctx)))
    Future(Try(f.api.placeSwapOrder(LDSwapOrderExecuteRequest("1000000000", ergIn = true, "1"), f.cache)))
    val selection = f.walletProbe.expectMsgType[SelectInputs](10.seconds)
    selection.erg shouldBe erg + Fee + LDOrderTransactions.RETURNED + Parameters.MinFee * 3
    selection.tokens shouldBe empty
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  CANCELLING
  // ══════════════════════════════════════════════════════════════════════════

  "Cancelling an order an unconfirmed transaction already spends" should "be a conflict, not a second spend" in {
    val (order, fill) = {
      val f0 = fixture()
      withCtx(f0) { ctx =>
        val o = box(ctx, LDOrderContracts.swapSell(mine(f0, ctx), erg, 1L), erg + Fee + erg / 1000, "a1" * 32)
        (o, mempoolTx("f1" * 32, o.boxId, poolBox(ctx, txId = "f1" * 32)))
      }
    }
    val f = fixture(CompleteMempool.Snapshot("00" * 32, Set(fill.id), Set(order.boxId), System.nanoTime(),
      transactions = Vector(fill)))
    serveOrders(f, Map.empty, Seq(order))

    intercept[LithosStateChanged](f.api.cancelOrder(order.boxId, f.cache)).getMessage should include("f1" * 32)
    expectNoReservation(f)
  }

  "Cancelling an order another key owns" should "be not found, since this wallet cannot sign its refund" in {
    val f = fixture()
    val order = withCtx(f)(ctx => box(ctx, LDOrderContracts.swapSell(stranger(ctx), erg, 1L), erg + Fee + erg / 1000, "a4" * 32))
    serveOrders(f, Map.empty, Seq(order))
    intercept[LithosNotFound](f.api.cancelOrder(order.boxId, f.cache))
    expectNoReservation(f)
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  REDEEM ORDERS AND THE PROVISIONS THEY HOLD
  // ══════════════════════════════════════════════════════════════════════════

  private val Shares = 10000000000L
  private val Step: BigInt = LDHelpers.SCALE / 1000000

  "A redeem order quote" should "split earned fees into what the placement claims and what the fill loses" in {
    // The vault has settled one step; the pool has earned a second it has not flushed
    val f = fixture()
    val nft = ErgoId.create("f0" * 32)
    val provision = withCtx(f) { ctx =>
      serve(f, poolBox(ctx, accX = Step * 2), Some(LDNodeFixtures.vaultBox(ctx, 5L * erg, accX = Step)))
      LDNodeFixtures.provisionBox(ctx, Shares, nft)
    }
    when(f.nodeApi.unconfirmedInputByBoxId(anyString())).thenReturn(Success(None))
    when(f.nodeApi.indexedBoxById(provision.boxId)).thenReturn(Success(Some(LDNodeFixtures.indexed(provision))))

    val quote = f.api.checkRedeemOrder(LDRedeemOrderRequest(provision.boxId), f.cache)
    val perStep = (BigInt(Shares) * Step / LDHelpers.SCALE).toLong
    quote.claimableX shouldEqual perStep.toString
    quote.unflushedX shouldEqual perStep.toString
    quote.receivedX shouldEqual
      (quote.amountX.toLong + LDHelpers.PROVISION_MIN + LDOrderTransactions.RETURNED - Fee).toString
    quote.totalErgRequired shouldEqual LDOrderTransactions.RETURNED.toString
  }

  "Listing provisions" should "keep a provision whose NFT waits in a redeem order, unclaimable, naming the order" in {
    val f = fixture()
    val nft = ErgoId.create("f0" * 32)
    val (order, provision) = withCtx(f) { ctx =>
      serve(f, poolBox(ctx, accX = Step * 2), Some(LDNodeFixtures.vaultBox(ctx, 5L * erg, accX = Step)))
      (box(ctx, LDOrderContracts.redeem(mine(f, ctx)), erg / 1000, "a5" * 32, Token(nft, 1L)),
        LDNodeFixtures.provisionBox(ctx, Shares, nft))
    }
    serveOrders(f, Map(hash(lithosdex.contracts.LDOrderKind.Redeem) -> Seq(LDNodeFixtures.indexed(order))), Seq.empty)
    // The NFT is in the order, not the wallet
    when(f.nodeApi.walletBalances()).thenReturn(Success(_root_.node.model.WalletBalances(500, erg, Seq.empty)))
    withCtx(f) { ctx =>
      when(f.nodeApi.unspentBoxesByErgoTree(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
        .thenAnswer { inv =>
          if (inv.getArgument[Paging](1).offset == 0 && inv.getArgument[String](0) == DexContracts(ctx).provisionGuard.ergoTreeHex)
            Success(Seq(LDNodeFixtures.indexed(provision)))
          else Success(Seq.empty[IndexedBox])
        }
    }

    val listed = f.api.listProvisions(f.cache).provisions
    listed.map(_.boxId) shouldEqual Seq(provision.boxId)
    listed.head.redeemOrderBoxId shouldEqual Some(order.boxId)
    // The vault has settled fees it would otherwise accept a claim for
    listed.head.canClaim shouldBe false
  }
}
