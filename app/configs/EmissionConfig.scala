package configs

import lfsm.LFSMHelpers
import org.ergoplatform.appkit.Parameters
import play.api.{ConfigLoader, Configuration}

/**
 * Everything under `emission` — the collateral queue: joining it with this miner's own funds, and
 * driving it forward with Activate and Clear. Separate from [[CandidateConfig]] and the rollup
 * settings because this is maintenance that runs on its own timer whether or not this miner ever
 * mines a block.
 *
 * @param enabled               Drive the collateral queue at all.
 * @param queueInterval         How often to look at the head of the queue, in ms.
 * @param maxQueueSpends        How many emission spends to chain in one funded pass. Each respends
 *                              the previous one's successor, so this is how deep into the queue one
 *                              pass reaches.
 * @param candidateActivates    How many Activates this miner's own block carries when no Clear is in
 *                              reach. A reachable Clear lifts this to the emissions source's maxTxs.
 * @param maxChainDepth         How many unconfirmed spends of the emission box to follow. Broadcasts
 *                              always build on the newest box; following the spends is what lets a
 *                              queue pass activate queue boxes that joins still in the mempool made.
 * @param queueScanLimit        How many queue boxes to page through when looking for the head.
 * @param txFee                 Fee on emission transactions, in nanoERG.
 * @param autoCollateralize     Put this miner's own ERG and LIT into the queue.
 * @param collateralizeInterval How often to check whether more collateral is wanted, in ms.
 * @param maxJoinsPerRun        How many queue boxes to create in one pass.
 * @param maxOwnCollateral      How many of this miner's own boxes may be live at once across the queue,
 *                              live collateral boxes and the active set. Each locks 2.915 ERG plus
 *                              its permit until it is mined against, so this is the spend ceiling.
 * @param maxLenderKeys         How many wallet addresses may be dedicated to lending. The active set
 *                              holds distinct KEYS, so one live box needs one address; a key that is
 *                              already in the set produces a box nobody can activate, only clear —
 *                              and clearing forfeits its principal and permit.
 * @param maxPermitPerJoin      The most LIT, in base units, to post as a permit on one box. The
 *                              permit rises with the backlog, so this is the price at which this miner
 *                              stops queueing rather than a fee.
 * @param priorityFeeNanoErgs   What each automatically created position adds above the floor, in
 *                              nanoERG, so a position locks `2.915 ERG + this`. A share goes to
 *                              whichever miner spends the box, which is what buys it priority; the
 *                              rest is added to the pool. 0 posts at the floor and offers nothing,
 *                              and anything else is at least `RollupProtocol.MinPriorityFee`. Above
 *                              `RollupProtocol.breakEvenPriorityFee` the coinbase no longer repays
 *                              the principal and the difference has to come from the block's fees.
 */
case class EmissionConfig(enabled: Boolean,
                          queueInterval: Int,
                          maxQueueSpends: Int,
                          candidateActivates: Int,
                          maxChainDepth: Int,
                          queueScanLimit: Int,
                          txFee: Long,
                          autoCollateralize: Boolean,
                          collateralizeInterval: Int,
                          maxJoinsPerRun: Int,
                          maxOwnCollateral: Int,
                          maxLenderKeys: Int,
                          maxPermitPerJoin: Long,
                          priorityFeeNanoErgs: Long)

object EmissionConfig {

  /** Mirrors the `emission` block in `application.conf`; keep the two in step. */
  val Default: EmissionConfig = EmissionConfig(
    enabled = true,
    queueInterval = 240000,
    maxQueueSpends = 4,
    candidateActivates = 5,
    maxChainDepth = 16,
    queueScanLimit = 500,
    txFee = Parameters.MinFee,
    autoCollateralize = false,
    collateralizeInterval = 900000,
    maxJoinsPerRun = 5,
    maxOwnCollateral = 10,
    // Must not exceed the shipped `node.numAddresses`, which is also 32: keys past the prover's
    // last index receive permit returns and coinbases that nothing can spend.
    maxLenderKeys = 32,
    // The thermostat's own ceiling, so the default never blocks a join on price alone.
    maxPermitPerJoin = LFSMHelpers.PERMIT_CEIL,
    // Post at the floor. A priority fee is opt-in: it raises what every position locks up.
    priorityFeeNanoErgs = 0L
  )

  def apply(config: Configuration): EmissionConfig = {
    def int(key: String, fallback: Int): Int =
      config.getOptional(s"emission.$key")(ConfigLoader.intLoader).getOrElse(fallback)

    def long(key: String, fallback: Long): Long =
      config.getOptional(s"emission.$key")(ConfigLoader.longLoader).getOrElse(fallback)

    def bool(key: String, fallback: Boolean): Boolean =
      config.getOptional(s"emission.$key")(ConfigLoader.booleanLoader).getOrElse(fallback)

    EmissionConfig(
      enabled = bool("enabled", Default.enabled),
      queueInterval = int("queueInterval", Default.queueInterval),
      maxQueueSpends = int("maxQueueSpends", Default.maxQueueSpends),
      candidateActivates = int("candidateActivates", Default.candidateActivates),
      maxChainDepth = int("maxChainDepth", Default.maxChainDepth),
      queueScanLimit = int("queueScanLimit", Default.queueScanLimit),
      txFee = long("txFee", Default.txFee),
      autoCollateralize = bool("autoCollateralize", Default.autoCollateralize),
      collateralizeInterval = int("collateralizeInterval", Default.collateralizeInterval),
      maxJoinsPerRun = int("maxJoinsPerRun", Default.maxJoinsPerRun),
      maxOwnCollateral = int("maxOwnCollateral", Default.maxOwnCollateral),
      maxLenderKeys = int("maxLenderKeys", Default.maxLenderKeys),
      maxPermitPerJoin = long("maxPermitPerJoin", Default.maxPermitPerJoin),
      priorityFeeNanoErgs = long("priorityFeeNanoErgs", Default.priorityFeeNanoErgs)
    )
  }
}
