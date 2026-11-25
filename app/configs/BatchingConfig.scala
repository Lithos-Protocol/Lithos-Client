package configs

import play.api.{ConfigLoader, Configuration}

/** Node mempool ranking mode, mirrored in configuration because the node does not report it. */
sealed trait MempoolSorting
object MempoolSorting {
  /** Fee per serialized byte. The node's own default. */
  case object BySize extends MempoolSorting
  /** Fee per unit of execution cost. */
  case object ByExecutionCost extends MempoolSorting

  def apply(config: Configuration): MempoolSorting =
    config.getOptional[String]("node.mempoolSorting").map(_.trim) match {
      case Some("byExecutionCost") => ByExecutionCost
      case _ => BySize
    }
}

/**
 * Order discovery limits and revenue floors. Candidate transaction budgets are configured per source.
 *
 * @param deniedOrders      order box ids never built, broadcast or handed to arb, for orders known to be unbuildable
 * @param skippedOrderTtlMs how long an order that priced but could not be built is left out of scans and builds
 * @param maxSkippedOrders  most such orders remembered at once, oldest forgotten first; 0 remembers none
 * @param maxAncestorTxs    unconfirmed transactions one order may need carried ahead of it: its placement and
 *                          every unconfirmed ancestor of that placement. 0 executes confirmed orders only
 * @param broadcastMempoolOrders with `broadcast`, also broadcast orders still unconfirmed, spending their
 *                          unconfirmed box, rather than waiting for them to confirm and be indexed
 * @param maxMempoolOrders  unconfirmed orders one build or broadcast pass takes, in rounds across the
 *                          transactions that created them, so no one transaction fills the list
 * @param maxMempoolOrdersPerTx unconfirmed orders one transaction may contribute to that list
 * @param maxUnbuildablePerRun orders a run fails to build before it stops trying more
 * @param maxUnbuildablePerTx orders created by one transaction a run fails to build before it passes over that
 *                          transaction's other orders untried, so one transaction cannot use up a run's attempts
 * @param strategy          names the strategy choosing which orders a run executes and in what order
 * @param searchBudgetMs    most milliseconds the strategy may search each plan for; 0 plans greedily
 */
case class BatchingConfig(enabled: Boolean,
                          scanIntervalMs: Long,
                          maxTrackedOrders: Int,
                          maxTrackedPools: Int,
                          maxOrdersPerBlock: Int,
                          minRevenueNanoErg: Long,
                          broadcastMinRevenueNanoErg: Long,
                          broadcastMinerFeeCeiling: Long,
                          maxPoolAgeBlocks: Int,
                          broadcast: Boolean,
                          deniedPools: Set[String],
                          skippedOrderTtlMs: Long,
                          maxSkippedOrders: Int,
                          maxAncestorTxs: Int,
                          broadcastMempoolOrders: Boolean,
                          maxMempoolOrders: Int,
                          maxMempoolOrdersPerTx: Int,
                          maxUnbuildablePerRun: Int,
                          maxUnbuildablePerTx: Int,
                          strategy: String = "maxFees",
                          searchBudgetMs: Long = 250L,
                          deniedOrders: Set[String] = Set.empty)

object BatchingConfig {

  /** Default discovery, revenue and broadcast settings, shared by every batcher. */
  val Default: BatchingConfig = BatchingConfig(
    enabled = true,
    scanIntervalMs = 8000,
    maxTrackedOrders = 512,
    maxTrackedPools = 256,
    maxOrdersPerBlock = 20,
    minRevenueNanoErg = 1000000L,
    broadcastMinRevenueNanoErg = 2500000L,
    broadcastMinerFeeCeiling = 2000000L,
    maxPoolAgeBlocks = 20160,
    broadcast = true,
    deniedPools = Set.empty,
    skippedOrderTtlMs = 3600000L,
    maxSkippedOrders = 4096,
    maxAncestorTxs = 2,
    broadcastMempoolOrders = true,
    maxMempoolOrders = 64,
    maxMempoolOrdersPerTx = 4,
    maxUnbuildablePerRun = 32,
    maxUnbuildablePerTx = 2,
    strategy = "maxFees",
    searchBudgetMs = 250L,
    deniedOrders = Set.empty)

  /**
   * The one token/token Machina grid open on mainnet. Its ERG cannot cover the child box a fill must leave, so no
   * filler can take it, and the arb stage would otherwise price and refuse it on every build.
   */
  final val UnfillableMachinaGrid: String = "84f59301ac4929d825c8bc0957c728f6f4a62a0da8cdd4f5ebc59f3f3aff09e7"

  /** The ErgoDEX batcher's defaults: [[Default]], with the orders known to be unfillable denied. */
  val ErgoDexDefault: BatchingConfig = Default.copy(deniedOrders = Set(UnfillableMachinaGrid))

  /** The `batching.<name>` block, reading the ErgoDEX batcher's defaults for `ergodex` and [[Default]] otherwise. */
  def apply(config: Configuration, name: String): BatchingConfig =
    apply(config, name, if (name == CandidateSourceConfig.ErgoDex) ErgoDexDefault else Default)

  /** The `batching.<name>` block, reading `defaults` for any key it leaves out. */
  def apply(config: Configuration, name: String, defaults: BatchingConfig): BatchingConfig = {
    def path(key: String): String = s"batching.$name.$key"
    def int(key: String, fallback: Int): Int =
      config.getOptional(path(key))(ConfigLoader.intLoader).getOrElse(fallback)
    def long(key: String, fallback: Long): Long =
      config.getOptional(path(key))(ConfigLoader.longLoader).getOrElse(fallback)
    def bool(key: String, fallback: Boolean): Boolean =
      config.getOptional(path(key))(ConfigLoader.booleanLoader).getOrElse(fallback)
    // Lowercased because token and box ids are compared as the node reports them.
    def ids(key: String, fallback: Set[String]): Set[String] =
      config.getOptional(path(key))(ConfigLoader.seqStringLoader)
        .map(_.map(_.trim.toLowerCase).filter(_.nonEmpty).toSet).getOrElse(fallback)

    BatchingConfig(
      enabled = bool("enabled", defaults.enabled),
      scanIntervalMs = long("scanIntervalMs", defaults.scanIntervalMs),
      maxTrackedOrders = int("maxTrackedOrders", defaults.maxTrackedOrders),
      maxTrackedPools = int("maxTrackedPools", defaults.maxTrackedPools),
      maxOrdersPerBlock = int("maxOrdersPerBlock", defaults.maxOrdersPerBlock),
      minRevenueNanoErg = long("minRevenueNanoErg", defaults.minRevenueNanoErg),
      broadcastMinRevenueNanoErg = long("broadcastMinRevenueNanoErg", defaults.broadcastMinRevenueNanoErg),
      broadcastMinerFeeCeiling = long("broadcastMinerFeeCeiling", defaults.broadcastMinerFeeCeiling),
      maxPoolAgeBlocks = int("maxPoolAgeBlocks", defaults.maxPoolAgeBlocks),
      broadcast = bool("broadcast", defaults.broadcast),
      deniedPools = ids("deniedPools", defaults.deniedPools),
      skippedOrderTtlMs = long("skippedOrderTtlMs", defaults.skippedOrderTtlMs),
      maxSkippedOrders = int("maxSkippedOrders", defaults.maxSkippedOrders),
      maxAncestorTxs = int("maxAncestorTxs", defaults.maxAncestorTxs),
      broadcastMempoolOrders = bool("broadcastMempoolOrders", defaults.broadcastMempoolOrders),
      maxMempoolOrders = int("maxMempoolOrders", defaults.maxMempoolOrders),
      maxMempoolOrdersPerTx = int("maxMempoolOrdersPerTx", defaults.maxMempoolOrdersPerTx),
      maxUnbuildablePerRun = int("maxUnbuildablePerRun", defaults.maxUnbuildablePerRun),
      maxUnbuildablePerTx = int("maxUnbuildablePerTx", defaults.maxUnbuildablePerTx),
      strategy = config.getOptional[String](path("strategy")).map(_.trim).getOrElse(defaults.strategy),
      searchBudgetMs = long("searchBudgetMs", defaults.searchBudgetMs),
      deniedOrders = ids("deniedOrders", defaults.deniedOrders))
  }
}
