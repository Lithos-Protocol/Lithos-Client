package state.messages

import org.ergoplatform.appkit.BlockchainContext
import work.lithos.mutations.{InputUTXO, UTXO}


case class BlockTx(
                    id: String,
                    inputs: Seq[TxInput],
                    dataInputs: Seq[TxInput],
                    outputs: Seq[TxOutput]
                  ) {
  def outputUTXOs(ctx: BlockchainContext): Seq[UTXO]      = outputs.map(_.toUTXO(ctx))
  def nextInputs(ctx: BlockchainContext): Seq[InputUTXO]  = outputs.map(_.toInput(ctx))
  override def hashCode(): Int = java.util.Arrays.hashCode(id.toCharArray)

  override def equals(obj: Any): Boolean =
    obj match {
      case t: BlockTx =>
        t.id == id
      case _ =>
        false
    }
}
