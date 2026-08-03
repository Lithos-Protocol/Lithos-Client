package work.lithos
package mutations

import org.ergoplatform.{ErgoScriptPredef, ErgoTreePredef}
import org.ergoplatform.appkit.{Address, BlockchainContext, Parameters, PreHeader, UnsignedTransaction, UnsignedTransactionBuilder}

import scala.collection.JavaConverters.seqAsJavaListConverter

class TxBuilder(ctx: BlockchainContext){
  val uTxB: UnsignedTransactionBuilder = ctx.newTxBuilder()
  private var tCtx: TxContext = TxContext(ctx, Seq.empty[InputUTXO], Seq.empty[InputUTXO])

  def inputs: Seq[InputUTXO] = tCtx.inputs
  def dataInputs: Seq[InputUTXO] = tCtx.dataInputs
  def preHeader: PreHeader = uTxB.getPreHeader
  def outputs: Seq[UTXO] = tCtx.outputs

  def setInputs(nextInputs: InputUTXO*): TxBuilder = {
    tCtx = tCtx.copy(inputs = nextInputs)
    this
  }

  def setDataInputs(nextDataInputs: InputUTXO*): TxBuilder = {
    tCtx = tCtx.copy(dataInputs = nextDataInputs)
    this
  }

  def setOutputs(nextOutputs: UTXO*): TxBuilder = {
    tCtx = tCtx.copy(outputs = nextOutputs)
    this
  }

  def addOutputs(addedOutputs: Seq[UTXO]): TxBuilder = {
    tCtx = tCtx.copy(outputs = outputs ++ addedOutputs)
    this
  }

  def setPreHeader(preHeader: PreHeader): TxBuilder = {
    uTxB.preHeader(preHeader)
    this
  }

  def mutateOutputs: TxBuilder = {
    tCtx = {
      inputs.flatMap{
        i =>
          i.contract.mutators ++ i.addMutators
      }.foldLeft(tCtx) {
        (tCtx: TxContext, mut: Mutator) =>
          tCtx.withNewOutputs(mut.execute(tCtx))
      }
    }
    this
  }

  def buildTx(fee: Long, changeAddress: Address, burntTokens: Seq[Token] = Seq.empty[Token]): UnsignedTransaction = {
    val totalChange = inputs.map(_.value).sum - outputs.map(_.value).sum
    val outputsToUse = {
      if(totalChange < Parameters.MinChangeValue && totalChange != 0){
        val feeIdx = outputs.indexWhere(_.contract.ergoTreeHex == Contract(ErgoTreePredef.feeProposition(720)).ergoTreeHex)
        outputs.patch(feeIdx, Seq(outputs(feeIdx).addValue(totalChange)), 1)
      }else{
        outputs
      }
    }

    val uTx = uTxB
      .boxesToSpend(inputs.map(_.toFullInput).toIndexedSeq.asJava)
      .withDataInputs(dataInputs.map(_.input).toIndexedSeq.asJava)
      .outputs(outputsToUse.map(_.toOutBox(ctx)): _*)
      .sendChangeTo(changeAddress.getErgoAddress)

    if(fee > 0)
      uTx.fee(fee)

    if(burntTokens.nonEmpty)
      uTx.tokensToBurn(burntTokens.map(_.toErgo): _*)

    uTx.build()
  }

}

object TxBuilder {
  def apply(ctx: BlockchainContext): TxBuilder = {
    new TxBuilder(ctx)
  }
}
