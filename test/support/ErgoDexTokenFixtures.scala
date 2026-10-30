package support

import node.model.{IndexedTransaction, NodeBox}
import node.rest.NodeCodecs
import sigma.ast.{Constant, ErgoTree, SType}
import work.lithos.mutations.Contract

import scala.io.Source

/**
 * Real ErgoDEX token pool v1 executions from mainnet, each the node's transaction response verbatim, and the
 * published order trees for orders a spec builds itself. Every execution spends the pool at input 0 and the
 * order at input 1, and pays the pool, the owner's reward, the executor and the miner, in that order.
 */
object ErgoDexTokenFixtures {

  final case class Execution(name: String, tx: IndexedTransaction) {
    def pool: NodeBox = tx.inputs.head.box
    def order: NodeBox = tx.inputs(1).box
    def poolAfter: NodeBox = tx.outputs.head.box
    def reward: NodeBox = tx.outputs(1).box
    def minerFee: Long = tx.outputs.map(_.box).filter(_.ergoTree == Contract.FEE.ergoTreeHex).map(_.value).sum
    /** What the executor kept and paid the miner: the order's ERG less the owner's. */
    def revenue: Long = order.value - reward.value
  }

  private def text(path: String): String = {
    val source = Source.fromResource(path)
    try source.mkString finally source.close()
  }

  private def load(name: String): Execution =
    Execution(name, NodeCodecs.indexedTransaction(
      new com.google.gson.JsonParser().parse(text(s"ergodex/t2t/$name.json")).getAsJsonObject))

  /** Sells X for Y, block 1,853,807. Reserves past 2^53, so a lossy parse would show here. */
  lazy val swapXIn: Execution = load("swap-x-in")
  /** Sells Y for X, block 1,853,147. */
  lazy val swapYIn: Execution = load("swap-y-in")
  /** A deposit with Y in excess, block 1,835,367. */
  lazy val deposit: Execution = load("deposit")
  /** Block 1,842,549. */
  lazy val redeem: Execution = load("redeem")

  lazy val executions: Seq[Execution] = Seq(swapXIn, swapYIn, deposit, redeem)

  /** A published v1 tree (`swap`, `deposit` or `redeem`) with the given constants replaced. */
  def published(name: String, terms: Map[Int, Constant[SType]] = Map.empty): ErgoTree = {
    val tree = ErgoTree.fromHex(text(s"ergodex/$name-t2t-v1.hex").trim)
    ErgoTree(tree.header, tree.constants.zipWithIndex.map { case (c, i) => terms.getOrElse(i, c) }, tree.root.right.get)
  }

  /** `tree` with the given constants replaced, for a live order's own tree. */
  def withTerms(treeHex: String, terms: Map[Int, Constant[SType]]): ErgoTree = {
    val tree = ErgoTree.fromHex(treeHex)
    ErgoTree(tree.header, tree.constants.zipWithIndex.map { case (c, i) => terms.getOrElse(i, c) }, tree.root.right.get)
  }
}
