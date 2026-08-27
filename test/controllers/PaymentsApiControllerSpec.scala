package controllers

import akka.actor.ActorSystem
import akka.testkit.TestKit
import api.models.PaymentTransaction
import api.{LithosApiErrors, PaymentsApi}
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import play.api.Configuration
import play.api.cache.SyncCacheApi
import play.api.test.Helpers._
import play.api.test.{FakeRequest, Helpers}
import scorex.crypto.hash.Blake2b256
import support.FakeCache

import scala.concurrent.Future

/** Covers payment-scan dispatch and externally actionable failures. */
class PaymentsApiControllerSpec
  extends TestKit(ActorSystem("payments-controller-spec",
    ConfigFactory.parseResources("application.conf").resolve().withFallback(ConfigFactory.load())))
    with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val apiKey = "payments-controller-spec-key"
  private val config = Configuration(ConfigFactory.parseString(
    s"""lithos.apiKeyHash = "${org.bouncycastle.util.encoders.Hex.toHexString(Blake2b256.hash(apiKey))}""""))
  private val cache = new FakeCache

  private val payment = PaymentTransaction(
    transactionId = "aa" * 32,
    payoutUTXO = "bb" * 32,
    amount = 50000000L,
    score = None,
    paymentHeight = 100,
    blockId = None,
    minedBlockHeight = None)

  private def controller(answer: => List[PaymentTransaction]): PaymentsApiController =
    new PaymentsApiController(Helpers.stubControllerComponents(), new PaymentsApi {
      override def getPayments(limit: Option[Int],
                               offset: Option[Int],
                               config: Configuration,
                               cache: SyncCacheApi): List[PaymentTransaction] = answer
    }, config, cache, system)

  private def call(c: PaymentsApiController, query: String = ""): Future[play.api.mvc.Result] =
    c.getPayments().apply(FakeRequest(GET, "/payments" + query).withHeaders("api_key" -> apiKey))

  "The payments controller" should "run the blocking scan on the existing API node-I/O dispatcher" in {
    @volatile var workerThread = ""
    val result = call(controller {
      workerThread = Thread.currentThread().getName
      List(payment)
    })

    status(result) shouldBe OK
    workerThread should include("dex-dispatcher")
  }

  it should "map an index failure to 503 rather than an empty-history 404" in {
    val result = call(controller(
      throw LithosApiErrors.LithosUnavailable("node index is unavailable")))

    status(result) shouldBe SERVICE_UNAVAILABLE
    contentAsString(result) should include("node index is unavailable")
  }

  it should "report a malformed pagination value as a bad request" in {
    val result = call(controller(List(payment)), "?limit=not-a-number")
    status(result) shouldBe BAD_REQUEST
  }
}
