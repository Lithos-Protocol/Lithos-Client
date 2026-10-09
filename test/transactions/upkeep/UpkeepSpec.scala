package transactions.upkeep

import com.typesafe.config.ConfigFactory
import configs.{CandidateConfig, CandidateSourceConfig, ConfigValidationException, Configs, UpkeepConfig}
import org.ergoplatform.appkit.{BlockchainParameters, Parameters}
import org.ergoplatform.sdk.ErgoId
import org.ergoplatform.wallet.boxes.ErgoBoxAssetExtractor
import org.ergoplatform.wallet.interpreter.ErgoInterpreter
import node.NodeApi
import node.model.{NodeTransaction, Paging}
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.{ConfigLoader, Configuration}
import transactions.candidate.BlockTxMessages.CandidateTx
import transactions.candidate.CandidateBudget
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

import scala.util.{Failure, Success, Try}

/**
 * The pure half: what the node charges a shape, how many successors a share affords, what the
 * refusal memory holds and for how long, and what config turns on. Driven with values alone, so
 * nothing here needs a node or an actor.
 */
class UpkeepSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  /** A box or transaction id from any seed: the seed's bytes as hex, repeated to 64 characters. */
  private def id(seed: String): String =
    (seed.getBytes("UTF-8").map(b => f"$b%02x").mkString * 64).take(64)

  private def prepared(n: Int, bytes: Int = 100, cost: Long = 1000L,
                       inputs: Set[String] = Set.empty): Upkeep.Prepared = {
    val spends = if (inputs.isEmpty) Set(id(s"box$n")) else inputs
    Upkeep.Prepared("fake", id(s"box$n"), CandidateTx(id(s"tx$n"), "{}", Upkeep.kind("fake"), spends, bytes, cost), Seq.empty)
  }

  private val unbounded = CandidateBudget.Unbounded

  // ─── sizing ───────────────────────────────────────────────────────────────

  /** Mainnet's launch parameters, which is what the mocked node reports too. */
  private val params: BlockchainParameters = {
    val p = mock[BlockchainParameters]
    when(p.getInputCost).thenReturn(2000)
    when(p.getDataInputCost).thenReturn(100)
    when(p.getOutputCost).thenReturn(100)
    when(p.getTokenAccessCost).thenReturn(100)
    p
  }

  "The node's accounting" should "charge the transaction, each input, data input and output, and each token entry with its id" in {
    Upkeep.accountedCost(inputs = 1, dataInputs = 0, outputs = 2, assets = 0, params) shouldBe
      10000L + 2000L + 200L
    Upkeep.accountedCost(inputs = 2, dataInputs = 1, outputs = 3, assets = 4, params) shouldBe
      10000L + 4000L + 100L + 300L + 800L
  }

  "A share" should "afford nothing once any dimension is spent" in {
    Upkeep.Share(slots = 0, bytes = 100L, cost = 100L).full shouldBe true
    Upkeep.Share(slots = 1, bytes = 0L, cost = 100L).full shouldBe true
    Upkeep.Share(slots = 1, bytes = 100L, cost = 0L).full shouldBe true
    Upkeep.Share(slots = 1, bytes = 1L, cost = 1L).full shouldBe false
  }

  it should "say whether a floor fits what is left, before anything is built" in {
    val share = Upkeep.Share(slots = 1, bytes = 500L, cost = 20000L)
    share.affords(500L, 20000L) shouldBe true
    share.affords(501L, 20000L) shouldBe false
    share.affords(500L, 20001L) shouldBe false
    share.copy(slots = 0).affords(1L, 1L) shouldBe false
  }

  it should "take a successor off every dimension it admits" in {
    val share = Upkeep.Share.of(2, CandidateBudget(1000L, 5000L))
    val admitted = share.admit(prepared(1, bytes = 300, cost = 2000L)).getOrElse(fail("not admitted"))
    admitted.slots shouldBe 1
    admitted.bytes shouldBe 700L
    admitted.cost shouldBe 3000L
    admitted.chosen.map(_.boxId) shouldBe Seq(id("box1"))
    admitted.usedBytes(CandidateBudget(1000L, 5000L)) shouldBe 300L
    admitted.usedCost(CandidateBudget(1000L, 5000L)) shouldBe 2000L
  }

  /** A box carrying `tokens` distinct tokens, as a successor's input. */
  private def boxWith(tokens: Int): InputUTXO = {
    val (nodeContext, _, _) = support.FakeNodeContext(mock[node.NodeApi], numAddresses = 1)
    nodeContext.getClient.execute { ctx =>
      UTXO(Contract.SIGMA_TRUE, Parameters.OneErg,
        (1 to tokens).map(i => Token(ErgoId.create(f"$i%064x"), i.toLong)))
        .setCreationHeight(100).toInput(ctx, ErgoId.create("00" * 32), 0.toShort)
    }
  }

  /**
   * The box's tokens go in and come out again, and the node charges each entry and each distinct
   * id on both sides: four charges per token, which is what the node's own arithmetic gives and
   * what the floor charges once, no more.
   */
  "A floor" should "charge a box's tokens what the node charges for carrying them through, counted once" in {
    val plain = boxWith(tokens = 0)
    val carrying = boxWith(tokens = 3)
    val tokenTerm = Upkeep.floor(carrying, params)._2 - Upkeep.floor(plain, params)._2

    tokenTerm shouldBe ErgoBoxAssetExtractor.totalAssetsAccessCost(3, 3, 3, 3, 100).toLong
    tokenTerm shouldBe 4L * 3 * 100
    Upkeep.floor(plain, params)._2 shouldBe Upkeep.InitCost + 2000L + 100L
    Upkeep.InitCost shouldBe ErgoInterpreter.interpreterInitCost.toLong
  }

  it should "weigh the box's own serialized bytes" in {
    val box = boxWith(tokens = 2)
    Upkeep.floor(box, params)._1 shouldBe box.bytes.length.toLong
  }

  // ─── the share, offered successors in turn ────────────────────────────────

  /** Offered in turn, as the build offers them: one that does not fit is passed over, not the end. */
  private def admitAll(candidates: Seq[Upkeep.Prepared], maxTxs: Int, budget: CandidateBudget): Seq[Upkeep.Prepared] =
    candidates.foldLeft(Upkeep.Share.of(maxTxs, budget))((share, c) => share.admit(c).getOrElse(share)).chosen

  "A share offered successors in turn" should "take everything it affords, in order" in {
    val all = (1 to 4).map(prepared(_))
    admitAll(all, maxTxs = 10, unbounded) shouldBe all
  }

  it should "stop at maxTxs" in {
    val all = (1 to 10).map(prepared(_))
    admitAll(all, maxTxs = 3, unbounded) shouldBe all.take(3)
  }

  it should "stop at maxBytes" in {
    val all = (1 to 10).map(prepared(_, bytes = 100))
    admitAll(all, maxTxs = 10, CandidateBudget(250L, Long.MaxValue)) shouldBe all.take(2)
  }

  it should "stop at maxCost" in {
    val all = (1 to 10).map(prepared(_, cost = 1000L))
    admitAll(all, maxTxs = 10, CandidateBudget(Long.MaxValue, 2500L)) shouldBe all.take(2)
  }

  /** Successors are independent transactions, so a large one says nothing about a small one behind it. */
  it should "pass over one that does not fit and take a smaller one behind it" in {
    val big = prepared(1, bytes = 1000)
    val small = prepared(2, bytes = 100)
    admitAll(Seq(big, small), maxTxs = 10, CandidateBudget(500L, Long.MaxValue)) shouldBe Seq(small)
  }

  it should "leave out a second successor spending a box the first already spends" in {
    val first = prepared(1, inputs = Set(id("shared"), id("one")))
    val second = prepared(2, inputs = Set(id("shared"), id("two")))
    val third = prepared(3)
    admitAll(Seq(first, second, third), maxTxs = 10, unbounded) shouldBe Seq(first, third)
  }

  it should "take nothing when the share affords no transaction" in {
    admitAll((1 to 3).map(prepared(_)), maxTxs = 0, unbounded) shouldBe empty
    admitAll((1 to 3).map(prepared(_)), maxTxs = 3, CandidateBudget(10L, 10L)) shouldBe empty
  }

  "The order boxes are built in" should "put the most revenue per byte first, then per unit of cost" in {
    val worths = Map(
      "dear" -> Upkeep.Worth(revenue = 1000L, bytes = 200L, cost = 20000L),
      "cheap" -> Upkeep.Worth(revenue = 1000L, bytes = 100L, cost = 20000L),
      "lean" -> Upkeep.Worth(revenue = 1000L, bytes = 200L, cost = 10000L),
      "free" -> Upkeep.Worth(revenue = 0L, bytes = 50L, cost = 5000L))
    Upkeep.byWorth(Seq("free", "dear", "lean", "cheap"))(worths) shouldBe Seq("cheap", "lean", "dear", "free")
  }

  it should "keep the order it was given among boxes worth the same, and put no revenue last" in {
    val same = Upkeep.Worth(revenue = 500L, bytes = 100L, cost = 1000L)
    val worths = Map("c" -> same, "a" -> same, "b" -> same,
      "unread" -> Upkeep.Worth.Unknown, "none" -> Upkeep.Worth(0L, 100L, 1000L))
    Upkeep.byWorth(Seq("unread", "c", "none", "a", "b"))(worths) shouldBe Seq("c", "a", "b", "unread", "none")
    Upkeep.byWorth(Seq.empty[String])(worths) shouldBe empty
  }

  // ─── space ────────────────────────────────────────────────────────────────

  private val configuredShare = Upkeep.Share(slots = 5, bytes = 1000L, cost = 10000L)
  private val packageBudget = CandidateBudget(maxBytes = 100000L, maxCost = 1000000L)

  /** What the block has beside this client's package share: where the mempool's transactions go. */
  private val rest = CandidateBudget(maxBytes = 100000L, maxCost = 1000000L)

  "An opportunistic share" should "keep the configured share when the waiting transactions do not fit beside a full package" in {
    Upkeep.opportunistic(configuredShare, rest, 200000L, 2000000L, maxTxs = 20) shouldBe configuredShare
    Upkeep.opportunistic(configuredShare, rest, 100001L, 0L, maxTxs = 20) shouldBe configuredShare
    Upkeep.opportunistic(configuredShare, rest, 0L, 1000001L, maxTxs = 20) shouldBe configuredShare
    // reaching the rest exactly is "at least this", the figure a saturated read gives: no growth
    Upkeep.opportunistic(configuredShare, rest, 100000L, 1000000L, maxTxs = 20) shouldBe configuredShare
  }

  it should "raise the count to the cap, within the configured bytes and cost, when they fit with room to spare" in {
    Upkeep.opportunistic(configuredShare, rest, 0L, 0L, maxTxs = 20) shouldBe
      Upkeep.Share(slots = 20, bytes = 1000L, cost = 10000L)
    Upkeep.opportunistic(configuredShare, rest, 99999L, 999999L, maxTxs = 20) shouldBe
      Upkeep.Share(slots = 20, bytes = 1000L, cost = 10000L)
  }

  it should "never take fewer slots than configured, whatever the cap" in {
    Upkeep.opportunistic(configuredShare, rest, 0L, 0L, maxTxs = 2).slots shouldBe 5
  }

  it should "not grow a configured count of zero, the sign the source is not to be asked" in {
    val off = configuredShare.copy(slots = 0)
    Upkeep.opportunistic(off, rest, 0L, 0L, maxTxs = 20) shouldBe off
  }

  private def waiting(n: Int, size: Option[Int], cost: Option[Long]): NodeTransaction =
    NodeTransaction(id(s"pending$n"), Seq.empty, Seq.empty, Seq.empty, size, cost)

  /** A node whose mempool is `txs`, served a page at a time, counting the pages it is asked for. */
  private def mempoolOf(txs: Seq[NodeTransaction]): (NodeApi, java.util.concurrent.atomic.AtomicInteger) = {
    val api = mock[NodeApi]
    val pages = new java.util.concurrent.atomic.AtomicInteger(0)
    when(api.unconfirmedTransactions(any[Paging])).thenAnswer { inv =>
      val paging = inv.getArgument[Paging](0)
      pages.incrementAndGet()
      Success(txs.slice(paging.offset, paging.offset + paging.limit))
    }
    (api, pages)
  }

  "The mempool's demand" should "add up what the node reports for every waiting transaction" in {
    val (api, pages) = mempoolOf((1 to 150).map(n => waiting(n, Some(100), Some(2000L))))
    Upkeep.demand(api, packageBudget) shouldBe Success((15000L, 300000L))
    pages.get shouldBe 2
  }

  it should "fail the read on a transaction reported without a size or a cost, rather than guess" in {
    Upkeep.weight(waiting(1, Some(300), Some(3000L))) shouldBe ((300L, 3000L))
    an[IllegalStateException] should be thrownBy Upkeep.weight(waiting(1, Some(300), None))
    an[IllegalStateException] should be thrownBy Upkeep.weight(waiting(1, None, Some(3000L)))
    val (api, _) = mempoolOf(Seq(waiting(1, Some(100), Some(1L)), waiting(2, Some(100), None)))
    Upkeep.demand(api, packageBudget).isFailure shouldBe true
  }

  it should "saturate at the budget rather than wrap on absurd figures" in {
    // each figure is below the budget; their sum would wrap a plain Long
    val (api, _) = mempoolOf((1 to 3).map(n => waiting(n, Some(10), Some(Long.MaxValue / 2 - 1L))))
    Upkeep.demand(api, packageBudget) shouldBe Success((30L, packageBudget.maxCost))
  }

  it should "fail the read on a figure of zero, which no real transaction has" in {
    an[IllegalStateException] should be thrownBy Upkeep.weight(waiting(1, Some(0), Some(10L)))
    an[IllegalStateException] should be thrownBy Upkeep.weight(waiting(1, Some(10), Some(0L)))
  }

  it should "stop reading once the demand reaches the budget" in {
    val (api, pages) = mempoolOf((1 to 1000).map(n => waiting(n, Some(1000), Some(1L))))
    val demanded = Upkeep.demand(api, packageBudget).get
    demanded._1 should be >= packageBudget.maxBytes
    pages.get shouldBe 1
  }

  it should "charge a mempool deeper than it reads as the whole budget" in {
    val (api, pages) = mempoolOf((1 to (Upkeep.MaxMempoolPages + 1) * Upkeep.MempoolPage)
      .map(n => waiting(n, Some(1), Some(1L))))
    Upkeep.demand(api, packageBudget) shouldBe Success((packageBudget.maxBytes, packageBudget.maxCost))
    pages.get shouldBe Upkeep.MaxMempoolPages
  }

  it should "fail a read found past its deadline before a page or at its end, rather than hold the build" in {
    val (api, _) = mempoolOf((1 to 250).map(n => waiting(n, Some(10), Some(10L))))
    Upkeep.demand(api, packageBudget, deadlineMs = System.currentTimeMillis() - 1L).isFailure shouldBe true
    val (short, _) = mempoolOf((1 to 5).map(n => waiting(n, Some(10), Some(10L))))
    Upkeep.demand(short, packageBudget, deadlineMs = System.currentTimeMillis() - 1L).isFailure shouldBe true
    Upkeep.demand(api, packageBudget, deadlineMs = System.currentTimeMillis() + 60000L) shouldBe Success((2500L, 2500L))
  }

  "The head by age" should "be the earliest-seen entry that fits, the first on a tie, or none" in {
    val seen = Map("a" -> 5, "b" -> 3, "c" -> 3, "d" -> 1)
    Upkeep.headByAge(Seq("a", "b", "c", "d"))(seen, _ != "d") shouldBe Some("b")
    Upkeep.headByAge(Seq("c", "b", "a"))(seen, _ => true) shouldBe Some("c")
    Upkeep.headByAge(Seq("a", "b"))(seen, _ => false) shouldBe None
  }

  it should "fail when a later page cannot be read, rather than count the pages before it" in {
    val api = mock[NodeApi]
    when(api.unconfirmedTransactions(any[Paging])).thenAnswer { inv =>
      val paging = inv.getArgument[Paging](0)
      if (paging.offset == 0) Success((1 to Upkeep.MempoolPage).map(n => waiting(n, Some(10), Some(10L))))
      else Failure(new RuntimeException("node down"))
    }
    Upkeep.demand(api, packageBudget).isFailure shouldBe true
  }

  "A successor's kind" should "name its job, so a refused block says which one built it" in {
    Upkeep.kind("heartbeat") shouldBe "upkeep:heartbeat"
  }

  // ─── the refusal memory ───────────────────────────────────────────────────

  "The refusal memory" should "hold a box for retryAfterScans passes that still find it, then offer it again" in {
    val memory = new UpkeepSource.Memory(retryAfterScans = 3)
    val a = id("a")
    memory.refuse(Set(a))
    memory.refusedIds shouldBe Set(a)
    memory.passesLeft(a) shouldBe Some(3)

    memory.passed(Set(a)) shouldBe empty
    memory.passesLeft(a) shouldBe Some(2)
    memory.passed(Set(a)) shouldBe empty
    memory.passesLeft(a) shouldBe Some(1)
    memory.passed(Set(a)) shouldBe Set(a)
    memory.refusedIds shouldBe empty
    memory.passesLeft(a) shouldBe None
  }

  it should "forget a box at once when a pass no longer finds it, or a build finds it spent" in {
    val memory = new UpkeepSource.Memory(retryAfterScans = 3)
    val (a, b) = (id("a"), id("b"))
    memory.refuse(Set(a, b))
    memory.passed(Set(b)) shouldBe empty
    memory.refusedIds shouldBe Set(b)
    memory.forget(Set(b))
    memory.refusedIds shouldBe empty
  }

  it should "start a box refused again from the full count" in {
    val memory = new UpkeepSource.Memory(retryAfterScans = 2)
    val a = id("a")
    memory.refuse(Set(a))
    memory.passed(Set(a)) shouldBe empty
    memory.refuse(Set(a))
    memory.passesLeft(a) shouldBe Some(2)
  }

  /** Cannot pay does not pass with time: no number of passes frees the box, only losing it does. */
  it should "hold an exhausted box for as long as passes find it, and report it once" in {
    val memory = new UpkeepSource.Memory(retryAfterScans = 1)
    val (a, b) = (id("a"), id("b"))
    memory.refuse(Set(a))
    memory.exhaust(Set(a, b)) shouldBe Set(a, b)
    memory.exhaust(Set(a)) shouldBe empty
    memory.refusedIds shouldBe empty
    memory.heldIds shouldBe Set(a, b)

    (1 to 3).foreach(_ => memory.passed(Set(a, b)) shouldBe empty)
    memory.exhaustedIds shouldBe Set(a, b)
    memory.passed(Set(a)) shouldBe empty
    memory.exhaustedIds shouldBe Set(a)
    memory.forget(Set(a))
    memory.heldIds shouldBe empty
  }

  // ─── config ───────────────────────────────────────────────────────────────

  /** On only when the operator says so, and then job by job. */
  "The default" should "leave the source off and no job on" in {
    CandidateConfig.Default.sources(CandidateSourceConfig.Upkeep).enabled shouldBe false
    UpkeepConfig.Default.jobs shouldBe empty
    UpkeepRegistry.enabled(UpkeepConfig.Default) shouldBe empty
  }

  "The registry" should "know the heartbeat job, under the name validation checks config against" in {
    UpkeepRegistry.names shouldBe Seq("heartbeat")
    UpkeepRegistry.all.map(_.name) shouldBe UpkeepRegistry.names
    UpkeepRegistry.all.foreach(factory => factory.make(UpkeepConfig.Job()).name shouldBe factory.name)
  }

  it should "turn the heartbeat on from config alone" in {
    val on = UpkeepConfig.Default.copy(jobs = Map("heartbeat" -> UpkeepConfig.Job(enabled = true)))
    UpkeepRegistry.enabled(on).map(_.name) shouldBe Seq("heartbeat")
  }

  it should "refuse a factory whose job answers to another name" in {
    val (_, _, wallet) = support.FakeNodeContext(mock[node.NodeApi], numAddresses = 1)
    val misnamed = JobFactory("one", _ => new FakeJob(wallet, "other"))
    val config = UpkeepConfig.Default.copy(jobs = Map("one" -> UpkeepConfig.Job(enabled = true)))
    an[IllegalArgumentException] should be thrownBy UpkeepRegistry.enabled(config, Seq(misnamed))
  }

  "Job blocks" should "be read generically from jobs.<name>" in {
    val config = UpkeepConfig(Configuration.from(Map(
      "stratum.candidate.sources.upkeep.scanIntervalMs" -> 5000,
      "stratum.candidate.sources.upkeep.retryAfterScans" -> 3,
      "stratum.candidate.sources.upkeep.jobs.heartbeat.enabled" -> true,
      "stratum.candidate.sources.upkeep.jobs.nosuchjob.enabled" -> false)))

    config.scanIntervalMs shouldBe 5000
    config.maxBoxesPerJob shouldBe UpkeepConfig.Default.maxBoxesPerJob
    config.retryAfterScans shouldBe 3
    config.jobs.map { case (name, job) => name -> job.enabled } shouldBe Map("heartbeat" -> true, "nosuchjob" -> false)
    config.jobEnabled("heartbeat") shouldBe true
    config.jobEnabled("nosuchjob") shouldBe false
    config.jobEnabled("never-mentioned") shouldBe false
  }

  it should "pick out of the registry only the jobs config turns on" in {
    val (_, _, wallet) = support.FakeNodeContext(mock[node.NodeApi], numAddresses = 1)
    val on = new FakeJob(wallet, "on")
    val off = new FakeJob(wallet, "off")
    val factories = Seq(JobFactory("off", _ => off), JobFactory("on", _ => on))
    val config = UpkeepConfig.Default.copy(jobs = Map(
      "on" -> UpkeepConfig.Job(enabled = true), "off" -> UpkeepConfig.Job(enabled = false)))

    UpkeepRegistry.enabled(config, factories).map(_.name) shouldBe Seq("on")
    UpkeepRegistry.enabled(config, Seq.empty) shouldBe empty
  }

  "A missing jobs block" should "read as no job on, with every other key at its default" in {
    val config = UpkeepConfig(Configuration.from(Map("stratum.candidate.sources.upkeep.enabled" -> true)))
    config.jobs shouldBe empty
    config.retryAfterScans shouldBe UpkeepConfig.Default.retryAfterScans
    config.mode shouldBe UpkeepConfig.Candidate
    config.observing shouldBe false
  }

  "The mode" should "default to candidate and read observe from config" in {
    UpkeepConfig.Default.mode shouldBe UpkeepConfig.Candidate
    val config = UpkeepConfig(Configuration.from(Map("stratum.candidate.sources.upkeep.mode" -> "observe")))
    config.mode shouldBe UpkeepConfig.Observe
    config.observing shouldBe true
  }

  "The space" should "default to fixed and read opportunistic and its cap from config" in {
    UpkeepConfig.Default.space shouldBe UpkeepConfig.Fixed
    UpkeepConfig(Configuration.empty).opportunistic shouldBe false
    UpkeepConfig(Configuration.empty).opportunisticMaxTxs shouldBe 20
    val config = UpkeepConfig(Configuration.from(Map(
      "stratum.candidate.sources.upkeep.space" -> "opportunistic",
      "stratum.candidate.sources.upkeep.opportunisticMaxTxs" -> 7)))
    config.opportunistic shouldBe true
    config.opportunisticMaxTxs shouldBe 7
  }

  "The order" should "default to rotation, read value from config, and refuse any other" in {
    UpkeepConfig(Configuration.empty).order shouldBe UpkeepConfig.Rotation
    UpkeepConfig(Configuration.empty).byValue shouldBe false
    UpkeepConfig(Configuration.from(Map("stratum.candidate.sources.upkeep.order" -> "value"))).byValue shouldBe true
    validated("""stratum.candidate.sources.upkeep.order = "value"""") shouldBe None
    validated("""stratum.candidate.sources.upkeep.order = "tip"""")
      .getOrElse(fail("an unknown order was accepted")) should include("upkeep.order")
  }

  "The space" should "leave the builder's allowance alone when fixed, and widen it to the cap when opportunistic" in {
    val limits = CandidateSourceConfig.Default.copy(enabled = true, maxTxs = 5)
    UpkeepConfig.Default.allowance(limits) shouldBe limits
    val widened = UpkeepConfig.Default.copy(space = UpkeepConfig.Opportunistic, opportunisticMaxTxs = 12).allowance(limits)
    widened shouldBe limits.copy(maxTxs = 12)
    UpkeepConfig.Default.copy(space = UpkeepConfig.Opportunistic, opportunisticMaxTxs = 2)
      .allowance(limits).maxTxs shouldBe 5
    UpkeepConfig.Default.copy(space = UpkeepConfig.Opportunistic).allowance(limits.copy(maxTxs = 0)) shouldBe
      limits.copy(maxTxs = 0)
  }

  "verifyWithNode" should "default to on and read off from config" in {
    UpkeepConfig.Default.verifyWithNode shouldBe true
    UpkeepConfig(Configuration.empty).verifyWithNode shouldBe true
    UpkeepConfig(Configuration.from(Map("stratum.candidate.sources.upkeep.verifyWithNode" -> false)))
      .verifyWithNode shouldBe false
  }

  "A job's box list" should "be read from jobs.<name>.boxIds for every job, and leave its flag alone" in {
    val ids = Seq("ab" * 32, "cd" * 32)
    val config = UpkeepConfig(Configuration.from(Map(
      "stratum.candidate.sources.upkeep.jobs.heartbeat.boxIds" -> ids,
      "stratum.candidate.sources.upkeep.jobs.nosuchjob.boxIds" -> ids.take(1))))
    config.jobs("heartbeat").boxIds shouldBe ids
    config.jobs("nosuchjob").boxIds shouldBe ids.take(1)
    config.jobEnabled("heartbeat") shouldBe false
  }

  /** A job with a key of its own, which only its factory knows how to read. */
  private final class Keyed(val limit: Int) extends UpkeepJob {
    override val name: String = "keyed"
    override def discover(ctx: org.ergoplatform.appkit.BlockchainContext, api: node.NodeApi, height: Int): Seq[String] = Seq.empty
    override def due(box: work.lithos.mutations.InputUTXO, height: Int): Boolean = false
    override def build(box: work.lithos.mutations.InputUTXO, bc: BuildContext): Option[UpkeepJob.Built] = None
  }

  private val keyedFactory = JobFactory("keyed",
    job => new Keyed(job.block.getOptional("limit")(ConfigLoader.intLoader).getOrElse(7)),
    block => Try(block.getOptional("limit")(ConfigLoader.intLoader)).toOption.flatten match {
      case Some(limit) if limit <= 0 => Seq("limit" -> s"$limit is not positive")
      case _ => Seq.empty
    })

  "A factory" should "read its own key from its job's block, and the framework the generic ones" in {
    def made(entries: (String, Any)*): Keyed = {
      val config = UpkeepConfig(Configuration.from(
        entries.map { case (k, v) => s"stratum.candidate.sources.upkeep.jobs.keyed.$k" -> v }.toMap))
      UpkeepRegistry.enabled(config, Seq(keyedFactory)) match {
        case Seq(job: Keyed) => job
        case other => fail(s"expected the keyed job, made $other")
      }
    }
    made("enabled" -> true, "limit" -> 3).limit shouldBe 3
    made("enabled" -> true).limit shouldBe 7
    UpkeepRegistry.enabled(UpkeepConfig(Configuration.from(Map(
      "stratum.candidate.sources.upkeep.jobs.keyed.limit" -> 3))), Seq(keyedFactory)) shouldBe empty
  }

  it should "report a problem with its own key under the job's path" in {
    keyedFactory.check(Configuration.from(Map("limit" -> 0))) shouldBe Seq("limit" -> "0 is not positive")
    keyedFactory.check(Configuration.from(Map("limit" -> 3))) shouldBe empty
  }

  // ─── validation ───────────────────────────────────────────────────────────

  private def shipped: Configuration =
    Configuration(ConfigFactory.parseResources("application.conf").resolve())

  private def validated(hocon: String): Option[String] = {
    val config = Configuration(ConfigFactory.parseString(hocon).withFallback(shipped.underlying).resolve())
    Try(Configs.validateAll(config)) match {
      case Failure(ex: ConfigValidationException) => Some(ex.getMessage)
      case Failure(ex) => throw ex
      case Success(_) => None
    }
  }

  "Validation" should "accept the shipped upkeep block" in {
    validated("") shouldBe None
  }

  it should "refuse an enabled job the registry does not know, and not a disabled one" in {
    validated("stratum.candidate.sources.upkeep.jobs.nosuchjob.enabled = true")
      .getOrElse(fail("an unknown enabled job was accepted")) should include("jobs.nosuchjob.enabled")
    validated("stratum.candidate.sources.upkeep.jobs.nosuchjob.enabled = false") shouldBe None
  }

  /** The list is generic, so a job the client does not run yet is held to it too. */
  it should "check boxIds for every job that lists them" in {
    val good = "ab" * 32
    Seq("heartbeat", "nosuchjob").foreach { job =>
      val key = s"stratum.candidate.sources.upkeep.jobs.$job.boxIds"
      validated(s"""$key = ["$good"]""") shouldBe None
      validated(s"""$key = ["$good", "${good.toUpperCase}"]""")
        .getOrElse(fail(s"$job: a repeated id was accepted")) should include("more than once")
      validated(s"""$key = ["abc"]""")
        .getOrElse(fail(s"$job: a short id was accepted")) should include(key)
      validated(s"""$key = "$good"""")
        .getOrElse(fail(s"$job: a bare string was accepted")) should include("list of box ids")
    }
  }

  it should "hold boxIds to the configured maxBoxesPerJob" in {
    val ids = (1 to 3).map(i => f"$i%064x").map(id => s""""$id"""").mkString("[", ", ", "]")
    validated(s"""stratum.candidate.sources.upkeep.maxBoxesPerJob = 3
                 |stratum.candidate.sources.upkeep.jobs.heartbeat.boxIds = $ids""".stripMargin) shouldBe None
    validated(s"""stratum.candidate.sources.upkeep.maxBoxesPerJob = 2
                 |stratum.candidate.sources.upkeep.jobs.heartbeat.boxIds = $ids""".stripMargin)
      .getOrElse(fail("three ids were accepted for a cap of two")) should include("at most 2")
  }

  it should "cap a configured box list at MaxConfiguredBoxes, each being a read on every scan" in {
    val many = (1 to UpkeepConfig.MaxConfiguredBoxes + 1).map(i => f"$i%064x").map(id => s""""$id"""")
      .mkString("[", ", ", "]")
    validated(s"""stratum.candidate.sources.upkeep.maxBoxesPerJob = 4096
                 |stratum.candidate.sources.upkeep.jobs.heartbeat.boxIds = $many""".stripMargin)
      .getOrElse(fail("a list past the cap was accepted")) should include(s"at most ${UpkeepConfig.MaxConfiguredBoxes}")
  }

  it should "run the heartbeat's own check of minTip" in {
    validated("stratum.candidate.sources.upkeep.jobs.heartbeat.minTip = 1000") shouldBe None
    validated("stratum.candidate.sources.upkeep.jobs.heartbeat.minTip = -1")
      .getOrElse(fail("a negative minTip was accepted")) should include("minTip")
  }

  it should "accept the two modes and refuse any other" in {
    validated("""stratum.candidate.sources.upkeep.mode = "observe"""") shouldBe None
    validated("""stratum.candidate.sources.upkeep.mode = "candidate"""") shouldBe None
    validated("""stratum.candidate.sources.upkeep.mode = "broadcast"""")
      .getOrElse(fail("an unknown mode was accepted")) should include("upkeep.mode")
  }

  it should "accept the two spaces and refuse any other, and hold the cap to its range" in {
    validated("""stratum.candidate.sources.upkeep.space = "opportunistic"""") shouldBe None
    validated("""stratum.candidate.sources.upkeep.space = "fixed"""") shouldBe None
    validated("""stratum.candidate.sources.upkeep.space = "greedy"""")
      .getOrElse(fail("an unknown space was accepted")) should include("upkeep.space")
    validated("stratum.candidate.sources.upkeep.opportunisticMaxTxs = 0")
      .getOrElse(fail("a cap of zero was accepted")) should include("opportunisticMaxTxs")
  }

  it should "refuse a verifyWithNode that is not true or false" in {
    validated("stratum.candidate.sources.upkeep.verifyWithNode = false") shouldBe None
    validated("""stratum.candidate.sources.upkeep.verifyWithNode = "sometimes"""")
      .getOrElse(fail("a non-boolean verifyWithNode was accepted")) should include("upkeep.verifyWithNode")
  }

  it should "refuse a job entry that is not a block" in {
    validated("stratum.candidate.sources.upkeep.jobs.heartbeat = true")
      .getOrElse(fail("a bare flag was accepted")) should include("must be a configuration block")
  }
}
