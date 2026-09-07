package work.lithos
package mutations

import org.ergoplatform.{ErgoScriptPredef, ErgoTreePredef}
import org.ergoplatform.appkit.{Address, BlockchainContext, Parameters, PreHeader, UnsignedTransaction, UnsignedTransactionBuilder}

import scala.collection.JavaConverters.seqAsJavaListConverter
import scala.collection.JavaConverters._

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

  /**
   * Complete the output plan and build the unsigned transaction.
   *
   * The EIP-27 adjustment runs first, before change is computed, because on mainnet it appends the
   * pay-to-re-emission output and moves the burned tokens out of change — both of which change what
   * the inputs have left to give. The completed transaction is validated against the same invariant
   * before it is returned, so a builder that reshaped the outputs cannot slip past it.
   */
  def buildTx(fee: Long, changeAddress: Address, burntTokens: Seq[Token] = Seq.empty[Token]): UnsignedTransaction = {
    val (adjustedOutputs, adjustedBurn) = Eip27Adjustment.adjust(inputs, outputs, burntTokens, fee, ctx.getNetworkType)
    val totalIn = inputs.foldLeft(0L)((n, b) => Math.addExact(n, b.value))
    val totalOut = adjustedOutputs.foldLeft(fee)((n, b) => Math.addExact(n, b.value))
    val totalChange = Math.subtractExact(totalIn, totalOut)
    require(totalChange >= 0, "inputs do not cover the completed output plan")
    val outputsToUse = {
      if(totalChange < Parameters.MinChangeValue && totalChange != 0){
        val feeIdx = adjustedOutputs.indexWhere(_.contract.ergoTreeHex == Contract(ErgoTreePredef.feeProposition(720)).ergoTreeHex)
        require(feeIdx >= 0, "sub-minimum change requires an explicit fee output")
        adjustedOutputs.patch(feeIdx, Seq(adjustedOutputs(feeIdx).addValue(totalChange)), 1)
      }else{
        adjustedOutputs
      }
    }

    val uTx = uTxB
      .addInputs(inputs.map(_.toFullInput):_*)
      .addDataInputs(dataInputs.map(_.input):_*)
      .addOutputs(outputsToUse.map(_.toOutBox(ctx)): _*)
      .sendChangeTo(changeAddress)

    if(fee > 0)
      uTx.fee(fee)

    if(adjustedBurn.nonEmpty)
      uTx.tokensToBurn(adjustedBurn.map(_.toErgo): _*)

    val completed = uTx.build()
    val actual = completed.getOutputs.asScala.toVector
    require(actual.size >= outputsToUse.size, "completed transaction omitted a planned output")
    outputsToUse.zip(actual).foreach { case (planned, output) =>
      val expected = planned.toOutBox(ctx)
      require(output.getValue == expected.getValue && output.getErgoTree == expected.getErgoTree &&
        output.getTokens == expected.getTokens && output.getRegisters == expected.getRegisters &&
        output.getCreationHeight == expected.getCreationHeight,
        "completed transaction changed a planned output or protocol position")
    }
    val extra = actual.drop(outputsToUse.size)
    val changeTree = changeAddress.toErgoContract.getErgoTree
    val feeTree = ErgoTreePredef.feeProposition(720)
    require(extra.forall(o => o.getErgoTree == changeTree || (fee > 0 && o.getErgoTree == feeTree)),
      "completed transaction sent change to an unintended recipient")
    require(extra.filter(_.getErgoTree == feeTree).foldLeft(0L)((n, b) => Math.addExact(n, b.getValue)) == fee,
      "completed transaction changed the requested fee")
    Eip27Adjustment.validate(completed, ctx.getNetworkType)
    completed
  }

}

object TxBuilder {
  def apply(ctx: BlockchainContext): TxBuilder = {
    new TxBuilder(ctx)
  }
}
