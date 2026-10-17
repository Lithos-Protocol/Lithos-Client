package controllers

import akka.actor.ActorSystem
import akka.testkit.TestKit
import api.LithosApiErrors.{LithosBadRequest, LithosNotFound, LithosStateChanged, LithosUnavailable, LithosUnprocessable}
import api.ForwardsApi
import api.models._
import com.typesafe.config.ConfigFactory
import mutations.NotEnoughInputsException
import org.mockito.ArgumentMatchers.{any, anyString}
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
 * The `/forwards` routes: which need the key, and which status each failure a caller can act on gets.
 *
 * Reads and checks read only public chain state and stay open. This wallet's positions name its addresses and
 * funds, so they are keyed like every endpoint that signs.
 */
class ForwardsApiControllerSpec
  extends TestKit(ActorSystem("forwards-controller-spec",
    ConfigFactory.parseResources("application.conf").resolve().withFallback(ConfigFactory.load())))
    with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val apiKey = "forwards-controller-spec-key"

  private val config = Configuration(ConfigFactory.parseString(
    s"""lithos.apiKeyHash = "${org.bouncycastle.util.encoders.Hex.toHexString(Blake2b256.hash(apiKey))}""""))

  private val boxId = "5035b2e112b4f5b873dd3444c01df1e7e697807fd84fe96790eacb128e46428d"

  private def controller(api: ForwardsApi) =
    new ForwardsApiController(Helpers.stubControllerComponents(), api, config, system)

  private def keyed[A](request: FakeRequest[A]) = request.withHeaders("api_key" -> apiKey)

  private val offerBody = Json.obj("makerSide" -> "long", "strike" -> "64000000000000", "notional" -> "10000000000000",
    "windowStart" -> 14814, "windowLength" -> 8)

  private val limits = DFOfferLimits(14814, 14932, 120, 1896064, 8, "1000000000000")

  "Every endpoint that signs, and the position list" should "be 403 without an api_key and never reach the API" in {
    val api = mock[ForwardsApi]
    val c = controller(api)
    val gated: Seq[(String, Action[AnyContent])] = Seq(
      "positions" -> c.listPositions(),
      "history" -> c.getHistory(),
      "create" -> c.createOffer(),
      "fill" -> c.fill(boxId),
      "cancel" -> c.cancel(boxId),
      "settle" -> c.settle(boxId))
    gated.foreach { case (name, action) =>
      withClue(s"$name: ")(status(action.apply(FakeRequest(POST, "/forwards").withJsonBody(offerBody))) shouldBe FORBIDDEN)
    }
    verify(api, never()).listPositions
    verify(api, never()).getHistory
    verify(api, never()).createOffer(any[DFOfferRequest])
    verify(api, never()).fill(anyString())
    verify(api, never()).cancel(anyString())
    verify(api, never()).settle(anyString())
  }

  "Reads and checks" should "need no api_key" in {
    val api = mock[ForwardsApi]
    when(api.getAccumulator).thenReturn(DFAccumulatorInfo(deployed = false, 1895966, 14812, 99, "64267743133696",
      Some(DFNextDifficulty(14813, "63000000000000", exact = false, 1895966, -0.0197)), None, None, None, None, None,
      Seq.empty, None, limits))
    when(api.listOffers(any[Option[Int]], any[Option[Int]])).thenReturn(DFOfferBook(1895966, 14812, Seq.empty, 0,
      truncated = false))
    val c = controller(api)
    val accumulator = c.getAccumulator().apply(FakeRequest(GET, "/forwards/accumulator"))
    status(accumulator) shouldBe OK
    (contentAsJson(accumulator) \ "nextDifficulty" \ "difficulty").as[String] shouldBe "63000000000000"
    (contentAsJson(accumulator) \ "limits" \ "shortWindowMaxNotional").as[String] shouldBe "1000000000000"
    status(c.listOffers(Some(0), Some(10)).apply(FakeRequest(GET, "/forwards/offers"))) shouldBe OK
    when(api.getMarket).thenReturn(DFMarket(1895966, 14812, DFBookSide(2, "20000000000000"), DFBookSide(0, "0"), 3,
      "30000000000000", 1, 1, 1, 0, Some(5000L), truncated = false))
    val market = c.getMarket().apply(FakeRequest(GET, "/forwards/market"))
    status(market) shouldBe OK
    (contentAsJson(market) \ "longs" \ "offers").as[Int] shouldBe 2

    when(api.checkSettle(anyString())).thenReturn(DFSettleQuote(boxId, refund = false, records = true, "2000000000000",
      "1600000000000", "2000000000", "1000000"))
    val settle = c.checkSettle(boxId).apply(FakeRequest(POST, s"/forwards/positions/$boxId/settle/check"))
    status(settle) shouldBe OK
    (contentAsJson(settle) \ "rewardLit").as[String] shouldBe "2000000000"
  }

  "An offer check" should "parse the body, and be 400 for a body that does not" in {
    val api = mock[ForwardsApi]
    when(api.checkOffer(any[DFOfferRequest])).thenReturn(DFOfferQuote("long", "64000000000000", "10000000000000",
      "2000000000000", 14814, 8, 14821, 1896064, 97, 1896064, "2001500000000", "2000000", "1000000", "2001500000000",
      Some(-0.004), None, Seq.empty))
    val c = controller(api)
    status(c.checkOffer().apply(FakeRequest(POST, "/forwards/offers/check").withJsonBody(offerBody))) shouldBe OK
    status(c.checkOffer().apply(FakeRequest(POST, "/forwards/offers/check")
      .withJsonBody(offerBody - "windowLength"))) shouldBe BAD_REQUEST
    status(c.checkOffer().apply(FakeRequest(POST, "/forwards/offers/check"))) shouldBe BAD_REQUEST
  }

  "A failure" should "carry the status a caller can act on" in {
    val cases: Seq[(Throwable, Int)] = Seq(
      LithosBadRequest("bad") -> BAD_REQUEST,
      LithosStateChanged("already spent by unconfirmed transaction") -> CONFLICT,
      LithosUnprocessable("expired") -> UNPROCESSABLE_ENTITY,
      new NotEnoughInputsException("short of LIT") -> UNPROCESSABLE_ENTITY,
      LithosNotFound("no offer") -> NOT_FOUND,
      LithosUnavailable("node down") -> SERVICE_UNAVAILABLE,
      new IllegalArgumentException("a builder require") -> BAD_REQUEST,
      new IllegalStateException("a defect") -> INTERNAL_SERVER_ERROR)
    cases.foreach { case (failure, expected) =>
      val api = mock[ForwardsApi]
      when(api.fill(anyString())).thenAnswer(_ => throw failure)
      withClue(s"${failure.getClass.getSimpleName}: ") {
        status(controller(api).fill(boxId).apply(keyed(FakeRequest(POST, s"/forwards/offers/$boxId/fill")))) shouldBe
          expected
      }
    }
  }

  "A keyed fill" should "answer with the transaction and the position it creates" in {
    val api = mock[ForwardsApi]
    when(api.fill(anyString())).thenReturn(DFFillResult("ab" * 32, "accepted", "cd" * 32))
    val result = controller(api).fill(boxId).apply(keyed(FakeRequest(POST, s"/forwards/offers/$boxId/fill")))
    status(result) shouldBe OK
    (contentAsJson(result) \ "positionBoxId").as[String] shouldBe "cd" * 32
    verify(api).fill(boxId)
  }
}
