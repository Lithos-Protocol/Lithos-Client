package state.messages

import lfsm.states.MinerTree
import plasmadex.states.LiquidityState
import state.messages.SyncMessages.Transform
import work.lithos.plasma.collections.LocalPlasmaMap

object DictionaryMessages {

  case class DictionaryTransform(blockInfo: BlockInfo, tx: BlockTx, tokenId: String) extends Transform {
    val input: TxInput = tx.inputs.head
    val output: TxOutput = tx.outputs.head

    override def toString: String = s"DictionaryTransform(${blockInfo.height}: ${input.id} => ${output.id})"
  }

  case class InitialMDState(height: Int, minerDictionary: MinerTree) {
    override def toString: String = s"InitialMDState($height: ${minerDictionary.utxoId})"
  }

  case class InitialLiqState(height: Int, liquidityState: LiquidityState) {
    override def toString: String = s"InitialLiqState($height: ${liquidityState.utxoId})"
  }
}
