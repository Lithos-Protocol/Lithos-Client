package transactions.rollups

/**
 * A transform stub no longer matches the rollup it was built for: its period moved, or the box the
 * transform would spend is no longer in the phase it transforms out of, as when another miner's
 * transform reached the mempool first. An expected miss, not a fault.
 *
 * An IllegalArgumentException so the engine does not retry a batch that ends on one. The same stub
 * meets the same state, and the processor offers a fresh stub once the rollup moves.
 */
class TransformIneligibleException(e: String) extends IllegalArgumentException(e)
