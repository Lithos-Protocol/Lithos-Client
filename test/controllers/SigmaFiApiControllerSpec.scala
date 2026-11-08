package controllers

import akka.actor.ActorSystem
import akka.testkit.TestKit
import api.LithosApiErrors.{LithosStateChanged, LithosUnprocessable}
import api.SigmaFiApi
import api.models._
import com.typesafe.config.ConfigFactory
import org.mockito.ArgumentMatchers.{any, anyBoolean, anyString, eq => is}
import org.mockito.Mockito.{never, verify, when}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.Configuration
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent}
import play.api.test.Helpers._
import play.api.test.{FakeRequest, Helpers}
import scorex.crypto.hash.Blake2b256

/**
 * The `/sigmafi` routes: which need the key, which only ever check, and which status each failure gets.
 *
 * Lists, the asset lookup and every check read public chain state and sign nothing, so they stay open. The
 * portfolio names this wallet's funds and every send signs with it, so those are keyed.
 */
class SigmaFiApiControllerSpec
  extends TestKit(ActorSystem("sigmafi-controller-spec",
    ConfigFactory.parseResources("application.conf").resolve().withFallback(ConfigFactory.load())))
    with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val apiKey = "sigmafi-controller-spec-key"

  private val config = Configuration(ConfigFactory.parseString(
    s"""lithos.apiKeyHash = "${org.bouncycastle.util.encoders.Hex.toHexString(Blake2b256.hash(apiKey))}""""))

  private val boxId = "5035b2e112b4f5b873dd3444c01df1e7e697807fd84fe96790eacb128e46428d"

  private def controller(api: SigmaFiApi) = new SigmaFiApiController(Helpers.stubControllerComponents(), api, config, system)

  private def keyed[A](request: FakeRequest[A]) = request.withHeaders("api_key" -> apiKey)

  private val orderBody = Json.obj("collateralNanoErgs" -> "5000000000", "principalNanoErgs" -> "1000000000",
    "repayment" -> "1100000000", "maturityHeight" -> 2000000)
  private val quote = SFQuote("x", None, None, "0", Seq.empty, "0", Seq.empty, "1000000", Seq.empty, Seq.empty)
  private val result = SFResult("ab" * 32, "accepted", Some(boxId), None)

  /** Every send, with the request each needs. */
  private def sends(c: SigmaFiApiController): Seq[(String, Action[AnyContent])] = Seq(
    "create order" -> c.createOrder(), "cancel order" -> c.cancelOrder(boxId),
    "fill" -> c.fillOrder(boxId),
    "repay" -> c.repay(boxId), "liquidate" -> c.liquidate(boxId), "claim" -> c.claim(boxId))

  private def checks(c: SigmaFiApiController): Seq[(String, Action[AnyContent])] = Seq(
    "create order" -> c.checkCreateOrder(),
    "cancel order" -> c.checkCancelOrder(boxId),
    "fill" -> c.checkFillOrder(boxId), "repay" -> c.checkRepay(boxId),
    "liquidate" -> c.checkLiquidate(boxId), "claim" -> c.checkClaim(boxId))

  private def body(name: String) = orderBody

  private def stubbed(): SigmaFiApi = {
    val api = mock[SigmaFiApi]
    when(api.createOrder(any[SFOrderRequest], anyBoolean())).thenAnswer(i => answer(i.getArgument[Boolean](1)))
    when(api.cancelOrder(anyString(), anyBoolean())).thenAnswer(i => answer(i.getArgument[Boolean](1)))
    when(api.fillOrder(anyString(), anyBoolean(), org.mockito.ArgumentMatchers.eq(None))).thenAnswer(i => answer(i.getArgument[Boolean](1)))
    when(api.repay(anyString(), anyBoolean())).thenAnswer(i => answer(i.getArgument[Boolean](1)))
    when(api.liquidate(anyString(), anyBoolean())).thenAnswer(i => answer(i.getArgument[Boolean](1)))
    when(api.claim(anyString(), anyBoolean())).thenAnswer(i => answer(i.getArgument[Boolean](1)))
    api
  }

  private def answer(send: Boolean): Either[SFQuote, SFResult] = if (send) Right(result) else Left(quote)

  "Every send, and the portfolio" should "be 403 without an api_key and never reach the API" in {
    val api = mock[SigmaFiApi]
    val c = controller(api)
    (sends(c) :+ ("portfolio" -> c.getPortfolio())).foreach { case (name, action) =>
      withClue(s"$name: ")(status(action.apply(FakeRequest(POST, "/sigmafi").withJsonBody(body(name)))) shouldBe FORBIDDEN)
    }
    verify(api, never()).getPortfolio
    verify(api, never()).createOrder(any[SFOrderRequest], anyBoolean())
    verify(api, never()).cancelOrder(anyString(), anyBoolean())
    verify(api, never()).fillOrder(anyString(), anyBoolean(), org.mockito.ArgumentMatchers.eq(None))
    verify(api, never()).repay(anyString(), anyBoolean())
    verify(api, never()).liquidate(anyString(), anyBoolean())
    verify(api, never()).claim(anyString(), anyBoolean())
  }

  "Every check" should "need no api_key, and only ever ask the API not to send" in {
    val api = stubbed()
    val c = controller(api)
    checks(c).foreach { case (name, action) =>
      val response = action.apply(FakeRequest(POST, "/sigmafi").withJsonBody(body(name)))
      withClue(s"$name: ") {
        status(response) shouldBe OK
        (contentAsJson(response) \ "action").as[String] shouldBe "x"
      }
    }
    verify(api, never()).createOrder(any[SFOrderRequest], is(true))
    verify(api, never()).cancelOrder(anyString(), is(true))
    verify(api, never()).fillOrder(anyString(), is(true), org.mockito.ArgumentMatchers.eq(None))
    verify(api, never()).repay(anyString(), is(true))
    verify(api, never()).liquidate(anyString(), is(true))
    verify(api, never()).claim(anyString(), is(true))
  }

  "Every send" should "ask the API to send, with the key" in {
    val api = stubbed()
    val c = controller(api)
    sends(c).foreach { case (name, action) =>
      val response = action.apply(keyed(FakeRequest(POST, "/sigmafi")).withJsonBody(body(name)))
      withClue(s"$name: ") {
        status(response) shouldBe OK
        (contentAsJson(response) \ "txId").as[String] shouldBe result.txId
      }
    }
  }

  "Lists and the asset lookup" should "need no api_key" in {
    val api = mock[SigmaFiApi]
    when(api.listOrders(any(), any(), any(), any())).thenReturn(SFOrderBook(1, Seq.empty, 0, truncated = false))
    when(api.listBonds(any(), any(), any())).thenReturn(SFBondList(1, Seq.empty, 0, 0, truncated = false))
    when(api.lookupAssets(any[SFAssetLookupRequest])).thenReturn(Seq.empty)
    val c = controller(api)
    status(c.listOrders(None, None, None, None).apply(FakeRequest())) shouldBe OK
    status(c.listBonds(None, None, None).apply(FakeRequest())) shouldBe OK
    status(c.lookupAssets().apply(FakeRequest(POST, "/sigmafi/assets").withJsonBody(Json.obj("tokenIds" -> Seq(boxId))))) shouldBe OK
  }

  "Failures" should "map to the status a caller can act on" in {
    val api = mock[SigmaFiApi]
    when(api.fillOrder(anyString(), anyBoolean(), org.mockito.ArgumentMatchers.eq(None))).thenThrow(LithosUnprocessable("no"))
    when(api.repay(anyString(), anyBoolean())).thenThrow(LithosStateChanged("spent"))
    val c = controller(api)
    status(c.checkFillOrder(boxId).apply(FakeRequest())) shouldBe UNPROCESSABLE_ENTITY
    status(c.checkRepay(boxId).apply(FakeRequest())) shouldBe CONFLICT
    status(c.checkCreateOrder().apply(FakeRequest(POST, "/").withJsonBody(Json.obj("x" -> 1)))) shouldBe BAD_REQUEST
  }

  "Fill requests" should "pass the reserve override through checks and keyed sends and reject malformed bodies" in {
    val api = mock[SigmaFiApi]
    when(api.fillOrder(anyString(), anyBoolean(), any[Option[String]]))
      .thenAnswer(i => answer(i.getArgument[Boolean](1)))
    val c = controller(api)
    val request = FakeRequest(POST, "/sigmafi").withJsonBody(Json.obj("batchingFee" -> "75000000"))
    status(c.checkFillOrder(boxId).apply(request)) shouldBe OK
    verify(api).fillOrder(boxId, false, Some("75000000"))
    status(c.fillOrder(boxId).apply(keyed(request))) shouldBe OK
    verify(api).fillOrder(boxId, true, Some("75000000"))
    status(c.checkFillOrder(boxId).apply(FakeRequest(POST, "/").withJsonBody(Json.obj("batchingFee" -> 1)))) shouldBe BAD_REQUEST
    status(c.checkFillOrder(boxId).apply(FakeRequest(POST, "/").withJsonBody(Json.arr()))) shouldBe BAD_REQUEST
  }

  "Asset verification" should "expose a named settlement even when its vault has already been claimed" in {
    val api = mock[SigmaFiApi]
    val vault = SFVaultView("cd" * 32, Some(boxId), "ef" * 32, "2000000000", Seq.empty)
    val settlement = SFSettlement("aa" * 32, 123456, Some(1700000000000L), boxId, vault, None, Some("ab" * 32))
    when(api.lookupAssets(any[SFAssetLookupRequest])).thenReturn(Seq(
      SFAssetLookup("ef" * 32, bondAsset = true, verified = true, None, None, Seq.empty,
        status = "Liquidated", settlement = Some(settlement))))
    val response = controller(api).lookupAssets().apply(FakeRequest(POST, "/sigmafi/assets")
      .withJsonBody(Json.obj("tokenIds" -> Seq("ef" * 32))))
    status(response) shouldBe OK
    val asset = contentAsJson(response).as[Seq[play.api.libs.json.JsValue]].head
    (asset \ "status").as[String] shouldBe "Liquidated"
    (asset \ "vault").as[Seq[play.api.libs.json.JsValue]] shouldBe empty
    (asset \ "settlement" \ "txId").as[String] shouldBe settlement.txId
    (asset \ "settlement" \ "vault" \ "nanoErgs").as[String] shouldBe "2000000000"
  }
}
