package configs

import play.api.{ConfigLoader, Configuration}

/**
 * The `batching.arb` block: arbitrage cycles and SigmaFi ZC collateral arbitrage in this miner's own blocks,
 * funded from the stake box at this wallet's arb address.
 *
 * @param enabled                 whether the arb stage runs at all. Off, no arb code runs and the arb address is not read
 * @param stakeNanoErg            the ERG auto-reserve sends to the arb address, and the most one cycle can put in
 * @param minProfitNanoErg        the least profit a cycle must leave to be built
 * @param autoReserve             with no box of the stake's size at the arb address, send one from the wallet, given
 *                                twice the stake spendable
 * @param zcEnabled               whether the arb stage also evaluates and arbitrages SigmaFi ZC lots, Dutch/Selective
 *                                vault auctions and AMM-priced sale orders on the arb stake
 * @param zcMinProfitNanoErg      the least confirmed ERG profit a ZC collateral arb bundle must leave
 * @param zcMaxCostNanoErg        most ERG one ZC collateral arb may put in; 0 uses `stakeNanoErg`
 * @param zcMaxBundlesPerBlock    most ZC collateral arb bundles built in one block
 * @param zcMinLegProceedsNanoErg least ERG an AMM swap of one collateral token must return to be worth a leg
 */
case class ArbConfig(enabled: Boolean, stakeNanoErg: Long, minProfitNanoErg: Long, autoReserve: Boolean = true,
                     zcEnabled: Boolean = true, zcMinProfitNanoErg: Long = 1000000L, zcMaxCostNanoErg: Long = 0L,
                     zcMaxBundlesPerBlock: Int = 5, zcMinLegProceedsNanoErg: Long = 100000L)

object ArbConfig {

  /** The batching block and candidate source key the arb stage reads. */
  final val Name: String = CandidateSourceConfig.Arb

  /** Mirrors `batching.arb` in application.conf; keep the two in step. */
  val Default: ArbConfig =
    ArbConfig(enabled = true, stakeNanoErg = 10000000000L, minProfitNanoErg = 1000000L, autoReserve = true,
      zcEnabled = true, zcMinProfitNanoErg = 1000000L, zcMaxCostNanoErg = 0L, zcMaxBundlesPerBlock = 5,
      zcMinLegProceedsNanoErg = 100000L)

  def apply(config: Configuration): ArbConfig = {
    def path(key: String): String = s"batching.$Name.$key"
    ArbConfig(
      enabled = config.getOptional(path("enabled"))(ConfigLoader.booleanLoader).getOrElse(Default.enabled),
      stakeNanoErg = config.getOptional(path("stakeNanoErg"))(ConfigLoader.longLoader).getOrElse(Default.stakeNanoErg),
      minProfitNanoErg =
        config.getOptional(path("minProfitNanoErg"))(ConfigLoader.longLoader).getOrElse(Default.minProfitNanoErg),
      autoReserve = config.getOptional(path("autoReserve"))(ConfigLoader.booleanLoader).getOrElse(Default.autoReserve),
      zcEnabled = config.getOptional(path("zcEnabled"))(ConfigLoader.booleanLoader).getOrElse(Default.zcEnabled),
      zcMinProfitNanoErg =
        config.getOptional(path("zcMinProfitNanoErg"))(ConfigLoader.longLoader).getOrElse(Default.zcMinProfitNanoErg),
      zcMaxCostNanoErg =
        config.getOptional(path("zcMaxCostNanoErg"))(ConfigLoader.longLoader).getOrElse(Default.zcMaxCostNanoErg),
      zcMaxBundlesPerBlock =
        config.getOptional(path("zcMaxBundlesPerBlock"))(ConfigLoader.intLoader).getOrElse(Default.zcMaxBundlesPerBlock),
      zcMinLegProceedsNanoErg =
        config.getOptional(path("zcMinLegProceedsNanoErg"))(ConfigLoader.longLoader).getOrElse(Default.zcMinLegProceedsNanoErg))
  }

  /**
   * Whether arb can reach a block: enabled, this client mines with block transactions, and the `arb`
   * candidate source has room. Anything else holds no stake and runs no stage.
   */
  def active(config: Configuration): Boolean = {
    val arb = ArbConfig(config)
    val candidate = CandidateConfig(config)
    val source = candidate.limitsFor(CandidateSourceConfig.Arb)
    val mining = scala.util.Try(new TasksConfig(config).stratumServerTaskConfig.enabled).getOrElse(false)
    arb.enabled && arb.stakeNanoErg > 0L && mining && candidate.blockTransactions && source.enabled && source.maxTxs > 0
  }

  /** The stake the wallet holds: `stakeNanoErg` while arb is active, else 0, which holds nothing. */
  def stakeTarget(config: Configuration): Long = if (active(config)) ArbConfig(config).stakeNanoErg else 0L
}
