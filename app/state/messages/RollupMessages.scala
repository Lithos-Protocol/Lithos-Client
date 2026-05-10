package state.messages

import lfsm.states.NISPTree
import state.messages.SyncMessages.Transform

object RollupMessages {

  case class Genesis(tree: NISPTree, blockInfo: BlockInfo)

  case class RollupTransform(blockInfo: BlockInfo, tx: BlockTx) extends Transform {
    val input: TxInput = tx.inputs.head
    val output: TxOutput = tx.outputs.head

    override def toString: String = s"RollupTransform(${blockInfo.height}: ${input.id} => ${output.id})"
  }

  case class StopTracking(utxoId: String, blockId: String)

  case class RemoveRollup(blockId: String, reason: String)

}
