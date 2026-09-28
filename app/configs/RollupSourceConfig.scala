package configs

import play.api.{ConfigLoader, Configuration}

/**
 * Settings of the rollup candidate source beyond the budget every source has.
 *
 * @param maxAncestorTxs unconfirmed transactions one rollup spend may carry into the block ahead of
 *                       itself: the chain behind that rollup's mempool tip, often other miners'
 *                       submissions. Work needing more is left out of the block until they confirm;
 *                       its funded copy still goes to the mempool.
 */
case class RollupSourceConfig(maxAncestorTxs: Int)

object RollupSourceConfig {

  /** Mirrors the `stratum.candidate.sources.rollups` block in `application.conf`; keep them in step. */
  val Default: RollupSourceConfig = RollupSourceConfig(maxAncestorTxs = 4)

  def apply(config: Configuration): RollupSourceConfig =
    RollupSourceConfig(
      maxAncestorTxs = config.getOptional("stratum.candidate.sources.rollups.maxAncestorTxs")(ConfigLoader.intLoader)
        .getOrElse(Default.maxAncestorTxs))
}
