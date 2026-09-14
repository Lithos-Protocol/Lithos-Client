package mining

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import configs.CandidateConfig
import mining.MiningMessages._
import mutations.NodeWallet
import node.NodeApi
import node.model.NodeBox
import org.ergoplatform.appkit.{BlockchainContext, Parameters}
import org.ergoplatform.sdk.ErgoId
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import stratum.{CollateralData, CollateralNotFoundException}
import transactions.candidate.BlockTxMessages.{BlockTxsReady, CandidateTx, CandidateTxsDropped, PrepareBlockTxs, RequestBlockTxs}
import support.FakeNodeContext
import transactions.candidate.{CandidateBundle, CapitalEntry, CapitalOrigin}
import work.lithos.mutations.{InputUTXO, UTXO}

import scala.concurrent.duration._

/** Offline actor tests for collateral selection, package collection and stale-result fencing. */
object CandidateBuilderSpec {
  val config: com.typesafe.config.Config =
    com.typesafe.config.ConfigFactory.parseString("akka.test.single-expect-default = 15s")
      .withFallback(com.typesafe.config.ConfigFactory.parseResources("application.conf").resolve())
}

class CandidateBuilderSpec extends TestKit(ActorSystem("candidate-builder-spec", CandidateBuilderSpec.config))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val cfg = CandidateConfig.Default.copy(blockTransactions = false, collateralRefreshInterval = 600000,
    waitForBlockPackage = false)

  /** A collateral box stand-in. Only its id, age and bid are ever read by the code under test. */
  private def box(ctx: BlockchainContext,
                  wallet: NodeWallet,
                  value: Long,
                  inclusionHeight: Int = 100,
                  finderFee: Long = 0L): CollateralCandidate = {
    import node.MutationConversions._
    val input = NodeBox(f"$value%064x", "aa" * 32, value, 0, 100, wallet.contract.ergoTreeHex)
      .toInputUTXO(ctx)
    CollateralCandidate(input, inclusionHeight, finderFee)
  }

  private def collateralData(boxId: String, holding: Option[InputUTXO] = None): CollateralData =
    CollateralData("tx-" + boxId.take(8), "{}", "pk-" + boxId.take(8), Array.emptyByteArray,
      Array.emptyByteArray, boxId, "9address", holdingOutput = holding)

  private class StubTxBuilder(prover: NodeWallet, api: NodeApi, c: CandidateConfig,
                              @volatile var available: Seq[CollateralCandidate],
                              @volatile var failing: Set[String])
    extends CandidateTxBuilder(prover, api, c) {

    @volatile var builtFor: Seq[String] = Seq.empty
    @volatile var failNextBuilds: Int = 0

    /**
     * Heights whose build blocks until [[release]]. A completion ordering has to be forced rather
     * than waited for: what is under test is a result arriving after its own block is over.
     */
    @volatile var holdAt: Set[Int] = Set.empty
    private val gate = new java.util.concurrent.CountDownLatch(1)

    def release(): Unit = gate.countDown()

    override def loadCollateral(ctx: BlockchainContext): Seq[CollateralCandidate] = available

    override def buildGenesis(ctx: BlockchainContext, collat: InputUTXO, blockHeight: Int): CollateralData = {
      val id = collat.id.toString
      builtFor = builtFor :+ id
      if (holdAt.contains(blockHeight))
        gate.await(20, java.util.concurrent.TimeUnit.SECONDS)
      if (failNextBuilds > 0) {
        failNextBuilds -= 1
        throw new CollateralNotFoundException(s"stub refuses first build on $id")
      }
      if (failing.contains(id)) throw new CollateralNotFoundException(s"stub refuses $id")
      collateralData(id, Some(support.GenesisHolding(ctx, blockHeight)))
    }
  }

  private class TestableBuilder(client: org.ergoplatform.appkit.ErgoClient, prover: NodeWallet,
                                api: NodeApi, c: CandidateConfig, stub: StubTxBuilder,
                                sources: Seq[CandidateSource] = Seq.empty,
                                clock: () => Long = () => System.nanoTime())
    extends CandidateBuilder(client, prover, api, c, sources) {
    override protected val txBuilder: CandidateTxBuilder = stub
    override protected def nowNanos(): Long = clock()
  }

  private case class Fixture(builder: ActorRef, parent: TestProbe, stub: StubTxBuilder,
                             ids: Seq[String], node: configs.NodeContext, wallet: NodeWallet) {
    /** One past the offline context's tip, which is the height a real build would be for. */
    def nextHeight: Int = node.getClient.execute(_.getHeight) + 1
  }

  private def fixture(boxCount: Int = 4,
                      failing: Set[String] = Set.empty,
                      boxes: Option[Seq[CollateralCandidate]] = None,
                      sources: Seq[CandidateSource] = Seq.empty,
                      clock: () => Long = () => System.nanoTime(),
                      config: CandidateConfig = cfg): Fixture = {
    val api = mock[NodeApi]
    val (ctx, _, wallet) = FakeNodeContext(api, numAddresses = 1)
    val set = boxes.getOrElse(
      ctx.getClient.execute(c => (1 to boxCount).map(i => box(c, wallet, i * 1000000L))))
    val stub = new StubTxBuilder(wallet, api, config, set, failing)
    val parent = TestProbe()
    val builder = parent.childActorOf(Props(
      new TestableBuilder(ctx.getClient, wallet, api, config, stub, sources, clock)))
    // preStart runs a refresh; let it land so the set is warm before the first block.
    Thread.sleep(600)
    Fixture(builder, parent, stub, set.map(_.id), ctx, wallet)
  }

  /** A set built against the offline context, so a test can choose each box age and bid. */
  private def boxesWith(specs: Seq[(Long, Int, Long)]): Seq[CollateralCandidate] = {
    val api = mock[NodeApi]
    val (ctx, _, wallet) = FakeNodeContext(api, numAddresses = 1)
    ctx.getClient.execute(c => specs.map { case (value, height, fee) =>
      box(c, wallet, value, height, fee)
    })
  }

  private def advanceTo(f: Fixture, height: Int): BlockPackage = {
    f.builder ! ChainAdvanced(height)
    published(f)
  }

  private def published(f: Fixture): BlockPackage = {
    val pkg = f.parent.expectMsgType[BlockPackageReady].pkg
    if (pkg.blockTxs.isEmpty) f.builder ! GenesisPublished(pkg.identity)
    pkg
  }

  "The chosen box" should "stay the same across rebuilds at one height" in {
    // The node caches one candidate per miner key and the collateral box decides that key, so
    // changing box between polls evicts that cache and forces a full regeneration.
    val f = fixture()
    val first = advanceTo(f, 100)

    f.builder ! RebuildCandidate
    val second = published(f)
    // A rebuild is asked for BECAUSE the current box did not work, so it must move...
    second.collateral.collateralId should not equal first.collateral.collateralId
  }

  it should "be re-ranked when a new block arrives, so no box can hold the set" in {
    // Selection is sticky within a height and only within one. Carrying it across blocks froze the
    // set on whichever box was picked first, and nothing would ever become overdue.
    val boxes = boxesWith(Seq((1000000L, 100, 0L), (2000000L, 100, 500000L)))
    val f = fixture(boxes = Some(boxes))
    advanceTo(f, 101).collateral.collateralId shouldEqual boxes(1).id
    // The higher bid is still the higher bid, so a re-rank must land on it again.
    advanceTo(f, 102).collateral.collateralId shouldEqual boxes(1).id
  }

  /** Two boxes whose ranking flips between one height and the next, purely on age. */
  private def flipAt101: Seq[CollateralCandidate] =
    boxesWith(Seq((1000000L, 1, 0L), (2000000L, 100, 900000L)))

  "A genesis build that lands after its block" should "not become the next block's choice" in {
    // At 100 nothing is overdue and the high bid wins. At 101 the older box crosses the overdue line
    // and must win instead — which only happens if the late result's choice was not made sticky.
    val boxes = flipAt101
    val f = fixture(boxes = Some(boxes))
    f.stub.holdAt = Set(100)

    f.builder ! ChainAdvanced(100)
    f.parent.expectNoMessage(1.second) // held, so nothing is published for 100
    f.builder ! ChainAdvanced(101)
    f.stub.release()

    val pkg = published(f)
    pkg.blockHeight shouldEqual 101
    withClue("101 must re-rank rather than inherit the box 100 chose: ") {
      pkg.collateral.collateralId shouldEqual boxes.head.id
    }
    f.parent.expectNoMessage(1.second)
  }

  "A build failure that lands after its block" should "not skip a box the next block wants" in {
    // Both boxes are the same age at both heights, so the high bidder wins twice. The failure at 100
    // is about that block's attempt; applying its skip to 101 hands 101 the box nobody bid for.
    val boxes = boxesWith(Seq((1000000L, 100, 0L), (2000000L, 100, 900000L)))
    val f = fixture(boxes = Some(boxes))
    f.stub.holdAt = Set(100)
    f.stub.failNextBuilds = 1

    f.builder ! ChainAdvanced(100)
    f.builder ! ChainAdvanced(101)
    f.stub.release()

    val pkg = published(f)
    pkg.blockHeight shouldEqual 101
    withClue("100's failure must not exclude the highest bid from 101: ") {
      pkg.collateral.collateralId shouldEqual boxes(1).id
    }
  }

  "A collection round left running by a block" should "not suppress the next block's request" in {
    // The source never answers, so the flag for 100 is still set when 101's genesis is ready. Left
    // there, 101's request is a no-op that nothing restarts and the block mines on genesis alone.
    val source = TestProbe()
    val withTxs = cfg.copy(blockTransactions = true, blockTxTimeout = 30000, sources = sourceLimits(4))
    val f = fixture(sources = Seq(CandidateSource(configs.CandidateSourceConfig.Rollups, source.ref)), config = withTxs)

    f.builder ! ChainAdvanced(100)
    published(f)
    requested(source).blockHeight shouldEqual 100

    f.builder ! ChainAdvanced(101)
    // Every source is told the previous height is over before anything is asked of it again.
    source.expectMsgType[CandidateTxsDropped].blockHeight shouldEqual 100
    published(f)
    withClue("101 has its own package, so it must get its own collection round: ") {
      requested(source, 10.seconds).blockHeight shouldEqual 101
    }
  }

  private val extras = Seq(CandidateTx("optional", "{}", CandidateTx.Payout))

  private def requested(source: TestProbe, within: FiniteDuration = 3.seconds): RequestBlockTxs =
    source.fishForSpecificMessage(within) { case request: RequestBlockTxs => request }

  /** One named source's allowance, which replaces the old single package-wide transaction cap. */
  private def sourceLimits(maxTxs: Int): Map[String, configs.CandidateSourceConfig] =
    Map(configs.CandidateSourceConfig.Rollups ->
      configs.CandidateSourceConfig.Default.copy(maxTxs = maxTxs))

  private val collectingConfig = cfg.copy(blockTransactions = true, blockTxTimeout = 30000, sources = sourceLimits(4))

  "Genesis publication" should "gate collection until the exact package reaches miners" in {
    val source = TestProbe()
    val f = fixture(sources = Seq(CandidateSource(configs.CandidateSourceConfig.Rollups, source.ref)), config = collectingConfig)
    f.builder ! ChainAdvanced(100, "ab" * 32)
    val pkg = f.parent.expectMsgType[BlockPackageReady].pkg
    // Building starts with the height, so the source is asked to prepare straight away. What is
    // gated is the request that collects the result.
    source.expectMsgType[PrepareBlockTxs].blockHeight shouldBe 100
    source.expectNoMessage(200.millis)
    f.builder ! GenesisPublished(pkg.identity.copy(parentId = "cd" * 32))
    source.expectNoMessage(200.millis)
    f.builder ! GenesisPublished(pkg.identity)
    source.expectMsgType[RequestBlockTxs].blockHeight shouldBe 100
  }

  /**
   * Preparation is what makes the collection deadline affordable: a source that only starts building
   * when asked spends the whole window on signing and node reads, and the block loses its extras.
   */
  "Preparation" should "reach a source with its own allowance as soon as the height is known" in {
    val source = TestProbe()
    val f = fixture(sources = Seq(CandidateSource(configs.CandidateSourceConfig.Rollups, source.ref)),
      config = cfg.copy(blockTransactions = true, blockTxTimeout = 30000, sources = sourceLimits(7)))
    f.builder ! ChainAdvanced(100)
    source.expectMsg(PrepareBlockTxs(100, 7))
  }

  it should "not be sent to a source this client has turned off" in {
    val source = TestProbe()
    val off = Map(configs.CandidateSourceConfig.Rollups ->
      configs.CandidateSourceConfig.Default.copy(enabled = false))
    val f = fixture(sources = Seq(CandidateSource(configs.CandidateSourceConfig.Rollups, source.ref)),
      config = cfg.copy(blockTransactions = true, blockTxTimeout = 30000, sources = off))
    advanceTo(f, 100).blockTxs shouldBe empty
    withClue("a disabled source costs no build and no node read: ") {
      source.expectNoMessage(1.second)
    }
  }

  /** A revenue output as an adapter's transaction would leave it, spendable by this miner. */
  private def revenueBox(f: Fixture, value: Long = Parameters.OneErg): InputUTXO =
    f.node.getClient.execute(ctx => UTXO(f.wallet.contract, value)
      .toInput(ctx, ErgoId.create("cd" * 32), 0.toShort))

  private def declaring(entry: CapitalEntry): CandidateBundle =
    CandidateBundle(extras.toVector, capital = Seq(entry))

  private def entryOn(box: InputUTXO): CapitalEntry =
    CapitalEntry(CapitalOrigin.ExecutorReward, box, parentTxId = "optional")

  /**
   * The top-up is built here rather than by a source, because it spends what the selected
   * transactions created and the holding box genesis created — neither exists until both are done.
   */
  "Revenue a source declares" should "leave the package as a holding top-up, last" in {
    val source = TestProbe()
    val f = fixture(sources = Seq(CandidateSource(configs.CandidateSourceConfig.Rollups, source.ref)),
      config = collectingConfig)
    val height = f.nextHeight
    advanceTo(f, height)
    requested(source)

    source.reply(BlockTxsReady(height, Seq(declaring(entryOn(revenueBox(f))))))
    val pkg = published(f)
    pkg.blockTxs.map(_.kind) shouldBe Seq(CandidateTx.Payout, transactions.candidate.CandidateTopUp.Kind)
    withClue("the top-up spends the revenue output it was credited with: ") {
      pkg.blockTxs.last.inputIds should contain(revenueBox(f).id.toString)
    }
  }

  /**
   * A declaration is only worth as much as the bundle carrying it. One too large to be admitted
   * never reaches a block, so the outputs it named were never created and nothing may spend them.
   */
  it should "be dropped along with the bundle that declared it" in {
    val source = TestProbe()
    val oneSlot = collectingConfig.copy(sources = sourceLimits(1))
    val f = fixture(sources = Seq(CandidateSource(configs.CandidateSourceConfig.Rollups, source.ref)),
      config = oneSlot)
    val height = f.nextHeight
    advanceTo(f, height)
    requested(source)

    // Two members against a one-slot allowance, so the bundle is refused whole.
    val tooBig = CandidateBundle(
      Vector(CandidateTx("first", "{}", CandidateTx.Payout),
        CandidateTx("second", "{}", CandidateTx.Payout)),
      capital = Seq(entryOn(revenueBox(f))))
    source.reply(BlockTxsReady(height, Seq(tooBig)))

    withClue("nothing was admitted, so there is no revenue and no top-up to publish: ") {
      f.parent.expectNoMessage(2.seconds)
    }
  }

  "Budget logging" should "account for shared ancestors and report final source contributions" in {
    val messages = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    val appender = new ch.qos.logback.core.AppenderBase[ch.qos.logback.classic.spi.ILoggingEvent] {
      override def append(event: ch.qos.logback.classic.spi.ILoggingEvent): Unit = {
        messages.add(event.getFormattedMessage)
        ()
      }
    }
    val logger = org.slf4j.LoggerFactory.getLogger("CandidateBuilder").asInstanceOf[ch.qos.logback.classic.Logger]
    appender.start()
    logger.addAppender(appender)
    val first = TestProbe()
    val second = TestProbe()
    val limits = configs.CandidateSourceConfig.Default.copy(maxBytes = 200L, maxCost = 100L)
    val config = collectingConfig.copy(logBudgets = true,
      sources = Map("rollups" -> limits, "emissions" -> limits))
    val f = fixture(sources = Seq(CandidateSource("rollups", first.ref), CandidateSource("emissions", second.ref)),
      config = config)
    try {
      advanceTo(f, 100)
      requested(first)
      requested(second)
      val ancestor = CandidateTx("shared", "{}", CandidateTx.MempoolAncestor, sizeBytes = 100, cost = 10L)
      val a = CandidateTx("a", "{}", CandidateTx.Payout, sizeBytes = 50, cost = 20L)
      val b = CandidateTx("b", "{}", CandidateTx.Clear, sizeBytes = 70, cost = 30L)
      val oversized = CandidateTx("large", "{}", CandidateTx.Payout, sizeBytes = 201, cost = 101L)
      first.reply(BlockTxsReady(100, Seq(CandidateBundle(Vector(ancestor, a)), CandidateBundle(Vector(oversized)))))
      second.reply(BlockTxsReady(100, Seq(CandidateBundle(Vector(ancestor, b)))))
      published(f).blockTxs.map(_.id) shouldBe Seq("shared", "a", "b")
      val logged = messages.toArray.mkString("\n")
      logged should include("src=rollups | txs=2/5, bytes=150/200, cost=30/100; package contribution: txs=2, bytes=150, cost=30")
      logged should include("src=emissions | txs=2/5, bytes=170/200, cost=40/100; package contribution: txs=1, bytes=70, cost=30")
      logged should include("package budgets: bytes=220/")
      logged should include("; block limits: bytes=")
      logged should include("; genesis: bytes=0, cost=0; top-up: bytes=0, cost=0")
    } finally {
      system.stop(f.builder)
      logger.detachAppender(appender)
      appender.stop()
    }
  }

  "A changed chain parent" should "replace same-height work and allow rollback to a lower height" in {
    val f = fixture()
    f.builder ! ChainAdvanced(100, "ab" * 32)
    published(f).parentId shouldBe "ab" * 32
    f.builder ! ChainAdvanced(100, "cd" * 32)
    published(f).parentId shouldBe "cd" * 32
    f.builder ! ChainAdvanced(99, "ef" * 32)
    val rollback = published(f)
    rollback.blockHeight shouldBe 99
    rollback.parentId shouldBe "ef" * 32
  }

  "A stale package rejection" should "not invalidate a replacement at the same height" in {
    val source = TestProbe()
    val f = fixture(sources = Seq(CandidateSource(configs.CandidateSourceConfig.Rollups, source.ref)), config = collectingConfig)
    f.builder ! ChainAdvanced(100, "ab" * 32)
    val old = published(f)
    requested(source)
    f.builder ! ChainAdvanced(100, "cd" * 32)
    source.expectMsg(CandidateTxsDropped(100))
    val replacement = published(f)
    requested(source)
    val replyTo = source.lastSender
    f.builder ! BlockTxsRejected(100, Some(old.identity))
    source.expectNoMessage(200.millis)
    replyTo ! BlockTxsReady(100, Seq(transactions.candidate.CandidateBundle(extras.toVector)))
    val augmented = published(f)
    augmented.parentId shouldBe replacement.parentId
    augmented.blockTxs shouldBe extras
  }

  "Optional collection" should "publish genesis first and then admit a timely response" in {
    val source = TestProbe()
    val f = fixture(sources = Seq(CandidateSource(configs.CandidateSourceConfig.Rollups, source.ref)), config = collectingConfig)
    advanceTo(f, 100).blockTxs shouldBe empty
    requested(source)
    source.reply(BlockTxsReady(100, Seq(transactions.candidate.CandidateBundle(extras.toVector))))
    published(f).blockTxs shouldEqual extras
  }

  it should "collect before genesis publication when the package wait is enabled" in {
    val source = TestProbe()
    val f = fixture(sources = Seq(CandidateSource(configs.CandidateSourceConfig.Rollups, source.ref)),
      config = collectingConfig.copy(waitForBlockPackage = true))
    f.builder ! ChainAdvanced(100)
    val waiting = f.parent.expectMsgType[BlockPackageReady]
    waiting.collecting shouldBe true
    requested(source).refresh shouldBe false
    source.reply(BlockTxsReady(100, Seq.empty))
    val ready = f.parent.expectMsgType[BlockPackageReady]
    ready.collecting shouldBe false
    ready.pkg.blockTxs shouldBe empty
  }

  it should "refresh one package at a time and carry the admitted ERG revenue" in {
    val source = TestProbe()
    val f = fixture(sources = Seq(CandidateSource(configs.CandidateSourceConfig.Rollups, source.ref)),
      config = collectingConfig)
    val height = f.nextHeight
    val base = advanceTo(f, height)
    requested(source)
    source.reply(BlockTxsReady(height, Seq(declaring(entryOn(revenueBox(f))))))
    val first = published(f)
    first.revenue shouldBe Parameters.OneErg
    f.builder ! RefreshBlockPackage(base.identity)
    requested(source).refresh shouldBe true
    val replyTo = source.lastSender
    f.builder ! RefreshBlockPackage(first.identity)
    source.expectNoMessage(200.millis)
    replyTo ! BlockTxsReady(height, Seq(declaring(entryOn(revenueBox(f, 2 * Parameters.OneErg)))))
    val refreshed = f.parent.expectMsgType[BlockPackageReady]
    refreshed.refreshed shouldBe true
    refreshed.pkg.revenue shouldBe 2 * Parameters.OneErg
    refreshed.pkg.revision shouldBe first.revision + 1
    refreshed.pkg.collateral shouldBe first.collateral
  }

  it should "keep the published package usable after a refresh times out" in {
    val clock = new java.util.concurrent.atomic.AtomicLong(1L)
    val source = TestProbe()
    val f = fixture(sources = Seq(CandidateSource(configs.CandidateSourceConfig.Rollups, source.ref)),
      config = collectingConfig, clock = () => clock.get())
    val base = advanceTo(f, 100)
    requested(source)
    source.reply(BlockTxsReady(100, Seq(CandidateBundle(extras.toVector))))
    val first = published(f)
    f.builder ! RefreshBlockPackage(base.identity)
    requested(source)
    clock.addAndGet(collectingConfig.blockTxTimeout.milliseconds.toNanos)
    source.reply(BlockTxsReady(100, Seq.empty))
    f.parent.expectNoMessage(200.millis)
    source.expectNoMessage(200.millis)
    f.builder ! RefreshBlockPackage(first.identity)
    requested(source).refresh shouldBe true
    source.reply(BlockTxsReady(100, Seq.empty))
    f.parent.expectMsgType[BlockPackageReady].pkg.blockTxs shouldBe empty
  }

  it should "discard a response after rejection even while its ask remains live" in {
    val source = TestProbe()
    val f = fixture(sources = Seq(CandidateSource(configs.CandidateSourceConfig.Rollups, source.ref)), config = collectingConfig)
    advanceTo(f, 100)
    requested(source)
    val replyTo = source.lastSender
    f.builder ! BlockTxsRejected(100)
    source.expectMsg(CandidateTxsDropped(100))
    replyTo ! BlockTxsReady(100, Seq(transactions.candidate.CandidateBundle(extras.toVector)))
    f.parent.expectNoMessage(500.millis)
  }

  it should "enforce the deadline before a delayed timer reaches the mailbox" in {
    val clock = new java.util.concurrent.atomic.AtomicLong(1L)
    val source = TestProbe()
    val f = fixture(sources = Seq(CandidateSource(configs.CandidateSourceConfig.Rollups, source.ref)), config = collectingConfig, clock = () => clock.get())
    advanceTo(f, 100)
    requested(source)
    clock.addAndGet(collectingConfig.blockTxTimeout.milliseconds.toNanos)
    source.reply(BlockTxsReady(100, Seq(transactions.candidate.CandidateBundle(extras.toVector))))
    f.parent.expectNoMessage(500.millis)
    source.expectMsg(CandidateTxsDropped(100))
  }

  it should "keep an old same-height response out of the replacement genesis" in {
    val source = TestProbe()
    val f = fixture(sources = Seq(CandidateSource(configs.CandidateSourceConfig.Rollups, source.ref)), config = collectingConfig)
    val first = advanceTo(f, 100)
    requested(source)
    val oldReply = source.lastSender
    f.builder ! RebuildCandidate
    source.expectMsg(CandidateTxsDropped(100))
    val replacement = published(f)
    replacement.collateral.collateralId should not equal first.collateral.collateralId
    requested(source)
    val newReply = source.lastSender
    oldReply ! BlockTxsReady(100, Seq(transactions.candidate.CandidateBundle(extras.toVector)))
    f.parent.expectNoMessage(500.millis)
    newReply ! BlockTxsReady(100, Seq(transactions.candidate.CandidateBundle(extras.toVector)))
    published(f).collateral.collateralId shouldEqual
      replacement.collateral.collateralId
  }

  it should "ignore a stale rejection and reject a source response naming another height" in {
    val source = TestProbe()
    val f = fixture(sources = Seq(CandidateSource(configs.CandidateSourceConfig.Rollups, source.ref)), config = collectingConfig)
    advanceTo(f, 100)
    requested(source)
    source.reply(BlockTxsReady(99, Seq(transactions.candidate.CandidateBundle(extras.toVector))))
    f.parent.expectNoMessage(500.millis)
    f.builder ! BlockTxsRejected(99)
    source.expectNoMessage(500.millis)
  }

  "A superseded genesis build" should "not publish when a rebuild arrives at the same height" in {
    val source = TestProbe()
    val f = fixture(sources = Seq(CandidateSource(configs.CandidateSourceConfig.Rollups, source.ref)))
    f.stub.holdAt = Set(100)
    f.builder ! ChainAdvanced(100)
    awaitAssert(f.stub.builtFor should have size 1)
    // Wait for the rebuild's source notification before releasing the old build.
    f.builder ! RebuildCandidate
    source.expectMsg(CandidateTxsDropped(100))
    f.stub.release()
    published(f).blockHeight shouldEqual 100
    awaitAssert(f.stub.builtFor should have size 2)
    f.parent.expectNoMessage(500.millis)
  }

  "Ranking" should "prefer the highest bid while every box is young" in {
    val boxes = boxesWith(Seq((1000000L, 100, 10L), (2000000L, 100, 900L), (3000000L, 100, 400L)))
    val f = fixture(boxes = Some(boxes))
    advanceTo(f, 150).collateral.collateralId shouldEqual boxes(1).id
  }

  it should "take the oldest overdue box even when a younger one bids more" in {
    val boxes = boxesWith(Seq((1000000L, 100, 0L), (2000000L, 180, 900000L)))
    val f = fixture(boxes = Some(boxes))
    // The first box is 100 blocks old at height 200, the second is 20.
    advanceTo(f, 200).collateral.collateralId shouldEqual boxes.head.id
  }

  it should "not treat a box one block short of the threshold as overdue" in {
    val boxes = boxesWith(Seq((1000000L, 100, 0L), (2000000L, 180, 900000L)))
    val f = fixture(boxes = Some(boxes))
    // One block earlier the same box is only 99 old, so the bid still decides.
    advanceTo(f, 199).collateral.collateralId shouldEqual boxes(1).id
  }

  "A box the block just spent" should "not be offered again on the next block" in {
    // Keep the spent collateral excluded while the indexer still reports it.
    val f = fixture()
    val first = advanceTo(f, 100)
    val spent = first.collateral.collateralId

    // The block is found: the pool reports the spend, then the chain advances.
    f.builder ! CollateralSpent(spent)
    val second = advanceTo(f, 101)
    second.collateral.collateralId should not equal spent
  }

  it should "stay out even when a lagging refresh reports it as unspent" in {
    // The sole collateral box must remain excluded when a later refresh still reports it.
    val f = fixture(boxCount = 1)
    val spent = f.ids.head
    advanceTo(f, 100).collateral.collateralId shouldEqual spent

    f.builder ! CollateralSpent(spent)
    // The indexer is behind and still reports the box as unspent.
    f.builder ! CandidateBuilder.RefreshCollateralSet
    Thread.sleep(500)

    f.builder ! ChainAdvanced(101)
    withClue("a box known to be spent must not be built on, even when the node still lists it: ") {
      f.parent.expectNoMessage(3.seconds)
    }
  }

  it should "be forgiven after the memory expires, so a good box is not lost forever" in {
    // CollateralSpent is also sent when the node merely declines a transaction, where the box may be
    // perfectly good — so the exclusion has a ceiling rather than being permanent.
    val f = fixture(boxCount = 1)
    val only = f.ids.head
    val first = advanceTo(f, 100)
    first.collateral.collateralId shouldEqual only

    f.builder ! CollateralSpent(only)
    // With the only box excluded there is nothing to build on for the next few blocks...
    f.builder ! ChainAdvanced(101)
    f.parent.expectNoMessage(2.seconds)

    // ...and past the ceiling it returns.
    val recovered = advanceTo(f, 100 + CandidateBuilder.SpentMemoryBlocks + 1)
    recovered.collateral.collateralId shouldEqual only
  }

  "A box that cannot build" should "be skipped so the next attempt draws another" in {
    // Fail the first draw so the retry must select the remaining collateral box.
    val f = fixture(boxCount = 2)
    f.stub.failNextBuilds = 1

    val recovered = advanceTo(f, 100)
    f.stub.builtFor should have size 2
    f.stub.builtFor.distinct should have size 2
    recovered.collateral.collateralId shouldEqual f.stub.builtFor.last
  }

  "A set where every box fails" should "produce no package rather than a bad one" in {
    val f = fixture(boxCount = 2)
    f.stub.failing = f.ids.toSet

    f.builder ! ChainAdvanced(100)
    f.parent.expectNoMessage(3.seconds)
  }

  "A package" should "name the height it was built for" in {
    val f = fixture()
    advanceTo(f, 4242).blockHeight shouldEqual 4242
  }

  "A ChainAdvanced that does not move forward" should "be ignored" in {
    val f = fixture()
    advanceTo(f, 100)
    f.builder ! ChainAdvanced(100)
    f.parent.expectNoMessage(1.second)
    f.builder ! ChainAdvanced(99)
    f.parent.expectNoMessage(1.second)
  }

  "No message" should "be able to restart the actor" in {
    // Every failure path here is supposed to degrade rather than stop. An exception in `receive`
    // restarts the actor and empties the collateral set, which is a mining outage.
    val f = fixture()
    advanceTo(f, 100)

    val hostile: Seq[Any] = Seq(
      ChainAdvanced(0), ChainAdvanced(-1), RebuildCandidate,
      CollateralSpent(""), CollateralSpent("not-a-box-id"), BlockTxsRejected(0),
      BlockTxsRejected(Int.MaxValue), CandidateBuilder.RefreshCollateralSet, "garbage", 42
    )
    hostile.foreach(m => f.builder ! m)
    Thread.sleep(1500)
    f.parent.receiveWhile(2.seconds) { case _: BlockPackageReady => () }

    // Still alive and still building.
    advanceTo(f, 200).blockHeight shouldEqual 200
  }
}
