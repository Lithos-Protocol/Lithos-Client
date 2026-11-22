package controllers

import akka.actor.ActorSystem
import akka.testkit.TestKit
import api.zc.ZcReads
import com.typesafe.config.ConfigFactory
import configs.{NodeContext, ZcConfig}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatestplus.mockito.MockitoSugar
import play.api.Configuration
import play.api.libs.json.Json
import play.api.test.Helpers._
import play.api.test.{FakeRequest, Helpers}
import scorex.crypto.hash.Blake2b256
import support.LDNodeFixtures
import transactions.batching.sigmafi.zc.{ZcResolver, ZcTracker}
import transactions.batching.tracking.ScriptedChain

import scala.concurrent.ExecutionContext

/**
 * The `/sigmafi/zc` routes: a poll at the current revision is answered 304 without running the read, the portfolio
 * needs the key, simulations are rate-limited per caller, and each failure gets the status a caller can act on.
 */
class ZcApiControllerSpec
  extends TestKit(ActorSystem("zc-controller-spec",
    ConfigFactory.parseResources("application.conf").resolve().withFallback(ConfigFactory.load())))
    with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar with Eventually {

  import transactions.batching.sigmafi.zc.ZcFixtures._
  import transactions.batching.sigmafi.zc.ZcMarketFixtures._

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(Span(10, Seconds), Span(20, Millis))

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val apiKey = "zc-controller-spec-key"
  private val config = Configuration(ConfigFactory.parseString(
    s"""lithos.apiKeyHash = "${org.bouncycastle.util.encoders.Hex.toHexString(Blake2b256.hash(apiKey))}""""))

  private def controller(): (ZcApiController, ZcTracker) = {
    val c = new ScriptedChain
    c.extend(H - 40, H)
    c.indexed = H
    c.scans(Seq(ergOffer(id(20)), oracleOrder(id(1)), oracle()).map(b => LDNodeFixtures.indexed(b, H - 10)): _*)
    val t = new ZcTracker(() => c.api, ExecutionContext.global, ZcConfig(enabled = true, 1000), retryMs = 20L)
    t.poke()
    eventually(t.view.isDefined shouldBe true)
    val r = new ZcResolver(() => c.api, t)
    val built = new ZcReads(t, r, () => c.api, () => Set.empty, () => (0L, Seq.empty), warmer = None)
    val fills = new stats.ZcFills(ZcConfig(enabled = true, 1000), () => stats.ZcFillStore.inMemory(ZcConfig(enabled = true, 1000)),
      () => c.api, ExecutionContext.global, enabled = false)
    val ctl = new ZcApiController(Helpers.stubControllerComponents(), t, r, mock[NodeContext], config, system, fills,
      new api.zc.ZcWrites(mock[NodeContext], system.deadLetters, t, r, config, system),
      new api.zc.ZcHistory(() => c.api, () => Set.empty[String], t, 0)) {
      override protected lazy val reads: ZcReads = built
    }
    (ctl, t)
  }

  "A poll at the current revision" should "be 304, and one at an older revision the read" in {
    val (c, t) = controller()
    val revision = t.view.get.revision
    status(c.info(Some(revision)).apply(FakeRequest())) shouldBe NOT_MODIFIED
    val fresh = c.info(Some(revision - 1)).apply(FakeRequest())
    status(fresh) shouldBe OK
    (contentAsJson(fresh) \ "at" \ "revision").as[Long] shouldBe revision
  }

  "The portfolio" should "be 403 without the key" in {
    val (c, _) = controller()
    status(c.portfolio(None).apply(FakeRequest())) shouldBe FORBIDDEN
    status(c.portfolio(None).apply(FakeRequest().withHeaders("api_key" -> apiKey))) shouldBe OK
  }

  "Every other read" should "need no key" in {
    val (c, _) = controller()
    status(c.asks("ERG", None, None, None, None, None, None, None, None, None, None).apply(FakeRequest())) shouldBe OK
    status(c.curves("ERG", None, None, None, None, None).apply(FakeRequest())) shouldBe OK
    status(c.auctions(None, None).apply(FakeRequest())) shouldBe OK
  }

  "Failures" should "map to 400, 404 and the like" in {
    val (c, _) = controller()
    status(c.asks("DOGE", None, None, None, None, None, None, None, None, None, None).apply(FakeRequest())) shouldBe BAD_REQUEST
    status(c.asks("ERG", None, Some("odd"), None, None, None, None, None, None, None, None).apply(FakeRequest())) shouldBe BAD_REQUEST
    status(c.matches(id(77), None).apply(FakeRequest())) shouldBe NOT_FOUND
    status(c.simulate(None).apply(FakeRequest(POST, "/").withJsonBody(Json.obj("draft" -> Json.obj("type" -> "x"))))) shouldBe BAD_REQUEST
  }

  "Simulations" should "be limited per caller to a burst, then a rate" in {
    val (c, _) = controller()
    val body = Json.obj("draft" -> Json.obj("type" -> "walletLend", "market" -> "ERG", "amount" -> "1000000000"))
    val answers = (1 to ZcApiController.SimulateBurst + 5).map(_ =>
      status(c.simulate(None).apply(FakeRequest(POST, "/").withJsonBody(body))))
    answers.take(ZcApiController.SimulateBurst).toSet shouldBe Set(OK)
    answers.drop(ZcApiController.SimulateBurst + 2) should contain(TOO_MANY_REQUESTS)
  }

  "Every ZC send" should "need the API key, and activity too" in {
    val (c, _) = controller()
    val x = id(1)
    val sends = Seq(c.createLot(), c.buyLot(x), c.fillOrder(x), c.buyAsk(x), c.buyVault(x), c.pull(x), c.fractionalize(x),
      c.repay(x), c.recollateralize(x), c.liquidate(x), c.extinguish(x), c.claim(x), c.createWrap(), c.unwrap(x), c.mint(x),
      c.activity(None, None))
    sends.map(a => status(a.apply(FakeRequest(POST, "/").withJsonBody(play.api.libs.json.Json.obj())))).toSet shouldBe Set(FORBIDDEN)
    // checkExtinguish is open (no key) and validates the bond asset id before hitting the node
    status(c.checkExtinguish("not-a-hex-id").apply(FakeRequest(POST, "/").withJsonBody(play.api.libs.json.Json.obj()))) shouldBe BAD_REQUEST
  }
}
