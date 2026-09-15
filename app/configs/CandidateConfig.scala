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
                           logBudgets: Boolean = false)

object CandidateConfig {

  /** Mirrors the `stratum.candidate` block in `application.conf`; keep the two in step. */
  val Default: CandidateConfig = CandidateConfig(
    collateralPoolSize = 100,
    collateralRefreshInterval = 60000,
    blockTransactions = false,
    sources = Map(
      CandidateSourceConfig.Rollups -> CandidateSourceConfig.Default,
      CandidateSourceConfig.Emissions -> CandidateSourceConfig.Default,
      // Off until a miner points it at a start height and has watched a scan pass run. One
      // transaction, because a rent collection sweeps every box it takes into a single sweep.
      CandidateSourceConfig.Rent -> CandidateSourceConfig.Default.copy(enabled = false, maxTxs = 1),
      // Disabled by default; the eight slots include placement ancestors and executions.
      CandidateSourceConfig.ErgoDex -> CandidateSourceConfig.Default.copy(enabled = false, maxTxs = 20),
      // On by default: LithosDex is the protocol's own DEX. Slots include placements and the flush.
      CandidateSourceConfig.LithosDex -> CandidateSourceConfig.Default.copy(enabled = true, maxTxs = 20)),
    blockShare = 0.5,
    useTruePropCollection = false,
    genesisWaitMs = 1500,
    mempoolRefreshMs = 20000,
    blockTxTimeout = 20000,
    logTimings = false,
    minCandidateChangeRevenue = 1000000L,
    waitForBlockPackage = true,
    logBudgets = false
  )

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
      logBudgets = bool("logBudgets", Default.logBudgets)
    )
  }
}
