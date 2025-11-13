package work.lithos
package mutations

import org.ergoplatform.appkit.Parameters

object StdMutators {
  def newBoxAt(contract: Contract, amount: Long, outputIdx: Int): Mutator = {
    val output = UTXO(
      contract,
      amount
    )
    new Mutator {
      override val preReqs: Seq[TxContext => Boolean] = Seq(
        (tCtx: TxContext) => tCtx.outputs.length > outputIdx
      )

      override protected def mutation(tCtx: TxContext): Seq[UTXO] = tCtx.outputsWithReplacements(output, outputIdx)
    }

  }

  /**
   * Create singleton pattern for input at a certain index
   * @param index index where singleton will be recycled
   * @param singletonIdx index of singleton token, defaults to 0
   * @return Mutator which creates output utxo at same index as the input, with all of the inputs attributes
   */
  def singletonWithIndex(index: Int, singletonIdx: Int = 0): Mutator = {
    new Mutator {
      override val preReqs: Seq[TxContext => Boolean] = Seq(
        (tCtx: TxContext) => tCtx.inputs.length > index,
        (tCtx: TxContext) => tCtx.outputs.length > index,
        (tCtx: TxContext) => tCtx.inputs(index).tokens(singletonIdx).amount == 1,
      )

      override protected def mutation(tCtx: TxContext): Seq[UTXO] = {
        tCtx.outputsWithReplacements(tCtx.inputs(index).toUTXO, index)
      }
    }
  }

  /**
   * Creates numOutputs number of dummy outputs to start transaction building
   * @param numOutputs Number of dummy outputs to create
   * @return Mutator which new takes new (no outputs) tCtx and creates numOutputs dummy outputs for it with
   *         TRUE contract and One Erg value
   */
  def withNOutputs(numOutputs: Int): Mutator = {
    new Mutator {
      override val preReqs: Seq[TxContext => Boolean] = Seq(
        (tCtx: TxContext) => tCtx.outputs.isEmpty
      )

      override protected def mutation(tCtx: TxContext): Seq[UTXO] = {
        (0 until numOutputs).map(_ => UTXO(Contract.SIGMA_TRUE, UTXO.ONE_ERG))
      }
    }
  }
}
