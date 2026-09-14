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

/** Order discovery limits and revenue floors. Candidate transaction budgets are configured per source. */
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
                          deniedPools: Set[String])

object BatchingConfig {

  /** Default discovery and revenue limits; broadcasting requires an explicit opt-in. */
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
    broadcast = false,
    deniedPools = Set.empty)

  def apply(config: Configuration, name: String): BatchingConfig = {
    def path(key: String): String = s"batching.$name.$key"
    def int(key: String, fallback: Int): Int =
      config.getOptional(path(key))(ConfigLoader.intLoader).getOrElse(fallback)
    def long(key: String, fallback: Long): Long =
      config.getOptional(path(key))(ConfigLoader.longLoader).getOrElse(fallback)
    def bool(key: String, fallback: Boolean): Boolean =
      config.getOptional(s"stratum.candidate.$key")(ConfigLoader.booleanLoader).getOrElse(fallback)

    BatchingConfig(
      enabled = bool("enabled", Default.enabled),
      scanIntervalMs = long("scanIntervalMs", Default.scanIntervalMs),
      maxTrackedOrders = int("maxTrackedOrders", Default.maxTrackedOrders),
      maxTrackedPools = int("maxTrackedPools", Default.maxTrackedPools),
      maxOrdersPerBlock = int("maxOrdersPerBlock", Default.maxOrdersPerBlock),
      minRevenueNanoErg = long("minRevenueNanoErg", Default.minRevenueNanoErg),
      broadcastMinRevenueNanoErg = long("broadcastMinRevenueNanoErg", Default.broadcastMinRevenueNanoErg),
      broadcastMinerFeeCeiling = long("broadcastMinerFeeCeiling", Default.broadcastMinerFeeCeiling),
      maxPoolAgeBlocks = int("maxPoolAgeBlocks", Default.maxPoolAgeBlocks),
      broadcast = config.getOptional(path("broadcast"))(ConfigLoader.booleanLoader)
        .getOrElse(Default.broadcast),
      // Lowercased because token ids are compared as the node reports them.
      deniedPools = config.getOptional(path("deniedPools"))(ConfigLoader.seqStringLoader)
        .map(_.map(_.trim.toLowerCase).toSet).getOrElse(Default.deniedPools))
  }
}
