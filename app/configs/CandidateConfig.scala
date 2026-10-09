package configs

import play.api.{ConfigLoader, Configuration}

/** Optional mining package settings. Durations are milliseconds and revenue is nanoERG. */
case class CandidateConfig(collateralPoolSize: Int,
                           collateralRefreshInterval: Int,
                           blockTransactions: Boolean,
                           sources: Map[String, CandidateSourceConfig],
                           blockShare: Double,
                           useTruePropCollection: Boolean,
                           genesisWaitMs: Int,
                           mempoolRefreshMs: Int,
                           blockTxTimeout: Int,
                           logTimings: Boolean,
                           minCandidateChangeRevenue: Long = 1000000L,
                           waitForBlockPackage: Boolean = true,
                           logBudgets: Boolean = false,
                           clearanceAge: Int = 7200,
                           refreshForProtocolTxs: Boolean = true,
                           minNewProtocolTxs: Int = 1,
                           pinnedInputs: Int = 1,
                           collateralStrategy: String = CandidateConfig.HighestFee)

object CandidateConfig {

  final val HighestFee = "highestFee"
  final val Random = "random"
  final val CollateralStrategies: Seq[String] = Seq(HighestFee, Random)

  /**
   * Bounds on `clearanceAge`. Below 100 a box that bids nothing overtakes the bids almost at once, and
   * above 14400 blocks, about 20 days on mainnet, a box can sit in the active set for weeks.
   */
  final val MinClearanceAge: Int = 100
  final val MaxClearanceAge: Int = 14400

  /** Mirrors the `stratum.candidate` block in `application.conf`; keep the two in step. */
  val Default: CandidateConfig = CandidateConfig(
    collateralPoolSize = 100,
    collateralRefreshInterval = 60000,
    blockTransactions = true,
    sources = Map(
      CandidateSourceConfig.Rollups -> CandidateSourceConfig.Default,
      // Room for a run of Clears; Activates alone stop at `emission.candidateActivates`.
      CandidateSourceConfig.Emissions -> CandidateSourceConfig.Default.copy(maxTxs = 20),
      // Two transactions: one sweep, and the merge folding its proceeds outputs into one box. Twice the
      // usual cost, because a claim with its own proceeds output also pays for a signed merge input.
      CandidateSourceConfig.Rent ->
        CandidateSourceConfig.Default.copy(enabled = true, maxTxs = 2, maxCost = 2000000L),
      // The slots include placement ancestors and executions.
      CandidateSourceConfig.ErgoDex -> CandidateSourceConfig.Default.copy(maxTxs = 20),
      // On by default: LithosDex is the protocol's own DEX. Slots include placements and the flush.
      CandidateSourceConfig.LithosDex -> CandidateSourceConfig.Default.copy(enabled = true, maxTxs = 20),
      // Off until the operator turns it on: which protocols a miner maintains is their call, and
      // nothing here earns until a job is enabled. One slot per box.
      CandidateSourceConfig.Upkeep -> CandidateSourceConfig.Default.copy(enabled = false)),
    blockShare = 0.5,
    useTruePropCollection = false,
    genesisWaitMs = 1500,
    mempoolRefreshMs = 20000,
    blockTxTimeout = 20000,
    logTimings = false,
    minCandidateChangeRevenue = 1000000L,
    waitForBlockPackage = true,
    logBudgets = false,
    clearanceAge = 7200,
    refreshForProtocolTxs = true,
    minNewProtocolTxs = 1,
    pinnedInputs = 1,
    collateralStrategy = HighestFee
  )

  /**
   * Wallet boxes the engine pins for candidate funding. Zero unless candidates are actually built
   * with rollup work, since a pin nothing uses only withholds a box.
   */
  def pinTarget(config: Configuration): Int = {
    val candidate = CandidateConfig(config)
    val rollups = candidate.sources.getOrElse(CandidateSourceConfig.Rollups, CandidateSourceConfig.Default)
    val mining = scala.util.Try(new TasksConfig(config).stratumServerTaskConfig.enabled).getOrElse(false)
    val transforming = !new StateConfig(config).disableTransforms.getOrElse(false)
    if (mining && transforming && candidate.blockTransactions && rollups.enabled && rollups.maxTxs > 0)
      candidate.pinnedInputs
    else 0
  }

  def apply(config: Configuration): CandidateConfig = {
    def int(key: String, fallback: Int): Int =
      config.getOptional(s"stratum.candidate.$key")(ConfigLoader.intLoader).getOrElse(fallback)

    def bool(key: String, fallback: Boolean): Boolean =
      config.getOptional(s"stratum.candidate.$key")(ConfigLoader.booleanLoader).getOrElse(fallback)

    def double(key: String, fallback: Double): Double =
      config.getOptional(s"stratum.candidate.$key")(ConfigLoader.doubleLoader).getOrElse(fallback)

    CandidateConfig(
      collateralPoolSize = int("collateralPoolSize", Default.collateralPoolSize),
      collateralRefreshInterval = int("collateralRefreshInterval", Default.collateralRefreshInterval),
      blockTransactions = bool("blockTransactions", Default.blockTransactions),
      sources = Default.sources.map { case (name, fallback) =>
        name -> CandidateSourceConfig(config, name, fallback) },
      blockShare = double("blockShare", Default.blockShare),
      useTruePropCollection = bool("useTruePropCollection", Default.useTruePropCollection),
      genesisWaitMs = int("genesisWaitMs", Default.genesisWaitMs),
      mempoolRefreshMs = int("mempoolRefreshMs", Default.mempoolRefreshMs),
      blockTxTimeout = int("blockTxTimeout", Default.blockTxTimeout),
      logTimings = bool("logTimings", Default.logTimings),
      minCandidateChangeRevenue = config.getOptional[Long]("stratum.candidate.minCandidateChangeRevenue")
        .getOrElse(Default.minCandidateChangeRevenue),
      waitForBlockPackage = bool("waitForBlockPackage", Default.waitForBlockPackage),
      logBudgets = bool("logBudgets", Default.logBudgets),
      clearanceAge = int("clearanceAge", Default.clearanceAge),
      refreshForProtocolTxs = bool("refreshForProtocolTxs", Default.refreshForProtocolTxs),
      minNewProtocolTxs = int("minNewProtocolTxs", Default.minNewProtocolTxs),
      pinnedInputs = int("pinnedInputs", Default.pinnedInputs),
      collateralStrategy = config.getOptional[String]("stratum.candidate.collateralStrategy")
        .getOrElse(Default.collateralStrategy)
    )
  }
}
