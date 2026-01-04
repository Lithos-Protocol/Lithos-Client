package state.messages

import lfsm.MinerTree

object DictionaryMessages {

  case class MinerDictionary(tree: MinerTree, nextStates: Seq[MinerTree])

  case class DictionaryTransform(blockInfo: BlockInfo, tx: BlockTx, tokenId: String) {
    val input: TxInput = tx.inputs.head
    val output: TxOutput = tx.outputs.head

    override def toString: String = s"DictionaryTransform(${blockInfo.height}: ${input.id} => ${output.id})"
  }
}
