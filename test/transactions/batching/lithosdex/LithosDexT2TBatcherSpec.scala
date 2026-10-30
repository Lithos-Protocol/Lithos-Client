package transactions.batching.lithosdex

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestActor, TestKit, TestProbe}
import configs.{BatchingConfig, LithosDexBatchingConfig}
import io.circe.parser.parse
import lithosdex.LDHelpers
import lithosdex.contracts.{LDContracts, LDOrderContracts, LDOrderTerms, LDT2TContracts}
import node.model._
import node.{NodeApi, NodeError}
import org.ergoplatform.appkit.scalaapi._
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue, NetworkType, Parameters}
import org.ergoplatform.sdk.ErgoId
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.{timeout, verify, when}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import sigma.Colls
import state.synchronization.CompleteMempool
import support.{FakeNodeContext, LDNodeFixtures}
import transactions.candidate.BlockTxMessages.{BlockTxsReady, RequestBlockTxs}
import transactions.candidate.CandidateBundle
import work.lithos.mutations.{Token, UTXO}

import java.math.BigInteger
import scala.concurrent.duration._
import scala.util.{Failure, Success}

/**
 * The batcher serving a token/token pool against a mocked node: a deployment built from its own ids, its vault,
 * and a swap order naming it, from which the batcher finds the pool, as it does an ERG/token one.
 */
class LithosDexT2TBatcherSpec extends TestKit(ActorSystem("lithosdex-t2t-batcher-spec", LithosDexBatcherSpec.config))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val Fee = 6000000L
  private val anchor = "cc" * 32

  private val t2t = LDT2TContracts(ErgoId.create("e1" * 32), ErgoId.create("e2" * 32), ErgoId.create("e3" * 32))
  private val tokenX = ErgoId.create("e4" * 32)
  private val tokenY = ErgoId.create("e5" * 32)
  private val lit = LDContracts(NetworkType.MAINNET)

  private def longs(v: Array[Long]): ErgoValue[_] = ErgoValue.of(Colls.fromArray(v), scalaLongType)
  private def zero: ErgoValue[_] = ErgoValue.of(BigInt(0).bigInteger)

  /**
   * @param discoverPools whether the batcher looks past the canonical pool
   * @param forgeGuard    the pool names ERG:LIT's guard instead of its own
   * @param candidates    the batcher builds this miner's blocks, flushing each run, and broadcasts nothing
   * @param withCanonical an order also names ERG:LIT, whose pool and vault stand too
   */
  private class Fixture(discoverPools: Boolean, forgeGuard: Boolean = false, candidates: Boolean = false,
                        withCanonical: Boolean = false) {
    val api: NodeApi = mock[NodeApi]
    val (nodeContext, _, _) = FakeNodeContext(api, numAddresses = 1)

    val (pool, vault, order) = nodeContext.getClient.execute { ctx: BlockchainContext =>
      val owner = ctx.newProverBuilder().withDLogSecret(BigInteger.valueOf(7007L)).build().getAddress
      val terms = LDOrderTerms(owner.getPublicKey, t2t.poolNFT, Fee, 2000000L)
      val genuine = LDNodeFixtures.nodeBox(ctx, UTXO(t2t.liquidityPool, LDHelpers.T2T_POOL_MIN_VALUE,
        Seq(Token(t2t.poolNFT, 1L), Token(tokenX, 50000000000L), Token(tokenY, 20000000000L),
          Token(t2t.provToken, LDHelpers.PROV_TOKEN_SUPPLY)),
        Seq(ErgoValue.of(LDHelpers.LOCKED_LP - LDHelpers.GENESIS_SUPPLY), longs(LDHelpers.GENESIS_FEE_PARAMS),
          longs(Array(0L, 0L)), zero, zero)))
      val poolBox =
        if (!forgeGuard) genuine
        else genuine.copy(ergoTree = genuine.ergoTree.replace(t2t.provisionGuard.hashedPropBytesHex,
          DexContracts(ctx).provisionGuard.hashedPropBytesHex))
      (poolBox,
        LDNodeFixtures.nodeBox(ctx, UTXO(t2t.feeVault, LDHelpers.T2T_VAULT_MIN_VALUE,
          Seq(Token(t2t.vaultNFT, 1L), Token(tokenX, 1L), Token(tokenY, 1L)), Seq(zero, zero)), index = 1),
        LDNodeFixtures.nodeBox(ctx, UTXO(LDOrderContracts.t2tSwap(terms, 1L), Parameters.MinFee + Fee,
          Seq(Token(tokenX, 1000000000L))), index = 5, txId = "cd" * 32))
    }

    val (litPool, litVault, litOrder) = nodeContext.getClient.execute { ctx: BlockchainContext =>
      val owner = ctx.newProverBuilder().withDLogSecret(BigInteger.valueOf(7009L)).build().getAddress
      val terms = LDOrderTerms(owner.getPublicKey, lit.poolNFT, Fee, 2000000L)
      (LDNodeFixtures.nodeBox(ctx, UTXO(lit.liquidityPool, 30L * Parameters.OneErg,
        Seq(Token(lit.poolNFT, 1L), Token(ErgoId.create("ee" * 32), 3000000000L), Token(lit.provToken, 1000000000000000L)),
        Seq(ErgoValue.of(LDHelpers.LOCKED_LP - LDHelpers.GENESIS_SUPPLY), longs(LDHelpers.GENESIS_FEE_PARAMS),
          longs(Array(0L, 0L)), zero, zero)), index = 8),
        LDNodeFixtures.nodeBox(ctx, UTXO(lit.feeVault, LDHelpers.VAULT_MIN, Seq(Token(lit.vaultNFT, 1L)),
          Seq(zero, zero)), index = 9),
        LDNodeFixtures.nodeBox(ctx, UTXO(LDOrderContracts.swapSell(terms, 3L * Parameters.OneErg, 1L),
          3L * Parameters.OneErg + Fee + Parameters.MinFee), index = 7, txId = "cf" * 32))
    }

    val engine = TestProbe()
    engine.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): TestActor.AutoPilot = {
        if (msg == CompleteMempool.Refresh)
          sender ! CompleteMempool.Observation(1L, Some(CompleteMempool.Snapshot(anchor, Set.empty, Set.empty,
            System.nanoTime(), transactions = Vector.empty)), None)
        this
      }
    })

    private val t2tTemplate = LDOrderContracts.template(LDT2TOrder.parse(order).get.kind).templateHash
    private val litTemplate = LDOrderContracts.template(LithosDexOrder.parse(litOrder).get.kind).templateHash

    when(api.unspentBoxesByTemplateHash(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenAnswer { inv =>
        inv.getArgument[String](0) match {
          case `t2tTemplate` => Success(Seq(IndexedBox(order, "", 100, 1L)))
          case `litTemplate` if withCanonical => Success(Seq(IndexedBox(litOrder, "", 100, 7L)))
          case _ => Success(Seq.empty[IndexedBox])
        }
      }
    when(api.unspentBoxesByTokenId(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenAnswer { inv =>
        inv.getArgument[String](0) match {
          case nft if nft == t2t.poolNFT.toString => Success(Seq(IndexedBox(pool, "", 100, 2L)))
          case nft if nft == t2t.vaultNFT.toString => Success(Seq(IndexedBox(vault, "", 100, 3L)))
          case nft if nft == lit.poolNFT.toString => Success(Seq(IndexedBox(litPool, "", 100, 8L)))
          case nft if nft == lit.vaultNFT.toString => Success(Seq(IndexedBox(litVault, "", 100, 9L)))
          case _ => Success(Seq.empty[IndexedBox])
        }
      }
    when(api.unspentBoxesByErgoTree(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenReturn(Success(Seq.empty[IndexedBox]))
    when(api.boxesWithPoolByIds(any[Seq[String]])).thenAnswer { inv =>
      val wanted = inv.getArgument[Seq[String]](0).toSet
      Success(Seq(order, pool, vault, litOrder, litPool, litVault).filter(box => wanted.contains(box.boxId)))
    }
    when(api.sendTransaction(anyString())).thenReturn(Failure(NodeError.Rejected("refused by the test")))
    when(api.info()).thenReturn(Success(support.ChainFixtures.infoAt(200000).copy(bestFullHeaderId = Some(anchor))))

    val batcher: ActorRef = system.actorOf(Props(new LithosDexBatcher(nodeContext,
      LithosDexBatchingConfig(BatchingConfig.Default.copy(scanIntervalMs = 3600000L, broadcast = !candidates),
        autoFlush = candidates, discoverPools = discoverPools),
      servesCandidates = candidates, engine.ref, useTrueProp = false)))

    @volatile private var height = 200000

    /** This miner's bundles for the next block, once a scan has tracked the orders. */
    def offeredOnceTracked(): Seq[CandidateBundle] =
      awaitAssert({
        val requester = TestProbe()
        height += 1
        requester.send(batcher, RequestBlockTxs(height, 8))
        val bundles = requester.expectMsgType[BlockTxsReady].bundles
        bundles should not be empty
        bundles
      }, 30.seconds, 250.millis)

    def stop(): Unit = system.stop(batcher)
  }

  private def outputTrees(json: String): Seq[String] =
    parse(json).getOrElse(fail("the member was not JSON")).hcursor.downField("outputs").values
      .getOrElse(fail("no outputs")).toSeq.flatMap(_.hcursor.get[String]("ergoTree").toOption)

  "The LithosDex batcher" should "broadcast an order against a verified token/token pool" in {
    val f = new Fixture(discoverPools = true)
    val sent = ArgumentCaptor.forClass(classOf[String])
    verify(f.api, timeout(30000).times(1)).sendTransaction(sent.capture())
    val tx = parse(sent.getValue).getOrElse(fail("the broadcast was not JSON"))
    val inputs = tx.hcursor.downField("inputs").values.getOrElse(fail("no inputs")).toSeq
    inputs.flatMap(_.hcursor.get[String]("boxId").toOption) should contain inOrder (f.pool.boxId, f.order.boxId)
    tx.hcursor.downField("outputs").downArray.get[String]("ergoTree") shouldBe Right(t2t.liquidityPool.ergoTreeHex)
    f.stop()
  }

  it should "leave token/token pools alone unless discoverPools is on" in {
    val f = new Fixture(discoverPools = false)
    verify(f.api, org.mockito.Mockito.after(5000).never()).sendTransaction(anyString())
    f.stop()
  }

  it should "never serve a token/token pool whose tree its own ids do not reproduce" in {
    val f = new Fixture(discoverPools = true, forgeGuard = true)
    verify(f.api, org.mockito.Mockito.after(5000).never()).sendTransaction(anyString())
    f.stop()
  }

  it should "execute a token/token order in this miner's own block, flushed into its vault" in {
    val f = new Fixture(discoverPools = true, candidates = true)
    val bundle = f.offeredOnceTracked().head
    bundle.members.map(_.kind) shouldBe Seq(LDT2TExecution.Kind, LDT2TExecution.FlushKind)
    outputTrees(bundle.members.head.json).head shouldBe t2t.liquidityPool.ergoTreeHex
    outputTrees(bundle.members(1).json).take(2) shouldBe Seq(t2t.liquidityPool.ergoTreeHex, t2t.feeVault.ergoTreeHex)
    f.stop()
  }

  it should "run ERG:LIT and a token/token pool in the same block, one bundle each" in {
    val f = new Fixture(discoverPools = true, candidates = true, withCanonical = true)
    val bundles = awaitAssert({ val found = f.offeredOnceTracked(); found should have size 2; found },
      30.seconds, 250.millis)
    bundles.map(bundle => outputTrees(bundle.members.head.json).head).toSet shouldBe
      Set(t2t.liquidityPool.ergoTreeHex, lit.liquidityPool.ergoTreeHex)
    f.stop()
  }
}
