package state.messages

import lfsm.states.{MinerTree, NISPTree}
import plasmadex.states.LiquidityState
import state.messages.RollupMessages.RollupTransform
import state.messages.SyncMessages.Transform
import work.lithos.mutations.InputUTXO

object MempoolMessages {
  // Upcoming transformation to be used in mempool.
  case class MempoolTransform(tx: BlockTx) extends Transform{
    val input: TxInput = tx.inputs.head
    val output: TxOutput = tx.outputs.head
    override def toString: String = s"MempoolTransform(${input.id} => ${output.id})"

    override def hashCode(): Int = tx.hashCode()

    override def equals(obj: Any): Boolean =
      obj match {
        case t: MempoolTransform =>
          t.tx.id == tx.id
        case _ =>
          false
      }
  }

  /**
   * Chain of Mempool Transformations to be associated with a given UTXO
   * @param transforms Sequence of transforms representing a chain
   */
  case class MempoolChain(transforms: Seq[MempoolTransform]) {
    /**
     * @return ID of the first input of the first transform in this chain
     */
    def startId: String = transforms.head.input.id
    /**
     * @return ID of the first output of the last transform in this chain
     */
    def endId: String = transforms.last.output.id

    def applyTransform: Option[MempoolChain] = {
      if(transforms.size > 1){
        Some(MempoolChain(transforms.drop(1)))
      }else{
        None
      }
    }

    override def toString: String = s"MempoolChain($startId => $endId)"

    override def equals(obj: Any): Boolean =
      obj match {
        case chain: MempoolChain =>
          chain.transforms == this.transforms
        case _ =>
          false
      }
  }


  case object BuildRollupChains

  // Mempool States
  /**
   *  NOTE: If toBeRemoved is true, we should not assume the nispTree is accurate.
   *  TODO: Switch to option?
    */
  case class MempoolRollupState(asInput: InputUTXO, nispTree: NISPTree, toBeRemoved: Boolean = false)

  case class MempoolMDState(asInput: InputUTXO, minerTree: MinerTree)

  case class MempoolPDState(asInput: InputUTXO, liquidityState: LiquidityState)

}
