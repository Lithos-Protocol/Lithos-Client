package api

import akka.actor.ActorSystem
import akka.testkit.{TestKit, TestProbe}
import api.models._
import cache.LDCache
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import play.api.Configuration
import play.api.libs.json.Json
import support.{FakeCache, FakeNodeContext}
import transactions.engine.{DexIntent, TransactionEngine}
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

class EngineApiGatewaySpec extends TestKit(ActorSystem("engine-api-gateway-spec"))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {
  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)
  private implicit val ec = system.dispatcher

  "The DEX API" should "forward the typed request and preserve an uncertain outcome and known ID" in {
    val engine = TestProbe()
    val api = new LithosDexApiImpl(FakeNodeContext()._1, engine.ref, system)
    val request = LDSwapExecuteRequest("1000000", ergIn = true, "1")
    val response = LDSwapResult("12", "ab" * 32, "uncertain")
    val result = Future(api.swap(request, new LDCache(new FakeCache)))
    engine.expectMsg(DexIntent.Swap(request))
    engine.reply(response)
    Await.result(result, 3.seconds) shouldBe response
    (Json.toJson(response) \ "outcome").as[String] shouldBe "uncertain"
  }

  "The collateral API" should "forward a join intent and preserve per-transaction outcomes" in {
    val engine = TestProbe()
    val api = new CollateralMarketApiImpl(FakeNodeContext()._1, Configuration.from(Map.empty[String, Any]), system, engine.ref)
    val request = CollateralJoinExecuteRequest(1, Some(true), None, None)
    val response = CollateralJoinResult(Seq(CollateralJoinEntry("cd" * 32, 0L, "lender", "2900000000", "1", "uncertain")), "0", Some("response lost"))
    val result = Future(api.join(request))
    engine.expectMsg(TransactionEngine.JoinCollateral(request))
    engine.reply(response)
    Await.result(result, 3.seconds) shouldBe response
    (Json.toJson(response.joins.head) \ "outcome").as[String] shouldBe "uncertain"
  }
}
