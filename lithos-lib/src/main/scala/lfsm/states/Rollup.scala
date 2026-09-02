package lfsm.states

import lfsm.LFSMPhase
import org.bouncycastle.util.encoders.Hex

/** The reads a rollup's R7 and box value answer, shared by the loaded and the resident form. */
trait RollupStateView {
  def state: RollupInfoState
  def value: Long

  /** Taken from the state so the phase and the meaning of its three slots can never disagree. */
  def phase: LFSMPhase = state.phase

  /** Height the current phase began at, absent once the rollup is paying out. */
  def currentPeriod: Option[Long] =
    if (phase == LFSMPhase.PAYOUT) None else Some(state.periodStart)

  /** Refundable ERG the box holds for its submitters, on top of the reward. */
  def totalBond: Long = state.totalBond

  /**
   * The distributable ERG. Before payout it is whatever the box holds beyond its bond ledger, which
   * is the figure the evaluation transform has to write into the payout box's state.
   */
  def totalReward: Long =
    if (phase == LFSMPhase.PAYOUT) state.totalErgReward else value - state.totalBond
}

/**
 * Small, always-resident state for one tracked rollup.
 *
 * The digest is copied into an immutable hexadecimal value so callers can route and filter rollups
 * without retaining, loading, or accidentally mutating the authenticated dictionary.
 */
final case class RollupMetadata(numMiners: Int,
                                totalScore: BigInt,
                                state: RollupInfoState,
                                value: Long,
                                startHeight: Int,
                                hasMiner: Boolean,
                                evaluated: Boolean,
                                blockId: String,
                                utxoId: String,
                                dictionaryDigest: String) extends UTXOState with RollupStateView {
  def materialize(dictionary: AuthenticatedDictionaryView): Rollup = {
    require(Hex.toHexString(dictionary.digest) == dictionaryDigest,
      s"Rollup $blockId dictionary digest does not match its metadata")
    Rollup(dictionary, numMiners, totalScore, state, value, startHeight, hasMiner, evaluated,
      blockId, utxoId)
  }
}

/** A materialized rollup. It is disposable and may be reconstructed from a checkpoint and journal. */
final case class Rollup(dictionary: AuthenticatedDictionaryView,
                        numMiners: Int,
                        totalScore: BigInt,
                        state: RollupInfoState,
                        value: Long,
                        startHeight: Int,
                        hasMiner: Boolean,
                        evaluated: Boolean = false,
                        blockId: String,
                        utxoId: String) extends UTXOState with RollupStateView {

  def metadata: RollupMetadata =
    RollupMetadata(numMiners, totalScore, state, value, startHeight, hasMiner, evaluated, blockId,
      utxoId, Hex.toHexString(dictionary.digest))
}
