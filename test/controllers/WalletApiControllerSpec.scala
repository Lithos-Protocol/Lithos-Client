package controllers

import akka.actor.ActorSystem
import akka.testkit.TestKit
import api.models._
import api.{LithosApiErrors, WalletApi}
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import play.api.Configuration
import play.api.libs.json.JsValue
import play.api.test.Helpers._
import play.api.test.{FakeRequest, Helpers}
import scorex.crypto.hash.Blake2b256

import scala.concurrent.Future

/**
 * The wallet route layer: which key gets in, and which exception becomes which status.
 *
 * The first controller spec in this codebase. `AUDIT-MASTER.MD` §7.3 has listed the route layer as
 * audited-but-untested since 2026-08-18, and the 2026-08-19 LithosDex pass found three status
 * errors in exactly this kind of code after four earlier reads had walked past them — a mapping
 * only ever read is a mapping nobody has run.
 *
 * The ActorSystem is built from the shipped `application.conf` because the controller resolves the
 * dex dispatcher through `Contexts`, whose fields are `val`s: a missing block fails construction.
 */
class WalletApiControllerSpec
  extends TestKit(ActorSystem("wallet-controller-spec",
    ConfigFactory.parseResources("application.conf").resolve().withFallback(ConfigFactory.load())))
    with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val apiKey = "wallet-controller-spec-key"

  private val config = Configuration(ConfigFactory.parseString(
    s"""lithos.apiKeyHash = "${org.bouncycastle.util.encoders.Hex.toHexString(Blake2b256.hash(apiKey))}""""))

  private val balances = WalletBalances(
    primaryAddress = "3WwbzR34YmLRy3Z6WLON5hBBkX8Zm1gUHmC7tEXDNRJfHBqhsZr",
    nanoErgs = "182400000000",
    tokens = Seq(WalletToken("aa" * 32, Some("LIT"), Some(9), "45000000000")),
    collateral = WalletCollateralStatus(
      autoCollateralize = false,
      maxOwnCollateral = 10,
      maxJoinsPerRun = 5,
      maxLenderKeys = 32,
      maxPermitPerJoinLit = "30000000000000",
      ownPositionsQueued = 0,
      ownPositionsLive = 0,
      ownBudgetRemaining = 10,
      freeLenderKeys = 4,
      keysDerivable = 28,
      lenderKeys = Seq.empty,
      positions = Seq.empty,
      rewards = CollateralRewardSummary(0, 0, "0", "0", None)))

  private def controller(answer: => WalletBalances): WalletApiController =
    new WalletApiController(Helpers.stubControllerComponents(), new WalletApi {
      override def getWalletBalances: WalletBalances = answer
    }, config, system)

  private def call(c: WalletApiController, key: Option[String]): Future[play.api.mvc.Result] = {
    val base = FakeRequest(GET, "/wallet/balances")
    c.getWalletBalances().apply(key.fold(base)(k => base.withHeaders("api_key" -> k)))
  }

  "A request with no api_key" should "be 403, not 200 and not 500" in {
    val result = call(controller(balances), None)
    status(result) shouldBe FORBIDDEN
    withClue("the body must not leak the balance it refused to serve: ") {
      contentAsString(result) should not include "182400000000"
    }
  }

  it should "be 403 when the key is wrong" in {
    status(call(controller(balances), Some("not-the-key"))) shouldBe FORBIDDEN
  }

  "A request with the right api_key" should "be 200 carrying both halves of the wallet" in {
    val result = call(controller(balances), Some(apiKey))
    status(result) shouldBe OK
    val json = contentAsJson(result)
    (json \ "nanoErgs").as[String] shouldBe "182400000000"
    (json \ "primaryAddress").as[String] shouldBe balances.primaryAddress
    withClue("the collateral half must be nested, not flattened: ") {
      (json \ "collateral" \ "freeLenderKeys").as[Int] shouldBe 4
      (json \ "collateral" \ "keysDerivable").as[Int] shouldBe 28
    }
  }

  "A node that will not answer" should "be 503 rather than 500" in {
    // The distinction the caller acts on: 503 says retry, 500 says stop. A locked wallet is the
    // former, and it reaches the controller as LithosUnavailable from WalletApiImpl.
    val result = call(controller(throw LithosApiErrors.LithosUnavailable("wallet is locked")), Some(apiKey))
    status(result) shouldBe SERVICE_UNAVAILABLE
    contentAsString(result) should include("wallet is locked")
  }

  "An emission box that does not exist yet" should "be 404 rather than 500" in {
    // Reachable in earnest: /wallet/balances composes the collateral status, whose first act is to
    // find the emission box. Before emission launches on a network there is not one.
    val result = call(
      controller(throw new stratum.CollateralNotFoundException("no emission box")), Some(apiKey))
    status(result) shouldBe NOT_FOUND
  }

  "A defect in this client" should "still be 500" in {
    // The catch-all has to stay a catch-all. A mapping that quietly turned every fault into a
    // retryable status would tell callers to keep hammering a broken client.
    val result = call(controller(throw new RuntimeException("boom")), Some(apiKey))
    status(result) shouldBe INTERNAL_SERVER_ERROR
  }
}
