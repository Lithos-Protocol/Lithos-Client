package controllers

import akka.actor.ActorSystem
import akka.testkit.TestKit
import api.LithosApiErrors.{LithosBadRequest, LithosNotFound, LithosStateChanged, LithosUnavailable}
import api.OraclesApi
import api.models.OracleFeeds
import com.typesafe.config.ConfigFactory
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.{doThrow, when}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.test.{FakeRequest, Helpers}
import play.api.test.Helpers._

class OraclesApiControllerSpec extends TestKit(ActorSystem("oracles-controller-spec",
  ConfigFactory.parseResources("application.conf").resolve().withFallback(ConfigFactory.load())))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {
  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  "Public oracle reads" should "need no API key and return actionable failures" in {
    val api = mock[OraclesApi]
    when(api.listFeeds()).thenReturn(OracleFeeds("MAINNET", Seq.empty))
    val controller = new OraclesApiController(Helpers.stubControllerComponents(), api, system)
    status(controller.listFeeds().apply(FakeRequest())) shouldBe OK
    Seq(new LithosBadRequest("bad") -> BAD_REQUEST, new LithosNotFound("unknown") -> NOT_FOUND,
      new LithosStateChanged("changed") -> CONFLICT, new LithosUnavailable("unavailable") -> SERVICE_UNAVAILABLE)
      .foreach { case (error, expected) =>
        doThrow(error).when(api).getFeed(anyString(), any(), any(), any())
        status(controller.getFeed("erg-usd", None, None, None).apply(FakeRequest())) shouldBe expected
      }
  }
}
