package lfsm.states

import lfsm.LFSMPhase
import org.ergoplatform.appkit.ErgoValue
import org.ergoplatform.appkit.scalaapi._
import sigma.{Coll, Colls}

/**
 * R7 of every box in the rollup chain: exactly three Longs whose meaning shifts with the phase.
 *
 * The phase is held beside the values so a payout box's reward can never be read out as a height.
 * It is not encoded — the script the box sits under is what fixes the meaning. The length and the
 * element type are consensus-relevant, because every rollup contract reads these by index.
 */
final case class RollupInfoState(phase: LFSMPhase, values: Vector[Long]) {
  require(values.size == RollupInfoState.Size,
    s"a rollup state is exactly ${RollupInfoState.Size} longs, found ${values.size}")

  /** Height the current phase began at. */
  def periodStart: Long = timed(0, "period start")

  /** Height the rollup's own block was mined at, fixed at genesis and never moved. */
  def genesisBlockHeight: Long = timed(1, "genesis block height")

  /** Distributable ERG, bonds excluded. */
  def totalErgReward: Long = paying(0, "total ERG reward")

  /** Distributable LIT. */
  def totalLitReward: Long = paying(1, "total LIT reward")

  /** Refundable ERG held on behalf of submitters. The one slot every phase shares. */
  def totalBond: Long = values(2)

  /** The same three values read under a different phase, for a transition that carries them over. */
  def withPhase(next: LFSMPhase): RollupInfoState = copy(phase = next)

  def withValues(first: Long, second: Long, bond: Long): RollupInfoState =
    copy(values = Vector(first, second, bond))

  def withBond(bond: Long): RollupInfoState = copy(values = values.updated(2, bond))

  def ergoValue: ErgoValue[Coll[Long]] =
    ErgoValue.of(Colls.fromArray(values.toArray), scalaLongType)

  private def timed(index: Int, name: String): Long = {
    require(phase != LFSMPhase.PAYOUT, s"$name was read off a payout state, where slot $index is a reward")
    values(index)
  }

  private def paying(index: Int, name: String): Long = {
    require(phase == LFSMPhase.PAYOUT, s"$name was read off a $phase state, where slot $index is a height")
    values(index)
  }
}

object RollupInfoState {

  val Size: Int = 3

  def holding(periodStart: Long, genesisBlockHeight: Long, totalBond: Long): RollupInfoState =
    RollupInfoState(LFSMPhase.HOLDING, Vector(periodStart, genesisBlockHeight, totalBond))

  def evaluation(periodStart: Long, genesisBlockHeight: Long, totalBond: Long): RollupInfoState =
    RollupInfoState(LFSMPhase.EVAL, Vector(periodStart, genesisBlockHeight, totalBond))

  def payout(totalErgReward: Long, totalLitReward: Long, totalBond: Long): RollupInfoState =
    RollupInfoState(LFSMPhase.PAYOUT, Vector(totalErgReward, totalLitReward, totalBond))

  def fromValues(phase: LFSMPhase, values: Seq[Long]): RollupInfoState =
    RollupInfoState(phase, values.toVector)

  /**
   * Decodes a serialized R7. Throws on the wrong runtime type or the wrong length rather than
   * padding or truncating, because a rollup box whose state does not decode is one no contract in
   * the chain would have accepted.
   */
  def fromErgoValue(value: ErgoValue[_], phase: LFSMPhase): RollupInfoState = {
    val raw = value.getValue.asInstanceOf[Coll[_]].toArray.toVector
    fromValues(phase, raw.map {
      case long: Long => long
      case other => throw new IllegalArgumentException(
        s"a rollup state is a Coll[Long]; found an element of type ${other.getClass.getSimpleName}")
    })
  }
}
