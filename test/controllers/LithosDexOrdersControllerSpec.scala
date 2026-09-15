package controllers

import akka.actor.ActorSystem
import akka.testkit.{TestKit, TestProbe}
import api.models._
import api.{LithosApiErrors, LithosDexApi}
import cache.LDCache
import com.typesafe.config.ConfigFactory
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.{never, verify, when}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.Configuration
import play.api.libs.json.Json
import play.api.test.Helpers._
import play.api.test.{FakeRequest, Helpers}
import scorex.crypto.hash.Blake2b256
import support.FakeCache

/**
 * The order routes: which need the key, and what a cancel racing a fill is told.
 *
 * The order list names this wallet's addresses and funds, so it is keyed like every endpoint that
 * signs. The order quotes read only public pool state and stay open, like the direct quotes.
 */
class LithosDexOrdersControllerSpec
  extends TestKit(ActorSystem("lithosdex-orders-controller-spec",
    ConfigFactory.parseResources("application.conf").resolve().withFallback(ConfigFactory.load())))
    with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val apiKey = "lithosdex-orders-controller-spec-key"

  private val config = Configuration(ConfigFactory.parseString(
    s"""lithos.apiKeyHash = "${org.bouncycastle.util.encoders.Hex.toHexString(Blake2b256.hash(apiKey))}""""))

  private val order = LDOrder("5035b2e112b4f5b873dd3444c01df1e7e697807fd84fe96790eacb128e46428d", "SWAP", "OPEN",
    "ab" * 32, Some(484719), None, "3WwbzR34YmLRy3Z6WLON5hBBkX8Zm1gUHmC7tEXDNRJfHBqhsZr", "1004000000", "3000000",
    "1000000", ergIn = Some(true), amountIn = Some("1000000000"), minOutput = Some("29974258"), fillableNow = true)

  private def controller(api: LithosDexApi) =
    new LithosDexApiController(Helpers.stubControllerComponents(), api, config, new FakeCache, TestProbe().ref, system)

  private def keyed[A](request: FakeRequest[A]) = request.withHeaders("api_key" -> apiKey)

  "Listing orders without an api_key" should "be 403 and never reach the node" in {
    val api = mock[LithosDexApi]
    status(controller(api).listOrders().apply(FakeRequest(GET, "/dex/orders"))) shouldBe FORBIDDEN
    verify(api, never()).listOrders(any[LDCache])
  }

  "Listing orders with the api_key" should "be 200 with the order under its spec field names" in {
    val api = mock[LithosDexApi]
    when(api.listOrders(any[LDCache])).thenReturn(LDOrderList(Seq(order)))
    val result = controller(api).listOrders().apply(keyed(FakeRequest(GET, "/dex/orders")))
    status(result) shouldBe OK
    val first = (contentAsJson(result) \ "orders")(0)
    (first \ "type").as[String] shouldBe "SWAP"
    (first \ "status").as[String] shouldBe "OPEN"
    (first \ "spendingTxId").toOption shouldBe None
  }

  "Cancelling without an api_key" should "be 403 and never reach the engine" in {
    val api = mock[LithosDexApi]
    status(controller(api).cancelOrder(order.boxId).apply(FakeRequest(POST, s"/dex/orders/${order.boxId}/cancel"))) shouldBe FORBIDDEN
    verify(api, never()).cancelOrder(anyString(), any[LDCache])
  }

  "Cancelling an order a fill already spends" should "be 409" in {
    val api = mock[LithosDexApi]
    when(api.cancelOrder(anyString(), any[LDCache]))
      .thenThrow(LithosApiErrors.LithosStateChanged("order is already being filled or cancelled"))
    status(controller(api).cancelOrder(order.boxId)
      .apply(keyed(FakeRequest(POST, s"/dex/orders/${order.boxId}/cancel")))) shouldBe CONFLICT
  }

  "Quoting a swap order" should "need no api_key" in {
    val api = mock[LithosDexApi]
    val swap = LDSwapQuote(ergIn = true, "1000000000", "1500000", "1497750", "998500000", "30124883", "2997750",
      0.0302, 0.0301, 0.0301, 0.0031, isExecutable = true)
    when(api.checkSwapOrder(any[LDSwapOrderRequest], any[LDCache]))
      .thenReturn(LDSwapOrderQuote(swap, "3000000", "1000000", "30124883", "1004000000", "1000000", "2000000"))
    val request = FakeRequest(POST, "/dex/orders/swap/check")
      .withJsonBody(Json.obj("amountIn" -> "1000000000", "ergIn" -> true))
    val result = controller(api).checkSwapOrder().apply(request)
    status(result) shouldBe OK
    (contentAsJson(result) \ "netOutput").as[String] shouldBe "30124883"
  }
}
