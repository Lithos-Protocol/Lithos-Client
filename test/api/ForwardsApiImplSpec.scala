package api

import akka.actor.ActorSystem
import akka.testkit.{TestKit, TestProbe}
import api.LithosApiErrors.LithosUnavailable
import configs.MiningStatsConfig
import node.NodeApi
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.Configuration
import play.api.libs.json.Json
import stats.{MiningStatsRefresh, MiningStatsStore}
import support.FakeNodeContext
import transactions.engine.{EngineIntent, ForwardsIntent, TransactionEngine}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/**
 * How a forwards mutation reaches the transaction engine. A request that has not reached the node within its
 * window is never sent, so a caller told it failed never finds it sent later and a retried offer cannot lock
 * its LIT twice. A caller left without an answer is told the outcome is unknown.
 */
class ForwardsApiImplSpec extends TestKit(ActorSystem("forwards-api-impl-spec"))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  private implicit val ec: ExecutionContext = system.dispatcher

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private def impl(engine: TestProbe, answerIn: FiniteDuration = ForwardsApiImpl.AnswerWait): ForwardsApiImpl = {
    val (node, _, _) = FakeNodeContext(mock[NodeApi], numAddresses = 1)
    val mining = new MiningStatsRefresh(MiningStatsConfig(enabled = false), false, 1,
      () => new MiningStatsStore(None, Json.obj()), () => throw new IllegalStateException("no source"), ec)
    new ForwardsApiImpl(node, engine.ref, mining, Configuration.empty, system) {
      override protected def answerWait: FiniteDuration = answerIn
    }
  }

  "A forwards mutation" should "reach the engine scheduled to expire 30 seconds after it is asked" in {
    val engine = TestProbe()
    val boxId = "ab" * 32
    Future(impl(engine).cancel(boxId))
    val scheduled = engine.expectMsgType[TransactionEngine.Schedule](10.seconds)
    scheduled.intent match {
      case EngineIntent.Forwards(request, _) => request shouldBe ForwardsIntent.Cancel(boxId)
      case other => fail(s"scheduled $other")
    }
    (scheduled.expiresAtMillis - scheduled.notBeforeMillis) shouldBe ForwardsApiImpl.SendWindow.toMillis
    ForwardsApiImpl.SendWindow shouldBe 30.seconds
    math.abs(scheduled.notBeforeMillis - System.currentTimeMillis()) should be < 10000L
  }

  it should "say nothing was sent when the engine drops it unrun" in {
    val engine = TestProbe()
    val result = Future(impl(engine).settle("cd" * 32))
    engine.expectMsgType[TransactionEngine.Schedule](10.seconds)
    engine.reply(TransactionEngine.Deferred("forwards:x", "intent expired before dispatch"))
    val refused = the[LithosUnavailable] thrownBy Await.result(result, 10.seconds)
    refused.getMessage should include("nothing was sent")
  }

  it should "say the outcome is unknown, not that it failed, when the engine does not answer in time" in {
    val engine = TestProbe()
    val result = Future(impl(engine, answerIn = 300.millis).fill("ef" * 32))
    engine.expectMsgType[TransactionEngine.Schedule](10.seconds)
    val unanswered = the[LithosUnavailable] thrownBy Await.result(result, 10.seconds)
    unanswered.getMessage should include("may still have reached the node")
  }

  "The answer wait" should "outlast the send window by a node call, and stay under the HTTP server's idle timeout" in {
    (ForwardsApiImpl.AnswerWait - ForwardsApiImpl.SendWindow) should be >= 30.seconds
    ForwardsApiImpl.AnswerWait should be < 75.seconds
  }
}
