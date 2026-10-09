package transactions.upkeep

import akka.actor.{ActorRef, ActorSystem, Kill, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import configs.{CandidateSourceConfig, UpkeepConfig}
import node.MutationConversions._
import node.NodeApi
import node.model.{NodeBox, NodeTransaction, Paging}
import org.ergoplatform.appkit.Parameters
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{never, verify, when}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import support.{CanonicalNodeBox, FakeNodeContext, RestartingSupervisor}
import work.lithos.mutations.Contract
import transactions.candidate.BlockTxMessages.{BlockTxsReady, CandidateTxsDropped, PrepareBlockTxs, RequestBlockTxs}
import transactions.candidate.CandidateBundle
import transactions.upkeep.UpkeepSource.ScanTick

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object UpkeepSourceSpec {
  val config: com.typesafe.config.Config = ConfigFactory.parseString("""
    akka.test.single-expect-default = 20s
    lithos-contexts.polling-dispatcher.thread-pool-executor.fixed-pool-size = 2
  """).withFallback(ConfigFactory.parseResources("application.conf").resolve())
}

/**
 * The source driven end to end with a job the spec steers, against a node that is a mock.
 *
 * Every property here is about what the actor does between its two halves — what a scan leaves
 * behind, what a build reads, forgets and refuses, and what a request is answered with — rather
 * than about any job's transaction, which is the job's own spec to prove.
 *
 * Nothing here looks inside the actor. A scan lands off the mailbox, so a spec waits for what it
 * left behind to show: in what the next build asks the node for, in the job's own counters, in
 * the refusal memory the source is handed, and in what a request is answered with. Those are the
 * effects the mining path sees, and the only ones the actor is accountable for.
 */
class UpkeepSourceSpec extends TestKit(ActorSystem("upkeep-source-spec", UpkeepSourceSpec.config))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val heights = new AtomicInteger(5000)

  private def nextHeight(): Int = heights.incrementAndGet()

  /** A box or transaction id from any seed: the seed's bytes as hex, repeated to 64 characters. */
  private def id(seed: String): String =
    (seed.getBytes("UTF-8").map(b => f"$b%02x").mkString * 64).take(64)

  private val defaultLimits: CandidateSourceConfig = CandidateSourceConfig.Default

  /**
   * @param jobs            how many of the two fake jobs are handed to the source
   * @param retryAfterScans passes a refused box sits out; large unless a spec is about the retry
   * @param supervised      under a restarting parent, for the incarnation test
   * @param firstScanDelay  long unless a spec is about the timer, so only the spec's ticks scan
   * @param space           fixed unless a spec is about the opportunistic share
   * @param order           the order due boxes are built in; the value specs set it
   * @param blockShare      the package's fraction of the block; the opportunistic specs use 0.5 so
   *                        an empty mempool leaves more than any configured share
   */
  private class Fixture(jobs: Int = 1,
                        maxBoxes: Int = UpkeepConfig.Default.maxBoxesPerJob,
                        retryAfterScans: Int = 1000,
                        limits: CandidateSourceConfig = defaultLimits,
                        supervised: Boolean = false,
                        mode: String = UpkeepConfig.Candidate,
                        verifyWithNode: Boolean = true,
                        firstScanDelay: FiniteDuration = 1.hour,
                        space: String = UpkeepConfig.Fixed,
                        opportunisticMaxTxs: Int = UpkeepConfig.Default.opportunisticMaxTxs,
                        order: String = UpkeepConfig.Rotation,
                        blockShare: Double = 1.0) {
    val api: NodeApi = mock[NodeApi]
    val (nodeContext, _, wallet) = FakeNodeContext(api, numAddresses = 1)

    /** What the node is asked for, one read per build. */
    val reads = ArrayBuffer.empty[Seq[String]]
    @volatile var live: Seq[NodeBox] = Seq.empty
    when(api.boxesWithPoolByIds(any[Seq[String]])).thenAnswer { inv =>
      val asked = inv.getArgument[Seq[String]](0)
      reads.synchronized(reads += asked)
      Success(live.filter(box => asked.contains(box.boxId)))
    }

    /** Every transaction put through the node's check, and whether the check refuses them. */
    val checked = ArrayBuffer.empty[String]
    @volatile var checkRefuses: Boolean = false
    when(api.checkTransaction(any[String])).thenAnswer { inv =>
      checked.synchronized(checked += inv.getArgument[String](0))
      if (checkRefuses) Failure(new RuntimeException("the node refuses it")) else Success("checked")
    }

    def checkCount: Int = checked.synchronized(checked.size)

    /** The mempool the node reports, served a page at a time; a failure fails every page. */
    @volatile var mempool: Try[Seq[NodeTransaction]] = Success(Seq.empty)
    val mempoolReads = new AtomicInteger(0)
    when(api.unconfirmedTransactions(any[Paging])).thenAnswer { inv =>
      val paging = inv.getArgument[Paging](0)
      mempoolReads.incrementAndGet()
      mempool.map(_.slice(paging.offset, paging.offset + paging.limit))
    }

    val job = new FakeJob(wallet)
    val other = new FakeJob(wallet, "other")
    val upkeepConfig: UpkeepConfig = UpkeepConfig.Default.copy(maxBoxesPerJob = maxBoxes,
      retryAfterScans = retryAfterScans, jobs = Map(
        job.name -> UpkeepConfig.Job(enabled = true), other.name -> UpkeepConfig.Job(enabled = true)),
      mode = mode, verifyWithNode = verifyWithNode, space = space, opportunisticMaxTxs = opportunisticMaxTxs,
      order = order)
    val enabledJobs: Seq[UpkeepJob] = UpkeepRegistry.enabled(upkeepConfig,
      Seq(job, other).take(jobs).map(fake => JobFactory(fake.name, _ => fake)))
    val memory = new UpkeepSource.Memory(retryAfterScans)
    val props: Props = Props(new UpkeepSource(nodeContext, upkeepConfig, limits.copy(enabled = true),
      enabledJobs, memory, useTrueProp = false, firstScanDelay = firstScanDelay, blockShare = blockShare))
    val probe = TestProbe()
    val source: ActorRef =
      if (!supervised) system.actorOf(props)
      else {
        val supervisor = system.actorOf(RestartingSupervisor.props(props))
        probe.send(supervisor, RestartingSupervisor.GetChild)
        probe.expectMsgType[RestartingSupervisor.Child].ref
      }

    /** An anyone-can-spend box carrying the id its bytes commit to, so the fake job can sign it with no key. */
    def box(seed: String): NodeBox =
      CanonicalNodeBox(id(seed), id(seed), Parameters.OneErg, 0, 100, Contract.SIGMA_TRUE.ergoTreeHex)

    /** A box at this wallet's own key: what no upkeep transaction may spend, whatever a job reports. */
    def walletBox(seed: String): NodeBox =
      CanonicalNodeBox(id(seed), id(seed), Parameters.OneErg, 0, 100, wallet.contract.ergoTreeHex)

    def request(height: Int = nextHeight()): Seq[CandidateBundle] = {
      probe.send(source, RequestBlockTxs(height, limits.maxTxs))
      val ready = probe.expectMsgType[BlockTxsReady]
      ready.blockHeight shouldBe height
      ready.bundles
    }

    /**
     * Tick, then ask for blocks until `landed` holds, and answer with the bundles of the request
     * that saw it. A request made before the pass lands is answered from the pass before, so the
     * effect a spec waits for has to be one a request after the pass produces: a node read, a
     * build, a count in the memory.
     */
    def scanUntil(landed: => Boolean): Seq[CandidateBundle] = {
      source ! ScanTick
      awaitAssert({
        val bundles = request()
        withClue(s"after ${reads.synchronized(reads.size)} reads, ${job.builds.get} builds, " +
          s"refused ${memory.refusedIds.map(_.take(8))}: ") { landed shouldBe true }
        bundles
      }, 20.seconds, 100.millis)
    }

    /**
     * Observe mode's counterpart: every request is answered empty at once, so the spec asks for the
     * same height until what the background task does shows. A height is observed once, and only
     * once a scan has given it something to build.
     */
    def observeUntil(height: Int)(landed: => Boolean): Unit = {
      source ! ScanTick
      awaitAssert({
        request(height) shouldBe empty
        landed shouldBe true
      }, 20.seconds, 100.millis)
    }

    def readCount: Int = reads.synchronized(reads.size)

    def lastRead: Seq[String] = reads.synchronized(reads.last)

    def nodeTouched: Boolean = !org.mockito.Mockito.mockingDetails(api).getInvocations.isEmpty
  }

  // ─── off, or nothing to do ────────────────────────────────────────────────

  /** The predicate `StartMiningServer` uses to decide whether a source exists at all. */
  "The wiring" should "start a source only when it is enabled and config turns on a job" in {
    val (_, _, wallet) = FakeNodeContext(mock[NodeApi], numAddresses = 1)
    val job = new FakeJob(wallet)
    UpkeepSource.runs(defaultLimits.copy(enabled = true), Seq(job)) shouldBe true
    UpkeepSource.runs(defaultLimits.copy(enabled = false), Seq(job)) shouldBe false
    UpkeepSource.runs(defaultLimits.copy(enabled = true), Seq.empty) shouldBe false
  }

  "The timer" should "run the first scan soon after start, with no tick sent" in {
    val f = new Fixture(firstScanDelay = 100.millis)
    awaitAssert(f.job.discoveries.get should be >= 1, 10.seconds, 50.millis)
  }

  // ─── scan and revalidation ────────────────────────────────────────────────

  "The scan" should "store what discover returned, and the build forget what the node no longer returns" in {
    val f = new Fixture()
    val a = f.box("a")
    val b = f.box("b")
    f.job.discovered = Seq(a.boxId, b.boxId)
    f.live = Seq(a) // b is already spent

    val bundles = f.scanUntil(f.readCount > 0)
    bundles should have size 1
    val member = bundles.head.members.head
    member.kind shouldBe Upkeep.kind("fake")
    member.inputIds shouldBe Set(a.boxId)
    member.sizeBytes should be > 0
    member.cost should be > 0L
    bundles.head.capital.map(_.value) shouldBe Seq(FakeJob.Tip)
    f.lastRead.sorted shouldBe Seq(a.boxId, b.boxId).sorted

    // The read is the revalidation: the id that did not come back is gone from what is held.
    f.request() should have size 1
    f.lastRead shouldBe Seq(a.boxId)
  }

  it should "read the boxes back in chunks of ReadChunk ids" in {
    val f = new Fixture(maxBoxes = UpkeepSource.ReadChunk + 44)
    val boxes = (0 until UpkeepSource.ReadChunk + 44).map(i => f.box(s"chunk$i"))
    f.job.discovered = boxes.map(_.boxId)
    f.live = boxes
    f.job.isDue = false

    f.scanUntil(f.readCount >= 2)
    f.reads.synchronized(f.reads.take(2).map(_.size)) shouldBe Seq(UpkeepSource.ReadChunk, 44)
  }

  it should "keep at most maxBoxesPerJob ids for one job" in {
    val f = new Fixture(maxBoxes = 1)
    val a = f.box("a")
    val b = f.box("b")
    f.job.discovered = Seq(a.boxId, b.boxId)
    f.live = Seq(a, b)

    f.scanUntil(f.readCount > 0)
    f.lastRead should have size 1
  }

  it should "never cut a configured id, and apply the cap to the rest" in {
    val f = new Fixture(maxBoxes = 1)
    val boxes = Seq("a", "b", "c", "d").map(f.box)
    f.job.listed = Set(boxes(2).boxId, boxes(3).boxId)
    f.job.discovered = Seq(boxes(2).boxId, boxes(3).boxId, boxes(0).boxId, boxes(1).boxId)
    f.live = boxes

    f.scanUntil(f.readCount > 0)
    f.lastRead.sorted shouldBe Seq(boxes(2).boxId, boxes(3).boxId, boxes(0).boxId).sorted
  }

  it should "skip a job whose discovery throws and still offer the others' work" in {
    val f = new Fixture(jobs = 2)
    val a = f.box("a")
    f.other.discovered = Seq(a.boxId)
    f.live = Seq(a)
    f.job.discoverFails = true

    val bundles = f.scanUntil(f.readCount > 0)
    f.job.discoveries.get should be >= 1
    bundles should have size 1
    bundles.head.members.head.kind shouldBe Upkeep.kind("other")
    f.lastRead shouldBe Seq(a.boxId)
  }

  // ─── due and refused ──────────────────────────────────────────────────────

  "A build" should "not build a box its job says is not due, and not hold that against it" in {
    val f = new Fixture()
    val a = f.box("a")
    f.job.discovered = Seq(a.boxId)
    f.live = Seq(a)
    f.job.isDue = false

    f.scanUntil(f.readCount > 0) shouldBe empty
    f.job.dueChecks.get should be >= 1
    f.job.builds.get shouldBe 0
    f.memory.refusedIds shouldBe empty

    // Not yet is not a refusal: the same box is built as soon as it is due.
    f.job.isDue = true
    f.request() should have size 1
  }

  it should "refuse a successor spending a box the job never discovered, and remember it" in {
    val f = new Fixture()
    val a = f.box("a")
    val stray = f.box("c")
    f.job.discovered = Seq(a.boxId)
    f.live = Seq(a, stray)
    f.job.behaviour = FakeJob.SpendAlso(stray)

    f.scanUntil(f.job.builds.get == 1) shouldBe empty
    f.memory.refusedIds shouldBe Set(a.boxId)

    f.request() shouldBe empty
    f.job.builds.get shouldBe 1
  }

  it should "hold a box its job cannot pay for as exhausted, and not build it next block" in {
    val f = new Fixture()
    val a = f.box("a")
    f.job.discovered = Seq(a.boxId)
    f.live = Seq(a)
    f.job.behaviour = FakeJob.Refuse

    f.scanUntil(f.job.builds.get == 1) shouldBe empty

    f.request() shouldBe empty
    withClue("a box that cannot pay must not be built again: ") { f.job.builds.get shouldBe 1 }
    f.memory.exhaustedIds shouldBe Set(a.boxId)
    f.memory.refusedIds shouldBe empty
  }

  /** A held box must not fill the cap and keep a payable box behind it out, pass after pass. */
  it should "keep held boxes out of the cap, and keep their holds" in {
    val f = new Fixture(maxBoxes = 1)
    val poor = f.box("a")
    val good = f.box("b")
    f.job.discovered = Seq(poor, good).map(_.boxId)   // discovery order: the poor box first, so the cap of one takes it
    f.live = Seq(poor, good)
    f.job.behaviour = FakeJob.CannotPayAny(Set(poor.boxId))

    f.scanUntil(f.job.builds.get >= 1) shouldBe empty
    f.memory.exhaustedIds shouldBe Set(poor.boxId)
    // the next pass leaves the held box out of the cap, so the payable one is tracked and built
    f.scanUntil(f.job.builds.get >= 2) should have size 1
    f.memory.exhaustedIds shouldBe Set(poor.boxId)
    f.request() should have size 1
  }

  /** Cannot pay does not pass with time, so the retry rule that frees a refused box does not apply. */
  it should "not offer an exhausted box again after retryAfterScans passes" in {
    val f = new Fixture(retryAfterScans = 2)
    val poor = f.box("a")
    val broken = f.box("b")
    f.job.discovered = Seq(poor.boxId, broken.boxId)
    f.live = Seq(poor, broken)
    f.job.behaviour = FakeJob.CannotPayFor(poor.boxId)

    f.scanUntil(f.job.builds.get == 2) shouldBe empty
    f.memory.exhaustedIds shouldBe Set(poor.boxId)
    f.memory.passesLeft(broken.boxId) shouldBe Some(2)

    // The refused box sits out its passes and is built again; the exhausted one stays held.
    f.scanUntil(f.memory.passesLeft(broken.boxId).contains(1)) shouldBe empty
    f.scanUntil(f.job.builds.get == 3) shouldBe empty
    f.memory.exhaustedIds shouldBe Set(poor.boxId)
    f.request() shouldBe empty
    f.job.builds.get shouldBe 3
  }

  /** A job that throws after real work must not cost one build a signing for every box it holds. */
  it should "stop after MaxRefusedPerBuild refusals and leave the rest for a later block" in {
    val f = new Fixture()
    val boxes = (0 until UpkeepSource.MaxRefusedPerBuild + 4).map(i => f.box(s"r$i"))
    f.job.discovered = boxes.map(_.boxId)
    f.live = boxes
    f.job.behaviour = FakeJob.Throw

    f.scanUntil(f.job.builds.get >= 1) shouldBe empty
    f.job.builds.get shouldBe UpkeepSource.MaxRefusedPerBuild
    f.memory.refusedIds should have size UpkeepSource.MaxRefusedPerBuild.toLong

    // The four not tried were not refused: the next block tries them.
    f.request() shouldBe empty
    f.job.builds.get shouldBe UpkeepSource.MaxRefusedPerBuild + 4
    f.memory.refusedIds should have size (UpkeepSource.MaxRefusedPerBuild + 4).toLong
  }

  /** Sizing catches most misfits before signing; what it misses must not cost a signing per due box. */
  it should "stop after MaxDeferredPerBuild successors were signed and did not fit, and refuse none" in {
    val probe = new Fixture()
    val sample = probe.box("a")
    probe.job.behaviour = FakeJob.Padded(20)
    val (heavy, floorCost) = probe.nodeContext.getClient.execute { ctx =>
      val bc = BuildContext(ctx, ctx.getHeight + 1, probe.wallet.contract)
      val input = sample.toInputUTXO(ctx)
      (Upkeep.member(probe.job.build(input, bc).get.tx, probe.job.name, input, bc.params).cost,
        Upkeep.floor(input, bc.params)._2)
    }
    heavy should be > floorCost

    // Every box fits by its floor and no built successor fits: each is signed, then deferred.
    val f = new Fixture(limits = defaultLimits.copy(maxTxs = 100, maxCost = (floorCost + heavy) / 2))
    val boxes = (0 until UpkeepSource.MaxDeferredPerBuild + 4).map(i => f.box(s"d$i"))
    f.job.discovered = boxes.map(_.boxId)
    f.live = boxes
    f.job.behaviour = FakeJob.Padded(20)

    f.scanUntil(f.job.builds.get >= 1) shouldBe empty
    f.job.builds.get shouldBe UpkeepSource.MaxDeferredPerBuild
    f.memory.refusedIds shouldBe empty
    f.memory.exhaustedIds shouldBe empty
  }

  /** Whatever a job reports, nothing it builds may spend the operator's ERG. */
  it should "refuse a successor that spends a box at this wallet's keys, even one the job discovered" in {
    val f = new Fixture()
    val mine = f.walletBox("w")
    f.job.discovered = Seq(mine.boxId)
    f.live = Seq(mine)
    f.scanUntil(f.job.builds.get >= 1) shouldBe empty
    f.memory.refusedIds shouldBe Set(mine.boxId)
  }

  /** A direct job's outputs are held to the same rule as a script job's: the source checks the signed transaction. */
  it should "refuse a successor that sends value anywhere but an input's script or this miner's collection contract" in {
    val f = new Fixture()
    val a = f.box("a")
    f.job.discovered = Seq(a.boxId)
    f.live = Seq(a)
    f.job.behaviour = FakeJob.PayElsewhere(Contract.FEE)   // a fee output: neither an input's script nor the collection contract
    f.scanUntil(f.job.builds.get >= 1) shouldBe empty
    f.memory.refusedIds shouldBe Set(a.boxId)
  }

  /** A hold must survive a pass in which the job's discovery failed: the job keeps what it last found. */
  it should "keep holds through a failed discovery pass" in {
    val f = new Fixture()
    val poor = f.box("a")
    f.job.discovered = Seq(poor.boxId)
    f.live = Seq(poor)
    f.job.behaviour = FakeJob.Refuse
    f.scanUntil(f.job.builds.get >= 1) shouldBe empty
    f.memory.exhaustedIds shouldBe Set(poor.boxId)
    f.job.discoverFails = true
    f.source ! ScanTick
    awaitAssert(f.job.discoveries.get should be >= 2, 20.seconds, 100.millis)
    Thread.sleep(300)
    f.memory.exhaustedIds shouldBe Set(poor.boxId)
  }

  /** A refresh at the same height is answered from what was prepared, not rebuilt. */
  it should "answer a refresh at the same height from what it prepared" in {
    val f = new Fixture()
    val a = f.box("a")
    f.job.discovered = Seq(a.boxId)
    f.live = Seq(a)
    f.scanUntil(f.job.builds.get >= 1) should have size 1
    val built = f.job.builds.get
    val height = nextHeight()
    f.source ! PrepareBlockTxs(height, defaultLimits.maxTxs)
    awaitAssert(f.job.builds.get shouldBe built + 1, 20.seconds, 100.millis)
    f.probe.send(f.source, RequestBlockTxs(height, defaultLimits.maxTxs, refresh = true))
    f.probe.expectMsgType[BlockTxsReady].bundles should have size 1
    f.job.builds.get shouldBe built + 1
  }

  it should "treat a build that throws as refused" in {
    val f = new Fixture()
    val a = f.box("a")
    f.job.discovered = Seq(a.boxId)
    f.live = Seq(a)
    f.job.behaviour = FakeJob.Throw

    f.scanUntil(f.job.builds.get == 1) shouldBe empty
    f.request() shouldBe empty
    f.job.builds.get shouldBe 1
    f.memory.refusedIds shouldBe Set(a.boxId)
  }

  /** A job is reviewed code, not trusted code: one box it trips on costs the block that box alone. */
  it should "lose only the box a job throws on, and still offer the rest and the other job's work" in {
    val f = new Fixture(jobs = 2)
    val a = f.box("a")
    val b = f.box("b")
    val c = f.box("c")
    f.job.discovered = Seq(a.boxId, b.boxId)
    f.other.discovered = Seq(c.boxId)
    f.live = Seq(a, b, c)
    f.job.behaviour = FakeJob.ThrowFor(a.boxId)

    val bundles = f.scanUntil(f.job.builds.get >= 2 && f.other.builds.get >= 1)
    bundles.map(_.members.head.kind).sorted shouldBe Seq(Upkeep.kind("fake"), Upkeep.kind("other"))
    bundles.flatMap(_.members.head.inputIds).toSet shouldBe Set(b.boxId, c.boxId)
    f.memory.refusedIds shouldBe Set(a.boxId)

    // Next block: the box that tripped the job is not tried again, the other two are.
    val built = (f.job.builds.get, f.other.builds.get)
    f.request() should have size 2
    (f.job.builds.get, f.other.builds.get) shouldBe (built._1 + 1, built._2 + 1)
  }

  it should "refuse a box the node reports in a form that cannot be read, and keep the rest" in {
    val f = new Fixture()
    val a = f.box("a")
    val b = f.box("b")
    // A register the node reports but that does not decode: parsing it, not the job, is what fails.
    val unreadable = b.copy(additionalRegisters = node.model.NodeRegisters(Map("R4" -> "zz")))
    f.job.discovered = Seq(a.boxId, b.boxId)
    f.live = Seq(a, unreadable)

    val bundles = f.scanUntil(f.readCount > 0)
    bundles should have size 1
    bundles.head.members.head.inputIds shouldBe Set(a.boxId)
    f.memory.refusedIds shouldBe Set(b.boxId)
  }

  it should "forget an exhausted box once no scan finds it any more" in {
    val f = new Fixture()
    val a = f.box("a")
    f.job.discovered = Seq(a.boxId)
    f.live = Seq(a)
    f.job.behaviour = FakeJob.Refuse

    f.scanUntil(f.job.builds.get == 1) shouldBe empty
    f.memory.exhaustedIds shouldBe Set(a.boxId)

    // The box changed: its successor has a new id, and the old one is not discovered again.
    f.job.discovered = Seq.empty
    f.scanUntil(f.memory.exhaustedIds.isEmpty)

    // Were the same id found again it would be a box to try, not a box refused.
    f.job.discovered = Seq(a.boxId)
    f.scanUntil(f.job.builds.get == 2)
  }

  // ─── the retry rule ───────────────────────────────────────────────────────

  "A refused box" should "be offered again after retryAfterScans passes, in case what refused it has passed" in {
    val f = new Fixture(retryAfterScans = 2)
    val a = f.box("a")
    f.job.discovered = Seq(a.boxId)
    f.live = Seq(a)
    f.job.behaviour = FakeJob.Throw

    f.scanUntil(f.job.builds.get == 1) shouldBe empty
    f.memory.passesLeft(a.boxId) shouldBe Some(2)

    // The trouble passes; the box still sits out the passes it was given.
    f.job.behaviour = FakeJob.Advance
    f.scanUntil(f.memory.passesLeft(a.boxId).contains(1)) shouldBe empty
    f.job.builds.get shouldBe 1

    // The second pass lets it through: built again, and this time advanced.
    val bundles = f.scanUntil(f.job.builds.get == 2)
    bundles should have size 1
    bundles.head.members.head.inputIds shouldBe Set(a.boxId)
    f.memory.refusedIds shouldBe empty
  }

  it should "sit out as many passes again when it is refused again" in {
    val f = new Fixture(retryAfterScans = 2)
    val a = f.box("a")
    f.job.discovered = Seq(a.boxId)
    f.live = Seq(a)
    f.job.behaviour = FakeJob.Throw

    f.scanUntil(f.job.builds.get == 1) shouldBe empty
    f.scanUntil(f.memory.passesLeft(a.boxId).contains(1)) shouldBe empty
    f.scanUntil(f.job.builds.get == 2) shouldBe empty
    f.memory.passesLeft(a.boxId) shouldBe Some(2)
    f.request() shouldBe empty
    f.job.builds.get shouldBe 2
  }

  // ─── the share ────────────────────────────────────────────────────────────

  /**
   * Measured, not estimated: what the two jobs' transactions cost the block as signing reports it.
   * The dear job comes first, so its successor is built and measured before it is left out; were
   * it second, its floor alone would keep it from being built once the cheap one had the share.
   */
  "The share" should "leave out a job whose transaction exceeds maxCost while a cheaper one fits" in {
    val probe = new Fixture(jobs = 2)
    val sample = probe.box("a")
    probe.job.behaviour = FakeJob.Padded(20)
    val (heavy, light) = probe.nodeContext.getClient.execute { ctx =>
      val bc = BuildContext(ctx, ctx.getHeight + 1, probe.wallet.contract)
      val input = sample.toInputUTXO(ctx)
      def cost(job: FakeJob): Long = Upkeep.member(job.build(input, bc).get.tx, job.name, input, bc.params).cost
      (cost(probe.job), cost(probe.other))
    }
    heavy should be > light

    val f = new Fixture(jobs = 2, limits = defaultLimits.copy(maxCost = (light + heavy) / 2))
    val a = f.box("a")
    val b = f.box("b")
    f.job.discovered = Seq(a.boxId)
    f.other.discovered = Seq(b.boxId)
    f.live = Seq(a, b)
    f.job.behaviour = FakeJob.Padded(20)

    val bundles = f.scanUntil(f.job.builds.get >= 1 && f.other.builds.get >= 1)
    bundles should have size 1
    bundles.head.members.head.kind shouldBe Upkeep.kind("other")
    bundles.head.members.head.cost shouldBe light
    // Over the share is not a refusal: the box is tried again next block, when it may fit.
    f.memory.refusedIds shouldBe empty
    f.request() should have size 1
  }

  /** A box deferred at the head every block would otherwise keep every box behind it waiting. */
  it should "start the box order at the block height modulo the number of boxes" in {
    val f = new Fixture(limits = defaultLimits.copy(maxTxs = 1))
    val boxes = Seq("a", "b", "c").map(f.box)
    f.job.discovered = boxes.map(_.boxId)
    f.live = boxes
    f.scanUntil(f.readCount > 0)

    val ordered = boxes.map(_.boxId).sorted
    Seq(900, 901, 902, 903).foreach { height =>
      val bundles = f.request(height)
      bundles should have size 1
      bundles.head.members.head.inputIds shouldBe Set(ordered(height % 3))
    }
    UpkeepSource.rotated(Seq(1, 2, 3), 4) shouldBe Seq(2, 3, 1)
    UpkeepSource.rotated(Seq.empty[Int], 7) shouldBe empty
  }

  /**
   * Boxes of one shape, so tip per byte orders as the tip does. The box at the head of the height
   * rotation goes first whatever it pays, then the highest tip per byte of the rest, so with two
   * slots the cheapest box is built only at the height that puts it at the head.
   */
  it should "admit the longest-unspent due box and then the highest tips per byte, by value, and build only those" in {
    val f = new Fixture(limits = defaultLimits.copy(maxTxs = 2), order = UpkeepConfig.Value)
    val Seq(low, high, middle) = Seq("a", "b", "c").map(f.box)
    f.job.discovered = Seq(low, high, middle).map(_.boxId)
    f.live = Seq(low, high, middle)
    val tips = Map(low.boxId -> 1000L, high.boxId -> 3000L, middle.boxId -> 2000L)
    f.job.declaredTips = tips
    f.scanUntil(f.readCount > 0)

    // all three boxes share a creation height, so the longest-unspent one is the rotation's head
    val inIdOrder = Seq(low, high, middle).map(_.boxId).sorted
    Seq(700, 701, 702).foreach { height =>
      val head = inIdOrder(Math.floorMod(height, 3))
      val best = (tips - head).maxBy(_._2)._1
      val before = f.job.builds.get
      val bundles = f.request(height)
      bundles.flatMap(_.members.flatMap(_.inputIds)).toSet shouldBe Set(head, best)
      withClue("a box was built though the share was full: ") { f.job.builds.get - before shouldBe 2 }
    }
  }

  /** The default order ignores what boxes pay: an operator sees no new order without asking for it. */
  it should "build in rotation order by default, whatever the boxes declare" in {
    val f = new Fixture(limits = defaultLimits.copy(maxTxs = 2))
    val Seq(low, high, middle) = Seq("a", "b", "c").map(f.box)
    f.job.discovered = Seq(low, high, middle).map(_.boxId)
    f.live = Seq(low, high, middle)
    f.job.declaredTips = Map(low.boxId -> 1000L, high.boxId -> 3000L, middle.boxId -> 2000L)
    f.scanUntil(f.readCount > 0)
    val inIdOrder = Seq(low, high, middle).map(_.boxId).sorted
    Seq(700, 701, 702).foreach { height =>
      val start = Math.floorMod(height, 3)
      val expected = Set(inIdOrder(start), inIdOrder((start + 1) % 3))
      f.request(height).flatMap(_.members.flatMap(_.inputIds)).toSet shouldBe expected
    }
  }

  it should "stop building once maxTxs successors are ready, and leave the rest for a later block" in {
    val f = new Fixture(limits = defaultLimits.copy(maxTxs = 1))
    val boxes = Seq("a", "b", "c").map(f.box)
    f.job.discovered = boxes.map(_.boxId)
    f.live = boxes

    val bundles = f.scanUntil(f.readCount > 0)
    bundles should have size 1
    withClue("every box was built for one slot: ") { f.job.builds.get shouldBe 1 }
    f.memory.refusedIds shouldBe empty
    f.request() should have size 1
    f.job.builds.get shouldBe 2
  }

  // ─── the opportunistic share ──────────────────────────────────────────────

  /**
   * One slot configured, room for three opportunistic ones, and four due boxes to fill them; the
   * package is half the block, so the mempool has the other half to fit in.
   */
  private class Space(space: String = UpkeepConfig.Opportunistic)
    extends Fixture(limits = defaultLimits.copy(maxTxs = 1, maxBytes = 4096L, maxCost = 200000L),
      space = space, opportunisticMaxTxs = 3, blockShare = 0.5) {
    val boxes: Seq[NodeBox] = Seq("a", "b", "c", "d").map(box)
    job.discovered = boxes.map(_.boxId)
    live = boxes
  }

  /** A waiting transaction larger than any block, so the mempool alone fills the package. */
  private val crowd = NodeTransaction(id("crowd"), Seq.empty, Seq.empty, Seq.empty,
    size = Some(Int.MaxValue / 2), cost = Some(Long.MaxValue / 4))

  "The opportunistic share" should "take up to opportunisticMaxTxs transactions when the mempool is empty" in {
    val f = new Space()
    val bundles = f.scanUntil(f.readCount > 0)
    bundles should have size 3
    f.mempoolReads.get should be > 0
  }

  it should "take up to the cap when what is waiting fits in the block beside the package" in {
    val f = new Space()
    f.mempool = Success(Seq(NodeTransaction(id("small"), Seq.empty, Seq.empty, Seq.empty, size = Some(1000), cost = Some(10000L))))
    val bundles = f.scanUntil(f.readCount > 0)
    bundles should have size 3
  }

  it should "keep the configured share when the waiting transactions do not fit beside a full package" in {
    val f = new Space()
    f.mempool = Success(Seq(crowd))
    val bundles = f.scanUntil(f.readCount > 0)
    bundles should have size 1
    f.mempoolReads.get should be > 0
  }

  it should "keep the configured share when the mempool cannot be read" in {
    val f = new Space()
    f.mempool = Failure(new RuntimeException("the node is busy"))
    val bundles = f.scanUntil(f.readCount > 0)
    bundles should have size 1
    f.memory.refusedIds shouldBe empty
  }

  it should "not read the mempool when it has no more due boxes than slots" in {
    val f = new Fixture(limits = defaultLimits.copy(maxTxs = 2), space = UpkeepConfig.Opportunistic, blockShare = 0.5)
    val a = f.box("a")
    f.job.discovered = Seq(a.boxId)
    f.live = Seq(a)
    f.scanUntil(f.readCount > 0) should have size 1
    verify(f.api, never()).unconfirmedTransactions(any[Paging])
  }

  "The fixed share" should "never read the mempool" in {
    val f = new Space(UpkeepConfig.Fixed)
    val bundles = f.scanUntil(f.readCount > 0)
    bundles should have size 1
    f.request() should have size 1
    verify(f.api, never()).unconfirmedTransactions(any[Paging])
    verify(f.api, never()).poolHistogram()
  }

  // ─── observe mode ─────────────────────────────────────────────────────────

  "Observe mode" should "answer empty at once and check each built successor once, in the background" in {
    val f = new Fixture(mode = UpkeepConfig.Observe)
    val a = f.box("a")
    val b = f.box("b")
    f.job.discovered = Seq(a.boxId, b.boxId)
    f.live = Seq(a, b)

    f.observeUntil(nextHeight())(f.checkCount >= 2)
    f.job.builds.get shouldBe 2
    f.checkCount shouldBe 2
    f.checked.synchronized(f.checked.toSet.size) shouldBe 2
    f.memory.refusedIds shouldBe empty

    // Every block is checked again: what is being watched is the job, block after block.
    f.request() shouldBe empty
    awaitAssert(f.checkCount shouldBe 4, 20.seconds, 100.millis)
    f.job.builds.get shouldBe 4
  }

  it should "observe a height once, however often it is prepared or asked for" in {
    val f = new Fixture(mode = UpkeepConfig.Observe)
    val a = f.box("a")
    f.job.discovered = Seq(a.boxId)
    f.live = Seq(a)
    f.observeUntil(nextHeight())(f.checkCount >= 1)
    val checks = f.checkCount

    val height = nextHeight()
    f.source ! PrepareBlockTxs(height, defaultLimits.maxTxs)
    f.request(height) shouldBe empty
    f.request(height) shouldBe empty
    awaitAssert(f.checkCount shouldBe checks + 1, 20.seconds, 100.millis)
    Thread.sleep(300)
    f.checkCount shouldBe checks + 1
  }

  /** Observe mode watches; a build that fails is logged, and the box is still built next block. */
  it should "hold nothing back when a build fails" in {
    val f = new Fixture(mode = UpkeepConfig.Observe)
    val a = f.box("a")
    f.job.discovered = Seq(a.boxId)
    f.live = Seq(a)
    f.job.behaviour = FakeJob.Throw
    f.scanUntil(f.job.builds.get >= 1) shouldBe empty
    f.request() shouldBe empty
    awaitAssert(f.job.builds.get should be >= 2, 20.seconds, 100.millis)
    f.memory.refusedIds shouldBe empty
    f.memory.exhaustedIds shouldBe empty
  }

  /** A refusal there is the finding observe mode exists to report, not a reason to stop watching. */
  it should "log a refusal by the node's check and not refuse the box" in {
    val f = new Fixture(mode = UpkeepConfig.Observe)
    val a = f.box("a")
    f.job.discovered = Seq(a.boxId)
    f.live = Seq(a)
    f.checkRefuses = true

    f.observeUntil(nextHeight())(f.checkCount >= 1)
    f.memory.refusedIds shouldBe empty
    f.request() shouldBe empty
    awaitAssert(f.checkCount shouldBe 2, 20.seconds, 100.millis)
    f.memory.refusedIds shouldBe empty
  }

  // ─── verifyWithNode ───────────────────────────────────────────────────────

  "Candidate mode" should "offer a successor the node's check accepts" in {
    val f = new Fixture()
    val a = f.box("a")
    f.job.discovered = Seq(a.boxId)
    f.live = Seq(a)

    f.scanUntil(f.job.builds.get >= 1) should have size 1
    f.checkCount shouldBe 1
  }

  /** The check runs at the node's own next height, so a refusal can be the height moving on, not the box. */
  it should "leave out a successor the node's check refuses and try the box again next height" in {
    val f = new Fixture()
    val a = f.box("a")
    f.job.discovered = Seq(a.boxId)
    f.live = Seq(a)
    f.checkRefuses = true

    f.scanUntil(f.job.builds.get >= 1) shouldBe empty
    f.checkCount shouldBe 1
    f.memory.refusedIds shouldBe empty

    f.checkRefuses = false
    f.request() should have size 1
    f.job.builds.get shouldBe 2
  }

  it should "offer without asking the node's check when verifyWithNode is off" in {
    val f = new Fixture(verifyWithNode = false)
    val a = f.box("a")
    f.job.discovered = Seq(a.boxId)
    f.live = Seq(a)

    f.scanUntil(f.job.builds.get >= 1) should have size 1
    f.checkCount shouldBe 0
  }

  // ─── the candidate protocol ───────────────────────────────────────────────

  "CandidateTxsDropped" should "forget a prepared height so the next request rebuilds it" in {
    val f = new Fixture()
    val a = f.box("a")
    f.job.discovered = Seq(a.boxId)
    f.live = Seq(a)
    f.scanUntil(f.readCount > 0)
    val built = f.job.builds.get

    val height = nextHeight()
    f.source ! PrepareBlockTxs(height, defaultLimits.maxTxs)
    f.request(height) should have size 1
    f.job.builds.get shouldBe built + 1

    // Prepared once, served from what was prepared.
    f.request(height) should have size 1
    f.job.builds.get shouldBe built + 1

    f.source ! CandidateTxsDropped(height)
    f.request(height) should have size 1
    f.job.builds.get shouldBe built + 2
  }

  "A restarted source" should "not re-offer what was refused in the same run" in {
    val f = new Fixture(retryAfterScans = 5, supervised = true)
    val a = f.box("a")
    f.job.discovered = Seq(a.boxId)
    f.live = Seq(a)
    f.job.behaviour = FakeJob.Throw

    f.scanUntil(f.job.builds.get == 1) shouldBe empty
    f.memory.passesLeft(a.boxId) shouldBe Some(5)

    f.source ! Kill
    // A restart empties the actor's own fields; the refusal lives outside them, and the new
    // incarnation's first pass counts against it like any other.
    f.memory.refusedIds shouldBe Set(a.boxId)
    val scans = f.job.discoveries.get
    f.scanUntil(f.memory.passesLeft(a.boxId).contains(4)) shouldBe empty
    f.job.discoveries.get should be > scans
    withClue("the new incarnation built the refused box again: ") { f.job.builds.get shouldBe 1 }
  }
}
