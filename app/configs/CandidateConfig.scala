package configs

import play.api.{ConfigLoader, Configuration}

/**
 * Tuning for [[mining.CandidateBuilder]] — the genesis transaction, the collateral boxes it is
 * built from, and how much of this miner's own block goes to transactions that pay no fee. Those
 * transactions are built by [[transactions.rollups.TransactionProcessor]] and
 * [[transactions.emissions.EmissionHandler]], not here.
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
 * @param maxBlockTxs               Ceiling on those insertions. They pay no fee, so every slot spent
 *                                  here is block space not earning from someone else's transaction —
 *                                  which is why the default is small.
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
 */
case class CandidateConfig(collateralPoolSize: Int,
                           collateralRefreshInterval: Int,
                           blockTransactions: Boolean,
                           maxBlockTxs: Int,
                           genesisWaitMs: Int,
                           mempoolRefreshMs: Int,
                           blockTxTimeout: Int)

object CandidateConfig {

  /** Mirrors the `stratum.candidate` block in `application.conf`; keep the two in step. */
  val Default: CandidateConfig = CandidateConfig(
    collateralPoolSize = 100,
    collateralRefreshInterval = 60000,
    blockTransactions = false,
    maxBlockTxs = 5,
    genesisWaitMs = 1500,
    mempoolRefreshMs = 10000,
    blockTxTimeout = 20000
  )

  def apply(config: Configuration): CandidateConfig = {
    def int(key: String, fallback: Int): Int =
      config.getOptional(s"stratum.candidate.$key")(ConfigLoader.intLoader).getOrElse(fallback)

    def bool(key: String, fallback: Boolean): Boolean =
      config.getOptional(s"stratum.candidate.$key")(ConfigLoader.booleanLoader).getOrElse(fallback)

    CandidateConfig(
      collateralPoolSize = int("collateralPoolSize", Default.collateralPoolSize),
      collateralRefreshInterval = int("collateralRefreshInterval", Default.collateralRefreshInterval),
      blockTransactions = bool("blockTransactions", Default.blockTransactions),
      maxBlockTxs = int("maxBlockTxs", Default.maxBlockTxs),
      genesisWaitMs = int("genesisWaitMs", Default.genesisWaitMs),
      mempoolRefreshMs = int("mempoolRefreshMs", Default.mempoolRefreshMs),
      blockTxTimeout = int("blockTxTimeout", Default.blockTxTimeout)
    )
  }
}
