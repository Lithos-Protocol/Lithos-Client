package transactions.batching.lithosdex

import node.model.NodeBox
import state.synchronization.CompleteMempool
import transactions.batching.BatchingMempool
import work.lithos.mutations.Contract

/**
 * A redeem order placed by the transaction that first claims its provision's fees: the one unconfirmed
 * LithosDex transaction a run chains from whose inputs are not all a wallet's.
 *
 * {{{
 *   IN  vault, provision, wallet...    OUT vault, provision, redeem order, change and fee...
 * }}}
 *
 * Neither claim path reads HEIGHT or a data input, so a claim the mempool accepted stays valid until one
 * of its inputs is spent.
 */
final class LDClaimPlacement(poolNft: String, vaultNft: String, vaultTree: String, provisionTree: String,
                             provToken: String) {

  private val feeTree = Contract.FEE.ergoTreeHex

  /**
   * Whether `tx` is exactly that shape, with [[BatchingMempool.uncontested]] inputs:
   *   - the vault at input 0, one provision at input 1, and only [[BatchingMempool.spentByKey]] boxes after
   *   - the vault at output 0 and the same provision at output 1
   *   - at output 2 a redeem order on this pool holding that provision's owner NFT
   *   - after it, only key-spent boxes and the fee
   */
  def accepts(tx: CompleteMempool.MempoolTx, inputBoxes: Map[String, NodeBox],
              spenders: BatchingMempool.Spenders): Boolean = {
    val ins = tx.body.inputs.flatMap(input => inputBoxes.get(input.boxId))
    val outs = tx.body.outputs
    ins.size == tx.body.inputs.size && ins.size >= 3 && outs.size >= 4 &&
      BatchingMempool.uncontested(tx, spenders) &&
      isVault(ins.head) && isProvision(ins(1)) && ins.drop(2).forall(BatchingMempool.spentByKey) &&
      isVault(outs.head) && isProvision(outs(1)) && owner(outs(1)).isDefined && owner(outs(1)) == owner(ins(1)) &&
      redeemOrder(outs(2)).exists(order => owner(outs(1)).contains(order.ownerNft)) &&
      outs.drop(3).forall(box => BatchingMempool.spentByKey(box) || box.ergoTree == feeTree)
  }

  /** The provision `tx` leaves beside `order`, when `tx` placed the order at output 2 of this shape. */
  def provisionFor(tx: CompleteMempool.MempoolTx, order: LithosDexOrder.Redeem): Option[NodeBox] =
    if (!tx.body.outputs.lift(2).exists(_.boxId == order.boxId)) None
    else tx.body.outputs.lift(1).filter(box => isProvision(box) && owner(box).contains(order.ownerNft))

  private def isVault(box: NodeBox): Boolean =
    box.ergoTree == vaultTree && box.assets.headOption.exists(a => a.tokenId == vaultNft && a.amount == 1L)

  private def isProvision(box: NodeBox): Boolean =
    box.ergoTree == provisionTree && box.assets.size == 1 &&
      box.assets.head.tokenId == provToken && box.assets.head.amount == 1L

  /** R6, the owner NFT's id, when it is a 32-byte `Coll[Byte]`. */
  private def owner(box: NodeBox): Option[String] =
    box.additionalRegisters.get(6).map(_.toLowerCase)
      .filter(r => r.length == 68 && r.startsWith(LDBoxes.OwnerRegisterPrefix)).map(_.drop(4))

  private def redeemOrder(box: NodeBox): Option[LithosDexOrder.Redeem] =
    LithosDexOrder.parse(box).collect { case order: LithosDexOrder.Redeem if order.poolNft == poolNft => order }
}
