package transactions.engine.execution

import akka.actor.ActorSystem
import akka.testkit.{TestKit, TestProbe}
import api.LithosApiErrors.{LithosBadRequest, LithosNotFound, LithosUnavailable, LithosUnprocessable}
import api.models._
import api.{GenesisProvision, GenesisVerdict, IndexedPool, LDPoolIndex, PoolIndexSnapshot}
import configs.{NodeContext, VerifiedPool}
import lithosdex.contracts.{LDContracts, LDT2TContracts}
import lithosdex.{LDHelpers, LDLiquidityPool, LDT2TLiquidityPool}
import node.MutationConversions._
import node.NodeApi
import node.model._
import org.ergoplatform.appkit.scalaapi.{scalaByteType, scalaLongType}
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue, NetworkType, Parameters}
import org.ergoplatform.sdk.ErgoId
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.{never, times, verify, when}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import sigma.Colls
import lithosdex.contracts.{LDOrderContracts, LDOrderTerms}
import org.ergoplatform.appkit.Address
import state.synchronization.CompleteMempool
import support.{FakeNodeContext, LDNodeFixtures}
import transactions.batching.lithosdex.{LDBoxes, LDOrderTransactions, LithosDexExecution}
import transactions.engine.wallet.EngineFunding
import transactions.engine.wallet.EngineWalletMessages.SelectInputs
import work.lithos.mutations.{Token, UTXO}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Try}

/**
 * Orders on any pool, against a fixed index snapshot and a mocked node: what each quote says for both kinds,
 * which pools refuse a placement before any wallet funds are reserved, and what a placement reserves.
 */
class DexPoolExecutionSpec extends TestKit(ActorSystem("dex-pool-execution-spec"))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  private implicit val ec: ExecutionContext = system.dispatcher

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val Fee = configs.LithosDexOrdersConfig.Default.executorFeeNanoErg
  private val tokenX = ErgoId.create("e4" * 32)
  private val tokenY = ErgoId.create("e5" * 32)
  private val t2t = LDT2TContracts(ErgoId.create("e1" * 32), ErgoId.create("e2" * 32), ErgoId.create("e3" * 32))
  private val n2t = LDContracts(ErgoId.create("d1" * 32), ErgoId.create("d2" * 32), ErgoId.create("d3" * 32),
    NetworkType.MAINNET)
  private val drained = LDContracts(ErgoId.create("c1" * 32), ErgoId.create("c2" * 32), ErgoId.create("c3" * 32),
    NetworkType.MAINNET)
  private val unchecked = LDContracts(ErgoId.create("b1" * 32), ErgoId.create("b2" * 32), ErgoId.create("b3" * 32),
    NetworkType.MAINNET)
  private val sound = Some(GenesisVerdict(Seq.empty, Seq.empty))

  private def longs(v: Array[Long]): ErgoValue[_] = ErgoValue.of(Colls.fromArray(v), scalaLongType)
  private def zero: ErgoValue[_] = ErgoValue.of(BigInt(0).bigInteger)

  private def indexed(kind: VerifiedPool.Kind, nft: ErgoId, genesis: Option[GenesisVerdict],
                      n: Option[LDContracts], t: Option[LDT2TContracts]): IndexedPool =
    IndexedPool(kind, nft.toString, "00" * 32, 100, None, tokenY.toString, 0L, 0L, 0L, 0L, 0L, Seq.empty, 0L,
      "", "", verified = false, genesis, n, t)

  private val pools = Seq(
    indexed(VerifiedPool.Kind.T2T, t2t.poolNFT, sound, None, Some(t2t)),
    indexed(VerifiedPool.Kind.N2T, n2t.poolNFT, sound, Some(n2t), None),
    indexed(VerifiedPool.Kind.N2T, drained.poolNFT, Some(GenesisVerdict(Seq("pool NFT minted 2"), Seq.empty)),
      Some(drained), None),
    indexed(VerifiedPool.Kind.N2T, unchecked.poolNFT, None, Some(unchecked), None))

  private case class Fixture(exec: DexPoolExecution, nodeApi: NodeApi, node: NodeContext, wallet: TestProbe)

  /**
   * @param uncheckedReads what checking the unchecked pool's genesis at placement finds
   * @param mempool        the observation order listings read, in place of the engine's
   */
  private def fixture(status: String = "ready", uncheckedReads: Option[GenesisVerdict] = sound,
                      mempool: Option[CompleteMempool.Snapshot] = None,
                      n2tGenesis: Seq[GenesisProvision] = Seq.empty): Fixture = {
    val api = mock[NodeApi]
    val (node, _, _) = FakeNodeContext(api, numAddresses = 1)
    val boxes = node.getClient.execute { ctx: BlockchainContext =>
      def n2tBox(c: LDContracts, index: Int) = LDNodeFixtures.nodeBox(ctx, UTXO(c.liquidityPool, 10L * Parameters.OneErg,
        Seq(Token(c.poolNFT, 1L), Token(tokenY, 1000000000L), Token(c.provToken, 1000L)),
        Seq(ErgoValue.of(LDHelpers.LOCKED_LP - 1000000000L), longs(LDHelpers.GENESIS_FEE_PARAMS), longs(Array(0L, 0L)),
          zero, zero)), index = index)
      Map(
        t2t.poolNFT.toString -> LDNodeFixtures.nodeBox(ctx, UTXO(t2t.liquidityPool, LDHelpers.T2T_POOL_MIN_VALUE,
          Seq(Token(t2t.poolNFT, 1L), Token(tokenX, 50000000000L), Token(tokenY, 20000000000L), Token(t2t.provToken, 1000L)),
          Seq(ErgoValue.of(LDHelpers.LOCKED_LP - 30000000000L), longs(LDHelpers.GENESIS_FEE_PARAMS), longs(Array(0L, 0L)),
            zero, zero))),
        n2t.poolNFT.toString -> n2tBox(n2t, 1),
        drained.poolNFT.toString -> n2tBox(drained, 2),
        unchecked.poolNFT.toString -> n2tBox(unchecked, 3),
        // Three X and two Y owed per share since genesis, beyond the one unit of each a vault is seeded with
        t2t.vaultNFT.toString -> LDNodeFixtures.nodeBox(ctx, UTXO(t2t.feeVault, 2L * Parameters.OneErg,
          Seq(Token(t2t.vaultNFT, 1L), Token(tokenX, 1000001L), Token(tokenY, 1000001L)),
          Seq(ErgoValue.of((LDHelpers.SCALE * 3).bigInteger), ErgoValue.of((LDHelpers.SCALE * 2).bigInteger))), index = 4),
        n2t.vaultNFT.toString -> LDNodeFixtures.nodeBox(ctx, UTXO(n2t.feeVault, Parameters.OneErg,
          Seq(Token(n2t.vaultNFT, 1L)), Seq(zero, zero)), index = 5))
    }
    when(api.unspentBoxesByTokenId(anyString(), any[Paging], any[SortDirection], any[MempoolOptions])).thenAnswer { inv =>
      Success(boxes.get(inv.getArgument[String](0)).map(LDNodeFixtures.indexed(_)).toSeq)
    }
    when(api.unconfirmedInputByBoxId(anyString())).thenReturn(Success(None))
    when(api.indexedBoxById(anyString())).thenReturn(Success(None))
    when(api.unconfirmedOutputByBoxId(anyString())).thenReturn(Success(None))
    val index = new LDPoolIndex(node, system) {
      override def snapshot(): PoolIndexSnapshot = PoolIndexSnapshot(status, 100, truncated = false,
        if (status == "ready") pools.map(p => if (p.nft == n2t.poolNFT.toString) p.copy(genesisProvisions = n2tGenesis) else p)
        else Seq.empty, Map.empty)
      // The unchecked pool is read at placement; the others keep what the snapshot says
      override def genesisNow(pool: IndexedPool): Option[GenesisVerdict] =
        if (pool.verified) None else if (pool.nft == unchecked.poolNFT.toString) uncheckedReads else pool.genesis
    }
    val probe = TestProbe()
    val exec = new DexPoolExecution(node, EngineFunding(probe.ref, 2.seconds, ec), index) {
      override protected def mempoolSnapshot(): CompleteMempool.Snapshot = mempool.getOrElse(super.mempoolSnapshot())
    }
    Fixture(exec, api, node, probe)
  }

  private def poolOf[A](f: Fixture, nft: ErgoId)(read: work.lithos.mutations.InputUTXO => A): A =
    f.node.getClient.execute { ctx: BlockchainContext =>
      read(f.nodeApi.unspentBoxesByTokenId(nft.toString, Paging(0, 1), SortDirection.Desc, MempoolOptions.WithMempool)
        .get.head.toInputUTXO(ctx))
    }

  "A token/token swap quote" should "sell the token named for the pool's other one, at the pool's own price" in {
    val f = fixture()
    val q = f.exec.checkSwap(t2t.poolNFT.toString, LDPoolSwapRequest(tokenY.toString, "400000000"))
    val expected = poolOf(f, t2t.poolNFT)(LDT2TLiquidityPool(_)).simSwap(400000000L, xIn = false)
    q.tokenOut shouldBe tokenX.toString
    q.amountOut shouldBe expected.amountOut.toString
    q.netOutput shouldBe q.amountOut
    q.totalErgRequired shouldBe LDOrderTransactions.t2tOrderValue(Fee).toString
    q.tokensRequired shouldBe Seq(LDTokenAmount(tokenY.toString, "400000000"))
  }

  it should "refuse a token the pool does not hold, and ERG" in {
    val f = fixture()
    a[LithosBadRequest] should be thrownBy f.exec.checkSwap(t2t.poolNFT.toString, LDPoolSwapRequest("ERG", "10"))
    a[LithosBadRequest] should be thrownBy f.exec.checkSwap(t2t.poolNFT.toString, LDPoolSwapRequest("aa" * 32, "10"))
  }

  "A swap quote" should "name the least input that still buys its output, on both kinds" in {
    val f = fixture()
    val t2tPool = poolOf(f, t2t.poolNFT)(LDT2TLiquidityPool(_))
    val t = f.exec.checkSwap(t2t.poolNFT.toString, LDPoolSwapRequest(tokenY.toString, "400000000"))
    val tLeast = t.minAmountIn.getOrElse(fail("no least input")).toLong
    tLeast should be <= 400000000L
    t2tPool.simSwap(tLeast, xIn = false).amountOut.toString shouldBe t.amountOut
    t2tPool.simSwap(tLeast - 1L, xIn = false).amountOut.toString should not be t.amountOut
    // Buying tokens worth about 10 nanoERG each with ERG, where the last few nanoERG may buy nothing
    val n2tPool = poolOf(f, n2t.poolNFT)(LDLiquidityPool(_))
    val n = f.exec.checkSwap(n2t.poolNFT.toString, LDPoolSwapRequest("ERG", "1000000009"))
    val nLeast = n.minAmountIn.getOrElse(fail("no least input")).toLong
    nLeast should be <= 1000000009L
    n2tPool.simSwap(nLeast, ergIn = true).amountOut.toString shouldBe n.amountOut
    n2tPool.simSwap(nLeast - 1L, ergIn = true).amountOut.toString should not be n.amountOut
  }

  "An ERG/token deposit quote" should "split the offer as the executor will, on a pool other than ERG:LIT" in {
    val f = fixture()
    val q = f.exec.checkDeposit(n2t.poolNFT.toString, LDPoolDepositRequest("2000000000", "300000000"))
    val split = poolOf(f, n2t.poolNFT)(box => LithosDexExecution.depositSplit(LDLiquidityPool(box), 2000000000L, 300000000L))
    q.shares shouldBe split.shares.toString
    q.excessY shouldBe split.excessY.toString
    q.totalErgRequired shouldBe LDOrderTransactions.depositValue(2000000000L, Fee).toString
    q.tokensRequired shouldBe Seq(LDTokenAmount(tokenY.toString, "300000000"))
  }

  "This wallet's orders on a pool" should "be its own orders of the pool's kind on that pool, and no one else's" in {
    val f = fixture(mempool = Some(CompleteMempool.Snapshot("ce" * 32, Set.empty, Set.empty, System.nanoTime())))
    val orders = f.node.getClient.execute { ctx: BlockchainContext =>
      val me = f.node.getNodeWallet.p2pk
      val other = FakeNodeContext.proverWith(f.node.getClient, 2).getEip3Addresses.get(1)
      def swap(owner: Address, pool: ErgoId, index: Int) = LDNodeFixtures.nodeBox(ctx,
        UTXO(LDOrderContracts.t2tSwap(LDOrderTerms(owner.getPublicKey, pool, Fee, Fee / 3), 1L),
          LDOrderTransactions.t2tOrderValue(Fee), Seq(Token(tokenX, 1000000L))), index = index, txId = "f7" * 32)
      def deposit(index: Int) = LDNodeFixtures.nodeBox(ctx,
        UTXO(LDOrderContracts.deposit(LDOrderTerms(me.getPublicKey, n2t.poolNFT, Fee, Fee / 3), Parameters.OneErg, 1L),
          LDOrderTransactions.depositValue(Parameters.OneErg, Fee), Seq(Token(tokenY, 100000000L))),
          index = index, txId = "f7" * 32)
      Seq(swap(me, t2t.poolNFT, 0), swap(other, t2t.poolNFT, 1), swap(me, drained.poolNFT, 2), deposit(3))
    }
    when(f.nodeApi.unspentBoxesByTemplateHash(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenReturn(Success(orders.map(LDNodeFixtures.indexed(_))))
    when(f.nodeApi.boxesWithPoolByIds(any[Seq[String]])).thenAnswer { inv =>
      val ids = inv.getArgument[Seq[String]](0).toSet
      Success(orders.filter(o => ids.contains(o.boxId)))
    }

    val t2tOrders = f.exec.listOrders(t2t.poolNFT.toString).orders
    t2tOrders.map(_.boxId) shouldBe Seq(orders.head.boxId)
    val swap = t2tOrders.head
    (swap.`type`, swap.status, swap.tokenIn, swap.ergIn, swap.amountIn) shouldBe
      (("SWAP", "OPEN", Some(tokenX.toString), None, Some("1000000")))
    swap.fillableNow shouldBe true
    // The ERG/token pool's listing reads ERG/token orders only, so the deposit is its one order there
    val n2tOrders = f.exec.listOrders(n2t.poolNFT.toString).orders
    n2tOrders.map(o => (o.boxId, o.`type`, o.amountX)) shouldBe
      Seq((orders(3).boxId, "DEPOSIT", Some(Parameters.OneErg.toString)))
  }

  "This wallet's provisions on a pool" should "be those whose NFT it holds or keeps in a redeem order there" in {
    val f = fixture()
    val (held, ordered, stranger) = (ErgoId.create("a1" * 32), ErgoId.create("a2" * 32), ErgoId.create("a3" * 32))
    val (provisions, order) = f.node.getClient.execute { ctx: BlockchainContext =>
      def provision(owner: ErgoId, index: Int) = LDNodeFixtures.nodeBox(ctx, UTXO(t2t.provisionGuard,
        LDHelpers.PROVISION_MIN, Seq(Token(t2t.provToken, 1L)), Seq(zero, zero,
          ErgoValue.of(Colls.fromArray(owner.getBytes), scalaByteType), ErgoValue.of(1000L))), index = index,
        txId = "f8" * 32)
      val me = f.node.getNodeWallet.p2pk
      (Seq(provision(held, 0), provision(ordered, 1), provision(stranger, 2)),
        LDNodeFixtures.nodeBox(ctx, UTXO(LDOrderContracts.t2tRedeem(LDOrderTerms(me.getPublicKey, t2t.poolNFT, Fee,
          Fee / 3)), LDOrderTransactions.t2tOrderValue(Fee), Seq(Token(ordered, 1L))), txId = "f9" * 32))
    }
    when(f.nodeApi.unspentBoxesByErgoTree(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenAnswer { inv =>
        Success(if (inv.getArgument[String](0) == t2t.provisionGuard.ergoTreeHex) provisions.map(LDNodeFixtures.indexed(_))
        else Seq.empty)
      }
    when(f.nodeApi.unspentBoxesByTemplateHash(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenReturn(Success(Seq(LDNodeFixtures.indexed(order))))
    when(f.nodeApi.walletBalances()).thenReturn(Success(_root_.node.model.WalletBalances(500, Parameters.OneErg,
      Seq(NodeAsset(held.toString, 1L), NodeAsset(tokenX.toString, 5L)))))

    val listed = f.exec.listProvisions(t2t.poolNFT.toString).provisions
    listed.map(p => (p.boxId, p.redeemOrderBoxId)) should contain theSameElementsAs Seq(
      provisions.head.boxId -> None, provisions(1).boxId -> Some(order.boxId))
    val accrued = poolOf(f, t2t.poolNFT)(LDT2TLiquidityPool(_)).feesAccrued(1000L, BigInt(0), BigInt(0))
    listed.foreach { p =>
      (p.claimableX, p.claimableY, p.accruedX, p.accruedY) shouldBe
        (("3000", "2000", accrued._1.toString, accrued._2.toString))
    }
    // Only the provision whose NFT is in the wallet can be claimed; the other waits on its redeem order
    listed.map(p => p.boxId -> p.canClaim).toMap shouldBe Map(provisions.head.boxId -> true, provisions(1).boxId -> false)
    // The ERG/token pool's guard holds none of them
    f.exec.listProvisions(n2t.poolNFT.toString).provisions shouldBe empty
  }

  "A provision NFT lookup" should "name only the tokens a provision of the pool their id names is owned by" in {
    val f = fixture()
    val (held, ghost, provision) = f.node.getClient.execute { ctx: BlockchainContext =>
      // Earlier pool boxes: an ownership NFT's id is the id of the pool box its deposit spent
      def historic(c: LDContracts, index: Int) = LDNodeFixtures.nodeBox(ctx, UTXO(c.liquidityPool, 9L * Parameters.OneErg,
        Seq(Token(c.poolNFT, 1L), Token(tokenY, 900000000L), Token(c.provToken, 1001L)),
        Seq(ErgoValue.of(LDHelpers.LOCKED_LP - 999000000L), longs(LDHelpers.GENESIS_FEE_PARAMS), longs(Array(0L, 0L)),
          zero, zero)), index = index, txId = "c7" * 32)
      val t2tHistoric = LDNodeFixtures.nodeBox(ctx, UTXO(t2t.liquidityPool, LDHelpers.T2T_POOL_MIN_VALUE,
        Seq(Token(t2t.poolNFT, 1L), Token(tokenX, 40000000000L), Token(tokenY, 10000000000L), Token(t2t.provToken, 1001L)),
        Seq(ErgoValue.of(LDHelpers.LOCKED_LP - 20000000000L), longs(LDHelpers.GENESIS_FEE_PARAMS), longs(Array(0L, 0L)),
          zero, zero)), index = 3, txId = "c8" * 32)
      val n2tHistoric = historic(n2t, 2)
      val prov = LDNodeFixtures.nodeBox(ctx, UTXO(n2t.provisionGuard, LDHelpers.PROVISION_MIN, Seq(Token(n2t.provToken, 1L)),
        Seq(zero, zero, ErgoValue.of(Colls.fromArray(ErgoId.create(n2tHistoric.boxId).getBytes), scalaByteType),
          ErgoValue.of(1000L))), index = 0, txId = "c9" * 32)
      (n2tHistoric, t2tHistoric, prov)
    }
    val stranger = "f0" * 32
    val boxes = Map(held.boxId -> held, ghost.boxId -> ghost)
    when(f.nodeApi.indexedBoxById(anyString())).thenAnswer(inv =>
      Success(boxes.get(inv.getArgument[String](0)).map(LDNodeFixtures.indexed(_))))
    when(f.nodeApi.unspentBoxesByErgoTree(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenAnswer { inv =>
        Success(if (inv.getArgument[String](0) == n2t.provisionGuard.ergoTreeHex) Seq(LDNodeFixtures.indexed(provision))
        else Seq.empty)
      }

    val found = f.exec.lookupNfts(Seq(held.boxId, ghost.boxId.toUpperCase, stranger))
    found.keySet shouldBe Set(held.boxId)
    val live = found(held.boxId).live.get
    val expected = poolOf(f, n2t.poolNFT)(LDLiquidityPool(_)).simRedeem(1000L)
    (found(held.boxId).pool.nft, live.provision.shares, live.provision.boxId, live.amountX, live.amountY) shouldBe
      ((n2t.poolNFT.toString, "1000", provision.boxId, expected.amountX, expected.amountY))
    withClue("an ERG/token provision has no refresh bounty, and anyone may refresh it once 525,600 blocks old: ") {
      live.refreshBounty shouldBe None
      live.refreshableHeight shouldBe live.provision.createdHeight + LDHelpers.REFRESH_AGE + 1
    }
    a[LithosBadRequest] should be thrownBy f.exec.lookupNfts(Seq.fill(101)("ab").zipWithIndex.map { case (_, i) => f"$i%064x" })
    a[LithosBadRequest] should be thrownBy f.exec.lookupNfts(Seq("not-an-id"))
  }

  it should "say how each NFT was minted, name a redeemed one, find a creator's, and read each once a tip" in {
    val creator = "a7" * 32
    val f = fixture(n2tGenesis = Seq(GenesisProvision(creator, 90, LDHelpers.GENESIS_SUPPLY - LDHelpers.MIN_SUPPLY,
      10L * Parameters.OneErg, 1000000000L)))
    def provisionOf(ctx: BlockchainContext, owner: String, entry: BigInt, index: Int, txId: String) =
      LDNodeFixtures.nodeBox(ctx, UTXO(n2t.provisionGuard, LDHelpers.PROVISION_MIN, Seq(Token(n2t.provToken, 1L)),
        Seq(ErgoValue.of(entry.bigInteger), zero, ErgoValue.of(Colls.fromArray(ErgoId.create(owner).getBytes), scalaByteType),
          ErgoValue.of(1000L))), index = index, txId = txId)
    val (claimed, redeemed, swapped, deposits, live) = f.node.getClient.execute { ctx: BlockchainContext =>
      def before(index: Int, txId: String) = LDNodeFixtures.nodeBox(ctx, UTXO(n2t.liquidityPool, 9L * Parameters.OneErg,
        Seq(Token(n2t.poolNFT, 1L), Token(tokenY, 900000000L), Token(n2t.provToken, 1001L)),
        Seq(ErgoValue.of(LDHelpers.LOCKED_LP - 999000000L), longs(LDHelpers.GENESIS_FEE_PARAMS), longs(Array(0L, 0L)),
          zero, zero)), index = index, txId = txId)
      val after = LDNodeFixtures.nodeBox(ctx, UTXO(n2t.liquidityPool, 10L * Parameters.OneErg,
        Seq(Token(n2t.poolNFT, 1L), Token(tokenY, 1000000000L), Token(n2t.provToken, 1000L)),
        Seq(ErgoValue.of(LDHelpers.LOCKED_LP - 1000000000L), longs(LDHelpers.GENESIS_FEE_PARAMS), longs(Array(0L, 0L)),
          zero, zero)), index = 0, txId = "d7" * 32)
      val (c, r, s) = (before(2, "c1" * 32), before(3, "c2" * 32), before(4, "c3" * 32))
      def deposit(spent: NodeBox, txId: String) = IndexedTransaction(txId, Seq(LDNodeFixtures.indexed(spent)), Seq.empty,
        Seq(LDNodeFixtures.indexed(after), LDNodeFixtures.indexed(provisionOf(ctx, spent.boxId, 0, 1, txId))), 80, 1,
        "block", 0L, 0, 1L, 100)
      val swap = IndexedTransaction("e9" * 32, Seq(LDNodeFixtures.indexed(s)), Seq.empty,
        Seq(LDNodeFixtures.indexed(after), LDNodeFixtures.indexed(after.copy(boxId = "e8" * 32))), 80, 1, "block", 0L, 0, 1L, 100)
      // The claimed NFT's provision has moved its entry since; the creator's sits as genesis made it
      val provisions = Seq(provisionOf(ctx, c.boxId, LDHelpers.SCALE, 0, "f1" * 32), provisionOf(ctx, creator, 0, 0, "f2" * 32))
      (c, r, s, Map(c.boxId -> deposit(c, "d1" * 32), r.boxId -> deposit(r, "d2" * 32), s.boxId -> swap), provisions)
    }
    val spentBy = Map(claimed.boxId -> "d1" * 32, redeemed.boxId -> "d2" * 32, swapped.boxId -> "e9" * 32)
    val boxes = Seq(claimed, redeemed, swapped).map(b => b.boxId -> b).toMap
    when(f.nodeApi.indexedBoxById(anyString())).thenAnswer(inv => Success(boxes.get(inv.getArgument[String](0))
      .map(b => LDNodeFixtures.indexed(b).copy(spentTransactionId = spentBy.get(b.boxId)))))
    when(f.nodeApi.indexedTransactionById(anyString())).thenAnswer(inv =>
      Success(deposits.values.find(_.id == inv.getArgument[String](0))))
    when(f.nodeApi.unspentBoxesByErgoTree(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenAnswer { inv =>
        Success(if (inv.getArgument[String](0) == n2t.provisionGuard.ergoTreeHex) live.map(LDNodeFixtures.indexed(_))
        else Seq.empty)
      }

    val ids = Seq(claimed.boxId, redeemed.boxId, swapped.boxId, creator)
    val found = f.exec.lookupNfts(ids)
    withClue("a pool box a swap spent minted nothing it can own: ") {
      found.keySet shouldBe Set(claimed.boxId, redeemed.boxId, creator)
    }
    val deposited = found(claimed.boxId).mint.get
    (deposited.height, deposited.shares, deposited.amountX, deposited.amountY, deposited.genesis) shouldBe
      ((80, 1000L, Parameters.OneErg, 100000000L, false))
    found(claimed.boxId).live.get.lastClaimedHeight shouldBe Some(found(claimed.boxId).live.get.provision.createdHeight)
    withClue("redeemed: its deposit made it an owner, and no provision names it now: ") {
      found(redeemed.boxId).live shouldBe None
      found(redeemed.boxId).mint.map(_.amountY) shouldBe Some(100000000L)
    }
    withClue("the creator's NFT, minted apart from the pool, is found from the genesis the index holds: ") {
      found(creator).mint.map(m => (m.genesis, m.amountX)) shouldBe Some((true, 10L * Parameters.OneErg))
      found(creator).live.get.lastClaimedHeight shouldBe None
      verify(f.nodeApi, never()).indexedBoxById(creator)
    }

    f.exec.lookupNfts(ids).keySet shouldBe found.keySet
    withClue("a second lookup at the same tip reads nothing again: ") {
      verify(f.nodeApi, times(1)).unspentBoxesByErgoTree(anyString(), any[Paging], any[SortDirection], any[MempoolOptions])
      verify(f.nodeApi, times(1)).indexedBoxById(claimed.boxId)
    }
  }

  it should "find a creator's NFT by the pool its description names when the index has no genesis for it" in {
    val creator = "a8" * 32
    val liar = "a9" * 32
    val f = fixture()
    val provision = f.node.getClient.execute { ctx: BlockchainContext =>
      LDNodeFixtures.nodeBox(ctx, UTXO(n2t.provisionGuard, LDHelpers.PROVISION_MIN, Seq(Token(n2t.provToken, 1L)),
        Seq(zero, zero, ErgoValue.of(Colls.fromArray(ErgoId.create(creator).getBytes), scalaByteType),
          ErgoValue.of(999000000000L))), index = 0, txId = "f3" * 32)
    }
    // Both name the pool; only one is named by a provision under its guard
    when(f.nodeApi.tokensByIds(any[Seq[String]])).thenReturn(Success(Seq(
      TokenInfo(creator, "00" * 32, 1L, "LD-NFT-a8a8a8", s"""{"type":"LithosDex Provision","pool":"${n2t.poolNFT}"}""", 0),
      TokenInfo(liar, "00" * 32, 1L, "LD-NFT-a9a9a9", s"LithosDex provision of 5 shares in pool ${n2t.poolNFT}", 0))))
    when(f.nodeApi.unspentBoxesByErgoTree(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenAnswer { inv =>
        Success(if (inv.getArgument[String](0) == n2t.provisionGuard.ergoTreeHex) Seq(LDNodeFixtures.indexed(provision))
        else Seq.empty)
      }
    val found = f.exec.lookupNfts(Seq(creator, liar))
    found.keySet shouldBe Set(creator)
    found(creator).live.map(_.provision.shares) shouldBe Some("999000000000")
  }

  "A pool" should "be not found when the index does not list it, and unavailable while the index builds" in {
    a[LithosNotFound] should be thrownBy fixture().exec.checkSwap("99" * 32, LDPoolSwapRequest("ERG", "10"))
    a[LithosUnavailable] should be thrownBy fixture(status = "building").exec.checkSwap(n2t.poolNFT.toString,
      LDPoolSwapRequest("ERG", "10"))
  }

  private val Ack = Some(true)

  "A placement" should "be refused on a pool that can be drained, before any funds are reserved" in {
    val f = fixture()
    val e = the[LithosUnprocessable] thrownBy f.exec.placeSwap(drained.poolNFT.toString,
      LDPoolSwapExecuteRequest("ERG", "1000000000", "1", acknowledgeUnverified = Ack))
    e.getMessage should include("pool NFT minted 2")
    f.wallet.expectNoMessage(200.millis)
  }

  it should "need acknowledgeUnverified on a pool the verified list does not name" in {
    val f = fixture()
    val e = the[LithosBadRequest] thrownBy f.exec.placeSwap(t2t.poolNFT.toString,
      LDPoolSwapExecuteRequest(tokenX.toString, "1000000000", "1"))
    e.getMessage should include("acknowledgeUnverified")
    a[LithosBadRequest] should be thrownBy f.exec.placeDeposit(n2t.poolNFT.toString,
      LDPoolDepositExecuteRequest("2000000000", "300000000", "1", acknowledgeUnverified = Some(false)))
    f.wallet.expectNoMessage(200.millis)
  }

  it should "check an unchecked pool's genesis there and then, and go ahead when it is sound" in {
    val f = fixture()
    Future(Try(f.exec.placeSwap(unchecked.poolNFT.toString,
      LDPoolSwapExecuteRequest("ERG", "1000000000", "1", acknowledgeUnverified = Ack))))
    f.wallet.expectMsgType[SelectInputs](10.seconds).erg should be > 1000000000L
  }

  it should "refuse an unchecked pool the check finds drainable, and wait when the node cannot answer" in {
    val drainable = fixture(uncheckedReads = Some(GenesisVerdict(Seq("vault NFT minted 3"), Seq.empty)))
    the[LithosUnprocessable].thrownBy(drainable.exec.placeSwap(unchecked.poolNFT.toString,
      LDPoolSwapExecuteRequest("ERG", "1000000000", "1", acknowledgeUnverified = Ack))).getMessage should
      include("vault NFT minted 3")
    val unreadable = fixture(uncheckedReads = None)
    a[LithosUnavailable] should be thrownBy unreadable.exec.placeSwap(unchecked.poolNFT.toString,
      LDPoolSwapExecuteRequest("ERG", "1000000000", "1", acknowledgeUnverified = Ack))
    drainable.wallet.expectNoMessage(200.millis)
    unreadable.wallet.expectNoMessage(200.millis)
  }

  it should "reserve a token/token swap's token, its fee, its return and the network headroom" in {
    val f = fixture()
    Future(Try(f.exec.placeSwap(t2t.poolNFT.toString,
      LDPoolSwapExecuteRequest(tokenX.toString, "1000000000", "1", acknowledgeUnverified = Ack))))
    val selection = f.wallet.expectMsgType[SelectInputs](10.seconds)
    selection.erg shouldBe Fee + LDOrderTransactions.RETURNED + Parameters.MinFee * 3
    selection.tokens shouldBe Seq(Token(tokenX, 1000000000L))
  }

  "A redeem" should "never be refused for its pool's genesis, so liquidity can always leave" in {
    // Past every pool check, it stops only at the provision lookup, which this fixture leaves empty
    val f = fixture()
    a[LDBoxes.NoSuchBoxException] should be thrownBy f.exec.placeRedeem(drained.poolNFT.toString,
      LDPoolRedeemRequest("ab" * 32))
  }
}
