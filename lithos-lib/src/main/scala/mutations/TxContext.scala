package work.lithos
package mutations

import org.ergoplatform.appkit.BlockchainContext

case class TxContext(ctx: BlockchainContext, inputs: Seq[InputUTXO], dataInputs: Seq[InputUTXO],
                    outputs: Seq[UTXO] = Seq.empty[UTXO]){
  def withNewOutputs(modOut: Seq[UTXO]): TxContext = this.copy(outputs = modOut)

  def outputsWithAdditions(utxos: UTXO*): Seq[UTXO] = {
    outputs++utxos
  }
  /**
   * Replaces single element in outputs with a new UTXO
   * @return New Output sequence with Output at given index replaced with a mutated output
   */
  def outputsWithReplacements(mutated: UTXO, idx: Int): Seq[UTXO] = {
    outputs.patch(idx, Seq(mutated), 1)
  }

  def outputsWithReplacements(mutated: Seq[UTXO], indices: Seq[Int]): Seq[UTXO] = {
    mutated.zip(indices).foldLeft(outputs){
      (z: Seq[UTXO], c: (UTXO, Int)) => z.patch(c._2, Seq(c._1), 1)
    }
  }

  override def equals(obj: Any): Boolean = {
    obj match {
      case tCtx: TxContext =>
        if(tCtx.inputs.length == this.inputs.length
          && tCtx.outputs.length == this.outputs.length
          && tCtx.dataInputs.length == this.dataInputs.length) {
          val inputsEqual = tCtx.inputs.zip(this.inputs).foldLeft(true){
            (z,x) =>
              x._1.id == x._2.id && z
          }
          val dataInputsEqual = tCtx.inputs.zip(this.dataInputs).foldLeft(true){
            (z,x) =>
              x._1.id == x._2.id && z
          }
          val outputsEqual    = tCtx.outputs.zip(this.outputs).foldLeft(true){
            (z,x) =>
              // If outputs have same dummy id, then their contents are the same
              x._1.toDummyInput(tCtx.ctx).id == x._2.toDummyInput(tCtx.ctx).id && z
          }
          inputsEqual && dataInputsEqual && outputsEqual
        }else
          false
      case _ => false
    }
  }

}

object TxContext {

}
