package stats

import node.model.{NodeAsset, NodeBox, NodeRegisters}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.NetworkType
import state.messages.{BlockInfo, BlockTx, TxOutput}
import transactions.batching.ergodex.{ErgoDexOrder, ErgoDexPool, OrderKind}
import transactions.batching.lithosdex.{DexContracts, LDDeployments, LithosDexOrder}
import work.lithos.mutations.Contract

import scala.collection.concurrent.TrieMap

/** Recognizes the executed pool/order/output layout. Carried executor boxes are never counted again. */
private[stats] object BatchingStatistics {
  def poolProtocol(input: TxOutput, network: NetworkType): Option[String] = {
    if (input.ergoTree == transactions.batching.ergodex.ErgoDexContracts.NativePoolErgoTree) Some("ergodex")
    else if (lithosDexPool(input, network)) Some("lithosdex")
    else None
  }

  /** The pool contract's template as hex, which every LithosDex deployment's pool tree ends with. */
  private val poolTemplates = TrieMap.empty[NetworkType, String]

  /**
   * Any LithosDex deployment's pool, ERG:LIT included: a box whose contracts, built from its own ids,
   * reproduce its tree. The template suffix is checked first, so most boxes cost no build at all.
   */
  private def lithosDexPool(box: TxOutput, network: NetworkType): Boolean =
    box.ergoTree.endsWith(poolTemplates.getOrElseUpdate(network,
      Hex.toHexString(DexContracts(network).liquidityPool.ergoTree.template))) &&
      LDDeployments.verify(network, box.ergoTree, box.assets.map(t => t.id.toString -> t.amount)).isDefined
  def transactionFee(tx: BlockTx): BigInt = tx.outputs.filter(_.ergoTree == Contract.FEE.ergoTreeHex)
    .map(out => BigInt(out.value)).sum

  private def box(out: TxOutput): NodeBox = NodeBox(out.id, out.txId, out.value, out.index,
    out.creationHeight, out.ergoTree, out.assets.map(t => NodeAsset(t.id.toString, t.amount)),
    NodeRegisters(out.registers.zipWithIndex.map { case (value, index) => s"R${index + 4}" -> value }.toMap))

  /** `minerTree` is this client's own script; an execution whose takings went to it is this client's. */
  def read(block: BlockInfo, tx: BlockTx, network: NetworkType, minerTree: String): Vector[BatchingFee] = {
    val inputs = tx.inputs.map(in => block.inputBox(in.id))
    if (inputs.size < 2 || inputs.head.isEmpty || tx.outputs.size < 2) return Vector.empty
    val pool = inputs.head.get
    val next = tx.outputs.head
    val nft = pool.assets.headOption.filter(_.amount == 1L).map(_.id.toString)
    if (nft.isEmpty || next.ergoTree != pool.ergoTree ||
      !next.assets.headOption.exists(t => nft.contains(t.id.toString) && t.amount == 1L)) return Vector.empty

    def result(order: TxOutput, owner: String, rewardIndex: Int, provisionIn: BigInt,
                   provisionOut: BigInt, protocol: String, expected: Option[Long]): Vector[BatchingFee] = {
      tx.outputs.lift(rewardIndex).filter(_.ergoTree == owner).toVector.flatMap { reward =>
        // Conservation across the pool, order, provision and designated owner output measures the
        // amount left for execution, independently of any takings carried from earlier transactions.
        val gross = BigInt(pool.value) + order.value + provisionIn - next.value - provisionOut - reward.value
        val fee = transactionFee(tx)
        // Past the pool and the owner's reward, the takings box is what the executor keeps. The reward
        // is skipped so filling this client's own order is not mistaken for executing it.
        val local = tx.outputs.zipWithIndex.exists { case (out, i) => i != 0 && i != rewardIndex && out.ergoTree == minerTree }
        if (gross < 0 || expected.exists(BigInt(_) != gross) || fee > gross) Vector.empty
        else Vector(BatchingFee(tx.id, order.id, protocol, gross.toString, fee.toString, local))
      }
    }
    ErgoDexPool.native(box(pool)) match {
      case Some(before) =>
        inputs(1).toVector.flatMap { input =>
          ErgoDexOrder.parse(box(input)).filter(o => nft.contains(o.poolNft)).toVector.flatMap { order =>
            val movement = order.contract.kind match {
              case OrderKind.SwapSell =>
                val quote = before.outputAmount(order.baseAmount, baseIsErg = true)
                Some(quote -> before.afterSwap(order.baseAmount, quote, baseIsErg = true))
              case OrderKind.SwapBuy =>
                val quote = before.outputAmount(order.baseAmount, baseIsErg = false)
                Some(quote -> before.afterSwap(order.baseAmount, quote, baseIsErg = false))
              case OrderKind.Deposit => before.deposit(order.baseAmount,
                input.assets.find(_.id.toString == before.yId).map(_.amount).getOrElse(0L))
              case OrderKind.Redeem => before.redeem(order.baseAmount)
            }
            movement.toVector.flatMap { case (quote, after) =>
              val actual = ErgoDexPool.native(box(next))
              if (!actual.exists(p => p.copy(boxId = after.boxId) == after) || quote < order.minQuoteAmount) Vector.empty
              else result(input, Hex.toHexString(order.redeemerPropBytes), 1, 0, 0, "ergodex", Some(order.dexFee(quote)))
            }
          }
        }
      case None if lithosDexPool(pool, network) =>
        inputs.slice(1, 3).flatten.toVector.flatMap { input =>
          LithosDexOrder.parse(box(input)).filter(o => nft.contains(o.poolNft)).toVector.flatMap { order =>
            order match {
              case _: LithosDexOrder.Deposit if inputs(1).contains(input) && tx.outputs.size > 2 =>
                result(input, order.terms.redeemerTree, 2, 0, BigInt(tx.outputs(1).value), "lithosdex", Some(order.terms.executorFee))
              case _: LithosDexOrder.Redeem if inputs.lift(2).flatten.contains(input) && inputs(1).isDefined =>
                result(input, order.terms.redeemerTree, 1, BigInt(inputs(1).get.value), 0, "lithosdex", Some(order.terms.executorFee))
              case _: LithosDexOrder.SwapSell | _: LithosDexOrder.SwapBuy if inputs(1).contains(input) =>
                result(input, order.terms.redeemerTree, 1, 0, 0, "lithosdex", Some(order.terms.executorFee))
              case _ => Vector.empty
            }
          }
        }
      case _ => Vector.empty
    }
  }
}
