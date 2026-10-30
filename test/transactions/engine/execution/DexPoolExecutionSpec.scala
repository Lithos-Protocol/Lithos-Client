package transactions.engine.execution

import akka.actor.ActorSystem
import akka.testkit.{TestKit, TestProbe}
import api.LithosApiErrors.{LithosBadRequest, LithosNotFound, LithosUnavailable, LithosUnprocessable}
import api.models._
import api.{GenesisVerdict, IndexedPool, LDPoolIndex, PoolIndexSnapshot}
import configs.{NodeContext, VerifiedPool}
import lithosdex.contracts.{LDContracts, LDT2TContracts}
import lithosdex.{LDHelpers, LDLiquidityPool, LDT2TLiquidityPool}
import node.MutationConversions._
import node.NodeApi
import node.model._
import org.ergoplatform.appkit.scalaapi.scalaLongType
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue, NetworkType, Parameters}
import org.ergoplatform.sdk.ErgoId
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.when
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import sigma.Colls
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

  /** @param uncheckedReads what checking the unchecked pool's genesis at placement finds */
  private def fixture(status: String = "ready", uncheckedReads: Option[GenesisVerdict] = sound): Fixture = {
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
        unchecked.poolNFT.toString -> n2tBox(unchecked, 3))
    }
    when(api.unspentBoxesByTokenId(anyString(), any[Paging], any[SortDirection], any[MempoolOptions])).thenAnswer { inv =>
      Success(boxes.get(inv.getArgument[String](0)).map(LDNodeFixtures.indexed(_)).toSeq)
    }
    when(api.unconfirmedInputByBoxId(anyString())).thenReturn(Success(None))
    when(api.indexedBoxById(anyString())).thenReturn(Success(None))
    when(api.unconfirmedOutputByBoxId(anyString())).thenReturn(Success(None))
    val index = new LDPoolIndex(node, system) {
      override def snapshot(): PoolIndexSnapshot = PoolIndexSnapshot(status, 100, truncated = false,
        if (status == "ready") pools else Seq.empty, Map.empty)
      // The unchecked pool is read at placement; the others keep what the snapshot says
      override def genesisNow(pool: IndexedPool): Option[GenesisVerdict] =
        if (pool.verified) None else if (pool.nft == unchecked.poolNFT.toString) uncheckedReads else pool.genesis
    }
    val probe = TestProbe()
    Fixture(new DexPoolExecution(node, EngineFunding(probe.ref, 2.seconds, ec), index), api, node, probe)
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

  "An ERG/token deposit quote" should "split the offer as the executor will, on a pool other than ERG:LIT" in {
    val f = fixture()
    val q = f.exec.checkDeposit(n2t.poolNFT.toString, LDPoolDepositRequest("2000000000", "300000000"))
    val split = poolOf(f, n2t.poolNFT)(box => LithosDexExecution.depositSplit(LDLiquidityPool(box), 2000000000L, 300000000L))
    q.shares shouldBe split.shares.toString
    q.excessY shouldBe split.excessY.toString
    q.totalErgRequired shouldBe LDOrderTransactions.depositValue(2000000000L, Fee).toString
    q.tokensRequired shouldBe Seq(LDTokenAmount(tokenY.toString, "300000000"))
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
