package state.messages

import lfsm.states.MinerTree
import work.lithos.plasma.collections.LocalPlasmaMap

object DictionaryMessages {

 case class MinerDictionary(persistent: LocalPlasmaMap[Array[Byte], Array[Byte]], mt: MinerTree)

  case class DictionaryTransform(blockInfo: BlockInfo, tx: BlockTx, tokenId: String) {
    val input: TxInput = tx.inputs.head
    val output: TxOutput = tx.outputs.head

    override def toString: String = s"DictionaryTransform(${blockInfo.height}: ${input.id} => ${output.id})"
  }

  case class InitialState(height: Int, minerDictionary: MinerTree) {
    override def toString: String = s"InitialState($height: ${minerDictionary.utxoId})"
  }
}
