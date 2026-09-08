package configs

import play.api.{ConfigLoader, Configuration}

/**
 * Tuning for [[mining.CandidateBuilder]] — the genesis transaction, the collateral boxes it is
 * built from, and how much of this miner's own block goes to transactions that pay no fee. Those
 * transactions are built by [[transactions.rollups.RollupProcessor]] and
 * [[transactions.emissions.EmissionsCore]], not here.
 *
 * Every key is optional and falls back to [[CandidateConfig.Default]].
 *
 * @param collateralPoolSize        How many collateral boxes to keep pre-loaded. The active set holds
 *                                  at most `CONST_MAX_ACTIVE` (100) live boxes, so 100 is the whole
 *                                  set once the protocol is out of bootstrap.
 * @param collateralRefreshInterval Backstop refresh of the pre-loaded set, in ms. The set is also
 *                                  refreshed on every new block, which is the only time a collateral
 *                                  box can actually be spent.
 * @param blockTransactions         Insert this client's own transactions into its blocks at all. Off
 *                                  gives the fastest possible candidate and leaves every transaction
 *                                  to the mempool. Never affects the genesis transaction, which is
 *                                  what makes the block a Lithos block and is always inserted.
 * @param sources                   Per-source limits, keyed by source name. Each is bounded on its
 *                                  own before the package is bounded as a whole, so one source
 *                                  cannot crowd out another. A source that is off is never asked.
 * @param blockShare                Fraction of the node's active block byte and cost limits this
 *                                  client's whole package may claim. Well under one on purpose: the
 *                                  node adds its own transactions and picks a remainder never seen
 *                                  here, and a carried unconfirmed ancestor reports no cost.
 * @param genesisWaitMs             How long a new block may pass with no job at all while the genesis
 *                                  transaction is built, in ms. Waiting means one job per block
 *                                  instead of a solo job followed by a collateral one; rigs lose more
 *                                  to two notifies in quick succession than to a short wait. Past
 *                                  this, a solo job goes out so nobody is left mining a dead height.
 *                                  Kept short on purpose: a build that has not finished by then is not
 *                                  a slow build, it is a node or selection fault, and the wait only
 *                                  delays the fallback that would have covered it.
 * @param mempoolRefreshMs          How often to pick up transactions that reached the mempool after
 *                                  a block's job went out, in ms. 0 mines the transaction set the
 *                                  block started with. A new block always goes out at once; this
 *                                  only paces the refreshes within one.
 * @param blockTxTimeout            How long to wait for the transaction actors to answer, in ms. The
 *                                  genesis transaction is never subject to this: it is published on
 *                                  its own before anything else is asked for.
 * @param logTimings                Append how long each stage took to the candidate log lines. Off
 *                                  by default: it is a `System.nanoTime` per stage and log noise on a
 *                                  miner that is working.
 */
case class CandidateConfig(collateralPoolSize: Int,
                           collateralRefreshInterval: Int,
                           blockTransactions: Boolean,
                           sources: Map[String, CandidateSourceConfig],
                           blockShare: Double,
                           genesisWaitMs: Int,
                           mempoolRefreshMs: Int,
                           blockTxTimeout: Int,
                           logTimings: Boolean)

object CandidateConfig {

  /** Mirrors the `stratum.candidate` block in `application.conf`; keep the two in step. */
  val Default: CandidateConfig = CandidateConfig(
    collateralPoolSize = 100,
    collateralRefreshInterval = 60000,
    blockTransactions = false,
    sources = Map(
      CandidateSourceConfig.Rollups -> CandidateSourceConfig.Default,
      CandidateSourceConfig.Emissions -> CandidateSourceConfig.Default),
    blockShare = 0.5,
    genesisWaitMs = 1500,
    mempoolRefreshMs = 10000,
    blockTxTimeout = 20000,
    logTimings = false
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
      sources = Default.sources.keys.map(name => name -> CandidateSourceConfig(config, name)).toMap,
      blockShare = double("blockShare", Default.blockShare),
      genesisWaitMs = int("genesisWaitMs", Default.genesisWaitMs),
      mempoolRefreshMs = int("mempoolRefreshMs", Default.mempoolRefreshMs),
      blockTxTimeout = int("blockTxTimeout", Default.blockTxTimeout),
      logTimings = bool("logTimings", Default.logTimings)
    )
  }
}
