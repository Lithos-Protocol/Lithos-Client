package transactions.rollups

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import org.ergoplatform.sdk.ErgoId
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import play.api.Configuration
import state.messages.RollupMessages.GetCurrentRollup
import support.{FakeCache, FakeNodeContext}
import transactions.BlockTxMessages.{BlockTxsReady, RequestBlockTxs}
import transactions.rollups.TransactionMessages.RollupTxType._
import transactions.rollups.TransactionMessages._

import scala.concurrent.duration._

/**
 * `SubmissionHandler`'s mailbox, and the lock over the fields a batch mutates.
 *
 * A batch is tens of seconds of selection asks, signing, node round trips and five-second retry
 * sleeps. It used to run inside `receive` — and this actor also answers `BuildBlockTxs`, which is a
 * miner assembling a block against a twenty-second budget. So a batch in flight silently cost every
 * block its inserted transactions: the timeout fires, mining proceeds on genesis alone, and nothing
 * in the log connects the two.
 *
 * The sync handler deliberately never answers, which is how a batch is made slow here — every
 * `latestRollupState` sits on its five-second ask, the same shape as real work.
 */
object SubmissionHandlerSpec {
  val config: com.typesafe.config.Config =
    com.typesafe.config.ConfigFactory.parseString("akka.test.single-expect-default = 40s")
}

class SubmissionHandlerSpec extends TestKit(ActorSystem("submission-handler-spec", SubmissionHandlerSpec.config))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

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

  // ─── the property a miner actually cares about ────────────────────────────

  "A block being assembled" should "be answered while a batch is running" in {
    // The regression for running the batch inline. Four stubs, each sitting on a five-second state
    // ask, so the batch occupies the actor for roughly twenty seconds; the block's request must come
    // back well inside that.
    val f = fixture()
    f.handler ! RollupBatch((1 to 4).map(i => stub(s"rollup-$i")))
    f.sync.expectMsgType[GetCurrentRollup](10.seconds) // the batch is underway

    val start = System.currentTimeMillis()
    f.probe.send(f.handler, BuildBlockTxs(500, Seq.empty))
    f.probe.expectMsgType[BlockTxsReady](12.seconds).blockHeight shouldEqual 500
    val elapsed = System.currentTimeMillis() - start

    withClue(s"answered in ${elapsed}ms, which must be well short of the batch: ") {
      elapsed should be < 12000L
    }
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
  }

  "A second batch arriving during the first" should "be refused rather than interleaved" in {
    // Inline, nothing else could be processed while a batch ran, so the lock guarded nothing — while
    // the parallel attempts went on reading feeAllocations after receive returned, where a second
    // batch could overwrite them underneath.
    val f = fixture()
    f.probe.send(f.handler, RollupBatch((1 to 4).map(i => stub(s"rollup-$i"))))
    f.probe.expectMsgType[BatchAccepted](5.seconds)
    f.sync.expectMsgType[GetCurrentRollup](10.seconds) // underway

    f.probe.send(f.handler, RollupBatch(Seq(stub("rollup-late"))))
    withClue("a refused batch is not acknowledged, so its sender keeps the stubs: ") {
      f.probe.expectNoMessage(4.seconds)
    }
  }

  it should "be accepted again once the first has finished" in {
    // The lock is released on BatchFinished from both branches, so a batch that fails does not wedge
    // the handler forever.
    val f = fixture()
    f.probe.send(f.handler, RollupBatch(Seq(stub("rollup-a"))))
    f.probe.expectMsgType[BatchAccepted](5.seconds)

    // One stub, one five-second ask, then the batch is done.
    Thread.sleep(9000)
    f.probe.send(f.handler, RollupBatch(Seq(stub("rollup-b"))))
    f.probe.expectMsgType[BatchAccepted](10.seconds).stubs.map(_.rollupBlockId) shouldEqual Seq("rollup-b")
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
    f.probe.expectMsgType[BlockTxsReady](25.seconds).txs shouldBe empty
  }
}
