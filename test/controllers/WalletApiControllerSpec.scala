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
import play.api.mvc.{Action, AnyContent, Result}
import play.api.test.Helpers._
import play.api.test.{FakeRequest, Helpers}
import scorex.crypto.hash.Blake2b256

import scala.concurrent.Future

/**
 * The wallet route layer: which key gets in, which routes need none, and which exception becomes
 * which status. The API is a stub, so every status here is decided by the controller's own mapping.
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

  private val litId = "aa" * 32

  private val balances = WalletBalances(
    primaryAddress = "3WwbzR34YmLRy3Z6WLON5hBBkX8Zm1gUHmC7tEXDNRJfHBqhsZr",
    nanoErgs = "182400000000",
    reservedBoxes = 2,
    reservedNanoErgs = "3000000000",
    tokens = Seq(WalletToken(litId, Some("LIT"), Some("LIT"), Some(9), "45000000000", verified = true)))

  private val arbStake = WalletArbStake("arbAddress", enabled = true, paused = false, "10000000000", "10000000000",
    1, "10000000000")

  private class StubApi(onBalances: => WalletBalances = balances,
                        onClaim: => WalletRewardClaimResult = WalletRewardClaimResult(Seq.empty, "0"),
                        onArb: => WalletArbStake = arbStake,
                        onReclaim: => WalletArbReclaimResult =
                          WalletArbReclaimResult("ab" * 32, 1, "10000000000", "accepted", 0))
    extends WalletApi {
    override def getArbStake: WalletArbStake = onArb
    override def reclaimArbStake: WalletArbReclaimResult = onReclaim
    override def getWalletBalances: WalletBalances = onBalances
    override def getRewardSummary: WalletRewardSummary = WalletRewardSummary(4, 2, "12000000000", "6000000000", Some(30))
    override def claimUnlockedRewards: WalletRewardClaimResult = onClaim
    override def getVerifiedTokens: VerifiedTokenList =
      VerifiedTokenList(Seq(VerifiedTokenInfo(litId, "LIT", "LIT", 9)))
  }

  private def controller(api: WalletApi = new StubApi()): WalletApiController =
    new WalletApiController(Helpers.stubControllerComponents(), api, config, system)

  private def call(action: Action[AnyContent], method: String, path: String, key: Option[String]): Future[Result] = {
    val base = FakeRequest(method, path)
    action.apply(key.fold(base)(k => base.withHeaders("api_key" -> k)))
  }

  private def balancesWith(c: WalletApiController, key: Option[String]): Future[Result] =
    call(c.getWalletBalances(), GET, "/wallet/balances", key)

  "GET /wallet/balances with no api_key" should "be 403, not 200 and not 500" in {
    val result = balancesWith(controller(), None)
    status(result) shouldBe FORBIDDEN
    withClue("the body must not leak the balance it refused to serve: ") {
      contentAsString(result) should not include "182400000000"
    }
  }

  it should "be 403 when the key is wrong" in {
    status(balancesWith(controller(), Some("not-the-key"))) shouldBe FORBIDDEN
  }

  "GET /wallet/balances with the right api_key" should "be 200 carrying the wallet alone" in {
    val result = balancesWith(controller(), Some(apiKey))
    status(result) shouldBe OK
    val json = contentAsJson(result)
    (json \ "nanoErgs").as[String] shouldBe "182400000000"
    (json \ "reservedBoxes").as[Int] shouldBe 2
    (json \ "reservedNanoErgs").as[String] shouldBe "3000000000"
    (json \ "tokens" \ 0 \ "verified").as[Boolean] shouldBe true
    withClue("the lending status is served by /collateral/wallet now: ") {
      (json \ "collateral").toOption shouldBe None
    }
  }

  "A node that will not answer" should "be 503 rather than 500" in {
    // The distinction the caller acts on: 503 says retry, 500 says stop. A locked wallet is the
    // former, and it reaches the controller as LithosUnavailable from WalletApiImpl.
    val result = balancesWith(
      controller(new StubApi(onBalances = throw LithosApiErrors.LithosUnavailable("wallet is locked"))), Some(apiKey))
    status(result) shouldBe SERVICE_UNAVAILABLE
    contentAsString(result) should include("wallet is locked")
  }

  "A defect in this client" should "still be 500" in {
    val result = balancesWith(controller(new StubApi(onBalances = throw new RuntimeException("boom"))), Some(apiKey))
    status(result) shouldBe INTERNAL_SERVER_ERROR
  }

  "GET /wallet/rewards" should "need the api_key" in {
    status(call(controller().getRewardSummary(), GET, "/wallet/rewards", None)) shouldBe FORBIDDEN
    val result = call(controller().getRewardSummary(), GET, "/wallet/rewards", Some(apiKey))
    status(result) shouldBe OK
    (contentAsJson(result) \ "unlockedNanoErgs").as[String] shouldBe "6000000000"
  }

  "POST /wallet/rewards/claim" should "need the api_key" in {
    status(call(controller().claimRewards(), POST, "/wallet/rewards/claim", None)) shouldBe FORBIDDEN
  }

  it should "be 400 when there is nothing to claim" in {
    val c = controller(new StubApi(onClaim = throw new WalletApi.NothingToClaim("no unlocked reward boxes to claim")))
    val result = call(c.claimRewards(), POST, "/wallet/rewards/claim", Some(apiKey))
    status(result) shouldBe BAD_REQUEST
    contentAsString(result) should include("no unlocked reward boxes")
  }

  it should "be 422 carrying the reason when the sweep failed" in {
    val c = controller(new StubApi(onClaim =
      throw LithosApiErrors.LithosUnprocessable("the reward sweep could not complete: node refused the sweep")))
    val result = call(c.claimRewards(), POST, "/wallet/rewards/claim", Some(apiKey))
    status(result) shouldBe UNPROCESSABLE_ENTITY
    contentAsString(result) should include("node refused the sweep")
  }

  "GET /wallet/arb" should "need the api_key, and carry the address and what it holds" in {
    val refused = call(controller().getArbStake(), GET, "/wallet/arb", None)
    status(refused) shouldBe FORBIDDEN
    contentAsString(refused) should not include "arbAddress"
    val result = call(controller().getArbStake(), GET, "/wallet/arb", Some(apiKey))
    status(result) shouldBe OK
    val json = contentAsJson(result)
    (json \ "address").as[String] shouldBe "arbAddress"
    (json \ "nanoErgs").as[String] shouldBe "10000000000"
    (json \ "paused").as[Boolean] shouldBe false
  }

  it should "be 503 when the arb address cannot be read" in {
    val c = controller(new StubApi(onArb = throw LithosApiErrors.LithosUnavailable("could not read the arb address")))
    status(call(c.getArbStake(), GET, "/wallet/arb", Some(apiKey))) shouldBe SERVICE_UNAVAILABLE
  }

  "POST /wallet/arb/reclaim" should "need the api_key, and never reclaim without it" in {
    var reclaimed = 0
    val c = controller(new StubApi(onReclaim = { reclaimed += 1; WalletArbReclaimResult("ab" * 32, 1, "1", "accepted", 0) }))
    status(call(c.reclaimArbStake(), POST, "/wallet/arb/reclaim", None)) shouldBe FORBIDDEN
    status(call(c.reclaimArbStake(), POST, "/wallet/arb/reclaim", Some("not-the-key"))) shouldBe FORBIDDEN
    reclaimed shouldBe 0
    val result = call(c.reclaimArbStake(), POST, "/wallet/arb/reclaim", Some(apiKey))
    status(result) shouldBe OK
    (contentAsJson(result) \ "txId").as[String] shouldBe "ab" * 32
    reclaimed shouldBe 1
  }

  it should "be 400 when the address holds nothing, and 422 carrying the node's reason when it refused" in {
    val empty = controller(new StubApi(onReclaim = throw new WalletApi.NothingToClaim("no confirmed box at the arb address")))
    status(call(empty.reclaimArbStake(), POST, "/wallet/arb/reclaim", Some(apiKey))) shouldBe BAD_REQUEST
    val refused = controller(new StubApi(onReclaim =
      throw LithosApiErrors.LithosUnprocessable("the node refused the reclaim: Double spending attempt")))
    val result = call(refused.reclaimArbStake(), POST, "/wallet/arb/reclaim", Some(apiKey))
    status(result) shouldBe UNPROCESSABLE_ENTITY
    contentAsString(result) should include("Double spending attempt")
  }

  "GET /wallet/tokens/verified" should "be open, so a page can draw it before a key is entered" in {
    val result = call(controller().getVerifiedTokens(), GET, "/wallet/tokens/verified", None)
    status(result) shouldBe OK
    (contentAsJson(result) \ "tokens" \ 0 \ "tokenId").as[String] shouldBe litId
  }
}
