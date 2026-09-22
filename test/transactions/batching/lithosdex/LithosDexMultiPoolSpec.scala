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
 * The batcher serving a deployment other than the canonical one, against a mocked node: ERG:BBC, compiled
 * from its real mainnet ids, found at the pool template, and a sell order naming it.
 */
class LithosDexMultiPoolSpec extends TestKit(ActorSystem("lithosdex-multi-pool-spec", LithosDexBatcherSpec.config))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val Fee = 6000000L
  private val anchor = "cc" * 32

  private val bbc = LDContracts(
    ErgoId.create("77ebb7ac1a9386d6cc5b64fffa52602bea574b0bb454fb856188ae54acb84894"),
    ErgoId.create("9cec4df2183f6047edf61ec9bb60649c22362e17c074fac7c41d75c5229129f0"),
    ErgoId.create("27bc91eaf7e72d2a17cf6e73de349262c87bea7894121963b4100a644a08da25"),
    NetworkType.MAINNET)
  private val bbcToken = ErgoId.create("fffe6122886e3b0ab9b72b401b39bf8d3f13580c1335a41d91d19deb8038ccd4")

  private def longs(v: Array[Long]): ErgoValue[_] = ErgoValue.of(Colls.fromArray(v), scalaLongType)

  /**
   * @param discoverPools whether the batcher looks past the canonical pool
   * @param forgeGuard    the pool at the template names ERG:LIT's guard instead of its own
   */
  private class Fixture(discoverPools: Boolean, forgeGuard: Boolean = false) {
    val api: NodeApi = mock[NodeApi]
    val (nodeContext, _, _) = FakeNodeContext(api, numAddresses = 1)

    val (pool, vault, order) = nodeContext.getClient.execute { ctx: BlockchainContext =>
      val owner = ctx.newProverBuilder().withDLogSecret(BigInteger.valueOf(7007L)).build().getAddress
      val terms = LDOrderTerms(owner.getPublicKey, bbc.poolNFT, Fee, 2000000L)
      val genuine = LDNodeFixtures.nodeBox(ctx, UTXO(bbc.liquidityPool, 10L * Parameters.OneErg,
        Seq(Token(bbc.poolNFT, 1L), Token(bbcToken, 1000000000L), Token(bbc.provToken, 1000000000000000L)),
        Seq(ErgoValue.of(LDHelpers.LOCKED_LP - LDHelpers.GENESIS_SUPPLY), longs(LDHelpers.GENESIS_FEE_PARAMS),
          longs(Array(0L, 0L)), ErgoValue.of(BigInt(0).bigInteger), ErgoValue.of(BigInt(0).bigInteger))))
      val poolBox =
        if (!forgeGuard) genuine
        else genuine.copy(ergoTree = genuine.ergoTree.replace(bbc.provisionGuard.hashedPropBytesHex,
          DexContracts(ctx).provisionGuard.hashedPropBytesHex))
      val vaultBox = LDNodeFixtures.nodeBox(ctx, UTXO(bbc.feeVault, LDHelpers.VAULT_MIN,
        Seq(Token(bbc.vaultNFT, 1L)), Seq(ErgoValue.of(BigInt(0).bigInteger), ErgoValue.of(BigInt(0).bigInteger))),
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
    private val poolTemplate = LDDeployments.poolTemplateHash(NetworkType.MAINNET)

    when(api.unspentBoxesByTemplateHash(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenAnswer { inv =>
        inv.getArgument[String](0) match {
          case `orderTemplate` => Success(Seq(IndexedBox(order, "", 100, 1L)))
          case `poolTemplate` => Success(Seq(IndexedBox(pool, "", 100, 2L)))
          case _ => Success(Seq.empty[IndexedBox])
        }
      }
    when(api.unspentBoxesByTokenId(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenAnswer { inv =>
        inv.getArgument[String](0) match {
          case nft if nft == bbc.poolNFT.toString => Success(Seq(IndexedBox(pool, "", 100, 2L)))
          case nft if nft == bbc.vaultNFT.toString => Success(Seq(IndexedBox(vault, "", 100, 3L)))
          case _ => Success(Seq.empty[IndexedBox])
        }
      }
    when(api.unspentBoxesByErgoTree(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenReturn(Success(Seq.empty[IndexedBox]))
    when(api.boxesWithPoolByIds(any[Seq[String]])).thenAnswer { inv =>
      val wanted = inv.getArgument[Seq[String]](0).toSet
      Success(Seq(order, pool, vault).filter(box => wanted.contains(box.boxId)))
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
    successor.get[String]("ergoTree") shouldBe Right(bbc.liquidityPool.ergoTreeHex)
    f.stop()
  }

  it should "leave every other deployment alone unless discoverPools is on" in {
    val f = new Fixture(discoverPools = false)
    verify(f.api, org.mockito.Mockito.after(5000).never()).sendTransaction(anyString())
    f.stop()
  }

  it should "never serve a pool at the template whose contracts do not compile back to its tree" in {
    val f = new Fixture(discoverPools = true, forgeGuard = true)
    verify(f.api, org.mockito.Mockito.after(5000).never()).sendTransaction(anyString())
    f.stop()
  }
}
