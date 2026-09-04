package transactions.rollups

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import org.ergoplatform.sdk.ErgoId
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import play.api.Configuration
import state.messages.RollupMessages.{CurrentRollup, GetCurrentRollupCritical, RollupUnavailable}
import support.{FakeCache, FakeNodeContext, SyncFixtures}
import node.MutationConversions._
import node.model.NodeBox
import transactions.BlockTxMessages.{BlockTxsReady, CandidateTxsDropped, RequestBlockTxs}
import transactions.wallet.WalletMessages.{MarkReservationUncertain, RetrieveCoveringInput, WalletInputs}
import transactions.wallet.{WalletReservation, WalletSelector}
import transactions.rollups.TransactionMessages.RollupTxType._
import transactions.rollups.TransactionMessages._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/**
 * `SubmissionHandler`'s mailbox, and the lock over the fields a batch mutates.
 *
 * A batch is tens of seconds of selection asks, signing, node round trips and five-second retry
 * sleeps. It used to run inside `receive` — and this actor also answers `BuildBlockTxs`, which is a
 * miner assembling a block against a twenty-second budget. So a batch in flight silently cost every
 * block its inserted transactions: the timeout fires, mining proceeds on genesis alone, and nothing
 * in the log connects the two.
 *
 * Individual tests hold a state ask open when they need slow work; all other asks are answered
 * explicitly so wall-clock timeouts are not being mistaken for concurrency coverage.
 */
object SubmissionHandlerSpec {
  val config: com.typesafe.config.Config =
    com.typesafe.config.ConfigFactory.parseString("akka.test.single-expect-default = 40s")
}

class SubmissionHandlerSpec extends TestKit(ActorSystem("submission-handler-spec", SubmissionHandlerSpec.config))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private implicit val ec: ExecutionContext = system.dispatcher

  private val quietConfig = Configuration.from(Map(
    "state.disableTransforms" -> true,
    "state.autoCommit" -> false,          // keeps the batch off the difficulty-commit path
    "stratum.diff" -> "4.0G",
    "stratum.stratumPort" -> 4444,
    "stratum.extraNonce1Size" -> 2,
    "stratum.connectionTimeout" -> 60000,
    "stratum.blockRefreshInterval" -> 1000,
    "stratum.reduceShareMessages" -> false,
    "stratum.diffRefreshInterval" -> 60000
  ))

  /**
   * A data box token, supplied rather than read from disk.
   *
   * `submitInitialTransaction` refuses the whole batch when no data box exists, so with the
   * production `DataBoxSource.Stored` these tests passed or failed on whether the developer's
   * `.lithos/md` LevelDB happened to hold a token from some earlier testnet run. Both batch tests
   * below assert on work that only happens past that guard.
   */
  private val storedDataBox: DataBoxSource = new DataBoxSource {
    override def getDataBoxToken: Option[ErgoId] = Some(ErgoId.create("11" * 32))
  }

  private case class Fixture(handler: ActorRef, sync: TestProbe, mempool: TestProbe,
                             wallet: TestProbe, probe: TestProbe)

  private def fixture(dataBoxes: DataBoxSource = storedDataBox): Fixture = {
    val (ctx, _, _) = FakeNodeContext()
    val sync = TestProbe()
    val mempool = TestProbe()
    val wallet = TestProbe()
    val handler = system.actorOf(Props(new SubmissionHandler(
      quietConfig, ctx, new FakeCache, dataBoxes, sync.ref, mempool.ref, wallet.ref)))
    Fixture(handler, sync, mempool, wallet, TestProbe())
  }

  private def stub(rollup: String, txType: RollupTxType = HoldingTransform): RollupTxStub =
    RollupTxStub(rollup, Some(100L), txType)

  private def refuseState(f: Fixture, count: Int = 1): Unit =
    (1 to count).foreach { _ =>
      f.sync.expectMsgType[GetCurrentRollupCritical](5.seconds)
      f.sync.reply(RollupUnavailable("deliberately unavailable in mailbox test"))
    }

  // ─── the property a miner actually cares about ────────────────────────────

  "A block being assembled" should "be answered while a batch is running" in {
    // Hold the worker on one state ask. If batch work ran in receive, the block request could not be
    // answered until that ask timed out.
    val f = fixture()
    f.handler ! RollupBatch(Seq(stub("rollup-a")))
    f.sync.expectMsgType[GetCurrentRollupCritical](10.seconds) // the batch is underway

    val start = System.currentTimeMillis()
    f.probe.send(f.handler, BuildBlockTxs(500, Seq.empty))
    f.probe.expectMsgType[BlockTxsReady](12.seconds).blockHeight shouldEqual 500
    val elapsed = System.currentTimeMillis() - start

    withClue(s"answered in ${elapsed}ms, which must be well short of the batch: ") {
      elapsed should be < 12000L
    }
    f.sync.reply(RollupUnavailable("release held batch"))
  }

  it should "be answered immediately when it asks for nothing" in {
    val f = fixture()
    f.probe.send(f.handler, BuildBlockTxs(500, Seq.empty))
    f.probe.expectMsgType[BlockTxsReady](5.seconds).txs shouldBe empty
  }

  // ─── the lock ─────────────────────────────────────────────────────────────

  "A batch" should "be acknowledged as soon as it is accepted" in {
    // Acknowledged on acceptance rather than on completion: a batch that fails part way has still
    // consumed its stubs, while one that never started must not lose them.
    val f = fixture()
    f.probe.send(f.handler, RollupBatch(Seq(stub("rollup-a"))))
    val ack = f.probe.expectMsgType[BatchAccepted](5.seconds)
    ack.stubs.map(_.rollupBlockId) shouldEqual Seq("rollup-a")
    refuseState(f)
  }

  "A second batch arriving during the first" should "be refused rather than interleaved" in {
    // Inline, nothing else could be processed while a batch ran, so the lock guarded nothing — while
    // the parallel attempts went on reading feeAllocations after receive returned, where a second
    // batch could overwrite them underneath.
    val f = fixture()
    f.probe.send(f.handler, RollupBatch(Seq(stub("rollup-a"))))
    f.probe.expectMsgType[BatchAccepted](5.seconds)
    f.sync.expectMsgType[GetCurrentRollupCritical](10.seconds) // underway

    f.probe.send(f.handler, RollupBatch(Seq(stub("rollup-late"))))
    withClue("a refused batch is not acknowledged, so its sender keeps the stubs: ") {
      f.probe.expectNoMessage(4.seconds)
    }
    f.sync.reply(RollupUnavailable("release held batch"))
  }

  it should "be accepted again once the first has finished" in {
    // The lock is released on BatchFinished from both branches, so a batch that fails does not wedge
    // the handler forever.
    val f = fixture()
    f.probe.send(f.handler, RollupBatch(Seq(stub("rollup-a"))))
    f.probe.expectMsgType[BatchAccepted](5.seconds)

    refuseState(f)
    awaitAssert({
      f.probe.send(f.handler, RollupBatch(Seq(stub("rollup-b"))))
      f.probe.expectMsgType[BatchAccepted](500.millis).stubs.map(_.rollupBlockId) shouldEqual Seq("rollup-b")
    }, 5.seconds, 100.millis)
    refuseState(f)
  }

  "A batch with no data box" should "be refused before any rollup state is read, and free the lock" in {
    // The guard in front of the whole batch: without a data box this client cannot commit a score,
    // so no rollup transaction is attempted. Asserted positively — the sync handler is asked for
    // nothing — and then at the moment the two behaviours differ, which is the NEXT batch: an
    // exception thrown while choosing the warning to log would also end the batch here, so only the
    // second acceptance separates "refused cleanly" from "died on the way to a log line".
    val f = fixture(new DataBoxSource { override def getDataBoxToken: Option[ErgoId] = None })
    f.probe.send(f.handler, RollupBatch((1 to 4).map(i => stub(s"rollup-$i"))))
    f.probe.expectMsgType[BatchAccepted](5.seconds)
    f.sync.expectNoMessage(3.seconds)

    f.probe.send(f.handler, RollupBatch(Seq(stub("rollup-late"))))
    f.probe.expectMsgType[BatchAccepted](10.seconds).stubs.map(_.rollupBlockId) shouldEqual Seq("rollup-late")
  }

  // ─── fee-less builds ──────────────────────────────────────────────────────

  "A fee-less build" should "answer with an empty set rather than failing the block" in {
    // Every stub here is unbuildable — no rollup state — and the block must still get an answer.
    // Mining continues on the genesis transaction alone; the block is not held up.
    val f = fixture()
    f.probe.send(f.handler, BuildBlockTxs(700, Seq(stub("rollup-a"), stub("rollup-b"))))
    refuseState(f, count = 2)
    val ready = f.probe.expectMsgType[BlockTxsReady](25.seconds)
    ready.blockHeight shouldEqual 700
    ready.txs shouldBe empty
  }

  "A fraud proof" should "never be offered to a block" in {
    // `buildFeeless` throws for NISPEvaluation rather than falling through: a fraud proof's context
    // variables ride on a wallet input, so unlike the three rollup phases it cannot balance on the
    // rollup box alone.
    val f = fixture()
    val fp = RollupTxStub("rollup-a", Some(100L), NISPEvaluation,
      fpInfo = Some(Array.fill[Byte](32)(1) -> "fp-hash"))
    f.probe.send(f.handler, BuildBlockTxs(800, Seq(fp)))
    refuseState(f)
    f.probe.expectMsgType[BlockTxsReady](25.seconds).txs shouldBe empty
  }

  // ─── candidate eligibility ────────────────────────────────────────────────
  //
  // Asserted on the decision rather than through `BuildBlockTxs`: a NISP submission that gets past
  // eligibility still fails at the NISP lookup in this fixture, so "no transaction was offered" is
  // true either way and would pass with the defect present.

  /** Holding age 359 at the tip, 360 at the block it would be mined in. */
  private val boundaryPeriod = 123054L
  private val boundaryTip = 123413

  private def holdingRollup(seed: Int, periodStart: Long) =
    SyncFixtures.emptyRollup(SyncFixtures.id(seed), SyncFixtures.id(seed + 1), periodStart.toInt)

  "A NISP submission valid only at the tip" should "be refused for the next block's candidate" in {
    // The node validates the package at the height it is mined at, and one rejection makes it refuse
    // every optional transaction for that height — so a stale stub suppresses unrelated valid work.
    val rollup = holdingRollup(9101, boundaryPeriod)
    val submission = RollupTxStub("rollup-a", Some(boundaryPeriod), NISPSubmission)

    withClue("valid at the tip, which is what made the tip the wrong height to ask about: ") {
      submission.validate(boundaryTip, rollup) shouldBe true
    }
    SubmissionHandler.eligibleForCandidate(
      submission, rollup, boundaryTip, boundaryTip + 1) shouldBe false
  }

  it should "be offered while it is still valid one height later" in {
    val rollup = holdingRollup(9111, boundaryPeriod)
    val submission = RollupTxStub("rollup-a", Some(boundaryPeriod), NISPSubmission)
    SubmissionHandler.eligibleForCandidate(
      submission, rollup, boundaryTip - 1, boundaryTip) shouldBe true
  }

  "A transform that only becomes valid at the candidate height" should "wait for the next block" in {
    // Deliberately conservative. It is built and signed in a tip-height context where the contract's
    // height condition does not hold yet, so offering it produces a build that cannot be signed.
    val rollup = holdingRollup(9121, boundaryPeriod)
    val transform = RollupTxStub("rollup-a", Some(boundaryPeriod), HoldingTransform)

    transform.validate(boundaryTip + 1, rollup) shouldBe true
    SubmissionHandler.eligibleForCandidate(
      transform, rollup, boundaryTip, boundaryTip + 1) shouldBe false
  }

  // ─── candidate leases ─────────────────────────────────────────────────────

  /** A real reservation over `probe`, so its lifecycle messages land where the test can see them. */
  private def leaseOver(probe: TestProbe): WalletReservation = {
    val (ctx, _, wallet) = FakeNodeContext()
    val input = ctx.getClient.execute { c =>
      NodeBox("bb" * 32, "aa" * 32, 5000000L, 0, 100, wallet.contract.ergoTreeHex).toInputUTXO(c)
    }
    val selector = WalletSelector(probe.ref, 5.seconds, ec)
    val pending = Future(selector.reserveCovering(1000000L))
    val ask = probe.expectMsgType[RetrieveCoveringInput](5.seconds)
    probe.reply(WalletInputs(Seq(input), ask.reservationId))
    Await.result(pending, 5.seconds)
  }

  "A lease taken for a height that was already dropped" should "be made uncertain at once" in {
    // A source can finish building after its height was dropped. Stored under that height instead,
    // nothing reconciles it again — the next height only moves the watermark if it asks for
    // transactions at all — and the wallet input is withheld for the life of the process.
    val f = fixture()
    val lease = leaseOver(f.wallet)

    f.probe.send(f.handler, CandidateTxsDropped(500))
    f.probe.send(f.handler, SubmissionHandler.CandidateLeaseTaken(500, lease))

    f.wallet.expectMsg(5.seconds, MarkReservationUncertain(lease.id))
  }

  it should "still be held while its own height is current" in {
    // The other side of the same rule: a lease for a live height is candidate-held, not reconciled.
    val f = fixture()
    val lease = leaseOver(f.wallet)

    f.probe.send(f.handler, SubmissionHandler.CandidateLeaseTaken(500, lease))
    f.wallet.expectNoMessage(2.seconds)
  }

  "The final send gate" should "not execute the node send after the confirmed input changes" in {
    val rollupId = SyncFixtures.id(9001)
    val oldInput = SyncFixtures.id(9002)
    val newInput = SyncFixtures.id(9003)
    val rollup = SyncFixtures.emptyRollup(rollupId, newInput, 100)
    var sent = false

    val changed = intercept[ProjectionChangedException] {
      SubmissionHandler.sendIfCurrentInput(rollupId, oldInput,
        CurrentRollup(newInput, rollup, None)) {
        sent = true
        "sent"
      }
    }

    changed.getMessage should include(s"changed from $oldInput to $newInput")
    sent shouldBe false
  }

  it should "execute the node send only after the final input matches" in {
    val rollupId = SyncFixtures.id(9011)
    val input = SyncFixtures.id(9012)
    val rollup = SyncFixtures.emptyRollup(rollupId, input, 100)
    var sends = 0

    val result = SubmissionHandler.sendIfCurrentInput(rollupId, input,
      CurrentRollup(input, rollup, None)) {
      sends += 1
      "tx-id"
    }

    result shouldEqual "tx-id"
    sends shouldEqual 1
  }
}
