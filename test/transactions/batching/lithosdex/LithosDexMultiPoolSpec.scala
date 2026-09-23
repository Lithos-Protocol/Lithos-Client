package transactions.batching.lithosdex

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestActor, TestKit, TestProbe}
import configs.{BatchingConfig, LithosDexBatchingConfig}
import io.circe.parser.parse
import lithosdex.LDHelpers
import lithosdex.contracts.{LDContracts, LDOrderContracts, LDOrderTerms}
import node.model._
import node.{NodeApi, NodeError}
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue, NetworkType, Parameters}
import org.ergoplatform.appkit.scalaapi._
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
import work.lithos.mutations.{Token, UTXO}

import java.math.BigInteger
import scala.util.{Failure, Success}

/**
 * The batcher serving a deployment other than the canonical one, against a mocked node: a second mainnet
 * deployment, built from its real ids, and a sell order naming it, from which the batcher finds the pool.
 */
class LithosDexMultiPoolSpec extends TestKit(ActorSystem("lithosdex-multi-pool-spec", LithosDexBatcherSpec.config))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val Fee = 6000000L
  private val anchor = "cc" * 32

  private val second = LDContracts(
    ErgoId.create("77ebb7ac1a9386d6cc5b64fffa52602bea574b0bb454fb856188ae54acb84894"),
    ErgoId.create("9cec4df2183f6047edf61ec9bb60649c22362e17c074fac7c41d75c5229129f0"),
    ErgoId.create("27bc91eaf7e72d2a17cf6e73de349262c87bea7894121963b4100a644a08da25"),
    NetworkType.MAINNET)
  private val secondToken = ErgoId.create("fffe6122886e3b0ab9b72b401b39bf8d3f13580c1335a41d91d19deb8038ccd4")

  /** A deployment whose pool and vault check out, but whose pool box carries no registers. */
  private val broken = LDContracts(ErgoId.create("ab" * 32), ErgoId.create("ac" * 32), ErgoId.create("ad" * 32),
    NetworkType.MAINNET)

  private def longs(v: Array[Long]): ErgoValue[_] = ErgoValue.of(Colls.fromArray(v), scalaLongType)

  /**
   * @param discoverPools whether the batcher looks past the canonical pool
   * @param forgeGuard    the pool names ERG:LIT's guard instead of its own
   * @param withBroken    an order also names [[broken]], whose vault stands but whose pool box will not read
   */
  private class Fixture(discoverPools: Boolean, forgeGuard: Boolean = false, withBroken: Boolean = false) {
    val api: NodeApi = mock[NodeApi]
    val (nodeContext, _, _) = FakeNodeContext(api, numAddresses = 1)

    val (brokenPool, brokenVault, brokenOrder) = nodeContext.getClient.execute { ctx: BlockchainContext =>
      val owner = ctx.newProverBuilder().withDLogSecret(BigInteger.valueOf(7008L)).build().getAddress
      val terms = LDOrderTerms(owner.getPublicKey, broken.poolNFT, Fee, 2000000L)
      (LDNodeFixtures.nodeBox(ctx, UTXO(broken.liquidityPool, 20L * Parameters.OneErg,
        Seq(Token(broken.poolNFT, 1L), Token(secondToken, 1000000000L), Token(broken.provToken, 1000L)), Seq.empty),
        index = 2),
        LDNodeFixtures.nodeBox(ctx, UTXO(broken.feeVault, LDHelpers.VAULT_MIN, Seq(Token(broken.vaultNFT, 1L)),
          Seq(ErgoValue.of(BigInt(0).bigInteger), ErgoValue.of(BigInt(0).bigInteger))), index = 3),
        LDNodeFixtures.nodeBox(ctx, UTXO(LDOrderContracts.swapSell(terms, 2L * Parameters.OneErg, 1L),
          2L * Parameters.OneErg + Fee + Parameters.MinFee), index = 6, txId = "ce" * 32))
    }

    val (pool, vault, order) = nodeContext.getClient.execute { ctx: BlockchainContext =>
      val owner = ctx.newProverBuilder().withDLogSecret(BigInteger.valueOf(7007L)).build().getAddress
      val terms = LDOrderTerms(owner.getPublicKey, second.poolNFT, Fee, 2000000L)
      val genuine = LDNodeFixtures.nodeBox(ctx, UTXO(second.liquidityPool, 10L * Parameters.OneErg,
        Seq(Token(second.poolNFT, 1L), Token(secondToken, 1000000000L), Token(second.provToken, 1000000000000000L)),
        Seq(ErgoValue.of(LDHelpers.LOCKED_LP - LDHelpers.GENESIS_SUPPLY), longs(LDHelpers.GENESIS_FEE_PARAMS),
          longs(Array(0L, 0L)), ErgoValue.of(BigInt(0).bigInteger), ErgoValue.of(BigInt(0).bigInteger))))
      val poolBox =
        if (!forgeGuard) genuine
        else genuine.copy(ergoTree = genuine.ergoTree.replace(second.provisionGuard.hashedPropBytesHex,
          DexContracts(ctx).provisionGuard.hashedPropBytesHex))
      val vaultBox = LDNodeFixtures.nodeBox(ctx, UTXO(second.feeVault, LDHelpers.VAULT_MIN,
        Seq(Token(second.vaultNFT, 1L)), Seq(ErgoValue.of(BigInt(0).bigInteger), ErgoValue.of(BigInt(0).bigInteger))),
        index = 1)
      val orderBox = LDNodeFixtures.nodeBox(ctx,
        UTXO(LDOrderContracts.swapSell(terms, Parameters.OneErg, 1L), Parameters.OneErg + Fee + Parameters.MinFee),
        index = 5, txId = "cd" * 32)
      (poolBox, vaultBox, orderBox)
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

    private val orderTemplate = LDOrderContracts.template(LithosDexOrder.parse(order).get.kind).templateHash
    private val orders = Seq(IndexedBox(order, "", 100, 1L)) ++
      (if (withBroken) Seq(IndexedBox(brokenOrder, "", 100, 4L)) else Seq.empty)

    when(api.unspentBoxesByTemplateHash(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenAnswer { inv =>
        inv.getArgument[String](0) match {
          case `orderTemplate` => Success(orders)
          case _ => Success(Seq.empty[IndexedBox])
        }
      }
    when(api.unspentBoxesByTokenId(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenAnswer { inv =>
        inv.getArgument[String](0) match {
          case nft if nft == second.poolNFT.toString => Success(Seq(IndexedBox(pool, "", 100, 2L)))
          case nft if nft == second.vaultNFT.toString => Success(Seq(IndexedBox(vault, "", 100, 3L)))
          case nft if nft == broken.poolNFT.toString => Success(Seq(IndexedBox(brokenPool, "", 100, 5L)))
          case nft if nft == broken.vaultNFT.toString => Success(Seq(IndexedBox(brokenVault, "", 100, 6L)))
          case _ => Success(Seq.empty[IndexedBox])
        }
      }
    when(api.unspentBoxesByErgoTree(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenReturn(Success(Seq.empty[IndexedBox]))
    when(api.boxesWithPoolByIds(any[Seq[String]])).thenAnswer { inv =>
      val wanted = inv.getArgument[Seq[String]](0).toSet
      Success(Seq(order, pool, vault, brokenOrder, brokenPool, brokenVault).filter(box => wanted.contains(box.boxId)))
    }
    when(api.sendTransaction(anyString())).thenReturn(Failure(NodeError.Rejected("refused by the test")))
    when(api.info()).thenReturn(Success(support.ChainFixtures.infoAt(200000).copy(bestFullHeaderId = Some(anchor))))

    val batcher: ActorRef = system.actorOf(Props(new LithosDexBatcher(nodeContext,
      LithosDexBatchingConfig(BatchingConfig.Default.copy(scanIntervalMs = 3600000L, broadcast = true),
        autoFlush = false, discoverPools = discoverPools),
      servesCandidates = false, engine.ref, useTrueProp = false)))

    def stop(): Unit = system.stop(batcher)
  }

  "The LithosDex batcher" should "broadcast an order against a verified deployment other than the canonical one" in {
    val f = new Fixture(discoverPools = true)
    val sent = ArgumentCaptor.forClass(classOf[String])
    verify(f.api, timeout(30000).times(1)).sendTransaction(sent.capture())

    val tx = parse(sent.getValue).getOrElse(fail("the broadcast was not JSON"))
    val inputs = tx.hcursor.downField("inputs").values.getOrElse(fail("no inputs")).toSeq
    inputs.flatMap(_.hcursor.get[String]("boxId").toOption) should contain inOrder (f.pool.boxId, f.order.boxId)
    val successor = tx.hcursor.downField("outputs").downArray
    successor.get[String]("ergoTree") shouldBe Right(second.liquidityPool.ergoTreeHex)
    f.stop()
  }

  it should "leave every other deployment alone unless discoverPools is on" in {
    val f = new Fixture(discoverPools = false)
    verify(f.api, org.mockito.Mockito.after(5000).never()).sendTransaction(anyString())
    f.stop()
  }

  it should "never serve a pool whose tree the contracts built from its own ids do not reproduce" in {
    val f = new Fixture(discoverPools = true, forgeGuard = true)
    verify(f.api, org.mockito.Mockito.after(5000).never()).sendTransaction(anyString())
    f.stop()
  }

  it should "keep serving the other pools when a verified pool's box will not read" in {
    // The broken pool costs its own order alone, so the scan still succeeds and the broadcast after it goes out
    val f = new Fixture(discoverPools = true, withBroken = true)
    val sent = ArgumentCaptor.forClass(classOf[String])
    verify(f.api, timeout(30000).times(1)).sendTransaction(sent.capture())
    sent.getValue should include(f.order.boxId)
    sent.getValue should not include f.brokenOrder.boxId
    f.stop()
  }
}
