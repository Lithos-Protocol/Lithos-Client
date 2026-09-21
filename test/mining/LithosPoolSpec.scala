package mining

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import configs.CandidateConfig
import mining.MiningMessages._
import node.model.NodeInfo
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.{HeaderWithoutPow, HeaderWithoutPowSerializer}
import org.json.{JSONArray, JSONObject}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import scorex.crypto.authds.ADDigest
import scorex.crypto.hash.Blake2b256
import scorex.util.bytesToId
import stratum.{BlockTemplate, CollateralData}
import stratum.data.{Data, Options}
import stats.StatsCollector.StratumObserved
import support.ChainFixtures
import transactions.candidate.BlockTxMessages.CandidateTx

import java.math.BigInteger
import java.util.concurrent.{CompletableFuture, LinkedBlockingQueue, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._

object LithosPoolSpec {
  val config = ConfigFactory.parseString("""
    akka.test.single-expect-default = 5s
    lithos-contexts.polling-dispatcher.thread-pool-executor.fixed-pool-size = 2
  """)
    .withFallback(ConfigFactory.parseResources("application.conf").resolve())
}

class LithosPoolSpec extends TestKit(ActorSystem("lithos-pool-spec", LithosPoolSpec.config))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  private val parentA = "ab" * 32
  private val parentB = "cd" * 32
  private val lender = "02" + "11" * 32
  private val soloKey = "03" + "22" * 32
  private val fixtures = ArrayBuffer.empty[Fixture]
  private val cfg = CandidateConfig.Default.copy(genesisWaitMs = 30000, blockTxTimeout = 30000,
    mempoolRefreshMs = 0, logTimings = true, waitForBlockPackage = false)

  override def beforeAll(): Unit = evaluation.NTable.lookUp(1)
  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)
  override def afterEach(): Unit = {
    fixtures.foreach { f =>
      system.stop(f.pool)
      f.node.close()
    }
    fixtures.clear()
  }

  private def nodeInfo(parent: String = parentA, height: Int = 100): NodeInfo = {
    val base = ChainFixtures.infoAt(height - 1)
    base.copy(bestFullHeaderId = Some(parent), parameters = base.parameters.copy(blockVersion = 4))
  }

  private case class Call(txs: Seq[String], pk: String, observed: NodeInfo,
                          response: CompletableFuture[JSONObject] = new CompletableFuture[JSONObject]())

  private class StubNode extends MiningNodeInterface("http://127.0.0.1:1/") {
    @volatile var observed: NodeInfo = LithosPoolSpec.this.nodeInfo()
    @volatile var closed = false
    val calls = new LinkedBlockingQueue[Call]()
    val solutions = new LinkedBlockingQueue[(String, String)]()
    val concurrent = new AtomicInteger()
    val maximumConcurrent = new AtomicInteger()
    private val pending = new java.util.concurrent.CopyOnWriteArrayList[Call]()
    override def info(): NodeInfo = observed
    override def candidateWithTxs(txs: Seq[String], apiKey: Option[String], pk: Option[String]): JSONObject =
      request(txs, pk.get)
    override def soloCandidate(): JSONObject = request(Seq.empty, soloKey)
    override def sendSolution(nonce: String, pk: String): Boolean = {
      solutions.add(nonce -> pk)
      true
    }
    private def request(txs: Seq[String], pk: String): JSONObject = {
      require(!closed, "fixture closed")
      val call = Call(txs, pk, observed)
      pending.add(call)
      maximumConcurrent.accumulateAndGet(concurrent.incrementAndGet(), (a: Int, b: Int) => math.max(a, b))
      calls.add(call)
      try call.response.get(15, TimeUnit.SECONDS)
      finally {
        concurrent.decrementAndGet()
        pending.remove(call)
      }
    }
    def close(): Unit = {
      closed = true
      pending.forEach(call => call.response.completeExceptionally(new IllegalStateException("fixture closed")))
    }
  }

  private case class Fixture(pool: ActorRef, builder: TestProbe, miner: TestProbe,
                             node: StubNode, clock: AtomicLong, manager: Option[TestProbe])

  private def fixture(config: CandidateConfig = cfg, controlledManager: Boolean = false,
                      nodeVersion: Int = 4, stats: Option[ActorRef] = None): Fixture = {
    val node = new StubNode
    node.observed = node.observed.copy(parameters = node.observed.parameters.copy(blockVersion = nodeVersion))
    val builder = TestProbe()
    val miner = TestProbe()
    val state = TestProbe()
    val manager = if (controlledManager) Some(TestProbe()) else None
    val clock = new AtomicLong(System.nanoTime())
    val options = new Options(2, 1L, 60000L, 3600000L, "http://127.0.0.1:1/",
      BigInteger.valueOf(4000000000L), new Data)
    val pool = system.actorOf(Props(new LithosPool(options, true, null, null, "test-key", false,
      null, state.ref, true, 3600000, config,
      Seq(CandidateSource(configs.CandidateSourceConfig.Rollups, builder.ref)), stats, 3600000) {
      override protected lazy val nodeInterface: MiningNodeInterface = node
      override protected def createCandidateBuilder(): Option[ActorRef] = Some(builder.ref)
      override protected def createJobManager(): ActorRef = manager.map(_.ref).getOrElse(super.createJobManager())
      override protected def nowNanos(): Long = clock.get()
    }))
    val f = Fixture(pool, builder, miner, node, clock, manager)
    fixtures += f
    builder.expectMsg(ChainAdvanced(100, parentA))
    miner.send(pool, MinerConnected("miner", miner.ref))
    f
  }

  private def pkg(parent: String = parentA, genesis: String = "01" * 32, revision: Int = 0): BlockPackage = {
    val collateral = CollateralData(genesis, new JSONObject().put("id", genesis).toString, lender,
      Array.emptyByteArray, Array.emptyByteArray, "02" * 32, "address")
    val extra = if (revision == 0) Seq.empty else
      Seq(CandidateTx(s"extra-$revision", new JSONObject().put("id", s"extra-$revision").toString, CandidateTx.Payout))
    BlockPackage(100, collateral, extra, revision, parent)
  }

  private def nextCall(f: Fixture): Call = {
    val call = f.node.calls.poll(5, TimeUnit.SECONDS)
    call should not be null
    call
  }

  private def response(call: Call, seed: Int = 1): JSONObject = {
    val digest = Blake2b256(Array(seed.toByte))
    val header = new HeaderWithoutPow(4.toByte, bytesToId(Hex.decode(call.observed.bestFullHeaderId.get)),
      digest, ADDigest @@ (digest ++ Array(0.toByte)), digest, 1700000000000L + seed,
      117825982L, call.observed.fullHeight.get + 1, digest, Array[Byte](0, 0, 0), Array.emptyByteArray)
    val bytes = HeaderWithoutPowSerializer.toBytes(header)
    val proofs = new JSONArray()
    call.txs.foreach(tx => proofs.put(new JSONObject().put("leaf", new JSONObject(tx).getString("id"))
      .put("levels", new JSONArray())))
    new JSONObject().put("msg", Hex.toHexString(Blake2b256(bytes))).put("h", call.observed.fullHeight.get + 1)
      .put("pk", call.pk).put("b", BigInteger.ONE)
      .put("proof", new JSONObject().put("msgPreimage", Hex.toHexString(bytes)).put("txProofs", proofs))
  }

  private def genesis(f: Fixture): BlockTemplate = {
    val base = pkg()
    f.miner.send(f.pool, BlockPackageReady(base))
    val call = nextCall(f)
    call.txs should have size 1
    call.response.complete(response(call))
    val job = f.miner.expectMsgType[BroadcastJob].template
    f.builder.expectMsg(GenesisPublished(base.identity))
    job
  }

  private def acknowledge(f: Fixture, msg: ProcessTemplate): Unit = {
    val template = new BlockTemplate("1", msg.candidate, msg.tau, msg.usesCollateral, msg.reducedShareMessages)
    f.manager.get.send(f.pool, NewJobAvailable(template, msg.publication))
  }

  private def template(f: Fixture): ProcessTemplate =
    f.manager.get.fishForMessage() { case _: ProcessTemplate => true; case _ => false }.asInstanceOf[ProcessTemplate]

  private def observation(probe: TestProbe)(accept: StratumObserved => Boolean): StratumObserved =
    probe.fishForMessage() { case event: StratumObserved => accept(event); case _ => false }
      .asInstanceOf[StratumObserved]

  "Stratum statistics" should "show only an acknowledged publication and repeat it while a replacement waits" in {
    val stats = TestProbe()
    val f = fixture(controlledManager = true, stats = Some(stats.ref))
    observation(stats)(_.activeJob.isEmpty)
    f.pool ! BlockPackageReady(pkg())
    val first = nextCall(f)
    first.response.complete(response(first))
    val gen = template(f)
    f.pool ! LithosPool.PublishStats
    observation(stats)(e => e.connectedConnections == 1 && e.activeJob.isEmpty)
    acknowledge(f, gen.copy(publication = gen.publication.map(_.copy(attempt = java.util.UUID.randomUUID()))))
    f.pool ! LithosPool.PublishStats
    observation(stats)(e => e.connectedConnections == 1 && e.activeJob.isEmpty)
    acknowledge(f, gen)
    f.miner.expectMsgType[BroadcastJob]
    val served = observation(stats)(_.activeJob.isDefined).activeJob.get
    served.mode shouldBe "genesis"
    served.publicationId shouldBe gen.publication.get.attempt.toString
    served.blockPackage.get.transactions.map(_.id) shouldBe Vector(pkg().collateral.txId)
    f.builder.expectMsg(GenesisPublished(pkg().identity))

    f.pool ! BlockPackageReady(pkg(revision = 1))
    val pending = nextCall(f)
    f.pool ! LithosPool.PublishStats
    observation(stats)(_.activeJob.contains(served)).activeJob shouldBe Some(served)
    pending.response.complete(response(pending, 2))
    val extra = template(f)
    f.pool ! LithosPool.PublishStats
    observation(stats)(_.activeJob.contains(served))
    acknowledge(f, extra)
    val augmented = observation(stats)(_.activeJob.exists(_.mode == "augmented")).activeJob.get
    augmented.blockPackage.get.revision shouldBe 1
    augmented.blockPackage.get.transactions.map(_.id) shouldBe Vector(pkg().collateral.txId, "extra-1")
  }

  it should "clear failed augmentation, then publish the genesis fallback without its removed revenue" in {
    val stats = TestProbe()
    val f = fixture(stats = Some(stats.ref))
    genesis(f)
    observation(stats)(_.activeJob.isDefined)
    f.pool ! BlockPackageReady(pkg(revision = 1).copy(revenue = 3000000L, sources = Set("rollups")))
    nextCall(f).response.completeExceptionally(new IllegalStateException("optional transactions refused"))
    observation(stats)(_.activeJob.isEmpty)
    val fallback = nextCall(f)
    fallback.response.complete(response(fallback, 2))
    val next = observation(stats)(_.activeJob.isDefined).activeJob.get
    next.mode shouldBe "genesis"
    next.blockPackage.get.expectedRevenueNanoErg shouldBe 0L
    next.blockPackage.get.sources shouldBe empty
    next.blockPackage.get.transactions should have size 1
  }

  it should "retain revenue-gated work and clear it on a same-height reorg and shutdown" in {
    val stats = TestProbe()
    val f = fixture(config = cfg.copy(minCandidateChangeRevenue = 1000000L), stats = Some(stats.ref))
    genesis(f)
    val served = observation(stats)(_.activeJob.isDefined).activeJob
    f.pool ! BlockPackageReady(pkg(revision = 1).copy(revenue = 999999L), refreshed = true)
    f.pool ! LithosPool.PublishStats
    observation(stats)(_.activeJob == served)
    f.node.calls.poll(100, TimeUnit.MILLISECONDS) shouldBe null
    f.node.observed = nodeInfo(parentB)
    f.pool ! PollBlockTemplate
    f.builder.expectMsg(ChainAdvanced(100, parentB))
    observation(stats)(_.activeJob.isEmpty)
    system.stop(f.pool)
    observation(stats)(_.stopped).activeJob shouldBe None
  }

  it should "describe a solo fallback as an active job with no block package" in {
    val stats = TestProbe()
    val f = fixture(stats = Some(stats.ref))
    f.pool ! BlockPackageReady(pkg())
    nextCall(f).response.completeExceptionally(new IllegalArgumentException("genesis refused"))
    f.builder.expectMsg(RebuildCandidate)
    val solo = nextCall(f)
    solo.response.complete(response(solo))
    val current = observation(stats)(_.activeJob.exists(_.mode == "solo")).activeJob.get
    current.parentId shouldBe parentA
    current.blockPackage shouldBe None
  }

  it should "stop reporting active work if the job manager stops while the pool remains alive" in {
    val stats = TestProbe()
    val f = fixture(stats = Some(stats.ref))
    genesis(f)
    observation(stats)(_.activeJob.isDefined)
    val probe = TestProbe()
    probe.send(f.pool, GetJobManager)
    val manager = probe.expectMsgType[ActorRef]
    system.stop(manager)
    observation(stats)(_.activeJob.isEmpty)
    f.pool ! LithosPool.PublishStats
    observation(stats)(_.activeJob.isEmpty).stopped shouldBe false
  }

  "Candidate HTTP" should "leave the mailbox, chain polling and solved blocks responsive while stalled" in {
    val f = fixture()
    val job = genesis(f)
    f.pool ! BlockPackageReady(pkg(revision = 1))
    val held = nextCall(f)
    held.txs should have size 2
    val probe = TestProbe()
    val started = System.nanoTime()
    probe.send(f.pool, GetJobManager)
    probe.expectMsgType[ActorRef](1.second)
    val accepted = ShareAccepted(job.jobId, "127.0.0.1", "miner", BigInteger.ONE, 100L,
      job.candidate.msg, BigInteger.ONE, true, BigInteger.ONE, Array.emptyByteArray,
      false, job.candidate, Array.fill[Byte](8)(1))
    f.pool ! accepted
    f.node.solutions.poll(1, TimeUnit.SECONDS) shouldBe (("01" * 8) -> lender)
    f.node.observed = nodeInfo(parentB)
    f.pool ! PollBlockTemplate
    f.builder.fishForMessage(1.second) { case ChainAdvanced(100, `parentB`) => true; case _ => false }
    val elapsed = (System.nanoTime() - started).nanos
    elapsed should be < 3.seconds
    info(s"Stalled-candidate mailbox, solution and chain notification: ${elapsed.toMillis}ms")
    f.node.maximumConcurrent.get() shouldBe 1
    held.response.complete(response(held, 2))
  }

  it should "publish genesis while optional polling workers are saturated" in {
    val gate = new java.util.concurrent.CountDownLatch(1)
    val started = new java.util.concurrent.CountDownLatch(2)
    val optional = system.dispatchers.lookup(configs.Contexts.key(configs.Contexts.Polling))
    (1 to 2).foreach { _ => optional.execute(new Runnable {
      override def run(): Unit = {
        started.countDown()
        gate.await(15, TimeUnit.SECONDS)
      }
    }) }
    try {
      started.await(2, TimeUnit.SECONDS) shouldBe true
      val f = fixture()
      val before = System.nanoTime()
      genesis(f).usedCollateral shouldBe true
      val elapsed = (System.nanoTime() - before).nanos
      elapsed should be < 3.seconds
      info(s"Genesis request through miner publication under optional-worker saturation: ${elapsed.toMillis}ms")
    } finally gate.countDown()
  }

  it should "discard a late same-height candidate and restore work for the new parent" in {
    val f = fixture()
    genesis(f)
    f.pool ! BlockPackageReady(pkg(revision = 1))
    val old = nextCall(f)
    f.node.observed = nodeInfo(parentB)
    f.pool ! PollBlockTemplate
    f.builder.expectMsg(ChainAdvanced(100, parentB))
    f.pool ! BlockPackageReady(pkg(parent = parentB))
    f.node.calls.poll(200, TimeUnit.MILLISECONDS) shouldBe null
    old.response.complete(response(old, 2))
    val restored = nextCall(f)
    restored.observed.bestFullHeaderId shouldBe Some(parentB)
    restored.txs should have size 1
    restored.response.complete(response(restored, 3))
    f.miner.expectMsgType[BroadcastJob].template.candidate.msg shouldEqual
      Hex.decode(response(restored, 3).getString("msg"))
    f.node.maximumConcurrent.get() shouldBe 1
  }

  it should "coalesce superseded revisions without overlapping cache mutations" in {
    val f = fixture()
    genesis(f)
    f.pool ! BlockPackageReady(pkg(revision = 1))
    val old = nextCall(f)
    f.pool ! BlockPackageReady(pkg(revision = 2))
    f.pool ! BlockPackageReady(pkg(revision = 3))
    f.node.calls.poll(200, TimeUnit.MILLISECONDS) shouldBe null
    old.response.complete(response(old, 2))
    val restored = nextCall(f)
    restored.txs should have size 1
    restored.response.complete(response(restored, 3))
    f.miner.expectMsgType[BroadcastJob]
    val latest = nextCall(f)
    latest.txs.last should include("extra-3")
    latest.response.complete(response(latest, 4))
    f.miner.expectMsgType[BroadcastJob]
    f.node.maximumConcurrent.get() shouldBe 1
  }

  it should "discard an expired augmentation and publish a fresh genesis candidate" in {
    val f = fixture()
    genesis(f)
    val extra = pkg(revision = 1)
    f.pool ! BlockPackageReady(extra)
    val old = nextCall(f)
    f.clock.addAndGet(cfg.blockTxTimeout.milliseconds.toNanos)
    old.response.complete(response(old, 2))
    f.builder.expectMsg(BlockTxsRejected(100, Some(extra.identity)))
    val fallback = nextCall(f)
    fallback.txs should have size 1
    fallback.response.complete(response(fallback, 3))
    f.miner.expectMsgType[BroadcastJob].template.candidate.msg shouldEqual
      Hex.decode(response(fallback, 3).getString("msg"))
    f.pool ! PollBlockTemplate
    f.node.calls.poll(200, TimeUnit.MILLISECONDS) shouldBe null
  }

  /** The node answers on the chain it holds; `f.node.observed` is what its /info reports. */
  it should "read the chain when a block overtakes a request, rejecting neither its additions nor its genesis" in {
    val f = fixture()
    genesis(f)
    f.pool ! BlockPackageReady(pkg(revision = 1))
    val overtaken = nextCall(f)
    f.node.observed = nodeInfo(parentB, 101)
    overtaken.response.complete(response(overtaken.copy(observed = f.node.observed), 2))
    f.builder.expectMsg(ChainAdvanced(101, parentB))
    f.node.calls.poll(200, TimeUnit.MILLISECONDS) shouldBe null
    f.miner.expectNoMessage(200.millis)
  }

  it should "read the chain again on the next poll, not in a loop, while /info trails the node's candidate" in {
    val f = fixture()
    genesis(f)
    f.pool ! BlockPackageReady(pkg(revision = 1))
    val reorged = nodeInfo(parentB)
    val first = nextCall(f)
    first.response.complete(response(first.copy(observed = reorged), 2))
    withClue("the read right after still shows the old tip, so the package is asked for once more, additions kept: ") {
      val again = nextCall(f)
      again.txs should have size 2
      again.response.complete(response(again.copy(observed = reorged), 3))
    }
    f.node.calls.poll(300, TimeUnit.MILLISECONDS) shouldBe null
    withClue("a candidate on another parent never reaches miners: ") {
      f.miner.expectNoMessage(200.millis)
    }
    f.node.observed = reorged
    f.pool ! PollBlockTemplate
    f.builder.expectMsg(ChainAdvanced(100, parentB))
  }

  "Job publication" should "acknowledge genesis before optional HTTP and reject delayed augmentation acknowledgements" in {
    val f = fixture(controlledManager = true)
    f.pool ! BlockPackageReady(pkg())
    val first = nextCall(f)
    first.response.complete(response(first))
    val gen = template(f)
    f.builder.expectNoMessage(200.millis)
    f.pool ! BlockPackageReady(pkg(revision = 1))
    f.node.calls.poll(200, TimeUnit.MILLISECONDS) shouldBe null
    acknowledge(f, gen)
    f.builder.expectMsg(GenesisPublished(pkg().identity))
    f.miner.expectMsgType[BroadcastJob]
    val extra = nextCall(f)
    extra.response.complete(response(extra, 2))
    val augmented = template(f)
    f.clock.addAndGet(cfg.blockTxTimeout.milliseconds.toNanos)
    acknowledge(f, augmented)
    f.builder.expectMsg(BlockTxsRejected(100, Some(pkg(revision = 1).identity)))
    val fallback = nextCall(f)
    fallback.txs should have size 1
    f.miner.expectNoMessage(200.millis)
    fallback.response.complete(response(fallback, 3))
    acknowledge(f, template(f))
    f.miner.expectMsgType[BroadcastJob]
  }

  it should "ignore an acknowledgement from another attempt of the same package" in {
    val f = fixture(controlledManager = true)
    f.pool ! BlockPackageReady(pkg())
    val call = nextCall(f)
    call.response.complete(response(call))
    val gen = template(f)
    acknowledge(f, gen.copy(publication = gen.publication.map(_.copy(attempt = java.util.UUID.randomUUID()))))
    f.miner.expectNoMessage(200.millis)
    f.builder.expectNoMessage(200.millis)
    acknowledge(f, gen)
    f.miner.expectMsgType[BroadcastJob]
    f.builder.expectMsg(GenesisPublished(pkg().identity))
  }

  it should "publish successive package revisions even when both contain extras" in {
    val f = fixture()
    genesis(f)
    (1 to 2).foreach { revision =>
      f.pool ! BlockPackageReady(pkg(revision = revision))
      val call = nextCall(f)
      call.response.complete(response(call, revision + 1))
      f.miner.expectMsgType[BroadcastJob]
    }
  }

  it should "publish additions in the first job when they finish within the genesis wait" in {
    val f = fixture(cfg.copy(waitForBlockPackage = true))
    f.pool ! BlockPackageReady(pkg(), collecting = true)
    f.node.calls.poll(200, TimeUnit.MILLISECONDS) shouldBe null
    f.pool ! BlockPackageReady(pkg(revision = 1))
    val call = nextCall(f)
    call.txs should have size 2
    call.response.complete(response(call))
    f.miner.expectMsgType[BroadcastJob]
    f.node.calls.poll(200, TimeUnit.MILLISECONDS) shouldBe null
  }

  it should "publish genesis at the deadline if additions are still building" in {
    val f = fixture(cfg.copy(waitForBlockPackage = true))
    f.pool ! BlockPackageReady(pkg(), collecting = true)
    f.node.calls.poll(200, TimeUnit.MILLISECONDS) shouldBe null
    f.clock.addAndGet(cfg.genesisWaitMs.milliseconds.toNanos)
    f.pool ! PollBlockTemplate
    val call = nextCall(f)
    call.txs should have size 1
    call.response.complete(response(call))
    f.miner.expectMsgType[BroadcastJob]
    f.builder.expectMsg(GenesisPublished(pkg().identity))
  }

  it should "publish genesis immediately when the package wait is disabled" in {
    val f = fixture()
    f.pool ! BlockPackageReady(pkg(), collecting = true)
    val call = nextCall(f)
    call.txs should have size 1
    call.response.complete(response(call))
    f.miner.expectMsgType[BroadcastJob]
  }

  "Mempool refresh" should "rebuild the package before changing the node's cached work" in {
    val f = fixture(cfg.copy(blockTransactions = true, mempoolRefreshMs = 1000))
    genesis(f)
    f.clock.addAndGet(1.second.toNanos)
    f.pool ! PollBlockTemplate
    f.builder.expectMsg(RefreshBlockPackage(pkg().identity))
    f.node.calls.poll(200, TimeUnit.MILLISECONDS) shouldBe null
    f.pool ! BlockPackageReady(pkg(revision = 1).copy(revenue = 1000000L), refreshed = true)
    val call = nextCall(f)
    call.txs.last should include("extra-1")
    call.response.complete(response(call, 2))
    f.miner.expectMsgType[BroadcastJob]
  }

  it should "accumulate revenue gains against the published package without invalidating skipped work" in {
    val f = fixture(cfg.copy(blockTransactions = true))
    val job = genesis(f)
    Seq(400000L, 999999L).zipWithIndex.foreach { case (revenue, i) =>
      f.pool ! BlockPackageReady(pkg(revision = i + 1).copy(revenue = revenue), refreshed = true)
      f.node.calls.poll(200, TimeUnit.MILLISECONDS) shouldBe null
    }
    val subscriber = TestProbe()
    subscriber.send(f.pool, RequestSubscription)
    subscriber.expectMsgType[SubscriptionData].currentJob.map(_.jobId) shouldBe Some(job.jobId)
    f.pool ! BlockPackageReady(pkg(revision = 3).copy(revenue = 1000000L), refreshed = true)
    val call = nextCall(f)
    call.response.complete(response(call, 2))
    f.miner.expectMsgType[BroadcastJob]
    f.pool ! BlockPackageReady(pkg(revision = 4).copy(revenue = 1999999L), refreshed = true)
    f.node.calls.poll(200, TimeUnit.MILLISECONDS) shouldBe null
    f.pool ! BlockPackageReady(pkg(revision = 5).copy(revenue = 2000000L), refreshed = true)
    val next = nextCall(f)
    next.response.complete(response(next, 3))
    f.miner.expectMsgType[BroadcastJob]
  }

  it should "keep the served package when a source late for the refresh carries work in it, whatever the refresh gains" in {
    val f = fixture(cfg.copy(blockTransactions = true))
    genesis(f)
    f.pool ! BlockPackageReady(pkg(revision = 1).copy(revenue = 1000000L, sources = Set("rollups", "emissions")),
      refreshed = true)
    val served = nextCall(f)
    served.response.complete(response(served, 2))
    f.miner.expectMsgType[BroadcastJob]

    // Emissions answers empty and the gain is too small: kept, so the served package still carries emissions work
    f.pool ! BlockPackageReady(pkg(revision = 2).copy(revenue = 1500000L, sources = Set("rollups")), refreshed = true)
    f.node.calls.poll(200, TimeUnit.MILLISECONDS) shouldBe null
    withClue("judged against the served package, not the last one offered: ") {
      f.pool ! BlockPackageReady(pkg(revision = 3).copy(revenue = 5000000L, sources = Set("rollups"),
        late = Set("emissions")), refreshed = true)
      f.node.calls.poll(200, TimeUnit.MILLISECONDS) shouldBe null
    }

    withClue("a source late for the refresh that carried nothing in the served package holds nothing back: ") {
      f.pool ! BlockPackageReady(pkg(revision = 4).copy(revenue = 5000000L, sources = Set("rollups", "emissions"),
        late = Set("payouts")), refreshed = true)
      val next = nextCall(f)
      next.txs.last should include("extra-4")
      next.response.complete(response(next, 3))
      f.miner.expectMsgType[BroadcastJob]
    }
  }

  it should "take a refresh that brings back a source late for the served package, without a revenue gain" in {
    val f = fixture(cfg.copy(blockTransactions = true))
    genesis(f)
    f.pool ! BlockPackageReady(pkg(revision = 1).copy(revenue = 1000000L, sources = Set("rollups"),
      late = Set("emissions")), refreshed = true)
    val served = nextCall(f)
    served.response.complete(response(served, 2))
    f.miner.expectMsgType[BroadcastJob]

    withClue("a source that answers with nothing brings nothing back, so the revenue gate still holds: ") {
      f.pool ! BlockPackageReady(pkg(revision = 2).copy(revenue = 1000000L, sources = Set("rollups")), refreshed = true)
      f.node.calls.poll(200, TimeUnit.MILLISECONDS) shouldBe null
    }
    f.pool ! BlockPackageReady(pkg(revision = 3).copy(revenue = 1000000L, sources = Set("rollups", "emissions")),
      refreshed = true)
    val next = nextCall(f)
    next.txs.last should include("extra-3")
    next.response.complete(response(next, 3))
    f.miner.expectMsgType[BroadcastJob]
  }

  it should "permit zero-revenue refreshes when the threshold is zero" in {
    val f = fixture(cfg.copy(minCandidateChangeRevenue = 0L))
    genesis(f)
    f.pool ! BlockPackageReady(pkg(revision = 1), refreshed = true)
    val call = nextCall(f)
    call.response.complete(response(call, 2))
    f.miner.expectMsgType[BroadcastJob]
  }

  it should "refresh mempool transactions with an empty package when the threshold is zero" in {
    val f = fixture(cfg.copy(blockTransactions = true, mempoolRefreshMs = 1000, minCandidateChangeRevenue = 0L))
    genesis(f)
    f.clock.addAndGet(1.second.toNanos)
    f.pool ! PollBlockTemplate
    f.builder.expectMsg(RefreshBlockPackage(pkg().identity))
    f.pool ! BlockPackageReady(pkg(), refreshed = true)
    val call = nextCall(f)
    call.txs should have size 1
    call.response.complete(response(call, 2))
    f.miner.expectMsgType[BroadcastJob]
  }

  it should "restore the latest package directly when the package wait is enabled" in {
    val f = fixture(cfg.copy(waitForBlockPackage = true))
    genesis(f)
    f.pool ! BlockPackageReady(pkg(revision = 1))
    val old = nextCall(f)
    f.pool ! BlockPackageReady(pkg(revision = 2))
    f.pool ! BlockPackageReady(pkg(revision = 3))
    f.node.calls.poll(200, TimeUnit.MILLISECONDS) shouldBe null
    old.response.complete(response(old, 2))
    val latest = nextCall(f)
    latest.txs.last should include("extra-3")
    latest.response.complete(response(latest, 3))
    f.miner.expectMsgType[BroadcastJob]
    f.node.calls.poll(200, TimeUnit.MILLISECONDS) shouldBe null
    f.node.maximumConcurrent.get() shouldBe 1
  }

  "A rejected genesis" should "fall back to solo without marking the failed package served" in {
    val f = fixture()
    f.pool ! BlockPackageReady(pkg())
    nextCall(f).response.completeExceptionally(new IllegalArgumentException("genesis refused"))
    f.builder.expectMsg(RebuildCandidate)
    val solo = nextCall(f)
    solo.txs shouldBe empty
    solo.response.complete(response(solo))
    val job = f.miner.expectMsgType[BroadcastJob].template
    job.usedCollateral shouldBe false
    job.candidate.collateralData shouldBe null
    job.candidate.pk shouldBe soloKey
  }

  "Candidate qualification" should "use the bound upcoming header version at an activation boundary" in {
    val f = fixture(nodeVersion = 3)
    genesis(f).candidate.version shouldBe 4
  }

  it should "reject a mismatched key or work message" in {
    val mutations: Seq[Call => JSONObject] = Seq(
      call => response(call).put("pk", soloKey),
      call => response(call).put("msg", "00" * 32))
    mutations.foreach { mutate =>
      val f = fixture()
      f.pool ! BlockPackageReady(pkg())
      val call = nextCall(f)
      call.response.complete(mutate(call))
      f.builder.expectMsg(RebuildCandidate)
      val fallback = nextCall(f)
      fallback.txs shouldBe empty
      fallback.response.complete(response(fallback, 3))
      f.miner.expectMsgType[BroadcastJob].template.usedCollateral shouldBe false
    }
  }
}
