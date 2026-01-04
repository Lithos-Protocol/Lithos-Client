package state.messages

import lfsm.NISPTree

object RollupMessages {

  case class Genesis(tree: NISPTree, blockInfo: BlockInfo)

  case class Transform(blockInfo: BlockInfo, tx: BlockTx) {
    val input: TxInput = tx.inputs.head
    val output: TxOutput = tx.outputs.head

    override def toString: String = s"RollupTransform(${blockInfo.height}: ${input.id} => ${output.id})"
  }

  case class StopTracking(utxoId: String, blockId: String)

  case class RemoveRollup(blockId: String, reason: String)

}
