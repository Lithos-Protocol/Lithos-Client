package transactions.upkeep

import com.typesafe.config.ConfigFactory
import configs.{CandidateConfig, CandidateSourceConfig, ConfigValidationException, Configs, UpkeepConfig}
import org.ergoplatform.appkit.{BlockchainParameters, Parameters}
import org.ergoplatform.sdk.ErgoId
import org.ergoplatform.wallet.boxes.ErgoBoxAssetExtractor
import org.ergoplatform.wallet.interpreter.ErgoInterpreter
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
