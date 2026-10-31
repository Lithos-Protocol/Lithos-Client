package stats

import configs.{DexStatsConfig, NodeContext, StatsConfig}
import lithosdex.LDHelpers
import node.NodeApi
import node.model._
import okhttp3.mockwebserver.{MockResponse, MockWebServer, SocketPolicy}
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.{never, times, verify, when}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import support.{ChainFixtures, FakeNodeContext, LDNodeFixtures}

import java.util.concurrent.TimeUnit
import scala.util.Success

class NodeDexStatsSourceSpec extends AnyFlatSpec with Matchers with MockitoSugar {
  private lazy val fake = FakeNodeContext(mock[NodeApi], numAddresses = 1)._1
  private def pool(index: Int, height: Int): IndexedBox = fake.getClient.execute { ctx =>
    LDNodeFixtures.indexed(LDNodeFixtures.poolBox(ctx,
      10000000000L + index.toLong * 1000000000L, 100000000L - index.toLong * 1000000L, index = index),
      height = height).copy(globalIndex = index.toLong)
  }
  private val before = ChainFixtures.infoAt(1000).copy(bestFullHeaderId = Some("ab" * 32))

  private def fixture(): (NodeApi, NodeDexStatsSource, NodeContext) = {
    val api = mock[NodeApi]
    val context = mock[NodeContext]
    when(context.getNetwork).thenReturn(fake.getNetwork)
    when(api.info()).thenReturn(Success(before))
    when(api.indexedHeight()).thenReturn(Success(BlockchainIndexHeight(1000, 1000)))
    val boxes = Vector(pool(2, 110), pool(1, 100))
    when(api.boxesByTokenId(anyString(), any[Paging])).thenReturn(Success(Paged(boxes, 2)))
    when(api.unspentBoxesByTokenId(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenReturn(Success(Vector(boxes.head)))
    when(api.tokenById(anyString())).thenReturn(Success(None))
    when(api.unconfirmedTransactionsByErgoTree(anyString(), any[Paging])).thenReturn(Success(Vector.empty))
    when(api.indexedTransactionById(anyString())).thenReturn(Success(None))
    val cache = new StatsCache(StatsConfig(dex = DexStatsConfig(timestampLookups = 0)))
    val source = new NodeDexStatsSource(context, cache) {
      override protected def createApi(deadline: Long): NodeApi = api
    }
    (api, source, context)
  }

  "DEX source" should "read one shared history for all graphs without opening a wallet or appkit context" in {
    val (api, source, context) = fixture()
    val data = source.read()
    data.prices.keySet shouldBe Set("1H", "24H", "7D", "30D")
    data.height shouldBe 1000
    data.blockId shouldBe before.bestFullHeaderId.get
    data.recent.activity should have size 1
    data.recent.activity.head.status shouldBe "CONFIRMED"
    verify(api, times(1)).boxesByTokenId(anyString(), any[Paging])
    verify(context, never()).getNodeWallet
    verify(context, never()).getClient
  }

  it should "read a verified token/token pool only from the height it joined the list, its reserves from its tokens" in {
    val (api, source, cache) = {
      val (a, s, _) = fixture()
      (a, s, new StatsCache(StatsConfig(dex = DexStatsConfig(timestampLookups = 0))))
    }
    val t2t = lithosdex.contracts.LDT2TContracts(org.ergoplatform.sdk.ErgoId.create("e1" * 32),
      org.ergoplatform.sdk.ErgoId.create("e2" * 32), org.ergoplatform.sdk.ErgoId.create("e3" * 32))
    val (tokenX, tokenY) = ("e4" * 32, "e5" * 32)
    def t2tBox(index: Int, height: Int, x: Long, y: Long): IndexedBox = fake.getClient.execute { ctx =>
      import org.ergoplatform.appkit.ErgoValue
      import org.ergoplatform.appkit.scalaapi.scalaLongType
      def longs(v: Array[Long]) = ErgoValue.of(sigma.Colls.fromArray(v), scalaLongType)
      LDNodeFixtures.indexed(LDNodeFixtures.nodeBox(ctx, work.lithos.mutations.UTXO(t2t.liquidityPool,
        LDHelpers.T2T_POOL_MIN_VALUE, Seq(work.lithos.mutations.Token(t2t.poolNFT, 1L),
          work.lithos.mutations.Token(org.ergoplatform.sdk.ErgoId.create(tokenX), x + 10L),
          work.lithos.mutations.Token(org.ergoplatform.sdk.ErgoId.create(tokenY), y + 20L),
          work.lithos.mutations.Token(t2t.provToken, 1000L)),
        Seq(ErgoValue.of(LDHelpers.LOCKED_LP - 1000000L), longs(LDHelpers.GENESIS_FEE_PARAMS), longs(Array(10L, 20L)),
          ErgoValue.of(BigInt(0).bigInteger), ErgoValue.of(BigInt(0).bigInteger))), index = index),
        height = height).copy(globalIndex = index.toLong)
    }
    // Newest first, as the node lists them: the box from 110 is the pool as it stood when it joined the list at 500
    val boxes = Vector(t2tBox(3, 980, 4000L, 9000L), t2tBox(2, 110, 5000L, 8000L), t2tBox(1, 100, 6000L, 7000L))
    when(api.boxesByTokenId(anyString(), any[Paging])).thenReturn(Success(Paged(boxes, 3)))
    when(api.unspentBoxesByTokenId(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenReturn(Success(Vector(boxes.head)))

    val target = DexStatsTarget(transactions.batching.lithosdex.LDBoxes.PoolLineage(t2t.poolNFT.toString,
      t2t.liquidityPool.ergoTreeHex, t2t.vaultNFT.toString, tokenToken = true), Some(tokenX), tokenY, since = 500)
    val data = source.read(target)
    (data.collectedFrom, data.fromHeight) shouldBe ((Some(500), 500))
    data.history.map(_.height) shouldBe Seq(110, 980)
    (data.current.reservesX, data.current.reservesY) shouldBe (("4000", "9000"))
    data.current.price shouldBe 2.25
    data.prices("30D").partial shouldBe true
    cache.publishVerifiedDex(Map(t2t.poolNFT.toString -> Success(data)), 1L, System.nanoTime())
    cache.verifiedDexSnapshot(t2t.poolNFT.toString).flatMap(_.view.collectedFrom) shouldBe Some(500)
  }

  "Verified pool targets" should "be every verified pool but ERG:LIT that names the height it joined the list" in {
    def indexed(nft: String, verified: Boolean) = api.IndexedPool(configs.VerifiedPool.Kind.T2T, nft, "00" * 32, 1,
      Some("e4" * 32), "e5" * 32, 0L, 0L, 0L, 0L, 0L, Seq.empty, 0L, "e2" * 32, "e3" * 32, verified, None, None,
      Some(lithosdex.contracts.LDT2TContracts(org.ergoplatform.sdk.ErgoId.create(nft),
        org.ergoplatform.sdk.ErgoId.create("e2" * 32), org.ergoplatform.sdk.ErgoId.create("e3" * 32))))
    val snapshot = api.PoolIndexSnapshot("ready", 1000, truncated = false,
      Seq(indexed("a1" * 32, verified = true), indexed("a2" * 32, verified = true), indexed("a3" * 32, verified = false),
        indexed("a4" * 32, verified = true)), Map.empty)
    val added = Map("a2" * 32 -> 700, "a3" * 32 -> 800)
    val targets = VerifiedPoolStatsRefresh.targets(snapshot, "a1" * 32, added.get)
    targets.map(t => (t.lineage.nft, t.since, t.lineage.tokenToken)) shouldBe Seq(("a2" * 32, 700, true))
  }

  "DEX source" should "refuse a mixed-branch read even when the height has not changed" in {
    val (api, source, _) = fixture()
    when(api.info()).thenReturn(Success(before), Success(before.copy(bestFullHeaderId = Some("cd" * 32))))
    val error = intercept[IllegalArgumentException](source.read())
    error.getMessage should include("chain changed")
  }

  it should "refuse a lagging index before scanning history" in {
    val (api, source, _) = fixture()
    when(api.indexedHeight()).thenReturn(Success(BlockchainIndexHeight(999, 1000)))
    intercept[IllegalArgumentException](source.read()).getMessage should include("caught up")
    verify(api, never()).boxesByTokenId(anyString(), any[Paging])
  }

  it should "fail rather than bridge an unreadable pool transition" in {
    val (api, source, _) = fixture()
    val original = pool(2, 110)
    val bad = original.copy(box = original.box.copy(additionalRegisters = NodeRegisters(Map.empty)))
    when(api.boxesByTokenId(anyString(), any[Paging])).thenReturn(Success(Paged(Vector(bad), 1)))
    intercept[IllegalStateException](source.read()).getMessage should include("malformed pool history")
  }

  "History bounds" should "retain one predecessor and stop before walking older pages" in {
    val api = mock[NodeApi]
    val base = pool(1, 30000)
    val page = (0 until 200).map(i => base.copy(inclusionHeight = 30000 - i, globalIndex = 1000L - i)).toVector
    when(api.boxesByTokenId(anyString(), any[Paging])).thenReturn(Success(Paged(page, 100000)))
    val (rows, complete) = NodeDexStatsSource.history(api,
      LDHelpers.getPoolNFT(fake.getNetwork).toString, base.ergoTree, 29995, 10)
    complete shouldBe true
    rows.map(_.height) shouldBe (29994 to 30000).toVector
    verify(api, times(1)).boxesByTokenId(anyString(), any[Paging])
  }

  it should "mark a capped suffix partial and reject overlapping pages" in {
    val api = mock[NodeApi]
    val base = pool(1, 30000)
    val page = (0 until 200).map(i => base.copy(inclusionHeight = 30000 - i, globalIndex = 1000L - i)).toVector
    when(api.boxesByTokenId(anyString(), any[Paging])).thenReturn(Success(Paged(page, 100000)))
    val nft = LDHelpers.getPoolNFT(fake.getNetwork).toString
    val (rows, complete) = NodeDexStatsSource.history(api, nft, base.ergoTree, 0, 1)
    rows should have size 200
    complete shouldBe false
    intercept[IllegalArgumentException](NodeDexStatsSource.history(api, nft, base.ergoTree, 0, 2))
      .getMessage should include("order changed")
  }

  "Stats HTTP" should "bound a node call that never produces a response" in {
    val server = new MockWebServer()
    server.start()
    try {
      server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
      val context = mock[NodeContext]
      when(context.getNodeUrl).thenReturn(server.url("/").toString)
      when(context.getNodeKey).thenReturn("")
      val source = new NodeDexStatsSource(context,
        new StatsCache(StatsConfig(dex = DexStatsConfig(readTimeoutMs = 100, refreshBudgetMs = 200))))
      val started = System.nanoTime()
      intercept[node.NodeError.Transport](source.read())
      TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) should be < 2000L
    } finally server.shutdown()
  }
}
