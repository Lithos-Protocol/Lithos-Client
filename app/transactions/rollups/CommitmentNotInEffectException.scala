package transactions.rollups

/**
 * No difficulty commitment is in force for the height a NISP would be judged against.
 *
 * Distinct from a missing data box, which is a registration or synchronisation state that resolves
 * on its own. This one cannot: the rollup's start height is fixed, and so is the commitment it is
 * measured against, so retrying the same rollup asks the same question and gets the same answer. A
 * rational miner stops tracking a rollup it can never submit to.
 */
class CommitmentNotInEffectException(e: String) extends IllegalStateException(e)
