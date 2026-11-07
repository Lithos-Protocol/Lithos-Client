package api

import api.LithosApiErrors.{LithosBadRequest, LithosNotFound, LithosStateChanged, LithosUnavailable}
import com.google.gson.JsonParser
import configs.{NodeContext, OracleConfig}
import node.NodeApi
import node.model._
import node.rest.NodeCodecs
import org.ergoplatform.appkit.{ErgoValue, NetworkType}
import org.mockito.ArgumentMatchers.{any, anyInt, anyString}
import org.mockito.Mockito.{doAnswer, never, verify, when}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.Configuration

import scala.io.Source
import scala.util.{Failure, Success}

class OraclesApiSpec extends AnyFlatSpec with Matchers with MockitoSugar {
  private val tree = "1004040204000e2019b7f2e2f11052c020800c8b620660f9f0b5fd5b3f2beacc8b44af960477a694" +
    "0e2030e273698c85bbe66319904c499bfefbd76f51487688d674b20623715ff0741bd801d6018cb2db6308b2a473000073010001d1ec93720173029372017303"
  private val feed = OracleConfig.UsdV2
  private val blockId = "ab" * 32
  private def resource(name: String): String = {
    val src = Source.fromResource("mockwebserver/node_responses/" + name)
    try src.mkString finally src.close()
  }
  private lazy val nodeInfo = NodeCodecs.info(new JsonParser().parse(resource("response_NodeInfo_v6.json")).getAsJsonObject)
    .copy(fullHeight = Some(1000), bestFullHeaderId = Some(blockId))
  private lazy val header = NodeCodecs.header(new JsonParser().parse(resource("response_LastHeaders_v6.json")).getAsJsonArray.get(0).getAsJsonObject)

  private def box(i: Int): IndexedBox = IndexedBox(NodeBox(f"${10000 + i}%064x", f"${20000 + i}%064x", 10000000L,
    0, 990 - i * 6, tree, Seq(NodeAsset(feed.poolNft, 1L)),
    NodeRegisters(Map("R4" -> ErgoValue.of(5000000000L + i).toHex, "R5" -> ErgoValue.of(1000 - i).toHex))),
    "", 991 - i * 6, 10000L - i, if (i == 0) None else Some(f"${20000 + i - 1}%064x"))

  private class Fixture(count: Int = 101, network: NetworkType = NetworkType.MAINNET) {
    val api = mock[NodeApi]
    val node = mock[NodeContext]
    val boxes = Vector.tabulate(count)(box)
    when(node.getNetwork).thenReturn(network)
    when(api.info()).thenReturn(Success(nodeInfo))
    when(api.indexedHeight()).thenReturn(Success(BlockchainIndexHeight(1000, 1000)))
    when(api.unspentBoxesByTokenId(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenReturn(Success(Seq(boxes.head)))
    when(api.boxById(anyString())).thenReturn(Success(Some(boxes.head.box)))
    when(api.boxesByTokenId(anyString(), any[Paging])).thenAnswer { call =>
      val p = call.getArgument[Paging](1)
      Success(Paged(boxes.slice(p.offset, p.offset + p.limit), boxes.size))
    }
    when(api.chainSlice(any[Option[Int]], any[Option[Int]])).thenAnswer { call =>
      val from = call.getArgument[Option[Int]](0).get
      val to = call.getArgument[Option[Int]](1).get
      assert(to - from <= 500)
      Success(boxes.filter(b => b.inclusionHeight > from && b.inclusionHeight <= to)
        .map(b => header.copy(height = b.inclusionHeight, timestamp = 1700000000000L + b.inclusionHeight)))
    }
    val service = new OraclesApiImpl(node, Configuration.empty) {
      override protected def createApi(deadline: Long): NodeApi = api
    }
    def get(offset: Option[Int] = None, limit: Option[Int] = None, at: Option[String] = None) =
      service.getFeed("erg-usd", offset, limit, at)
  }

  "The oracle endpoint" should "return exact raw units, the inverse price and 100 confirmed updates by default" in {
    val f = new Fixture()
    val state = f.get()
    state.feed.rawUnit shouldBe "nanoERG/USD"
    state.feed.priceUnit shouldBe "USD/ERG"
    state.current.rawValue shouldBe "5000000000"
    state.current.price shouldBe "0.2"
    state.current.priceNumerator shouldBe "1000000000"
    state.current.priceDenominator shouldBe "5000000000"
    state.history.size shouldBe 100
    state.history.head shouldBe state.current
    state.history.map(_.epoch) shouldBe (1000 to 901 by -1)
    state.current.timestamp shouldBe Some(1700000000991L)
    state.ageBlocks shouldBe 10
    state.stale shouldBe false
    state.timestampsComplete shouldBe true
    state.nextOffset shouldBe Some(100)
    verify(f.api).boxesByTokenId(feed.poolNft, Paging(0, 100))
    verify(f.node, never()).getNodeWallet
    verify(f.node, never()).getClient
  }

  it should "offer a cheap current-only read and anchored history pagination" in {
    val f = new Fixture()
    val current = f.get(limit = Some(0))
    current.history shouldBe empty
    current.total shouldBe None
    verify(f.api, never()).boxesByTokenId(anyString(), any[Paging])
    val page = f.get(offset = Some(100), limit = Some(100), at = Some(blockId))
    page.history.size shouldBe 1
    page.history.head.boxId shouldBe f.boxes.last.boxId
    page.nextOffset shouldBe None
    an[LithosStateChanged] should be thrownBy f.get(at = Some("cd" * 32))
  }

  it should "mark an old price stale and preserve exact integers above JavaScript's safe range" in {
    val f = new Fixture()
    val large = f.boxes.head.copy(box = f.boxes.head.box.copy(additionalRegisters = NodeRegisters(Map(
      "R4" -> ErgoValue.of(9007199254740993L).toHex, "R5" -> ErgoValue.of(1000).toHex))))
    when(f.api.unspentBoxesByTokenId(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenReturn(Success(Seq(large)))
    when(f.api.boxById(anyString())).thenReturn(Success(Some(large.box)))
    when(f.api.info()).thenReturn(Success(nodeInfo.copy(fullHeight = Some(1100))))
    when(f.api.indexedHeight()).thenReturn(Success(BlockchainIndexHeight(1100, 1100)))
    val state = f.get(limit = Some(0))
    state.current.rawValue shouldBe "9007199254740993"
    state.current.priceDenominator shouldBe "9007199254740993"
    state.stale shouldBe true
  }

  it should "reject ambiguous, counterfeit, unsupported and malformed current pool boxes" in {
    val f = new Fixture()
    val original = f.boxes.head
    val bad = Seq(original.copy(box = original.box.copy(assets = Seq(NodeAsset(feed.poolNft, 2L)))),
      original.copy(box = original.box.copy(assets = Seq(NodeAsset("cc" * 32, 1L)))),
      original.copy(box = original.box.copy(ergoTree = "00")),
      original.copy(box = original.box.copy(additionalRegisters = NodeRegisters(Map("R4" -> ErgoValue.of(3).toHex, "R5" -> ErgoValue.of(1).toHex)))),
      original.copy(box = original.box.copy(additionalRegisters = NodeRegisters(Map("R4" -> ErgoValue.of(-1L).toHex, "R5" -> ErgoValue.of(1).toHex)))))
    bad.foreach { b =>
      when(f.api.unspentBoxesByTokenId(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
        .thenReturn(Success(Seq(b)))
      an[LithosUnavailable] should be thrownBy f.get(limit = Some(0))
    }
    when(f.api.unspentBoxesByTokenId(anyString(), any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenReturn(Success(Seq(original, original)))
    an[LithosUnavailable] should be thrownBy f.get(limit = Some(0))
  }

  it should "refuse lagging indices, spent UTXOs, broken history and a changed tip" in {
    val f = new Fixture()
    when(f.api.indexedHeight()).thenReturn(Success(BlockchainIndexHeight(999, 1000)))
    an[LithosUnavailable] should be thrownBy f.get()
    when(f.api.indexedHeight()).thenReturn(Success(BlockchainIndexHeight(1000, 1000)))
    when(f.api.boxById(anyString())).thenReturn(Success(None))
    an[LithosUnavailable] should be thrownBy f.get()
    when(f.api.boxById(anyString())).thenReturn(Success(Some(f.boxes.head.box)))
    doAnswer((_: org.mockito.invocation.InvocationOnMock) => Success(Paged(f.boxes.take(2).reverse, 2)))
      .when(f.api).boxesByTokenId(anyString(), any[Paging])
    an[LithosUnavailable] should be thrownBy f.get()
    when(f.api.info()).thenReturn(Success(nodeInfo), Success(nodeInfo.copy(bestFullHeaderId = Some("cd" * 32))))
    an[LithosStateChanged] should be thrownBy f.get(limit = Some(0))
  }

  it should "report timestamp gaps without inventing times, and validate ids, network and request bounds" in {
    val f = new Fixture()
    doAnswer((_: org.mockito.invocation.InvocationOnMock) => Failure(new RuntimeException("missing headers")))
      .when(f.api).chainSlice(any[Option[Int]], any[Option[Int]])
    val state = f.get(limit = Some(1))
    state.timestampsComplete shouldBe false
    state.current.timestamp shouldBe None
    Seq(-1, 201).foreach(n => an[LithosBadRequest] should be thrownBy f.get(limit = Some(n)))
    an[LithosBadRequest] should be thrownBy f.get(offset = Some(-1))
    an[LithosBadRequest] should be thrownBy f.get(at = Some("bad"))
    an[LithosNotFound] should be thrownBy f.service.getFeed("unknown", None, None, None)
    val testnet = new Fixture(network = NetworkType.TESTNET)
    testnet.service.listFeeds().feeds shouldBe empty
    an[LithosNotFound] should be thrownBy testnet.get()
  }
}
