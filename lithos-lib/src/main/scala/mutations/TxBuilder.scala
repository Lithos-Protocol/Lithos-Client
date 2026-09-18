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
        val feeIdx = adjustedOutputs.indexWhere(_.contract.ergoTreeHex == Contract(ErgoTreePredef.feeProposition(_root_.mutations.NodeWallet.MINER_REWARD_DELAY)).ergoTreeHex)
        require(feeIdx >= 0, "sub-minimum change requires an explicit fee output")
        adjustedOutputs.patch(feeIdx, Seq(adjustedOutputs(feeIdx).addValue(totalChange)), 1)
      }else{
        adjustedOutputs
      }
    }

    // Consensus refuses an output created below the newest input, which a spend of an unconfirmed box
    // built for a later block produces. An output with no height of its own takes that height instead
    // of the tip, so chaining onto such a box needs no height passed down to every builder.
    val newest = TxBuilder.newestInput(inputs)
    val stamp = math.max(ctx.getHeight, newest)
    val stamped = outputsToUse.map(out => if (out.creationHeight.isEmpty) out.setCreationHeight(stamp) else out)
    val feeOut = if (fee > 0) Seq(UTXO.feeBox(fee).setCreationHeight(stamp)) else Seq.empty[UTXO]

    // The builder creates change at the tip and takes no height for it. When an input sits above the
    // tip, a probe build on a fresh builder finds that change, and it is planned here as outputs at
    // `stamp`: the real build then balances exactly and adds no change of its own.
    val change =
      if (newest <= ctx.getHeight) Seq.empty[UTXO]
      else complete(ctx.newTxBuilder(), stamped ++ feeOut, changeAddress, adjustedBurn)
        .getOutputs.asScala.toVector.drop(stamped.size + feeOut.size)
        .map(out => UTXO(Contract(out.getErgoTree), out.getValue, out.getTokens.asScala.toSeq.map(Token.ergo))
          .setCreationHeight(stamp))

    val completed = complete(uTxB, stamped ++ feeOut ++ change, changeAddress, adjustedBurn)
    val actual = completed.getOutputs.asScala.toVector
    require(actual.size >= stamped.size, "completed transaction omitted a planned output")
    stamped.zip(actual).foreach { case (planned, output) =>
      val expected = planned.toOutBox(ctx)
      require(output.getValue == expected.getValue && output.getErgoTree == expected.getErgoTree &&
        output.getTokens == expected.getTokens && output.getRegisters == expected.getRegisters &&
        output.getCreationHeight == expected.getCreationHeight,
        "completed transaction changed a planned output or protocol position")
    }
    // An output its builder pinned keeps that height, so one pinned below the newest input is refused
    // here rather than by the node.
    val lowest = actual.map(_.getCreationHeight).min
    require(lowest >= newest,
      s"an output is created at $lowest, below the newest input's $newest, which consensus refuses " +
        s"(tip ${ctx.getHeight})")
    val extra = actual.drop(stamped.size)
    val changeTree = changeAddress.toErgoContract.getErgoTree
    val feeTree = ErgoTreePredef.feeProposition(_root_.mutations.NodeWallet.MINER_REWARD_DELAY)
    require(extra.forall(o => o.getErgoTree == changeTree || (fee > 0 && o.getErgoTree == feeTree)),
      "completed transaction sent change to an unintended recipient")
    require(extra.filter(_.getErgoTree == feeTree).foldLeft(0L)((n, b) => Math.addExact(n, b.getValue)) == fee,
      "completed transaction changed the requested fee")
    Eip27Adjustment.validate(completed, ctx.getNetworkType)
    completed
  }

  /** One build on `builder`, which cannot be reused: it takes its change address and burn only once. */
  private def complete(builder: UnsignedTransactionBuilder, planned: Seq[UTXO], changeAddress: Address,
                       burn: Seq[Token]): UnsignedTransaction = {
    val uTx = builder
      .addInputs(inputs.map(_.toFullInput): _*)
      .addDataInputs(dataInputs.map(_.input): _*)
      .addOutputs(planned.map(_.toOutBox(ctx)): _*)
      .sendChangeTo(changeAddress)
    if (burn.nonEmpty)
      uTx.tokensToBurn(burn.map(_.toErgo): _*)
    uTx.build()
  }

}

object TxBuilder {
  def apply(ctx: BlockchainContext): TxBuilder = {
    new TxBuilder(ctx)
  }

  /**
   * The newest creation height among `inputs`, and so the lowest height any output of a spend of them
   * may carry. Zero when there are none.
   */
  def newestInput(inputs: Seq[InputUTXO]): Int =
    inputs.foldLeft(0)((height, input) => math.max(height, input.input.getCreationHeight))
}
