package state.messages

import lfsm.states.MinerTree
import state.messages.SyncMessages.Transform

object DictionaryMessages {

  case class DictionaryTransform(blockInfo: BlockInfo, tx: BlockTx) extends Transform {
    val input: TxInput = tx.inputs.head
    val output: TxOutput = tx.outputs.head

    override def toString: String = s"DictionaryTransform(${blockInfo.height}: ${input.id} => ${output.id})"
  }

  case class InitialMDState(height: Int, minerDictionary: MinerTree) {
    override def toString: String = s"InitialMDState($height: ${minerDictionary.utxoId})"
  }

}
