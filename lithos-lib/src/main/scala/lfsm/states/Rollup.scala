package lfsm.states

import lfsm.LFSMPhase
import org.bouncycastle.util.encoders.Hex

/**
 * Small, always-resident state for one tracked rollup.
 *
 * The digest is copied into an immutable hexadecimal value so callers can route and filter rollups
 * without retaining, loading, or accidentally mutating the authenticated dictionary.
 */
final case class RollupMetadata(numMiners: Int,
                                totalScore: BigInt,
                                currentPeriod: Option[Long],
                                totalReward: Long,
                                startHeight: Int,
                                hasMiner: Boolean,
                                phase: LFSMPhase,
                                evaluated: Boolean,
                                blockId: String,
                                utxoId: String,
                                dictionaryDigest: String) extends UTXOState {
  def materialize(dictionary: AuthenticatedDictionaryView): Rollup = {
    require(Hex.toHexString(dictionary.digest) == dictionaryDigest,
      s"Rollup $blockId dictionary digest does not match its metadata")
    Rollup(dictionary, numMiners, totalScore, currentPeriod, totalReward, startHeight, hasMiner,
      phase, evaluated, blockId, utxoId)
  }
}

/** A materialized rollup. It is disposable and may be reconstructed from a checkpoint and journal. */
final case class Rollup(dictionary: AuthenticatedDictionaryView,
                        numMiners: Int,
                        totalScore: BigInt,
                        currentPeriod: Option[Long],
                        totalReward: Long,
                        startHeight: Int,
                        hasMiner: Boolean,
                        phase: LFSMPhase,
                        evaluated: Boolean = false,
                        blockId: String,
                        utxoId: String) extends UTXOState {

  def metadata: RollupMetadata =
    RollupMetadata(numMiners, totalScore, currentPeriod, totalReward, startHeight, hasMiner, phase,
      evaluated, blockId, utxoId, Hex.toHexString(dictionary.digest))
}
