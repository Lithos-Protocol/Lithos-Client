package state

import org.ergoplatform.appkit.{BlockchainContext, JavaHelpers}
import org.ergoplatform.restapi.client.ErgoTransaction
import work.lithos.mutations.{InputUTXO, UTXO}


case class BlockTx(
                    id: String,
                    inputs: Seq[TxInput],
                    dataInputs: Seq[TxInput],
                    outputs: Seq[TxOutput]
                  ) {
  def outputUTXOs(ctx: BlockchainContext): Seq[UTXO]      = outputs.map(_.toUTXO(ctx))
  def nextInputs(ctx: BlockchainContext): Seq[InputUTXO]  = outputs.map(_.toInput(ctx))
}

object BlockTx {
  def fromSync(et: ErgoTransaction): BlockTx = {
    BlockTx(
      et.getId,
      JavaHelpers.toIndexedSeq(et.getInputs).map(TxInput.fromSyncInput),
      JavaHelpers.toIndexedSeq(et.getDataInputs).map(TxInput.fromSyncDataInput),
      JavaHelpers.toIndexedSeq(et.getOutputs).map(TxOutput.fromSync)
    )
  }
}
