package configs

import play.api.{ConfigLoader, Configuration}

/**
 * What one transaction source may contribute to this miner's own block.
 *
 * Each source is bounded on its own before the package is bounded as a whole, so a busy source
 * cannot crowd out a quieter one that matters more. A source that is off contributes no work at
 * all — it is never asked, so it costs no build and no node read.
 *
 * @param maxTxs   transactions this source may place in one block
 * @param maxBytes serialized bytes it may claim, counting unconfirmed ancestors it carries
 * @param maxCost  execution cost it may claim. An unconfirmed ancestor reports none, which is part
 *                 of why the package as a whole claims only a share of the block
 */
case class CandidateSourceConfig(enabled: Boolean, maxTxs: Int, maxBytes: Long, maxCost: Long) {
  def budget: transactions.candidate.CandidateBudget = transactions.candidate.CandidateBudget(maxBytes, maxCost)
}

object CandidateSourceConfig {

  /** Names are the config keys under `stratum.candidate.sources`, and the wiring order in the pool. */
  final val Rollups = "rollups"
  final val Emissions = "emissions"
  final val Rent = "rent"

  /**
   * Conservative on every axis. Fee-less insertions are block space not earning from someone else's
   * transaction, so a source has to be turned up deliberately rather than discovered to be large.
   */
  val Default: CandidateSourceConfig =
    CandidateSourceConfig(enabled = true, maxTxs = 5, maxBytes = 262144L, maxCost = 1000000L)

  def apply(config: Configuration, name: String): CandidateSourceConfig = {
    def path(key: String): String = s"stratum.candidate.sources.$name.$key"
    def int(key: String, fallback: Int): Int =
      config.getOptional(path(key))(ConfigLoader.intLoader).getOrElse(fallback)
    def long(key: String, fallback: Long): Long =
      config.getOptional(path(key))(ConfigLoader.longLoader).getOrElse(fallback)

    CandidateSourceConfig(
      enabled = config.getOptional(path("enabled"))(ConfigLoader.booleanLoader).getOrElse(Default.enabled),
      maxTxs = int("maxTxs", Default.maxTxs),
      maxBytes = long("maxBytes", Default.maxBytes),
      maxCost = long("maxCost", Default.maxCost))
  }
}
