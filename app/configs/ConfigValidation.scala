package configs

import lfsm.{CollateralParams, LFSMHelpers, RollupProtocol}
import org.ergoplatform.appkit.Parameters
import play.api.{ConfigLoader, Configuration}

import java.net.URI
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success, Try}

/** One wrong value: where it lives, what is wrong with it, and what to do about it. */
final case class ConfigProblem(key: String, problem: String)

object ConfigProblem {
  def render(problems: Seq[ConfigProblem]): String = {
    val body = problems.map(p => s"  ${p.key}\n      ${p.problem}").mkString("\n\n")
    val plural = if (problems.size == 1) "problem" else "problems"
    s"""|
        |The client found ${problems.size} configuration $plural and cannot start safely:
        |
        |$body
        |
        |Fix these in conf/application.conf and restart.""".stripMargin
  }
}

/** Thrown once at startup carrying every problem found, so one edit session fixes all of them. */
final class ConfigValidationException(problems: Seq[ConfigProblem])
  extends RuntimeException(ConfigProblem.render(problems))

/** Validates all runtime configuration before dependency injection constructs its consumers. */
object Configs {

  /** Throws immediately for a single fatal problem found outside the batch validation pass. */
  def fail(key: String, problem: String): Nothing =
    throw new ConfigValidationException(Seq(ConfigProblem(key, problem)))

  def validateAll(config: Configuration): Unit = {
    val v = new ConfigValidator(config)
    v.bool("stats.enabled")
    val statsRefresh = v.range("stats.refreshIntervalMs", v.int("stats.refreshIntervalMs"),
      100, 60000, "milliseconds").getOrElse(StatsConfig.Default.refreshIntervalMs)
    val statsStale = v.range("stats.staleAfterMs", v.int("stats.staleAfterMs"),
      101, 3600000, "milliseconds").getOrElse(StatsConfig.Default.staleAfterMs)
    if (statsStale <= statsRefresh)
      v.problem("stats.staleAfterMs", "must exceed stats.refreshIntervalMs")
    v.bool("stats.dex.enabled")
    val dexRefresh = v.range("stats.dex.refreshIntervalMs", v.int("stats.dex.refreshIntervalMs"),
      1000, 3600000, "milliseconds").getOrElse(StatsConfig.Default.dex.refreshIntervalMs)
    val dexStale = v.range("stats.dex.staleAfterMs", v.int("stats.dex.staleAfterMs"),
      1001, 86400000, "milliseconds").getOrElse(StatsConfig.Default.dex.staleAfterMs)
    if (dexStale <= dexRefresh) v.problem("stats.dex.staleAfterMs", "must exceed stats.dex.refreshIntervalMs")
    v.range("stats.dex.readTimeoutMs", v.int("stats.dex.readTimeoutMs"), 100, 30000, "milliseconds")
    v.range("stats.dex.refreshBudgetMs", v.int("stats.dex.refreshBudgetMs"), 100, 120000, "milliseconds")
    v.range("stats.dex.historyPages", v.int("stats.dex.historyPages"), 1, 50, "pages of 200 pool boxes")
    v.range("stats.dex.timestampLookups", v.int("stats.dex.timestampLookups"), 0, 250, "header timestamps")
    StatsStorageConfig.validate(v)
    MiningStatsConfig.validate(v)
    // ---- wallet ----
    // Absent keys fall back to WalletConfig.Default, so these bound what is supplied rather than
    // requiring it. The upper bounds are what the engine can actually honour, not taste.
    v.range("wallet.max-inputs", v.int("wallet.max-inputs"), 1, 4096, "inputs per selection")
    v.range("wallet.max-descriptors", v.int("wallet.max-descriptors"), 1, 1000000, "cached descriptors")
    v.longRange("wallet.max-descriptor-bytes", v.long("wallet.max-descriptor-bytes"),
      1024L, 1024L * 1024L * 1024L, "descriptor cache bytes")
    v.range("wallet.max-input-bytes", v.int("wallet.max-input-bytes"), 512, 1024 * 1024, "bytes per box")
    v.range("wallet.page-size", v.int("wallet.page-size"), 1, 10000, "boxes per node read")
    v.longRange("wallet.inventory-walk-timeout-ms", v.long("wallet.inventory-walk-timeout-ms"),
      1000L, 3600000L, "milliseconds")
    v.longRange("wallet.reservation-timeout-ms", v.long("wallet.reservation-timeout-ms"),
      1000L, 600000L, "milliseconds")
    v.longRange("wallet.node-call-timeout-ms", v.long("wallet.node-call-timeout-ms"),
      1000L, 600000L, "milliseconds")
    v.longRange("wallet.node-read-timeout-ms", v.long("wallet.node-read-timeout-ms"),
      1000L, 600000L, "milliseconds")
    v.range("wallet.node-max-response-bytes", v.int("wallet.node-max-response-bytes"),
      65536, 268435456, "bytes in one node response")
    val walletInputs = v.range("wallet.max-wallet-inputs", v.int("wallet.max-wallet-inputs"),
      1, 100000, "wallet inputs the engine may own at once")
    val optionalInputs = v.range("wallet.max-optional-inputs", v.int("wallet.max-optional-inputs"),
      1, 100000, "wallet inputs optional work may own at once")
    // Optional work must leave the engine room for a critical one. Above the engine ceiling the
    // reserve is negative and a fraud proof can be starved by a saturated consolidation queue.
    for (ceiling <- walletInputs; optional <- optionalInputs if optional > ceiling)
      v.problem("wallet.max-optional-inputs",
        s"$optional exceeds wallet.max-wallet-inputs ($ceiling), which leaves nothing for a NISP " +
          "submission or fraud proof to fund itself with")

    v.bool("wallet.consolidation.enabled")
    v.range("wallet.consolidation.target-utxos", v.int("wallet.consolidation.target-utxos"),
      1, Int.MaxValue, "desired total wallet UTXO count")
    v.longRange("wallet.consolidation.interval-ms", v.long("wallet.consolidation.interval-ms"),
      1000L, 86400000L, "milliseconds")
    v.range("wallet.consolidation.min-inputs", v.int("wallet.consolidation.min-inputs"),
      2, 4096, "inputs worth one consolidation")
    v.range("wallet.consolidation.num-transactions", v.int("wallet.consolidation.num-transactions"),
      1, 1000, "consolidations per pass")
    val attemptTimeout = v.longRange("wallet.consolidation.attempt-timeout-ms",
      v.long("wallet.consolidation.attempt-timeout-ms"), 10000L, 86400000L, "milliseconds")
    v.longRange("wallet.consolidation.request-timeout-ms", v.long("wallet.consolidation.request-timeout-ms"),
      1000L, 600000L, "milliseconds")
    // A walk that runs to its own limit would leave the pass nothing to sign and send in
    for (attempt <- attemptTimeout; walk <- v.long("wallet.inventory-walk-timeout-ms") if attempt <= walk)
      v.problem("wallet.consolidation.attempt-timeout-ms",
        s"$attempt is not longer than wallet.inventory-walk-timeout-ms ($walk), so a pass whose wallet walk " +
          "takes that long expires before it can send")

    // ---- node ----
    v.url("node.url", v.string("node.url"))
    v.requireExisting("node.key", v.string("node.key"))
    // Empty shipped values are placeholders validated by NodeConfig after user configuration.
    v.requireExisting("node.storagePath", v.string("node.storagePath"))
    v.requireExisting("node.pass", v.string("node.pass"))
    v.requireExisting("node.networkType", v.string("node.networkType")).foreach { raw =>
      if (!Set("MAINNET", "TESTNET").contains(raw.trim.toUpperCase))
        v.problem("node.networkType", s""""$raw" is not a network type - use MAINNET or TESTNET""")
    }
    v.url("node.explorerURL", v.string("node.explorerURL"), allowDefaultSentinel = true)
    // Require an explicit address count so lender keys cannot exceed available wallet secrets.
    val numAddresses =
      v.range("node.numAddresses", v.intReq("node.numAddresses"), 1, 1000,
        "how many EIP-3 addresses the prover holds keys for")

    // ---- stratum ----
    v.requireExisting("stratum.diff", v.string("stratum.diff")).foreach { d =>
      LFSMHelpers.parseDiffValueForStratum(d) match {
        case Failure(_) =>
          v.problem("stratum.diff",
            s""""$d" is not a difficulty value. Format it as <number><K|M|G|T|P>, e.g. "4.0G" or "1.52M"""")
        case Success(tau) if tau.signum() <= 0 =>
          v.problem("stratum.diff", s""""$d" resolves to zero or negative difficulty""")
        case Success(_) => ()
      }
    }
    // Require keys that StratumConfig reads without defaults.
    v.port("stratum.stratumPort", v.intReq("stratum.stratumPort"))
    v.range("stratum.extraNonce1Size", v.intReq("stratum.extraNonce1Size"), 1, 8,
      "hex bytes of extraNonce1; 2 is recommended, higher invites duplicate shares")
    v.range("stratum.connectionTimeout", v.intReq("stratum.connectionTimeout"), 1000, 3600000, "ms")
    v.range("stratum.blockRefreshInterval", v.intReq("stratum.blockRefreshInterval"), 100, 600000, "ms")
    v.boolReq("stratum.reduceShareMessages")
    v.int("stratum.reductionMultiplier").foreach { m =>
      if (!StratumConfig.ReductionMultipliers.contains(m))
        v.problem("stratum.reductionMultiplier",
          s"$m is not one of ${StratumConfig.ReductionMultipliers.mkString(", ")}")
    }
    v.range("stratum.diffRefreshInterval", v.intReq("stratum.diffRefreshInterval"), 1000, 3600000, "ms")
    v.range("stratum.rotateExtraNonceInterval", v.int("stratum.rotateExtraNonceInterval"), 0, 3600000,
      "ms without work before extraNonce rotation; 0 disables it")
    v.bool("stratum.forceConfigDiff")

    // ---- stratum.candidate ----
    v.range("stratum.candidate.collateralPoolSize", v.int("stratum.candidate.collateralPoolSize"), 1, 100,
      "collateral boxes pre-loaded; the active set never holds more than 100")
    v.range("stratum.candidate.collateralRefreshInterval", v.int("stratum.candidate.collateralRefreshInterval"), 100, 3600000, "ms")
    v.doubleRange("stratum.candidate.blockShare", v.double("stratum.candidate.blockShare"), 0.0, 1.0,
      "fraction of the block's byte and cost limits this client's package may claim")
    Seq(configs.CandidateSourceConfig.Rollups, configs.CandidateSourceConfig.Emissions,
      configs.CandidateSourceConfig.Rent, configs.CandidateSourceConfig.ErgoDex,
      configs.CandidateSourceConfig.LithosDex).foreach { source =>
      v.bool(s"stratum.candidate.sources.$source.enabled")
      v.range(s"stratum.candidate.sources.$source.maxTxs",
        v.int(s"stratum.candidate.sources.$source.maxTxs"), 0, 100, "transactions inserted per block")
      v.longRange(s"stratum.candidate.sources.$source.maxBytes",
        v.long(s"stratum.candidate.sources.$source.maxBytes"), 0L, 8388608L, "serialized bytes per block")
      v.longRange(s"stratum.candidate.sources.$source.maxCost",
        v.long(s"stratum.candidate.sources.$source.maxCost"), 0L, 100000000L, "execution cost per block")
    }
    v.range("stratum.candidate.genesisWaitMs", v.int("stratum.candidate.genesisWaitMs"), 0, 60000, "ms to wait for the genesis transaction before falling back to a solo candidate")
    v.range("stratum.candidate.mempoolRefreshMs", v.int("stratum.candidate.mempoolRefreshMs"), 0, 3600000, "ms between candidate refreshes within a block; 0 mines the block's initial transaction set")
    v.range("stratum.candidate.blockTxTimeout", v.int("stratum.candidate.blockTxTimeout"), 0, 600000, "ms")
    v.range("stratum.candidate.sources.rent.startHeight",
      v.int("stratum.candidate.sources.rent.startHeight"), 0, 100000000,
      "inclusion height the storage-rent walk begins at")
    v.range("stratum.candidate.sources.rent.scanIntervalMs",
      v.int("stratum.candidate.sources.rent.scanIntervalMs"), 1000, 3600000, "ms")
    v.range("stratum.candidate.sources.rent.blocksPerScan",
      v.int("stratum.candidate.sources.rent.blocksPerScan"), 1, 10000, "blocks read per scan pass")
    v.bool("stratum.candidate.useTruePropCollection")
    v.bool("stratum.candidate.logTimings")
    v.bool("stratum.candidate.waitForBlockPackage")
    v.bool("stratum.candidate.logBudgets")
    v.longRange("stratum.candidate.minCandidateChangeRevenue",
      v.long("stratum.candidate.minCandidateChangeRevenue"), 0L, Long.MaxValue, "additional package revenue in nanoERG")

    // ---- batching.ergodex ----
    v.bool(s"batching.ergodex.enabled")
    v.longRange("batching.ergodex.scanIntervalMs", v.long("batching.ergodex.scanIntervalMs"),
      5000L, 3600000L, "ms between order scans; each pages the node's indexer")
    v.range("batching.ergodex.maxTrackedOrders", v.int("batching.ergodex.maxTrackedOrders"), 1, 4096,
      "order ids held between scans")
    v.range("batching.ergodex.maxTrackedPools", v.int("batching.ergodex.maxTrackedPools"), 1, 1024,
      "pools held between scans")
    v.range("batching.ergodex.maxOrdersPerBlock", v.int("batching.ergodex.maxOrdersPerBlock"), 0, 100,
      "orders executed per block or broadcast pass")
    v.longRange("batching.ergodex.minRevenueNanoErg", v.long("batching.ergodex.minRevenueNanoErg"),
      0L, 1000000000000L, "nanoERG")
    v.longRange("batching.ergodex.broadcastMinRevenueNanoErg",
      v.long("batching.ergodex.broadcastMinRevenueNanoErg"), 0L, 1000000000000L, "nanoERG")
    v.longRange("batching.ergodex.broadcastMinerFeeCeiling",
      v.long("batching.ergodex.broadcastMinerFeeCeiling"), 0L, 100000000L, "nanoERG per execution")
    v.range("batching.ergodex.maxPoolAgeBlocks", v.int("batching.ergodex.maxPoolAgeBlocks"), 1, 10000000,
      "blocks since a pool last traded")
    v.bool("batching.ergodex.broadcast")
    v.bool("batching.ergodex.broadcastMempoolOrders")
    v.range("batching.ergodex.maxMempoolOrders", v.int("batching.ergodex.maxMempoolOrders"), 0, 4096,
      "unconfirmed orders kept per build or broadcast pass")
    v.range("batching.ergodex.maxMempoolOrdersPerTx", v.int("batching.ergodex.maxMempoolOrdersPerTx"), 1, 4096,
      "unconfirmed orders one transaction may contribute")
    v.range("batching.ergodex.maxUnbuildablePerRun", v.int("batching.ergodex.maxUnbuildablePerRun"), 1, 1000,
      "unbuildable orders before a run stops trying")
    v.range("batching.ergodex.maxUnbuildablePerTx", v.int("batching.ergodex.maxUnbuildablePerTx"), 1, 1000,
      "unbuildable orders from one transaction before its others are passed over")
    v.longRange("batching.ergodex.skippedOrderTtlMs", v.long("batching.ergodex.skippedOrderTtlMs"),
      60000L, 86400000L, "ms an unbuildable order is left out")
    v.range("batching.ergodex.maxSkippedOrders", v.int("batching.ergodex.maxSkippedOrders"), 0, 100000,
      "unbuildable orders remembered; about 200 bytes each")
    v.range("batching.ergodex.maxAncestorTxs", v.int("batching.ergodex.maxAncestorTxs"), 0, 100,
      "unconfirmed transactions carried ahead of one order")
    v.strategy("batching.ergodex.strategy")
    v.longRange("batching.ergodex.searchBudgetMs", v.long("batching.ergodex.searchBudgetMs"), 0L, 10000L,
      "ms one plan may search; 0 plans greedily")

    // ---- batching.lithosdex ----
    v.bool("batching.lithosdex.enabled")
    v.longRange("batching.lithosdex.scanIntervalMs", v.long("batching.lithosdex.scanIntervalMs"),
      5000L, 3600000L, "ms between order scans; each pages the node's indexer")
    v.range("batching.lithosdex.maxTrackedOrders", v.int("batching.lithosdex.maxTrackedOrders"), 1, 4096,
      "order ids held between scans")
    v.range("batching.lithosdex.maxOrdersPerBlock", v.int("batching.lithosdex.maxOrdersPerBlock"), 0, 100,
      "orders executed per block or broadcast pass")
    v.longRange("batching.lithosdex.minRevenueNanoErg", v.long("batching.lithosdex.minRevenueNanoErg"),
      0L, 1000000000000L, "nanoERG")
    v.longRange("batching.lithosdex.broadcastMinRevenueNanoErg",
      v.long("batching.lithosdex.broadcastMinRevenueNanoErg"), 0L, 1000000000000L, "nanoERG")
    v.longRange("batching.lithosdex.broadcastMinerFeeCeiling",
      v.long("batching.lithosdex.broadcastMinerFeeCeiling"), 0L, 100000000L, "nanoERG per execution")
    v.bool("batching.lithosdex.broadcast")
    v.bool("batching.lithosdex.broadcastMempoolOrders")
    v.range("batching.lithosdex.maxMempoolOrders", v.int("batching.lithosdex.maxMempoolOrders"), 0, 4096,
      "unconfirmed orders kept per build or broadcast pass")
    v.range("batching.lithosdex.maxMempoolOrdersPerTx", v.int("batching.lithosdex.maxMempoolOrdersPerTx"), 1, 4096,
      "unconfirmed orders one transaction may contribute")
    v.range("batching.lithosdex.maxUnbuildablePerRun", v.int("batching.lithosdex.maxUnbuildablePerRun"), 1, 1000,
      "unbuildable orders before a run stops trying")
    v.range("batching.lithosdex.maxUnbuildablePerTx", v.int("batching.lithosdex.maxUnbuildablePerTx"), 1, 1000,
      "unbuildable orders from one transaction before its others are passed over")
    v.bool("batching.lithosdex.autoFlush")
    v.longRange("batching.lithosdex.skippedOrderTtlMs", v.long("batching.lithosdex.skippedOrderTtlMs"),
      60000L, 86400000L, "ms an unbuildable order is left out")
    v.range("batching.lithosdex.maxSkippedOrders", v.int("batching.lithosdex.maxSkippedOrders"), 0, 100000,
      "unbuildable orders remembered; about 200 bytes each")
    v.range("batching.lithosdex.maxAncestorTxs", v.int("batching.lithosdex.maxAncestorTxs"), 0, 100,
      "unconfirmed transactions carried ahead of one order")
    v.bool("batching.lithosdex.discoverPools")
    v.range("batching.lithosdex.maxTrackedPools", v.int("batching.lithosdex.maxTrackedPools"), 1, 1024,
      "pools held between scans besides ERG:LIT")
    v.strategy("batching.lithosdex.strategy")
    v.longRange("batching.lithosdex.searchBudgetMs", v.long("batching.lithosdex.searchBudgetMs"), 0L, 10000L,
      "ms one plan may search; 0 plans greedily")

    // ---- emission ----
    v.bool("emission.enabled")
    v.range("emission.queueInterval", v.int("emission.queueInterval"), 1000, 3600000, "ms")
    v.range("emission.maxQueueSpends", v.int("emission.maxQueueSpends"), 1, 128, "chained emission spends per pass")
    v.bool("emission.mempoolChaining")
    v.range("emission.maxChainDepth", v.int("emission.maxChainDepth"), 0, 4096, "unconfirmed spends followed before falling back to confirmed state")
    v.range("emission.queueScanLimit", v.int("emission.queueScanLimit"), 1, 100000, "queue boxes paged through per head search")
    v.long("emission.txFee").foreach { fee =>
      if (fee < Parameters.MinFee || fee > 1000000000L)
        v.problem("emission.txFee",
          s"$fee nanoERG is outside ${Parameters.MinFee}..1000000000 (at least the network minimum fee, at most 1 ERG)")
    }
    v.bool("emission.autoCollateralize")
    v.range("emission.collateralizeInterval", v.int("emission.collateralizeInterval"), 1000, 86400000, "ms")
    v.range("emission.maxJoinsPerRun", v.int("emission.maxJoinsPerRun"), 1, 128, "queue boxes created per pass")
    v.range("emission.maxOwnCollateral", v.int("emission.maxOwnCollateral"), 0, 2000,
      "own boxes live at once; includes collateral, queue, and newly made pos boxes")
    val maxLenderKeys =
      v.range("emission.maxLenderKeys", v.int("emission.maxLenderKeys"), 1, 1000, "wallet addresses dedicated to lending")
    v.long("emission.maxPermitPerJoin").foreach { permit =>
      if (permit <= 0)
        v.problem("emission.maxPermitPerJoin", s"$permit must be positive (LIT base units); joins stop once the thermostat passes this price")
    }
    // A fee past break-even is a deliberate bet on the mined block's fees, so `selfCollateralize`
    // warns about it per pass rather than refusing to start. What IS fatal is a fee too small for
    // the finder's share of it to be worth the output it arrives in.
    v.longRange("emission.priorityFeeNanoErgs", v.long("emission.priorityFeeNanoErgs"),
      0L, CollateralParams.BLOCK_REWARD,
      "nanoERG added to each position above the 2.915 ERG floor; 0 posts at the floor")
      .foreach { fee =>
        if (fee > 0 && fee < RollupProtocol.MinPriorityFee)
          v.problem("emission.priorityFeeNanoErgs",
            s"$fee must be 0 or at least ${RollupProtocol.MinPriorityFee} nanoERG " +
              s"(${RollupProtocol.MinPriorityFee / 1000000} mERG); below that the block's finder is " +
              "left too little to be worth the output it is paid in")
      }

    // Every lender key must have a wallet secret capable of spending returned funds.
    for {
      addr <- numAddresses
      keys <- maxLenderKeys
      if keys > addr
    } v.problem("emission.maxLenderKeys",
      s"$keys exceeds node.numAddresses ($addr). Addresses past index ${addr - 1} would receive funds this client cannot sign for. Raise node.numAddresses to at least $keys")

    // ---- state ----
    v.bool("state.disableTransforms")
    v.bool("state.autoCommit")

    // ---- sync ----
    v.range("sync.startHeight", v.intReq("sync.startHeight"), 2, 2000000000,
      "block height to start synchronizing from; minimum 1")
    // Cursors are ~100 bytes, and the node serves at most 16384 headers in one chainSlice.
    v.range("sync.cursorWindow", v.int("sync.cursorWindow"), 1, 16000,
      "canonical cursors retained for locating a fork after restart")
    v.range("sync.materializedDictionaryCacheEntries", v.int("sync.materializedDictionaryCacheEntries"),
      1, 64, "authenticated dictionaries retained after lazy materialization")
    v.range("sync.catchUpBatchBlocks", v.int("sync.catchUpBatchBlocks"), 1, 64,
      "blocks fetched per canonical round trip; each is held whole until it commits")
    // Intervals are floors, not just parseable durations. Zero or negative makes every tick due, and
    // the repair holds the producer, so a zero interval stops canonical synchronization outright.
    v.durationRangeReq("sync.pollInterval", 1000L, 600000L)
    v.durationRangeReq("sync.revalidationChecks", 1000L, 3600000L)
    v.durationRangeReq("sync.minerDictionary.repairInterval", 30000L, 86400000L)
    v.durationRangeReq("sync.mempool.refreshInterval", 1000L, 600000L)
    v.range("sync.retriesBeforeAlarm", v.int("sync.retriesBeforeAlarm"), 1, 10000,
      "consecutive failures at one height before synchronization reports itself stalled")
    v.range("sync.mempool.maxTransactions", v.int("sync.mempool.maxTransactions"), 1, 100000,
      "unconfirmed transactions read before the mempool view gives up on a complete revision")
    v.range("sync.quarantine.repairAttempts", v.int("sync.quarantine.repairAttempts"), 0, 100,
      "rebuild attempts for a quarantined rollup before it is dropped for good")
    v.range("sync.quarantine.retentionBlocks", v.int("sync.quarantine.retentionBlocks"), 1, 10000000,
      "committed blocks a terminal or retry-exhausted quarantine is retained for diagnostics")
    v.bool("sync.quarantine.maintenanceCheckpoints")
    v.range("sync.quarantine.checkpointIntervalBlocks",
      v.int("sync.quarantine.checkpointIntervalBlocks"), 1, 100000,
      "committed catch-up blocks between quarantine repair checkpoints")
    v.range("sync.quarantine.maxTransforms", v.int("sync.quarantine.maxTransforms"), 1, 10000000,
      "rollup spends followed during one quarantine rebuild")
    v.durationRangeReq("sync.quarantine.repairTimeout", 1000L, 3600000L)
    v.bool("sync.minerDictionary.bootstrap")
    v.range("sync.minerDictionary.maxTransforms", v.int("sync.minerDictionary.maxTransforms"), 1, 10000000,
      "dictionary spends followed during bootstrap")
    v.requireExisting("sync.storage.backend", v.string("sync.storage.backend")).foreach { backend =>
      storage.KeyValueStore.factoryFor(backend).left.foreach(error =>
        v.problem("sync.storage.backend", error.message))
    }
    v.requireExisting("sync.storage.path", v.string("sync.storage.path"))
    v.bool("sync.snapshots.enabled")
    v.range("sync.snapshots.intervalBlocks", v.int("sync.snapshots.intervalBlocks"), 1, 10000000,
      "committed blocks between persistent snapshots")
    v.range("sync.snapshots.retention", v.int("sync.snapshots.retention"), 2, 100,
      "complete snapshot generations retained")
    // Dictionary subtrees, headers, and the small generation record are each independently bounded.
    v.range("sync.snapshots.maxEntryBytes", v.int("sync.snapshots.maxEntryBytes"), 1048576, 536870912,
      "bytes one snapshot entry may reach before the generation is abandoned")

    // ---- lithos-tasks ----
    // Require every task field that TasksConfig reads without a default.
    TasksConfig.Names.foreach { name =>
      v.boolReq(TasksConfig.key(name, "enabled"))
      v.durationRangeReq(TasksConfig.key(name, "startup"), 1, 604800000L)
      v.durationRangeReq(TasksConfig.key(name, "interval"), 1, 604800000L)
    }

    // ---- lithos-contexts ----
    // Akka validates dispatcher contents; this pass verifies that each named block exists.
    Contexts.Names.foreach(name => v.requireBlock(Contexts.key(name)))

    // ---- api ----
    // Controllers require a Blake2b256 API-key hash at construction.
    v.requireExisting("lithos.apiKeyHash", v.string("lithos.apiKeyHash")).foreach { raw =>
      val hash = raw.trim
      if (!hash.matches("(?i)[0-9a-f]{64}"))
        v.problem("lithos.apiKeyHash",
          "is not a 64-character hex hash. This is the BLAKE2b-256 of your api key, not the key " +
            "itself - hash it the same way you set your node's apiKeyHash")
    }

    v.finish()
  }
}

/**
 * Accumulates problems across every section and raises them together in [[finish]], so a user sees
 * all their mistakes in one startup instead of fixing them one crash at a time.
 */
final class ConfigValidator(config: Configuration) {

  private val problems = ListBuffer.empty[ConfigProblem]
  private val flagged  = mutable.Set.empty[String]

  def problem(key: String, message: String): Unit = {
    problems += ConfigProblem(key, message)
    flagged += key
  }

  def finish(): Unit =
    if (problems.nonEmpty) throw new ConfigValidationException(problems.toList)

  def string(key: String): Option[String]         = read(ConfigLoader.stringLoader, key, "a string")
  def int(key: String): Option[Int]               = read(ConfigLoader.intLoader, key, "an integer")
  def long(key: String): Option[Long]             = read(ConfigLoader.longLoader, key, "an integer")
  def bool(key: String): Option[Boolean]          = read(ConfigLoader.booleanLoader, key, "true or false")
  def double(key: String): Option[Double]         = read(ConfigLoader.doubleLoader, key, "a number")
  def duration(key: String): Option[FiniteDuration] =
    read(ConfigLoader.finiteDurationLoader, key, "a duration such as \"30 seconds\"")

  /** An int whose absence is itself a problem - required keys the client cannot default sensibly. */
  def intReq(key: String): Option[Int] = requireExisting(key, int(key))

  /** As [[intReq]]. Use wherever the reading code calls `Configuration.get`, which throws. */
  def boolReq(key: String): Option[Boolean] = requireExisting(key, bool(key))

  /** A whole config block must exist, without saying anything about what is inside it. */
  def requireBlock(key: String): Unit =
    requireExisting(key, read(ConfigLoader.configurationLoader, key, "a configuration block"))

  /** Reports absence unless something was already reported for this key. */
  def requireExisting[A](key: String, value: Option[A]): Option[A] =
    value.orElse {
      if (!flagged.contains(key)) problem(key, "is required but missing from application.conf")
      None
    }

  def range(key: String, value: Option[Int], min: Long, max: Long, unit: String): Option[Int] =
    value match {
      case Some(v) if v < min || v > max =>
        problem(key, s"$v is outside $min..$max ($unit)")
        None
      case other => other
    }

  def longRange(key: String, value: Option[Long], min: Long, max: Long, unit: String): Option[Long] =
    value match {
      case Some(v) if v < min || v > max =>
        problem(key, s"$v is outside $min..$max ($unit)")
        None
      case other => other
    }

  def doubleRange(key: String, value: Option[Double], min: Double, max: Double,
                  unit: String): Option[Double] =
    value match {
      case Some(v) if v < min || v > max =>
        problem(key, s"$v is outside $min..$max ($unit)")
        None
      case other => other
    }

  def port(key: String, value: Option[Int]): Option[Int] =
    range(key, value, 1, 65535, "a TCP port")

  /** A batching strategy name, which must be one the client knows. */
  def strategy(key: String): Unit =
    string(key).foreach { name =>
      if (transactions.batching.RunStrategy.named(name).isEmpty)
        problem(key, s""""$name" is not a batching strategy. Known: ${transactions.batching.RunStrategy.all.map(_.name).mkString(", ")}""")
    }

  /** Validates a required task duration and reports one error for a malformed value. */
  def durationRangeReq(key: String, minMs: Long, maxMs: Long): Unit =
    requireExisting(key, duration(key)).foreach { d =>
      if (d.toMillis < minMs || d.toMillis > maxMs)
        problem(key, s"${d.toString} is outside ${minMs}ms..${maxMs}ms")
    }

  /** http(s) URL with a host; `allowDefaultSentinel` accepts the literal "default". */
  def url(key: String, value: Option[String], allowDefaultSentinel: Boolean = false): Unit = {
    requireExisting(key, value).foreach { raw =>
      if (!(allowDefaultSentinel && raw.trim == "default")) {
        val ok = Try(new URI(raw.trim)) match {
          case Success(uri) if Set("http", "https").contains(Option(uri.getScheme).getOrElse("")) =>
            Option(uri.getHost).exists(_.nonEmpty)
          case _ => false
        }
        if (!ok)
          problem(key, s""""$raw" is not a usable URL - use http:// or https:// with a host, e.g. http://127.0.0.1""")
      }
    }
  }

  private def read[A](loader: ConfigLoader[A], key: String, expected: String): Option[A] =
    Try(config.getOptional[A](key)(loader)) match {
      case Success(v) => v
      case Failure(_) =>
        problem(key, s"must be $expected")
        None
    }
}
