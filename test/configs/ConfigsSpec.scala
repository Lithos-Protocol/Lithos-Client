package configs

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.Configuration

/** Verifies that startup validation accepts shipped configuration and aggregates invalid keys. */
class ConfigsSpec extends AnyFlatSpec with Matchers {

  private def shipped: Configuration =
    Configuration(ConfigFactory.parseResources("application.conf").resolve())

  "Configs.validateAll" should "accept the shipped application.conf" in {
    try Configs.validateAll(shipped)
    catch {
      case t: ConfigValidationException => fail(t.getMessage)
    }
  }

  it should "reject negative candidate revenue thresholds" in {
    val configured = Configuration(ConfigFactory.parseString("stratum.candidate.minCandidateChangeRevenue = -1")
      .withFallback(shipped.underlying).resolve())
    val thrown = the[ConfigValidationException] thrownBy Configs.validateAll(configured)
    thrown.getMessage should include("stratum.candidate.minCandidateChangeRevenue")
  }

  it should "bound the collateral clearance age to 100 through 14400 blocks" in {
    def withAge(age: Int): Configuration = Configuration(
      ConfigFactory.parseString(s"stratum.candidate.clearanceAge = $age").withFallback(shipped.underlying).resolve())
    Seq(100, 7200, 14400).foreach { age =>
      withClue(s"clearanceAge $age: ")(noException should be thrownBy Configs.validateAll(withAge(age)))
    }
    Seq(0, 99, 14401).foreach { age =>
      val thrown = the[ConfigValidationException] thrownBy Configs.validateAll(withAge(age))
      thrown.getMessage should include("stratum.candidate.clearanceAge")
    }
  }

  it should "accept only the listed reduction multipliers" in {
    def withMultiplier(m: Int): Configuration = Configuration(
      ConfigFactory.parseString(s"stratum.reductionMultiplier = $m").withFallback(shipped.underlying).resolve())
    StratumConfig.ReductionMultipliers.foreach { m =>
      withClue(s"multiplier $m: ")(noException should be thrownBy Configs.validateAll(withMultiplier(m)))
    }
    Seq(0, 1, 50, 100000).foreach { m =>
      val thrown = the[ConfigValidationException] thrownBy Configs.validateAll(withMultiplier(m))
      thrown.getMessage should include("stratum.reductionMultiplier")
    }
  }

  it should "never allow a reduction multiplier above the super-share coefficient" in {
    // Above it the advertised threshold is harder than a super share, and the miner never submits
    // the shares a NISP is built from.
    all(StratumConfig.ReductionMultipliers) should be <= lfsm.LFSMHelpers.NISP_COEFFICIENT
    StratumConfig.ReductionMultipliers should contain(StratumConfig.DefaultReductionMultiplier)
  }

  it should "reject statistics intervals that would always label fresh observations stale" in {
    val configured = Configuration(ConfigFactory.parseString(
      "stats.refreshIntervalMs = 15000\nstats.staleAfterMs = 1000")
      .withFallback(shipped.underlying).resolve())
    val thrown = the[ConfigValidationException] thrownBy Configs.validateAll(configured)
    thrown.getMessage should include("stats.staleAfterMs")
    an[ConfigValidationException] should be thrownBy StatsConfig(configured)
    StatsConfig(shipped) shouldBe StatsConfig.Default
  }

  it should "reject DEX collection settings that remove bounds or always produce stale results" in {
    val configured = Configuration(ConfigFactory.parseString(
      """stats.dex.refreshIntervalMs = 30000
        |stats.dex.staleAfterMs = 10000
        |stats.dex.readTimeoutMs = 0
        |stats.dex.refreshBudgetMs = 0
        |stats.dex.historyPages = 0
        |stats.dex.timestampLookups = 251""".stripMargin)
      .withFallback(shipped.underlying).resolve())
    val thrown = the[ConfigValidationException] thrownBy Configs.validateAll(configured)
    Seq("staleAfterMs", "readTimeoutMs", "refreshBudgetMs", "historyPages", "timestampLookups")
      .foreach(key => thrown.getMessage should include(s"stats.dex.$key"))
    an[ConfigValidationException] should be thrownBy StatsConfig(configured)
  }

  it should "validate stats storage bounds while allowing pruning to be disabled" in {
    val configured = Configuration(ConfigFactory.parseString(
      """stats.storage.backend = "unknown"
        |stats.storage.path = ""
        |stats.storage.flushIntervalMs = 0
        |stats.storage.sampleIntervalMinutes = 0
        |stats.storage.pruning.retentionDays = 0
        |stats.storage.pruning.intervalMs = 0
        |stats.storage.pruning.batchSize = 1441""".stripMargin)
      .withFallback(shipped.underlying).resolve())
    val thrown = the[ConfigValidationException] thrownBy Configs.validateAll(configured)
    Seq("backend", "path", "flushIntervalMs", "sampleIntervalMinutes", "pruning.retentionDays",
      "pruning.intervalMs", "pruning.batchSize").foreach(key => thrown.getMessage should include(s"stats.storage.$key"))
    an[ConfigValidationException] should be thrownBy StatsConfig(configured)
    val noPruning = Configuration(ConfigFactory.parseString("stats.storage.pruning.enabled = false")
      .withFallback(shipped.underlying).resolve())
    noException should be thrownBy Configs.validateAll(noPruning)
    StatsConfig(noPruning).storage.pruningEnabled shouldBe false
  }

  /** A value the LithosDex batcher's constructor cannot read stops that actor, and the stratum then waits it out every block. */
  it should "reject LithosDex batching values before the batcher is built" in {
    val configured = Configuration(ConfigFactory.parseString(
      """batching.lithosdex.scanIntervalMs = 0
        |batching.lithosdex.autoFlush = "sometimes"
        |batching.lithosdex.skippedOrderTtlMs = 0
        |batching.lithosdex.maxSkippedOrders = -1
        |batching.lithosdex.maxAncestorTxs = -1
        |batching.lithosdex.broadcastMempoolOrders = "sometimes"
        |batching.lithosdex.maxMempoolOrders = -1
        |batching.lithosdex.maxMempoolOrdersPerTx = -1
        |batching.lithosdex.maxUnbuildablePerRun = -1
        |batching.lithosdex.maxUnbuildablePerTx = -1
        |batching.lithosdex.discoverPools = "sometimes"
        |batching.lithosdex.maxTrackedPools = 0
        |batching.lithosdex.strategy = "maxFee"
        |batching.lithosdex.searchBudgetMs = -1""".stripMargin)
      .withFallback(shipped.underlying).resolve())
    val thrown = the[ConfigValidationException] thrownBy Configs.validateAll(configured)
    Seq("scanIntervalMs", "autoFlush", "skippedOrderTtlMs", "maxSkippedOrders", "maxAncestorTxs",
      "broadcastMempoolOrders", "maxMempoolOrders", "maxMempoolOrdersPerTx", "maxUnbuildablePerRun", "maxUnbuildablePerTx",
      "discoverPools", "maxTrackedPools", "strategy", "searchBudgetMs")
      .foreach(key => thrown.getMessage should include(s"batching.lithosdex.$key"))
  }

  it should "name the known strategies when a batching strategy is misspelled" in {
    val configured = Configuration(ConfigFactory.parseString("batching.ergodex.strategy = \"maxfees\"")
      .withFallback(shipped.underlying).resolve())
    val thrown = the[ConfigValidationException] thrownBy Configs.validateAll(configured)
    thrown.getMessage should include("batching.ergodex.strategy")
    thrown.getMessage should include(transactions.batching.RunStrategy.Default.name)
  }

  it should "reject ErgoDEX skip-list values out of range" in {
    val configured = Configuration(ConfigFactory.parseString(
      """batching.ergodex.skippedOrderTtlMs = 0
        |batching.ergodex.maxSkippedOrders = -1
        |batching.ergodex.maxAncestorTxs = 101
        |batching.ergodex.broadcastMempoolOrders = "sometimes"
        |batching.ergodex.maxMempoolOrdersPerTx = 0
        |batching.ergodex.maxUnbuildablePerRun = 0
        |batching.ergodex.maxUnbuildablePerTx = 0
        |batching.ergodex.searchBudgetMs = 10001""".stripMargin)
      .withFallback(shipped.underlying).resolve())
    val thrown = the[ConfigValidationException] thrownBy Configs.validateAll(configured)
    Seq("skippedOrderTtlMs", "maxSkippedOrders", "maxAncestorTxs", "broadcastMempoolOrders",
      "maxMempoolOrdersPerTx", "maxUnbuildablePerRun", "maxUnbuildablePerTx", "searchBudgetMs")
      .foreach(key => thrown.getMessage should include(s"batching.ergodex.$key"))
  }

  it should "reject consolidation timeouts out of range" in {
    val configured = Configuration(ConfigFactory.parseString(
      """wallet.consolidation.attempt-timeout-ms = 0
        |wallet.consolidation.request-timeout-ms = 0
        |wallet.consolidation.num-transactions = 0""".stripMargin)
      .withFallback(shipped.underlying).resolve())
    val thrown = the[ConfigValidationException] thrownBy Configs.validateAll(configured)
    Seq("attempt-timeout-ms", "request-timeout-ms", "num-transactions")
      .foreach(key => thrown.getMessage should include(s"wallet.consolidation.$key"))
  }

  /** The attempt deadline covers the wallet walk, so one no longer than the walk's own limit can expire every pass. */
  it should "reject a consolidation attempt timeout no longer than the wallet walk" in {
    val configured = Configuration(ConfigFactory.parseString(
      """wallet.inventory-walk-timeout-ms = 600000
        |wallet.consolidation.attempt-timeout-ms = 600000""".stripMargin)
      .withFallback(shipped.underlying).resolve())
    val thrown = the[ConfigValidationException] thrownBy Configs.validateAll(configured)
    thrown.getMessage should include("wallet.consolidation.attempt-timeout-ms")
    thrown.getMessage should include("wallet.inventory-walk-timeout-ms")
  }

  it should "allow a maintenance checkpoint after every committed catch-up block" in {
    val configured = Configuration(ConfigFactory.parseString(
      "sync.quarantine.checkpointIntervalBlocks = 1")
      .withFallback(shipped.underlying).resolve())
    noException should be thrownBy Configs.validateAll(configured)
  }

  it should "reject a quarantine repair timeout below the supported floor" in {
    val configured = Configuration(ConfigFactory.parseString(
      "sync.quarantine.repairTimeout = 500 milliseconds")
      .withFallback(shipped.underlying).resolve())
    val thrown = the[ConfigValidationException] thrownBy Configs.validateAll(configured)
    thrown.getMessage should include("sync.quarantine.repairTimeout")
  }

  it should "name every required key missing from an empty configuration" in {
    // Required keys are those read without an application default.
    val thrown = the[ConfigValidationException] thrownBy
      Configs.validateAll(Configuration(ConfigFactory.empty()))
    val message = thrown.getMessage

    List(
      "node.url",
      "node.storagePath",
      "node.pass",
      "node.networkType",
      // NodeConfig falls back to ONE address, while emission.maxLenderKeys defaults to 32.
      "node.numAddresses",
      "stratum.diff",
      "stratum.stratumPort",
      "stratum.extraNonce1Size",
      "stratum.connectionTimeout",
      "stratum.blockRefreshInterval",
      "stratum.reduceShareMessages",
      "stratum.diffRefreshInterval",
      "sync.startHeight",
      // Every authenticated controller reads this at construction.
      "lithos.apiKeyHash",
      // TasksConfig reads all nine of these with `get`.
      "lithos-tasks.stratum-server.enabled",
      "lithos-tasks.rollup-sync-task.startup",
      "lithos-tasks.dictionary-sync-task.interval",
      // `Contexts` resolves these during Guice provisioning, past the readable report.
      "lithos-contexts.dex-dispatcher",
      "lithos-contexts.tx-dispatcher"
    ).foreach(key => withClue(s"expected $key in report:\n$message\n")(message should include(key)))
  }

  it should "name every dispatcher Contexts looks up" in {
    // Keep runtime dispatcher lookups and validation names aligned.
    Contexts.Names should contain theSameElementsAs
      Seq(Contexts.Stratum, Contexts.Polling, Contexts.Sync, Contexts.Tx, Contexts.Dex,
        Contexts.Database, Contexts.SnapshotIo, Contexts.CandidateIo, Contexts.MiningControlIo, Contexts.Genesis, Contexts.EngineIo, Contexts.MempoolIo, Contexts.WalletIo, Contexts.WalletMaintenance, Contexts.EngineCandidate, Contexts.CriticalWallet, Contexts.CriticalTx, Contexts.BatchingIo,
        Contexts.LithosDexBatchingIo, Contexts.Stats, Contexts.StatsRead, Contexts.StatsStore, Contexts.MiningStats)
  }

  it should "reject an apiKeyHash that is the key rather than its hash" in {
    // A plaintext key must be reported even when the configuration has other errors.
    val thrown = the[ConfigValidationException] thrownBy
      Configs.validateAll(Configuration(ConfigFactory.parseString("""lithos.apiKeyHash = "hello"""")))
    thrown.getMessage should include("lithos.apiKeyHash")
  }

  it should "report every bad value together, naming each offending key" in {
    val broken = Configuration(ConfigFactory.parseString("""
      node.url = "ftp://not-a-node"
      node.networkType = "MAINNETT"
      node.numAddresses = 16
      stratum.stratumPort = 99999
      stratum.diff = "4.0Z"
      emission.maxLenderKeys = 64
    """).resolve())

    val thrown = the[ConfigValidationException] thrownBy Configs.validateAll(broken)
    val message = thrown.getMessage

    List(
      "node.url",
      "node.networkType",
      "stratum.stratumPort",
      "stratum.diff"
    ).foreach(key => withClue(s"expected $key in report:\n$message\n")(message should include(key)))

    // The cross-config rule: lender keys past the prover's last address strand funds.
    message should include("emission.maxLenderKeys")
  }

  "Configs.fail" should "throw a single-problem exception" in {
    val thrown = the[ConfigValidationException] thrownBy Configs.fail("node.pass", "rejected")
    thrown.getMessage should include("node.pass")
  }

  /** Distinguishes required task construction from tolerant status-message lookup. */
  "TasksConfig" should "throw on a missing task block, and read the flag tolerantly" in {
    val empty = Configuration(ConfigFactory.empty())

    an[Exception] should be thrownBy new TasksConfig(empty)
    TasksConfig.isEnabled(empty, TasksConfig.DictionarySync) shouldBe false

    val on = Configuration(ConfigFactory.parseString(
      s"""${TasksConfig.key(TasksConfig.DictionarySync, "enabled")} = true"""))
    TasksConfig.isEnabled(on, TasksConfig.DictionarySync) shouldBe true
  }

  it should "name every task the shipped conf configures" in {
    // Keep task construction and validation names aligned.
    TasksConfig.Names should contain theSameElementsAs
      Seq(TasksConfig.StratumServer, TasksConfig.RollupSync, TasksConfig.DictionarySync)
    noException should be thrownBy new TasksConfig(shipped)
  }
}
