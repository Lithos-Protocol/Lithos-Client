package work.lithos
package mutations

import org.ergoplatform.appkit.{Address, BlockchainContext, JavaHelpers, PreHeader, UnsignedTransaction, UnsignedTransactionBuilder}

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
    val uTx = uTxB
      .boxesToSpend(JavaHelpers.toJList(inputs.map(_.toFullInput).toIndexedSeq))
      .withDataInputs(JavaHelpers.toJList(dataInputs.map(_.input).toIndexedSeq))
      .outputs(outputs.map(_.toOutBox(ctx)): _*)
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
