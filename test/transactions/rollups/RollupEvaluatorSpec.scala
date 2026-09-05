package transactions.rollups

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import lfsm.LFSMHelpers
import nisp.{ResolvedNisp, ResolvedNisps}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import play.api.Configuration
import state.messages.RollupMessages.{GetCurrentRollupCritical, RollupUnavailable, UpdateEvaluation}
import support.{FakeNodeContext, NispHistoryFixtures}
import transactions.rollups.RollupEvaluator.EvaluateNextBatch
import transactions.rollups.TransactionMessages.RollupTxType.{EvalTransform, NISPEvaluation}
import transactions.rollups.TransactionMessages.{EvaluationSet, FailedEvaluation, FraudBatch, RollupTxStub, SuccessfulEvaluation}

import scala.concurrent.duration._

/**
 * The in-flight guard on `RollupEvaluator`.
 *
 * A batch is up to five rollups, each running nine proof contracts against every miner in it —
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

  "A rollup whose proofs keep failing" should "remain incomplete after its retry alarm threshold" in {
    // The accused chooses what each proof is handed, so one failed evaluation must not retire a
    // rollup. The count has to survive the rollup leaving evalMap, which StopEvaluating does after
    // every batch, and a set that does not mention it, since an EvaluationSet is truncated to twenty.
    val f = fixture()
    val a = stub("rollup-a")

    (1 until RollupEvaluator.MaxEvaluationAttempts).foreach { attempt =>
      f.evaluator ! EvaluationSet(Seq(a))
      f.evaluator ! FailedEvaluation(a, Map.empty[Array[Byte], String])
      withClue(s"attempt $attempt of ${RollupEvaluator.MaxEvaluationAttempts} must not retire it: ") {
        f.sync.expectNoMessage(1.second)
      }

      // A tick with another rollup queued and this one not, which is where StopEvaluating has just
      // left it. A count pruned against evalMap is dropped here and the attempts never run out.
      f.evaluator ! EvaluationSet(Seq(stub(s"rollup-filler-$attempt")))
      awaitAssert({
        f.evaluator ! EvaluateNextBatch
        f.sync.expectMsgType[GetCurrentRollupCritical](1.second)
      }, 10.seconds, 100.millis)
      f.sync.reply(RollupUnavailable("finish the filler batch"))
    }

    f.evaluator ! EvaluationSet(Seq(a))
    f.evaluator ! FailedEvaluation(a, Map.empty[Array[Byte], String])
    f.sync.expectNoMessage(1.second)
    (1 to RollupEvaluator.MaxEvaluationAttempts).foreach { _ =>
      f.evaluator ! FailedEvaluation(a, Map.empty[Array[Byte], String], payloadUnavailable = true)
    }
    f.sync.expectNoMessage(1.second)
  }

  "A rollup that evaluates cleanly" should "not carry its earlier failures" in {
    // The count is dropped on success, so a rollup that fails once and then evaluates does not
    // arrive at its next failure one attempt from being retired.
    val f = fixture()
    val a = stub("rollup-a")
    f.evaluator ! EvaluationSet(Seq(a))

    f.evaluator ! FailedEvaluation(a, Map.empty[Array[Byte], String])
    f.sync.expectNoMessage(1.second)

    f.evaluator ! SuccessfulEvaluation("rollup-a")
    f.sync.expectMsg(10.seconds, UpdateEvaluation("rollup-a"))

    // Back to a full budget: two more failures still must not retire it.
    (1 until RollupEvaluator.MaxEvaluationAttempts).foreach { _ =>
      f.evaluator ! EvaluationSet(Seq(a))
      f.evaluator ! FailedEvaluation(a, Map.empty[Array[Byte], String])
      f.sync.expectNoMessage(1.second)
    }
  }

  "An empty queue" should "not produce a batch at all" in {
    val f = fixture()
    f.evaluator ! EvaluateNextBatch
    f.sync.expectNoMessage(2.seconds)
    f.processor.expectNoMessage(500.millis)
  }

  "Fraud found during incomplete recovery" should "dispatch with shared payload ownership and leave evaluation incomplete" in {
    val f = fixture()
    val minerA = Array.fill[Byte](32)(1)
    val minerB = Array.fill[Byte](32)(2)
    val recovered = ResolvedNisps("rollup-a", Map("02" * 32 -> ResolvedNisp(Array.fill[Byte](8)(0))))
    val a = stub("rollup-a").copy(resolvedNisps = Some(recovered))

    f.evaluator ! FailedEvaluation(a, Map(minerA -> "commitment-proof", minerB -> "payload-proof"),
      payloadUnavailable = true)

    val batch = f.processor.expectMsgType[FraudBatch]
    batch.fpStubs should have size 2
    batch.fpStubs.map(_.fpInfo.get._2).toSet shouldEqual Set("commitment-proof", "payload-proof")
    batch.fpStubs.foreach(_.resolvedNisps.get should be theSameInstanceAs recovered)
    f.sync.expectNoMessage(500.millis)
  }

  "An evaluation transform" should "require a complete evaluation even after the challenge period" in {
    val rollup = NispHistoryFixtures.chain().target
    val transform = RollupTxStub(rollup.blockId, rollup.currentPeriod, EvalTransform)
    val height = (rollup.currentPeriod.get + LFSMHelpers.EVAL_PERIOD).toInt

    transform.validate(height, rollup) shouldBe false
    transform.validate(height, rollup.copy(evaluated = true)) shouldBe true
  }
}
