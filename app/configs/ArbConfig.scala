package configs

import play.api.{ConfigLoader, Configuration}

/**
 * The `batching.arb` block: arbitrage cycles in this miner's own blocks, funded from one wallet box held as
 * the stake.
 *
 * @param enabled          whether the arb stage runs at all. Off, no arb code runs and no stake is held
 * @param stakeNanoErg     the least ERG the stake box holds, and the most one cycle can put in
 * @param minProfitNanoErg the least profit a cycle must leave to be built
 * @param autoReserve      with no wallet box free to hold the stake, merge plain boxes into one, given twice the
 *                         stake in confirmed plain boxes
 */
case class ArbConfig(enabled: Boolean, stakeNanoErg: Long, minProfitNanoErg: Long, autoReserve: Boolean = true)

object ArbConfig {

  /** The batching block and candidate source key the arb stage reads. */
  final val Name: String = CandidateSourceConfig.Arb

  /** Mirrors `batching.arb` in application.conf; keep the two in step. */
  val Default: ArbConfig =
    ArbConfig(enabled = true, stakeNanoErg = 10000000000L, minProfitNanoErg = 1000000L, autoReserve = true)

  def apply(config: Configuration): ArbConfig = {
    def path(key: String): String = s"batching.$Name.$key"
    ArbConfig(
      enabled = config.getOptional(path("enabled"))(ConfigLoader.booleanLoader).getOrElse(Default.enabled),
      stakeNanoErg = config.getOptional(path("stakeNanoErg"))(ConfigLoader.longLoader).getOrElse(Default.stakeNanoErg),
      minProfitNanoErg =
        config.getOptional(path("minProfitNanoErg"))(ConfigLoader.longLoader).getOrElse(Default.minProfitNanoErg),
      autoReserve = config.getOptional(path("autoReserve"))(ConfigLoader.booleanLoader).getOrElse(Default.autoReserve))
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
