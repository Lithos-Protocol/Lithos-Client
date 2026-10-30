package api

import akka.actor.ActorSystem
import akka.testkit.{TestKit, TestProbe}
import configs.VerifiedPool
import lithosdex.LDGenesisBuilder.{N2TSeed, T2TSeed}
import lithosdex.contracts.{LDContracts, LDT2TContracts}
import lithosdex.{LDGenesisBuilder, LDHelpers}
import node.NodeApi
import node.model._
import org.ergoplatform.appkit.{BlockchainContext, NetworkType, Parameters}
import org.ergoplatform.sdk.ErgoId
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.{times, verify, when}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.Configuration
import support.{ChainFixtures, FakeNodeContext, LDNodeFixtures}
import transactions.batching.lithosdex.DexContracts
import work.lithos.mutations.{Token, UTXO}

import scala.util.{Failure, Success}

/**
 * The pool index against a mocked node holding ERG:LIT, an unverified ERG/token pool and a token/token pool,
 * each as `LDGenesisBuilder` creates them: which pools it lists, how it marks them, what a genesis it cannot
 * check reports, and that a genesis is read once.
 */
class LDPoolIndexSpec extends TestKit(ActorSystem("ld-pool-index-spec")) with AnyFlatSpecLike with Matchers
  with BeforeAndAfterAll with MockitoSugar {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val Tip = 200000
  private val lit = LDContracts(NetworkType.MAINNET)
  private val n2t = LDContracts(ErgoId.create("a1" * 32), ErgoId.create("a2" * 32), ErgoId.create("a3" * 32),
    NetworkType.MAINNET)
  private val t2t = LDT2TContracts(ErgoId.create("b1" * 32), ErgoId.create("b2" * 32), ErgoId.create("b3" * 32))
  private val tokenA = ErgoId.create("c1" * 32)
  private val tokenB = ErgoId.create("c2" * 32)
  private val n2tGenesisTx = "d1" * 32
  private val t2tGenesisTx = "d2" * 32
  private val litId = LDHelpers.getTokenY(NetworkType.MAINNET)

  /**
   * @param vaultApart     the ERG/token pool's vault was not created in its genesis transaction
   * @param provMintedMore the ERG/token pool's provision token was minted beyond what the pool holds
   * @param genesisFails   the node cannot answer the ERG/token pool's token lookups
   * @param forged         the token/token pool names another deployment's guard
   */
  private class Fixture(vaultApart: Boolean = false, provMintedMore: Boolean = false, genesisFails: Boolean = false,
                        forged: Boolean = false) {
    val api: NodeApi = mock[NodeApi]
    val (nodeContext, _, _) = FakeNodeContext(api, numAddresses = 1)

    private def indexed(box: NodeBox, height: Int = 100) = IndexedBox(box, "", height, 1L)
    private def tx(id: String, outs: NodeBox*) =
      IndexedTransaction(id, Seq.empty, Seq.empty, outs.map(indexed(_)), 100, 10, "ff" * 32, 0L, 0, 1L, 100)

    val (litPool, litVault, n2tPool, n2tVault, t2tPool, t2tVault) = nodeContext.getClient.execute { ctx: BlockchainContext =>
      def zero = org.ergoplatform.appkit.ErgoValue.of(BigInt(0).bigInteger)
      val litPoolBox = LDNodeFixtures.nodeBox(ctx, UTXO(lit.liquidityPool, 30L * Parameters.OneErg,
        Seq(Token(lit.poolNFT, 1L), Token(litId, 3000000000L), Token(lit.provToken, LDHelpers.PROV_TOKEN_SUPPLY)),
        LDGenesisBuilder.poolRegisters(LDHelpers.GENESIS_FEE_PARAMS.toSeq)), index = 8, txId = "e1" * 32)
      val litVaultBox = LDNodeFixtures.nodeBox(ctx, UTXO(lit.feeVault, LDHelpers.VAULT_MIN, Seq(Token(lit.vaultNFT, 1L)),
        Seq(zero, zero)), index = 9, txId = "e2" * 32)
      val n2tSeed = N2TSeed(tokenA, 2L * Parameters.OneErg, 1000000L)
      val n2tPoolUtxo = LDGenesisBuilder.n2tPool(n2t, n2tSeed)
      val n2tPoolBox = LDNodeFixtures.nodeBox(ctx, n2tPoolUtxo, index = 0, txId = n2tGenesisTx)
      val n2tVaultBox = LDNodeFixtures.nodeBox(ctx, LDGenesisBuilder.n2tVault(n2t), index = 1,
        txId = if (vaultApart) "e3" * 32 else n2tGenesisTx)
      val t2tSeed = T2TSeed(tokenA, tokenB, 5000000L, 1000000L)
      val t2tPoolBox = {
        val genuine = LDNodeFixtures.nodeBox(ctx, LDGenesisBuilder.t2tPool(t2t, t2tSeed), index = 0, txId = t2tGenesisTx)
        if (!forged) genuine
        else genuine.copy(ergoTree = genuine.ergoTree.replace(t2t.provisionGuard.hashedPropBytesHex,
          DexContracts(ctx).provisionGuard.hashedPropBytesHex))
      }
      val t2tVaultBox = LDNodeFixtures.nodeBox(ctx, LDGenesisBuilder.t2tVault(t2t, t2tSeed), index = 1, txId = t2tGenesisTx)
      (litPoolBox, litVaultBox, n2tPoolBox, n2tVaultBox, t2tPoolBox, t2tVaultBox)
    }

    private val n2tTemplate = LDPoolIndex.n2tTemplateHash(NetworkType.MAINNET)
    private val t2tTemplate = LDPoolIndex.t2tTemplateHash(NetworkType.MAINNET)

    when(api.info()).thenReturn(Success(ChainFixtures.infoAt(Tip)))
    when(api.unspentBoxesByTemplateHash(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenAnswer { inv =>
        inv.getArgument[String](0) match {
          case `n2tTemplate` => Success(Seq(indexed(litPool, 90), indexed(n2tPool, 150)))
          case `t2tTemplate` => Success(Seq(indexed(t2tPool, 160)))
          case _ => Success(Seq.empty[IndexedBox])
        }
      }
    when(api.unspentBoxesByTokenId(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenAnswer { inv =>
        inv.getArgument[String](0) match {
          case id if id == lit.vaultNFT.toString => Success(Seq(indexed(litVault)))
          case id if id == n2t.vaultNFT.toString => Success(Seq(indexed(n2tVault)))
          case id if id == t2t.vaultNFT.toString => Success(Seq(indexed(t2tVault)))
          case _ => Success(Seq.empty[IndexedBox])
        }
      }
    private def info(id: ErgoId, mintBox: String, emission: Long) =
      Some(TokenInfo(id.toString, mintBox, emission, "", "", 0))
    when(api.tokenById(anyString())).thenAnswer { inv =>
      val id = inv.getArgument[String](0)
      if (genesisFails && id.startsWith("a")) Failure(new RuntimeException("index unavailable"))
      else Success(id match {
        case i if i == n2t.provToken.toString =>
          info(n2t.provToken, n2tPool.boxId, LDHelpers.PROV_TOKEN_SUPPLY + (if (provMintedMore) 1L else 0L))
        case i if i == t2t.provToken.toString => info(t2t.provToken, t2tPool.boxId, LDHelpers.PROV_TOKEN_SUPPLY)
        case i if Seq(n2t.poolNFT, n2t.vaultNFT, t2t.poolNFT, t2t.vaultNFT).exists(_.toString == i) =>
          Some(TokenInfo(i, "00" * 32, 1L, "", "", 0))
        case _ => None
      })
    }
    when(api.indexedBoxById(anyString())).thenAnswer { inv =>
      Success(Seq(n2tPool, t2tPool).find(_.boxId == inv.getArgument[String](0)).map(indexed(_)))
    }
    when(api.indexedTransactionById(anyString())).thenAnswer { inv =>
      Success(inv.getArgument[String](0) match {
        case `n2tGenesisTx` => Some(if (vaultApart) tx(n2tGenesisTx, n2tPool) else tx(n2tGenesisTx, n2tPool, n2tVault))
        case `t2tGenesisTx` => Some(tx(t2tGenesisTx, t2tPool, t2tVault))
        case _ => None
      })
    }
    when(api.tokensByIds(any[Seq[String]])).thenAnswer { inv =>
      Success(inv.getArgument[Seq[String]](0).map(id => TokenInfo(id, "00" * 32, 1L, s"name-${id.take(4)}", "", 3)))
    }

    val index = new LDPoolIndex(nodeContext, system)
    val pools: LDPoolsApiImpl = new LDPoolsApiImpl(nodeContext, index, TestProbe().ref, Configuration.empty, system)
  }

  "The pool index" should "list ERG:LIT, an ERG/token pool and a token/token pool, verified first" in {
    val f = new Fixture()
    val s = f.index.refreshNow()
    s.status shouldBe "ready"
    s.height shouldBe Tip
    s.pools.map(_.nft) shouldBe Seq(lit.poolNFT.toString, t2t.poolNFT.toString, n2t.poolNFT.toString)
    s.pools.map(_.kind) shouldBe Seq(VerifiedPool.Kind.N2T, VerifiedPool.Kind.T2T, VerifiedPool.Kind.N2T)
    s.pools.map(_.verified) shouldBe Seq(true, false, false)
  }

  it should "leave a verified pool's genesis to the hand check, and find the builder's genesis sound" in {
    val s = new Fixture().index.refreshNow()
    s.pools.find(_.nft == lit.poolNFT.toString).get.genesis shouldBe None
    Seq(n2t.poolNFT, t2t.poolNFT).foreach { nft =>
      withClue(s"$nft: ")(s.pools.find(_.nft == nft.toString).get.genesis shouldBe Some(GenesisVerdict(Seq.empty, Seq.empty)))
    }
  }

  it should "report a genesis it cannot check as a problem, never as sound" in {
    val s = new Fixture(vaultApart = true).index.refreshNow()
    s.pools.find(_.nft == n2t.poolNFT.toString).get.genesis.get.problems shouldBe Seq(LDPoolIndex.CannotBeChecked)
  }

  it should "report provision tokens minted beyond the pool's holding" in {
    val s = new Fixture(provMintedMore = true).index.refreshNow()
    s.pools.find(_.nft == n2t.poolNFT.toString).get.genesis.get.problems.mkString should include("provision token")
  }

  it should "not list a pool whose tree its own ids do not reproduce" in {
    new Fixture(forged = true).index.refreshNow().pools.map(_.nft) should not contain t2t.poolNFT.toString
  }

  it should "read each genesis once, and retry one the node could not answer" in {
    val f = new Fixture()
    f.index.refreshNow()
    f.index.refreshNow()
    verify(f.api, times(1)).indexedTransactionById(n2tGenesisTx)
    verify(f.api, times(1)).indexedTransactionById(t2tGenesisTx)

    val failing = new Fixture(genesisFails = true)
    failing.index.refreshNow().pools.find(_.nft == n2t.poolNFT.toString).get.genesis shouldBe None
    failing.index.refreshNow()
    verify(failing.api, times(2)).tokenById(n2t.provToken.toString)
  }

  "The pools API" should "name ERG and verified tokens, and price Y per X at display decimals" in {
    val f = new Fixture()
    f.index.refreshNow()
    val list = f.pools.listPools
    val litSummary = list.pools.find(_.poolNFT == lit.poolNFT.toString).get
    litSummary.tokenX.tokenId shouldBe "ERG"
    litSummary.tokenY.ticker shouldBe Some("LIT")
    litSummary.tokenY.verified shouldBe true
    // 3 LIT (9 decimals) against 30 ERG
    litSummary.price.get shouldBe 0.1 +- 1e-12
    val t2tSummary = list.pools.find(_.poolNFT == t2t.poolNFT.toString).get
    t2tSummary.kind shouldBe "t2t"
    t2tSummary.tokenX.verified shouldBe false
    t2tSummary.genesisChecked shouldBe true
  }

  it should "give a pool's vault, and 404 for a pool it does not know" in {
    val f = new Fixture()
    f.index.refreshNow()
    f.pools.getPool(t2t.poolNFT.toString).vault.vaultNFT shouldBe t2t.vaultNFT.toString
    a[LithosApiErrors.LithosNotFound] should be thrownBy f.pools.getPool("99" * 32)
  }
}
