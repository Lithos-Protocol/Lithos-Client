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
    val api = new LithosDexApiImpl(FakeNodeContext()._1, engine.ref, TestProbe().ref, Configuration.empty, system)
    val request = LDSwapExecuteRequest("1000000", ergIn = true, "1")
    val response = LDSwapResult("12", "ab" * 32, "uncertain")
    val result = Future(api.swap(request, new LDCache(new FakeCache)))
    engine.expectMsg(DexIntent.Swap(request))
    engine.reply(response)
    Await.result(result, 3.seconds) shouldBe response
    (Json.toJson(response) \ "outcome").as[String] shouldBe "uncertain"
  }

  it should "send order placements and cancels to the engine, where wallet inputs are reserved" in {
    val engine = TestProbe()
    val api = new LithosDexApiImpl(FakeNodeContext()._1, engine.ref, TestProbe().ref, Configuration.empty, system)
    val cache = new LDCache(new FakeCache)
    val order = LDOrder("5035b2e112b4f5b873dd3444c01df1e7e697807fd84fe96790eacb128e46428d", "SWAP", "PENDING",
      "ab" * 32, None, None, "owner", "1004000000", "3000000", "1000000", fillableNow = true)

    val swap = LDSwapOrderExecuteRequest("1000000000", ergIn = true, "1")
    val placed = Future(api.placeSwapOrder(swap, cache))
    engine.expectMsg(DexIntent.PlaceSwapOrder(swap))
    engine.reply(LDOrderPlacementResult("uncertain", "ab" * 32, order))
    Await.result(placed, 3.seconds).outcome shouldBe "uncertain"

    val cancelled = Future(api.cancelOrder(order.boxId, cache))
    engine.expectMsg(DexIntent.CancelOrder(order.boxId))
    engine.reply(LDOrderCancelResult("accepted", "cd" * 32, "1004000000", Seq.empty, "2000000"))
    Await.result(cancelled, 3.seconds).txId shouldBe "cd" * 32
    // `type` is a reserved word in Scala and must still reach the wire under its spec name
    (Json.toJson(order) \ "type").as[String] shouldBe "SWAP"
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
