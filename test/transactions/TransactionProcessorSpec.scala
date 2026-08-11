package transactions

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.Configuration
import support.{FakeCache, FakeNodeContext}
import transactions.BlockTxMessages.{BlockTxsReady, RequestBlockTxs}
import transactions.TransactionMessages.RollupTxType._
import transactions.TransactionMessages._
import transactions.TransactionProcessor.ProcessTransactions

import scala.concurrent.duration._

/**
 * `TransactionProcessor` owns the queue of stubs waiting to be sent, and the rule that decides when
 * one is forgotten.
 *
 * Two defects live here historically, and both are silent. Removal used to match on `txType` alone,
 * which deleted every fraud proof for a rollup once one was dispatched; and removal used to happen
 * on the SEND, which lost the whole batch whenever `SubmissionHandler` refused it — and nothing but
 * a fresh evaluation cycle re-derives a fraud proof stub.
 *
 * `pendingRollupTxs` is private, so everything here is asserted through the messages the actor
 * sends. That is the right level anyway: what matters is whether a stub comes back, not where it was
 * stored.
 */
object TransactionProcessorSpec {
  val config: com.typesafe.config.Config =
    com.typesafe.config.ConfigFactory.parseString("akka.test.single-expect-default = 15s")
}

class TransactionProcessorSpec extends TestKit(ActorSystem("tx-processor-spec", TransactionProcessorSpec.config))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  /** Transforms disabled, so the three-minute ticker never fires and every tick here is deliberate. */
  private val quietConfig = Configuration.from(Map("state.disableTransforms" -> true))

  private case class Fixture(processor: ActorRef, sync: TestProbe, submission: TestProbe,
                             evaluator: TestProbe, probe: TestProbe)

  private def fixture(): Fixture = {
    val (ctx, _, _) = FakeNodeContext()
    val sync = TestProbe()
    val submission = TestProbe()
    val evaluator = TestProbe()
    val processor = system.actorOf(Props(new TransactionProcessor(
      quietConfig, ctx, new FakeCache, sync.ref, submission.ref, evaluator.ref)))
    Fixture(processor, sync, submission, evaluator, TestProbe())
  }

  private def fpStub(rollup: String, miner: Byte, period: Long = 100L): RollupTxStub =
    RollupTxStub(rollup, Some(period), NISPEvaluation, fpInfo = Some(Array.fill[Byte](32)(miner) -> "fp-hash"))

  private def evalStub(rollup: String, period: Long = 100L): RollupTxStub =
    RollupTxStub(rollup, Some(period), NISPEvaluation)

  /** Seed stubs without a sync round trip — FraudBatch merges straight into the pending map. */
  private def seed(f: Fixture, stubs: RollupTxStub*): Unit = {
    f.processor ! FraudBatch(stubs)
    Thread.sleep(200)
  }

  private def tick(f: Fixture): Unit = f.processor ! ProcessTransactions

  // ─── the §4b.2 regression ─────────────────────────────────────────────────

  "A dispatched batch" should "keep its stubs until they are acknowledged" in {
    // Removal used to happen on the send. Making `batchLock` real made SubmissionHandler's refusal
    // branch reachable, and a refused batch then lost its stubs outright.
    val f = fixture()
    seed(f, fpStub("rollup-a", 1))

    tick(f)
    val first = f.submission.expectMsgType[RollupBatch]
    first.stubs should have size 1

    // No BatchAccepted — as if the lock had refused it. The next tick must offer it again.
    tick(f)
    val second = f.submission.expectMsgType[RollupBatch]
    second.stubs.map(_.rollupBlockId) shouldEqual Seq("rollup-a")
  }

  it should "forget them once BatchAccepted comes back" in {
    val f = fixture()
    seed(f, fpStub("rollup-a", 1))

    tick(f)
    val batch = f.submission.expectMsgType[RollupBatch]
    f.processor ! BatchAccepted(batch.stubs)
    Thread.sleep(200)

    tick(f)
    f.submission.expectNoMessage(1.second)
  }

  it should "not lose the others when only one is acknowledged" in {
    // A fraud proof stub is identified by its target, not just its type — matching on type alone is
    // what deleted every other miner's proof for the same rollup.
    val f = fixture()
    seed(f, fpStub("rollup-a", 1), fpStub("rollup-a", 2), fpStub("rollup-a", 3))

    tick(f)
    val batch = f.submission.expectMsgType[RollupBatch]
    batch.stubs should have size 1 // one fraud proof per rollup per tick
    f.processor ! BatchAccepted(batch.stubs)
    Thread.sleep(200)

    // The other two must still be there, one per tick.
    tick(f)
    val next = f.submission.expectMsgType[RollupBatch]
    next.stubs should have size 1
    next.stubs.head.fpInfo.get._1 should not equal batch.stubs.head.fpInfo.get._1
  }

  "Three fraudulent miners on one rollup" should "all be slashed, one tick at a time" in {
    // The machinery is built for many proofs per rollup and was throttled to one in two independent
    // places. They are inherently serial — each spends the same evaluation box — so one per tick is
    // correct; what matters is that all three eventually go out and none silently vanishes.
    val f = fixture()
    seed(f, fpStub("rollup-a", 1), fpStub("rollup-a", 2), fpStub("rollup-a", 3))

    val dispatched = (1 to 3).map { _ =>
      tick(f)
      val batch = f.submission.expectMsgType[RollupBatch]
      batch.stubs should have size 1
      f.processor ! BatchAccepted(batch.stubs)
      Thread.sleep(200)
      batch.stubs.head.fpInfo.get._1.toSeq
    }

    dispatched.distinct should have size 3
    tick(f)
    f.submission.expectNoMessage(1.second)
  }

  // ─── merging ──────────────────────────────────────────────────────────────

  "FraudBatch" should "keep one stub per fraudulent miner rather than collapsing them" in {
    // All the stubs in a batch share a rollupBlockId by construction, so keying them by that
    // collapsed every miner but the last.
    val f = fixture()
    f.processor ! FraudBatch(Seq(fpStub("rollup-a", 1), fpStub("rollup-a", 2)))
    Thread.sleep(200)

    val seen = (1 to 2).map { _ =>
      tick(f)
      val batch = f.submission.expectMsgType[RollupBatch]
      f.processor ! BatchAccepted(batch.stubs)
      Thread.sleep(200)
      batch.stubs.head.fpInfo.get._1.toSeq
    }
    seen.distinct should have size 2
  }

  it should "not add the same target twice" in {
    val f = fixture()
    seed(f, fpStub("rollup-a", 1))
    seed(f, fpStub("rollup-a", 1))

    tick(f)
    val batch = f.submission.expectMsgType[RollupBatch]
    f.processor ! BatchAccepted(batch.stubs)
    Thread.sleep(200)

    tick(f)
    f.submission.expectNoMessage(1.second)
  }

  it should "not trigger a sync query" in {
    // FraudBatch is merged directly rather than through PublishedRollupMap, which would spend a full
    // GetSynced round trip re-validating entries the evaluator just derived from current state.
    val f = fixture()
    f.processor ! FraudBatch(Seq(fpStub("rollup-a", 1)))
    f.sync.expectNoMessage(1.second)
  }

  // ─── evaluation stubs ─────────────────────────────────────────────────────

  "Evaluation stubs" should "go to the evaluator and be consumed on the send" in {
    // RollupEvaluator takes every set it is given, so there is nothing to acknowledge.
    val f = fixture()
    f.processor ! FraudBatch(Seq(evalStub("rollup-b")))
    Thread.sleep(200)

    tick(f)
    f.evaluator.expectMsgType[EvaluationSet].stubs should have size 1
    f.submission.expectNoMessage(500.millis)

    tick(f)
    f.evaluator.expectNoMessage(1.second)
  }

  // ─── the block-assembly path ──────────────────────────────────────────────

  "RequestBlockTxs" should "answer empty immediately when the limit is zero" in {
    // A miner is waiting on this. Nothing may be asked of anyone else.
    val f = fixture()
    seed(f, fpStub("rollup-a", 1))

    f.probe.send(f.processor, RequestBlockTxs(500, 0))
    f.probe.expectMsgType[BlockTxsReady].txs shouldBe empty
    f.submission.expectNoMessage(500.millis)
  }

  it should "answer empty immediately when nothing is queued" in {
    val f = fixture()
    f.probe.send(f.processor, RequestBlockTxs(500, 5))
    f.probe.expectMsgType[BlockTxsReady].txs shouldBe empty
  }

  it should "hand the build to SubmissionHandler with the miner as the reply address" in {
    // `tell(..., requester)` so the answer goes straight back to the builder rather than making a
    // second hop through this actor, which is on the block's critical path.
    val f = fixture()
    seed(f, fpStub("rollup-a", 1))

    f.probe.send(f.processor, RequestBlockTxs(500, 5))
    val build = f.submission.expectMsgType[BuildBlockTxs]
    build.blockHeight shouldEqual 500
    build.stubs should have size 1
    f.submission.lastSender shouldEqual f.probe.ref
  }

  it should "not consume the stubs it offers to a block" in {
    // Nothing is dispatched or removed there, so the funded copies still reach the mempool if no
    // block is found.
    val f = fixture()
    seed(f, fpStub("rollup-a", 1))

    f.probe.send(f.processor, RequestBlockTxs(500, 5))
    f.submission.expectMsgType[BuildBlockTxs]

    tick(f)
    f.submission.expectMsgType[RollupBatch].stubs should have size 1
  }
}
