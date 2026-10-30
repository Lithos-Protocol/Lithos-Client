package support

import node.model.{IndexedTransaction, NodeBox}
import node.rest.NodeCodecs
import work.lithos.mutations.Contract

import scala.io.Source

/**
 * Real Machina Finance fills and orders from mainnet, each the node's response verbatim. Every fill spends an
 * ErgoDEX native pool at input 0 and the order at input 1, with no capital of the filler's, and pays the pool,
 * the order's child, the miner and the filler, in that order.
 */
object MachinaFixtures {

  final case class Fill(name: String, tx: IndexedTransaction) {
    def pool: NodeBox = tx.inputs.head.box
    def order: NodeBox = tx.inputs(1).box
    def poolAfter: NodeBox = tx.outputs.head.box
    def child: NodeBox = tx.outputs(1).box
    def minerFee: Long = tx.outputs.map(_.box).filter(_.ergoTree == Contract.FEE.ergoTreeHex).map(_.value).sum
    /** ERG the two legs left: the filler's output and the miner fee together. */
    def gap: Long = pool.value + order.value - poolAfter.value - child.value
    /** Tokens moved, and whether they left the order. */
    def tokensMoved: Long = math.abs(childTokens - orderTokens)
    def boxSells: Boolean = childTokens < orderTokens
    private def orderTokens: Long = order.assets.headOption.map(_.amount).getOrElse(0L)
    private def childTokens: Long = child.assets.headOption.map(_.amount).getOrElse(0L)
  }

  private def json(name: String) = {
    val source = Source.fromResource(s"machina/$name.json")
    val text = try source.mkString finally source.close()
    new com.google.gson.JsonParser().parse(text).getAsJsonObject
  }

  private def load(name: String): Fill = Fill(name, NodeCodecs.indexedTransaction(json(name)))

  /** A current limit order buying 2,828 tokens at 35,000, left holding 1,020,000 nanoERG. Block 1,904,970. */
  lazy val limitBuys: Fill = load("limit-buys")
  /** A grid selling 3,131 tokens at its ask. Block 1,905,739. */
  lazy val gridSells: Fill = load("grid-sells")
  /** A grid buying 3,207 tokens at its bid. Block 1,905,007. */
  lazy val gridBuys: Fill = load("grid-buys")

  lazy val fills: Seq[Fill] = Seq(limitBuys, gridSells, gridBuys)

  /** An unfilled current limit order selling 5,000 units of `limitBuys`'s token at 45,233. Block 1,903,888. */
  lazy val limitSellOrder: NodeBox = NodeCodecs.indexedBox(json("limit-sell-order")).box

  /** A fill of the older limit contract, whose orders are no longer filled. Block 1,892,431. */
  lazy val oldLimit: Fill = load("old-limit-fill")
}
