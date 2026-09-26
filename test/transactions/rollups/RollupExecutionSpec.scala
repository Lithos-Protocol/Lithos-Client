package transactions.rollups
import transactions.engine.execution.RollupExecution

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import org.ergoplatform.sdk.ErgoId
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import play.api.Configuration
import state.messages.RollupMessages.{CurrentRollup, GetCurrentRollupCritical, GetRollupMetadata, RollupUnavailable}
import support.{FakeCache, FakeNodeContext, SyncFixtures}
import node.MutationConversions._
import node.model.NodeBox
import transactions.candidate.BlockTxMessages.{BlockTxsReady, CandidateTxsDropped, RequestBlockTxs}
import transactions.engine.wallet.EngineWalletMessages.{GetPinnedInputs, MarkReservationUncertain, PinnedInputs, SelectInputs, UnpinInput, WalletInputs}
import transactions.engine.wallet.{EngineFunding, FundingAllocation}
import transactions.rollups.TransactionMessages.RollupTxType._
import transactions.rollups.TransactionMessages._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/**
 * `RollupExecution`'s mailbox, and the lock over the fields a batch mutates.
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
object RollupExecutionSpec {
  val config: com.typesafe.config.Config =
    com.typesafe.config.ConfigFactory.parseString("akka.test.single-expect-default = 40s").withFallback(com.typesafe.config.ConfigFactory.load())
}

class RollupExecutionSpec extends TestKit(ActorSystem("submission-handler-spec", RollupExecutionSpec.config))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private implicit val ec: ExecutionContext = system.dispatcher

  private val quietConfig = Configuration.from(Map(
    "state.disableTransforms" -> true,
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
                             wallet: TestProbe, probe: TestProbe, api: node.NodeApi)

  private def fixture(dataBoxes: DataBoxSource = storedDataBox,
                      config: Configuration = quietConfig): Fixture = {
    val (ctx, api, _) = FakeNodeContext()
    val sync = TestProbe()
    val mempool = TestProbe()
    val wallet = TestProbe()
    val handler = system.actorOf(Props(new RollupEngineHarness(
      config, ctx, new FakeCache, dataBoxes, sync.ref, mempool.ref, wallet.ref)))
    Fixture(handler, sync, mempool, wallet, TestProbe(), api)
  }

  /** Answer the build's pin query, which it asks the wallet once and only when a fraud proof is offered. */
  private def pins(f: Fixture, ids: String*): Unit = {
    f.wallet.expectMsg(10.seconds, GetPinnedInputs)
    f.wallet.reply(PinnedInputs(ids))
  }

  private def stub(rollup: String, txType: RollupTxType = EvalTransform): RollupTxStub =
    RollupTxStub(rollup, Some(100L), txType)

  private def refuseState(f: Fixture, count: Int = 1): Unit =
    (1 to count).foreach { _ =>
      f.sync.expectMsgType[GetRollupMetadata](5.seconds)
      f.sync.reply(RollupUnavailable("deliberately unavailable in mailbox test"))
    }

  // ─── the property a miner actually cares about ────────────────────────────

  "A block being assembled" should "be answered while a batch is running" in {
    // Hold the worker on one state ask. If batch work ran in receive, the block request could not be
    // answered until that ask timed out.
    val f = fixture()
    f.handler ! RollupBatch(Seq(stub("rollup-a")))
    f.sync.expectMsgType[GetRollupMetadata](10.seconds) // the batch is underway

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
    f.probe.expectMsgType[BlockTxsReady](5.seconds).bundles shouldBe empty
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
    f.sync.expectMsgType[GetRollupMetadata](10.seconds) // underway

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

  "A transform batch with no data box" should "read metadata and release its lock on unavailable state" in {
    val f = fixture(new DataBoxSource { override def getDataBoxToken: Option[ErgoId] = None })
    f.probe.send(f.handler, RollupBatch(Seq(stub("rollup-a", HoldingTransform))))
    f.probe.expectMsgType[BatchAccepted](5.seconds)
    refuseState(f)
    awaitAssert({
      f.probe.send(f.handler, RollupBatch(Seq(stub("rollup-b"))))
      f.probe.expectMsgType[BatchAccepted](500.millis)
    }, 5.seconds, 100.millis)
    refuseState(f)
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
    ready.bundles shouldBe empty
  }

  it should "report every stub it was given and the ones it built, for the funded path to hold" in {
    val f = fixture()
    val processor = TestProbe()
    val stubs = Seq(stub("rollup-a"), stub("rollup-b"))
    f.probe.send(f.handler, BuildBlockTxs(705, stubs, reportTo = Some(processor.ref)))
    refuseState(f, count = 2)
    processor.expectMsg(25.seconds, CandidateStubsBuilt(705, stubs, Seq.empty))
    f.probe.expectMsgType[BlockTxsReady](25.seconds).blockHeight shouldEqual 705
  }

  // ─── prepare-ahead ────────────────────────────────────────────────────────
  //
  // A build is state asks, signing and node round trips, and it used to happen entirely inside the
  // miner's collection deadline. Preparation starts it when the height is first known instead, so
  // the request that follows genesis publication collects work already done. Each test below counts
  // metadata asks, because one ask per stub is what a build costs.

  "Preparation" should "build without answering, and answer the request that follows from it" in {
    val f = fixture()
    f.probe.send(f.handler, BuildBlockTxs(900, Seq(stub("rollup-a")), answer = false))
    refuseState(f)
    withClue("preparation is not itself a request, so nothing is sent back: ") {
      f.probe.expectNoMessage(1.second)
    }

    awaitAssert({
      f.probe.send(f.handler, BuildBlockTxs(900, Seq(stub("rollup-a"))))
      f.probe.expectMsgType[BlockTxsReady](500.millis).blockHeight shouldEqual 900
    }, 15.seconds, 200.millis)
    withClue("the prepared build is what answers, so nothing is built a second time: ") {
      f.sync.expectNoMessage(1.second)
    }
  }

  "A request arriving mid-build" should "wait for that build rather than be told there is nothing" in {
    // Answering empty here would waste the preparation entirely: the work is moments from ready and
    // the block would mine on genesis alone.
    val f = fixture()
    f.probe.send(f.handler, BuildBlockTxs(901, Seq(stub("rollup-a")), answer = false))
    f.sync.expectMsgType[GetRollupMetadata](10.seconds) // the build is underway

    f.probe.send(f.handler, BuildBlockTxs(901, Seq(stub("rollup-a"))))
    f.probe.expectNoMessage(1.second)
    f.sync.reply(RollupUnavailable("release held build"))
    f.probe.expectMsgType[BlockTxsReady](25.seconds).blockHeight shouldEqual 901
  }

  "Work prepared for a dropped height" should "not be handed to the request that follows it" in {
    // The package was refused or the chain moved on, so those transactions can no longer land. Kept,
    // they would be offered again against a block they are no longer valid for.
    val f = fixture()
    f.probe.send(f.handler, BuildBlockTxs(902, Seq(stub("rollup-a")), answer = false))
    refuseState(f)
    f.probe.send(f.handler, CandidateTxsDropped(902))

    f.probe.send(f.handler, BuildBlockTxs(902, Seq(stub("rollup-a"))))
    withClue("the dropped build cannot answer, so this one is built: ") {
      refuseState(f)
    }
    f.probe.expectMsgType[BlockTxsReady](25.seconds).blockHeight shouldEqual 902
  }

  it should "not be kept when the drop lands while it is still running" in {
    // A rebuild at the same height is exactly this: the node refused the package, the height is
    // dropped, and the replacement asks again. Keeping the in-flight result would offer the node
    // back the transactions it just refused.
    val f = fixture()
    f.probe.send(f.handler, BuildBlockTxs(903, Seq(stub("rollup-a")), answer = false))
    f.sync.expectMsgType[GetRollupMetadata](10.seconds) // the build is underway
    f.probe.send(f.handler, CandidateTxsDropped(903))
    f.sync.reply(RollupUnavailable("release held build"))

    f.probe.send(f.handler, BuildBlockTxs(903, Seq(stub("rollup-a"))))
    withClue("the superseded build cannot answer, so the replacement is built: ") {
      refuseState(f)
    }
    f.probe.expectMsgType[BlockTxsReady](25.seconds).blockHeight shouldEqual 903
  }

  private val fraudProof = RollupTxStub("rollup-a", Some(100L), NISPEvaluation,
    fpInfo = Some(Array.fill[Byte](32)(1) -> "fp-hash"))

  "A fraud proof" should "not be offered to a block without a free pinned input, nor read any state trying" in {
    // Its context variables ride on a wallet input, so unlike the three rollup phases it cannot
    // balance on the rollup box alone. Refused before the state ask, so it costs the stubs queued
    // behind it nothing.
    val f = fixture()
    f.probe.send(f.handler, BuildBlockTxs(800, Seq(fraudProof)))
    pins(f)
    f.probe.expectMsgType[BlockTxsReady](25.seconds).bundles shouldBe empty
    f.sync.expectNoMessage(500.millis)
  }

  it should "not ask the wallet at all when pinning is off" in {
    val f = fixture(config = quietConfig ++ Configuration.from(Map("stratum.candidate.pinnedInputs" -> 0)))
    f.probe.send(f.handler, BuildBlockTxs(802, Seq(fraudProof)))
    f.probe.expectMsgType[BlockTxsReady](25.seconds).bundles shouldBe empty
    f.wallet.expectNoMessage(500.millis)
    f.sync.expectNoMessage(500.millis)
  }

  "A pinned input no longer unspent" should "be reported to the wallet, and the proof left out" in {
    // Nothing broadcasts a pinned box, so absence from the confirmed UTXO set means a block spent it.
    // Reported so the wallet pins a replacement instead of offering the same dead box every build.
    import org.mockito.Mockito.when
    val f = fixture()
    val gone = "cd" * 32
    when(f.api.boxById(gone)).thenReturn(scala.util.Success(None))
    f.probe.send(f.handler, BuildBlockTxs(803, Seq(fraudProof)))
    pins(f, gone)
    f.wallet.expectMsg(10.seconds, UnpinInput(gone))
    f.probe.expectMsgType[BlockTxsReady](25.seconds).bundles shouldBe empty
    f.sync.expectNoMessage(500.millis)
  }

  it should "stay pinned when the read itself failed" in {
    import org.mockito.Mockito.when
    val f = fixture()
    val unread = "ef" * 32
    when(f.api.boxById(unread)).thenReturn(scala.util.Failure(new RuntimeException("node unreachable")))
    f.probe.send(f.handler, BuildBlockTxs(804, Seq(fraudProof)))
    pins(f, unread)
    f.probe.expectMsgType[BlockTxsReady](25.seconds).bundles shouldBe empty
    f.wallet.expectNoMessage(500.millis)
  }

  "A candidate build" should "try the stubs behind one it cannot build" in {
    // The engine is handed every candidate in priority order and builds until its limit. Taking the
    // first `limit` stubs instead let a head that could never be built hold the slot empty.
    val f = fixture()
    f.probe.send(f.handler, BuildBlockTxs(801, Seq(fraudProof, stub("rollup-b", HoldingTransform)), limit = 1))
    pins(f)
    withClue("the transform behind the refused fraud proof is still attempted: ") {
      f.sync.expectMsgType[GetRollupMetadata](10.seconds).blockId shouldEqual "rollup-b"
    }
    f.sync.reply(RollupUnavailable("unavailable"))
    f.probe.expectMsgType[BlockTxsReady](25.seconds).blockHeight shouldEqual 801
  }

  "A refresh" should "rebuild rather than answer with what the height already prepared" in {
    // Prepared the instant the block lands, while sync is still applying it and before the stubs for
    // the new height exist, so the prepared set is the least informed build of the height.
    val f = fixture()
    f.probe.send(f.handler, BuildBlockTxs(904, Seq(stub("rollup-a")), answer = false))
    refuseState(f)
    awaitAssert({
      f.probe.send(f.handler, BuildBlockTxs(904, Seq(stub("rollup-a"))))
      f.probe.expectMsgType[BlockTxsReady](500.millis)
    }, 15.seconds, 200.millis)

    f.probe.send(f.handler, BuildBlockTxs(904, Seq(stub("rollup-a")), refresh = true))
    withClue("the refresh reads state again: ") {
      refuseState(f)
    }
    f.probe.expectMsgType[BlockTxsReady](25.seconds).blockHeight shouldEqual 904
  }

  // ─── candidate eligibility ────────────────────────────────────────────────
  //
  // Asserted on the decision rather than through `BuildBlockTxs`: a NISP submission that gets past
  // eligibility still fails at the NISP lookup in this fixture, so "no transaction was offered" is
  // true either way and would pass with the defect present.
  //
  // Every builder signs with its pre-header pinned to the block it executes in, which is also the
  // height the node validates at, pooled or in a block. So that height is the only one asked about.

  /** Holding age 359 at the tip, 360 at the block it would be mined in. */
  private val boundaryPeriod = 123054L
  private val boundaryTip = 123413

  private def holdingRollup(seed: Int, periodStart: Long) =
    SyncFixtures.emptyRollup(SyncFixtures.id(seed), SyncFixtures.id(seed + 1), periodStart.toInt)

  "A NISP submission valid only at the tip" should "be refused for the next block's candidate" in {
    // The node validates the package at the height it is mined at, so work valid only at the tip is
    // dropped from the block the candidate becomes.
    val rollup = holdingRollup(9101, boundaryPeriod)
    val submission = RollupTxStub("rollup-a", Some(boundaryPeriod), NISPSubmission)

    withClue("valid at the tip, which is what made the tip the wrong height to ask about: ") {
      submission.validate(boundaryTip, rollup) shouldBe true
    }
    RollupExecution.eligibleForCandidate(submission, rollup, boundaryTip + 1) shouldBe false
  }

  it should "be offered while it is still valid at the block it is mined in" in {
    val rollup = holdingRollup(9111, boundaryPeriod)
    val submission = RollupTxStub("rollup-a", Some(boundaryPeriod), NISPSubmission)
    RollupExecution.eligibleForCandidate(submission, rollup, boundaryTip) shouldBe true
  }

  "A NISP submission to a rollup mined at the tip" should "be valid in the very next block" in {
    // Holding_Logic refuses a submission only inside the rollup's own block. Signing at the tip made
    // the next block look like that block, so these waited one block for nothing.
    val rollup = holdingRollup(9131, boundaryPeriod)
    val submission = RollupTxStub("rollup-a", Some(boundaryPeriod), NISPSubmission)

    submission.validate(boundaryPeriod.toInt, rollup) shouldBe false
    submission.validate(boundaryPeriod.toInt + 1, rollup) shouldBe true
  }

  "A transform that becomes valid at the candidate height" should "be offered to that block" in {
    // Signed with a pre-header at the candidate height, so the contract condition holds at signing.
    val rollup = holdingRollup(9121, boundaryPeriod)
    val transform = RollupTxStub("rollup-a", Some(boundaryPeriod), HoldingTransform)

    RollupExecution.eligibleForCandidate(transform, rollup, boundaryTip) shouldBe false
    RollupExecution.eligibleForCandidate(transform, rollup, boundaryTip + 1) shouldBe true
  }

  // ─── what a transform spends ──────────────────────────────────────────────

  private def projected(rollup: lfsm.states.Rollup,
                        ancestors: Seq[String]): state.messages.MempoolMessages.MempoolRollupMetadata = {
    val (node, _, wallet) = FakeNodeContext()
    val input = node.getClient.execute { c =>
      NodeBox("cc" * 32, "dd" * 32, 5000000L, 0, 100, wallet.contract.ergoTreeHex).toInputUTXO(c)
    }
    state.messages.MempoolMessages.MempoolRollupMetadata(input, rollup.metadata, ancestorIds = ancestors)
  }

  "A holding transform" should "spend the confirmed box when unconfirmed submissions sit on top of it" in {
    // Submissions land only before the period ends and the transform only after it, so a transform
    // chained onto one is refused wherever it goes: the parent lapses the moment the child is valid.
    val rollup = holdingRollup(9141, boundaryPeriod)
    val confirmed = SyncFixtures.id(9142)
    val reply = state.messages.RollupMessages.CurrentRollupMetadata(confirmed, rollup.metadata,
      Some(projected(rollup, Seq("ab" * 32))))

    RollupExecution.spendTarget(reply, Some(lfsm.LFSMPhase.HOLDING)) shouldEqual Some(Left(confirmed))
    withClue("a submission chains onto the same projection, which is still valid for it: ") {
      RollupExecution.spendTarget(reply, None).flatMap(_.toOption).map(_.ancestorIds) shouldEqual
        Some(Seq("ab" * 32))
    }
    withClue("the send gate agrees, so the transform built on the confirmed box is not refused: ") {
      RollupExecution.sendIfCurrentInput(rollup.blockId, confirmed, reply,
        Some(lfsm.LFSMPhase.HOLDING))("sent") shouldEqual "sent"
    }
  }

  it should "chain onto a projection that has no unconfirmed ancestors" in {
    val rollup = holdingRollup(9151, boundaryPeriod)
    val reply = state.messages.RollupMessages.CurrentRollupMetadata(SyncFixtures.id(9152), rollup.metadata,
      Some(projected(rollup, Seq.empty)))
    RollupExecution.spendTarget(reply, Some(lfsm.LFSMPhase.HOLDING)).exists(_.isRight) shouldBe true
  }

  // ─── candidate leases ─────────────────────────────────────────────────────

  /** A real reservation over `probe`, so its lifecycle messages land where the test can see them. */
  private def leaseOver(probe: TestProbe): FundingAllocation = {
    val (ctx, _, wallet) = FakeNodeContext()
    val input = ctx.getClient.execute { c =>
      NodeBox("bb" * 32, "aa" * 32, 5000000L, 0, 100, wallet.contract.ergoTreeHex).toInputUTXO(c)
    }
    val selector = EngineFunding(probe.ref, 5.seconds, ec)
    val pending = Future(selector.reserveCovering(1000000L))
    val ask = probe.expectMsgType[SelectInputs](5.seconds)
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
    f.probe.send(f.handler, RollupExecution.CandidateLeaseTaken(500, lease.reservationId))

    f.wallet.expectMsg(5.seconds, MarkReservationUncertain(lease.reservationId))
  }

  it should "still be held while its own height is current" in {
    // The other side of the same rule: a lease for a live height is candidate-held, not reconciled.
    val f = fixture()
    val lease = leaseOver(f.wallet)

    f.probe.send(f.handler, RollupExecution.CandidateLeaseTaken(500, lease.reservationId))
    f.wallet.expectNoMessage(2.seconds)
  }

  "A funded payout" should "be refused before any funding when the state it reads is not in payout" in {
    // The node can drop a pending transform from its own mempool when a candidate replaces or
    // carries it, and the read then shows the evaluation box. Signing a payout against that box
    // fails inside the interpreter, as an index error, after the wallet has been asked for inputs.
    val f = fixture()
    val evalRollup = SyncFixtures.emptyRollup(SyncFixtures.id(9201), SyncFixtures.id(9202), 100)
      .copy(state = lfsm.states.RollupInfoState.evaluation(100L, 100L, 0L))
    f.probe.send(f.handler, RollupBatch(Seq(stub("rollup-a", Payout))))
    f.probe.expectMsgType[BatchAccepted](5.seconds)
    f.sync.expectMsgType[GetCurrentRollupCritical](10.seconds)
    f.sync.reply(CurrentRollup(evalRollup.utxoId, evalRollup,
      Some(state.messages.MempoolMessages.MempoolRollupState(projected(evalRollup, Seq.empty).asInput, evalRollup))))
    withClue("no funding is asked for work that cannot be signed: ") {
      f.wallet.expectNoMessage(2.seconds)
    }
  }

  "A rollup box gone at the node" should "read as a one-line state change, not the node's error body" in {
    val ctx = org.mockito.Mockito.mock(classOf[org.ergoplatform.appkit.BlockchainContext])
    org.mockito.Mockito.when(ctx.getBoxesById("ab" * 32)).thenThrow(new org.ergoplatform.appkit.ErgoClientException(
      "Error executing API request to http://127.0.0.1:9052/utxo/byId/" + "ab" * 32 +
        ": 404: {\n  \"error\" : 404,\n  \"reason\" : \"not-found\",\n  \"detail\" : null\n}", null))
    val changed = intercept[StateChangedException](RollupExecution.unspentBox(ctx, "ab" * 32))
    changed.getMessage should not include "\n"
    changed.getMessage should include("ab" * 32)
  }

  "The final send gate" should "not execute the node send after the confirmed input changes" in {
    val rollupId = SyncFixtures.id(9001)
    val oldInput = SyncFixtures.id(9002)
    val newInput = SyncFixtures.id(9003)
    val rollup = SyncFixtures.emptyRollup(rollupId, newInput, 100)
    var sent = false

    val changed = intercept[ProjectionChangedException] {
      RollupExecution.sendIfCurrentInput(rollupId, oldInput,
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

    val result = RollupExecution.sendIfCurrentInput(rollupId, input,
      CurrentRollup(input, rollup, None)) {
      sends += 1
      "tx-id"
    }

    result shouldEqual "tx-id"
    sends shouldEqual 1
  }
}
