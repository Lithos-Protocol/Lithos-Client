package transactions.rollups

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import play.api.Configuration
import state.messages.RollupMessages.{GetCurrentRollupCritical, RollupUnavailable}
import support.FakeNodeContext
import transactions.rollups.RollupEvaluator.EvaluateNextBatch
import transactions.rollups.TransactionMessages.RollupTxType.NISPEvaluation
import transactions.rollups.TransactionMessages.{EvaluationSet, RollupTxStub}

import scala.concurrent.duration._

/**
 * The in-flight guard on `RollupEvaluator`.
 *
 * A batch is up to five rollups, each running seven proof contracts against every miner in it —
 * minutes of interpreter and AVL work — and entries only leave `evalMap` when that work reports
 * back. Without a guard the four-minute tick starts the same rollups again, and the duplicate runs
 * on the ten-thread pool where `WalletManager`'s five-second asks are waiting.
 *
 * Observed through the sync handler's mailbox rather than through `evalMap`, which is private: one
 * batch asks for each rollup's state once, so a second batch shows up as a second ask.
 */
object RollupEvaluatorSpec {
  val config: com.typesafe.config.Config =
    com.typesafe.config.ConfigFactory.parseString("akka.test.single-expect-default = 20s")
}

class RollupEvaluatorSpec extends TestKit(ActorSystem("rollup-evaluator-spec", RollupEvaluatorSpec.config))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  /** Transforms disabled, so the four-minute ticker never fires and every tick here is deliberate. */
  private val quietConfig = Configuration.from(Map("state.disableTransforms" -> true))

  private case class Fixture(evaluator: ActorRef, sync: TestProbe, processor: TestProbe)

  private def fixture(): Fixture = {
    val (ctx, _, _) = FakeNodeContext()
    val sync = TestProbe()
    val processor = TestProbe()
    val evaluator = system.actorOf(Props(new RollupEvaluator(quietConfig, ctx, sync.ref, processor.ref)))
    Fixture(evaluator, sync, processor)
  }

  private def stub(rollup: String, period: Long = 100L): RollupTxStub =
    RollupTxStub(rollup, Some(period), NISPEvaluation)

  "Two ticks in the same instant" should "still only dispatch one batch" in {
    // The flag is claimed in the tick that decides to dispatch, not in the batch handler — that
    // message is sent to self and lands at the back of the mailbox, so both ticks would otherwise
    // see it clear. Unreachable at a four-minute ticker, but this is the shape the guard has to have.
    val f = fixture()
    f.evaluator ! EvaluationSet(Seq(stub("rollup-a")))

    f.evaluator ! EvaluateNextBatch
    f.evaluator ! EvaluateNextBatch

    f.sync.expectMsgType[GetCurrentRollupCritical](30.seconds)
    f.sync.expectNoMessage(3.seconds)
    f.sync.reply(RollupUnavailable("finish the duplicate-tick test batch"))
  }

  "A tick while a batch is running" should "be skipped rather than starting a second" in {
    // The sync handler never answers, so the running batch sits on its 5-second state ask — which is
    // the window a real batch spends doing interpreter work.
    val f = fixture()
    f.evaluator ! EvaluationSet(Seq(stub("rollup-a")))

    f.evaluator ! EvaluateNextBatch
    f.sync.expectMsgType[GetCurrentRollupCritical](30.seconds) // the batch is underway

    f.evaluator ! EvaluateNextBatch
    withClue("a second batch would ask for the same rollup's state again: ") {
      f.sync.expectNoMessage(3.seconds)
    }
    f.sync.reply(RollupUnavailable("finish the in-flight guard test batch"))
  }

  "An evaluation set" should "not queue the same rollup twice" in {
    // `evalMap` is keyed by rollup, and a stub already being evaluated is not re-added — otherwise a
    // rollup that keeps arriving on the publisher's tick would be evaluated repeatedly.
    val f = fixture()
    f.evaluator ! EvaluationSet(Seq(stub("rollup-a")))
    f.evaluator ! EvaluationSet(Seq(stub("rollup-a")))

    f.evaluator ! EvaluateNextBatch
    f.sync.expectMsgType[GetCurrentRollupCritical](30.seconds)
    f.sync.expectNoMessage(3.seconds)
    f.sync.reply(RollupUnavailable("finish the deduplication test batch"))
  }

  "The guard" should "clear once the batch finishes, so later ticks still run" in {
    // The flag is cleared on BatchEvaluated from both branches of the Future's completion, so a
    // batch that fails does not wedge the evaluator forever.
    val f = fixture()
    f.evaluator ! EvaluationSet(Seq(stub("rollup-a")))

    f.evaluator ! EvaluateNextBatch
    val first = f.sync.expectMsgType[GetCurrentRollupCritical](30.seconds)
    first.blockId shouldEqual "rollup-a"

    // End the first batch explicitly rather than relying on the production state timeout. The stub
    // is dropped by StopEvaluating on failure, so a fresh one is needed for the next batch.
    f.sync.reply(RollupUnavailable("finish the first test batch"))
    f.evaluator ! EvaluationSet(Seq(stub("rollup-b")))

    // BatchEvaluated is posted by the Future completion callback. Retry the tick until that callback
    // has cleared the guard instead of racing it with a fixed sleep.
    val second = awaitAssert({
      f.evaluator ! EvaluateNextBatch
      f.sync.expectMsgType[GetCurrentRollupCritical](1.second)
    }, 10.seconds, 100.millis)
    second.blockId shouldEqual "rollup-b"
    f.sync.reply(RollupUnavailable("finish the reopened-guard test batch"))
  }

  "An empty queue" should "not produce a batch at all" in {
    val f = fixture()
    f.evaluator ! EvaluateNextBatch
    f.sync.expectNoMessage(2.seconds)
    f.processor.expectNoMessage(500.millis)
  }
}
