package controllers

import akka.actor.ActorSystem
import akka.testkit.TestKit
import api.models._
import api.{CollateralMarketApi, LithosApiErrors}
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import play.api.Configuration
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{AnyContentAsEmpty, AnyContentAsJson, Result}
import play.api.test.Helpers._
import play.api.test.{FakeRequest, Helpers}
import scorex.crypto.hash.Blake2b256
import transactions.engine.wallet.FundingExpiredException

import scala.concurrent.Future

/**
 * The collateral join routes: which key gets in, which refusal becomes which status, and that the
 * quote fields a script reads (`blockedReason`, `keysDerivable`, `fitting`) reach the wire.
 *
 * The API is a stub, so every status here is decided by the controller's own mapping. The engine's
 * answers it stands in for are the ones `CollateralMarketApiImpl.join` and `CollateralExecution` throw.
 */
class CollateralMarketApiControllerSpec
  extends TestKit(ActorSystem("collateral-controller-spec",
    ConfigFactory.parseResources("application.conf").resolve().withFallback(ConfigFactory.load())))
    with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val apiKey = "collateral-controller-spec-key"

  private val config = Configuration(ConfigFactory.parseString(
    s"""lithos.apiKeyHash = "${org.bouncycastle.util.encoders.Hex.toHexString(Blake2b256.hash(apiKey))}""""))

  private def quote(blocked: Option[String], fitting: Int): CollateralJoinQuote =
    CollateralJoinQuote(count = 3, principalEachNanoErgs = "1000000000", priorityFeeEachNanoErgs = "0",
      minPriorityFeeNanoErgs = "0", breakEvenPriorityFeeNanoErgs = "0", netAtCoinbaseEachNanoErgs = "0",
      txFeeEachNanoErgs = "1000000", permitsLit = Seq("2000", "2000", "2000"), permitTotalLit = "6000",
      totalNanoErgs = "3003000000", firstPosition = 41L, emissionTipBoxId = "ee" * 32, affordNow = true,
      shortfallNanoErgs = "0", ownRoomLeft = 8, manualJoinAllowed = blocked.isEmpty, blockedReason = blocked,
      lenderAddresses = Seq.empty, freeLenderKeys = 1, keysRequired = 2, keysDerivable = 0, fitting = fitting)

  private val sent = CollateralJoinResult(
    Seq(CollateralJoinEntry("aa" * 32, 41L, "9lender", "1000000000", "2000")), "1001000000", None)

  /** Answers every route from the functions given; the rest are never reached by these tests. */
  private class StubApi(onCheck: CollateralJoinCheckRequest => CollateralJoinQuote,
                        onJoin: CollateralJoinExecuteRequest => CollateralJoinResult) extends CollateralMarketApi {
    var joined: Option[CollateralJoinExecuteRequest] = None
    override def checkJoin(request: CollateralJoinCheckRequest): CollateralJoinQuote = onCheck(request)
    override def join(request: CollateralJoinExecuteRequest): CollateralJoinResult = {
      joined = Some(request)
      onJoin(request)
    }
    override def getMarketInfo: CollateralMarketInfo = ???
    override def getQueue(limit: Option[Int], offset: Option[Int]): Seq[CollateralQueueEntry] = ???
    override def getActiveSet(limit: Option[Int], offset: Option[Int]): Seq[CollateralActiveEntry] = ???
    override def getPermitHistory(limit: Option[Int]): CollateralPermitHistory = ???
    override def getWalletStatus: WalletCollateralStatus = ???
    override def getRewardSummary: CollateralRewardSummary = ???
    override def claimUnlockedRewards: CollateralRewardClaimResult = ???
  }

  private def controller(api: CollateralMarketApi): CollateralMarketApiController =
    new CollateralMarketApiController(Helpers.stubControllerComponents(), api, config, system)

  private def joinFailing(ex: => Throwable): StubApi = new StubApi(_ => quote(None, 3), _ => throw ex)

  private def joinBody(count: Int): JsValue =
    Json.obj("count" -> count, "acknowledgeNoWithdrawal" -> true)

  private def join(api: CollateralMarketApi, body: Option[JsValue] = Some(joinBody(3)),
                   key: Option[String] = Some(apiKey)): Future[Result] = {
    val base = FakeRequest(POST, "/collateral/join")
    val keyed = key.fold(base)(k => base.withHeaders("api_key" -> k))
    body match {
      case Some(json) => controller(api).join().apply(keyed.withBody(AnyContentAsJson(json)))
      case None => controller(api).join().apply(keyed.withBody(AnyContentAsEmpty))
    }
  }

  private def check(api: CollateralMarketApi): Future[Result] =
    controller(api).checkJoin().apply(FakeRequest(POST, "/collateral/join/check")
      .withHeaders("api_key" -> apiKey).withBody(AnyContentAsJson(Json.obj("count" -> 3))))

  "POST /collateral/join without the api key" should "be 403 and never reach the engine" in {
    val api = new StubApi(_ => quote(None, 3), _ => sent)
    status(join(api, key = None)) shouldBe FORBIDDEN
    status(join(api, key = Some("wrong"))) shouldBe FORBIDDEN
    api.joined shouldBe None
  }

  it should "pass counts up to the cap through to the engine and answer 200" in {
    Seq(11, 25).foreach { count =>
      val api = new StubApi(_ => quote(None, count), _ => sent)
      val result = join(api, Some(joinBody(count)))
      status(result) shouldBe OK
      api.joined.map(_.count) shouldBe Some(count)
      (contentAsJson(result) \ "joins" \ 0 \ "txId").as[String] shouldBe "aa" * 32
    }
  }

  "A join the engine deferred" should "be 503, never 500" in {
    val deferred = joinFailing(LithosApiErrors.LithosUnavailable(
      "the transaction engine did not run the join, so nothing was sent: critical work queued"))
    val result = join(deferred)
    status(result) shouldBe SERVICE_UNAVAILABLE
    contentAsString(result) should include("nothing was sent")
  }

  "A join the engine has not answered" should "be 503 and say it may still send" in {
    val timedOut = joinFailing(LithosApiErrors.LithosUnavailable(
      "the transaction engine has not answered and may still send. Read GET /collateral/wallet before trying again"))
    val result = join(timedOut)
    status(result) shouldBe SERVICE_UNAVAILABLE
    contentAsString(result) should include("may still send")
  }

  "A join with fewer positions fitting than asked" should "be 422 and carry fitting in the detail" in {
    val short = joinFailing(LithosApiErrors.LithosUnprocessable(
      "only 1 of 3 position(s) fit within the configured exposure budget (10) and free lender keys; " +
        "the node wallet could not be unlocked, so no key was derived - nothing was sent; fitting=1"))
    val result = join(short)
    status(result) shouldBe UNPROCESSABLE_ENTITY
    contentAsString(result) should include("fitting=1")
  }

  "A manual join while autoCollateralize is on" should "be 409" in {
    val owned = joinFailing(LithosApiErrors.LithosStateChanged(
      "autoCollateralize is set, so this client's automated pass owns lender-key selection"))
    status(join(owned)) shouldBe CONFLICT
  }

  "A count outside the allowed range" should "be 400" in {
    status(join(joinFailing(new IllegalArgumentException("count must be between 1 and 25")))) shouldBe BAD_REQUEST
  }

  "A join whose funding selection expired" should "be 503, not a shortfall" in {
    val busy = join(joinFailing(new FundingExpiredException("selection expired before it ran")))
    status(busy) shouldBe SERVICE_UNAVAILABLE
    contentAsString(busy) should not include "INSUFFICIENT_FUNDS"
  }

  "A join with no JSON body" should "be 400, not 500" in {
    status(join(new StubApi(_ => quote(None, 3), _ => sent), body = None)) shouldBe BAD_REQUEST
  }

  "A join body without a count" should "be 400 and never reach the engine" in {
    val api = new StubApi(_ => quote(None, 3), _ => sent)
    status(join(api, Some(Json.obj("acknowledgeNoWithdrawal" -> true)))) shouldBe BAD_REQUEST
    api.joined shouldBe None
  }

  "An unexpected failure" should "be the only path to 500" in {
    status(join(joinFailing(new IllegalStateException("unexpected answer from the transaction engine")))) shouldBe
      INTERNAL_SERVER_ERROR
  }

  "POST /collateral/join/check with a locked node wallet" should "be 200 with WALLET_LOCKED and the key fields" in {
    val result = check(new StubApi(_ => quote(Some(CollateralJoinBlock.WalletLocked), 1), _ => sent))
    status(result) shouldBe OK
    val json = contentAsJson(result)
    (json \ "blockedReason").as[String] shouldBe "WALLET_LOCKED"
    (json \ "manualJoinAllowed").as[Boolean] shouldBe false
    (json \ "keysDerivable").as[Int] shouldBe 0
    (json \ "fitting").as[Int] shouldBe 1
  }
}
