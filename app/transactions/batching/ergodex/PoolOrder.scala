package transactions.batching.ergodex

import node.model.NodeBox
import org.ergoplatform.appkit.BlockchainContext
import work.lithos.mutations.InputUTXO

/**
 * Work an ErgoDEX pool run executes: an [[ErgoDexOrder]], which names its pool, or a [[MachinaOrder]], routed
 * through a pool chosen for it. Either spends the pool at input 0 and itself at input 1, so both chain through
 * one pool in one run.
 */
trait PoolOrder {
  def box: NodeBox

  def boxId: String = box.boxId

  /** The pool this order is executed against. */
  def poolNft: String

  /** Most an execution broadcast for this order may pay the miner. */
  def maxMinerFee: Long

  /** Reconstructs the order input from the reported box fields. */
  def boxAsInput(ctx: BlockchainContext): InputUTXO = {
    import node.MutationConversions._
    box.toInputUTXO(ctx)
  }
}
