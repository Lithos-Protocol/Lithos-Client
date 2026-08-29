package state.messages

import lfsm.states.Rollup
import state.messages.MempoolMessages.{MempoolChain, MempoolRollupState}
import state.messages.SyncMessages.Transform

object RollupMessages {

  case class Genesis(tree: Rollup, blockInfo: BlockInfo)

  case class RollupTransform(blockInfo: BlockInfo, tx: BlockTx) extends Transform {
    val input: TxInput = tx.inputs.head
    val output: TxOutput = tx.outputs.head

    override def toString: String = s"RollupTransform(${blockInfo.height}: ${input.id} => ${output.id})"
  }

  case class RemoveRollup(blockId: String, reason: String)
  // Safely updates evaluation status for a rollup synchronizer
  case class UpdateEvaluation(blockId: String)
  // Gets the current rollup state and returns RollupInfo
  case class GetCurrentRollup(blockId: String)

  sealed trait RollupInfo
  case class CurrentRollup(utxoId: String,
                           rollup: Rollup,
                           mempoolState: Option[MempoolRollupState]) extends RollupInfo
  case class NoRollupFound() extends RollupInfo
  /** The rollup exists, but its authenticated dictionary could not be made current for this request. */
  case class RollupUnavailable(reason: String) extends RollupInfo
}
