package controllers

import akka.actor.ActorSystem
import akka.testkit.TestKit
import api.models._
import api.{LithosApiErrors, MiningApi}
import com.typesafe.config.ConfigFactory
import mutations.NotEnoughInputsException
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import play.api.Configuration
import play.api.libs.json.Json
import play.api.test.Helpers._
import play.api.test.{FakeRequest, Helpers}
import scorex.crypto.hash.Blake2b256

import scala.concurrent.Future

/**
 * The commitment routes: which one needs the key, and which failure becomes which status.
 *
 * The status is the only part of a refusal a caller branches on. 409 says read the status and retry,
 * 422 says not now, 503 says the client could not decide, and only a defect here is 500.
 */
class MiningApiControllerSpec
  extends TestKit(ActorSystem("mining-controller-spec",
    ConfigFactory.parseResources("application.conf").resolve().withFallback(ConfigFactory.load())))
    with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val apiKey = "mining-controller-spec-key"

  private val config = Configuration(ConfigFactory.parseString(
    s"""lithos.apiKeyHash = "${org.bouncycastle.util.encoders.Hex.toHexString(Blake2b256.hash(apiKey))}""""))

  private val unregistered = DifficultyCommitment(DifficultyCommitment.Unregistered, None, Some(100), None, None, None,
    None, canCommit = true, None, autoCommit = false, "4.0G", DifficultyCommitment.Timing)

  private val result = CommitmentResult("cd" * 32, "registration", "accepted", "1500000", "1.50M", 100, 165, 163,
    225, 945)

  private def controller(answer: CommitmentRequest => CommitmentResult): MiningApiController =
    new MiningApiController(Helpers.stubControllerComponents(), new MiningApi {
      override def getBestNISPAtHeight(height: Int, score: Long): Option[NISPRepresentation] = None
      override def getStratumInfo(config: Configuration): StratumInfo = throw new UnsupportedOperationException
      override def getCandidateSettings(config: Configuration): CandidateSettings = throw new UnsupportedOperationException
      override def getCommitment: DifficultyCommitment = unregistered
      override def commit(request: CommitmentRequest): CommitmentResult = answer(request)
    }, config, system)

  private def post(c: MiningApiController, key: Option[String], body: String = """{"diff": "1.5M"}""") = {
    val base = FakeRequest(POST, "/mining/commitment").withJsonBody(Json.parse(body))
    c.commit().apply(key.fold(base)(k => base.withHeaders("api_key" -> k)))
  }

  "GET /mining/commitment" should "be open, since the page warns from it before any key is entered" in {
    val got = controller(_ => result).getCommitment().apply(FakeRequest(GET, "/mining/commitment"))
    status(got) shouldBe OK
    (contentAsJson(got) \ "state").as[String] shouldBe "unregistered"
  }

  "POST /mining/commitment" should "be 403 without the key, or with the wrong one, and never reach the API" in {
    var reached = false
    val c = controller { _ => reached = true; result }
    status(post(c, None)) shouldBe FORBIDDEN
    status(post(c, Some("not-the-key"))) shouldBe FORBIDDEN
    reached shouldBe false
  }

  it should "be 200 with the sent transaction under the right key" in {
    var asked: Option[String] = None
    val got = post(controller { r => asked = Some(r.diff); result }, Some(apiKey))
    status(got) shouldBe OK
    (contentAsJson(got) \ "txId").as[String] shouldBe "cd" * 32
    asked shouldBe Some("1.5M")
  }

  it should "be 400 for a body without a diff" in {
    status(post(controller(_ => result), Some(apiKey), """{"difficulty": "1.5M"}""")) shouldBe BAD_REQUEST
  }

  it should "map each refusal to the status a caller acts on" in {
    val cases: Seq[(Throwable, Int)] = Seq(
      LithosApiErrors.LithosBadRequest("already newest") -> BAD_REQUEST,
      LithosApiErrors.LithosStateChanged("auto-commit owns it") -> CONFLICT,
      LithosApiErrors.LithosUnprocessable("locked") -> UNPROCESSABLE_ENTITY,
      new NotEnoughInputsException("wallet empty") -> UNPROCESSABLE_ENTITY,
      LithosApiErrors.LithosUnavailable("node down") -> SERVICE_UNAVAILABLE,
      new RuntimeException("boom") -> INTERNAL_SERVER_ERROR)
    for ((thrown, expected) <- cases) withClue(s"${thrown.getClass.getSimpleName}: ") {
      val got = post(controller(_ => throw thrown), Some(apiKey))
      status(got) shouldBe expected
      contentAsString(got) should include(thrown.getMessage)
    }
  }
}
