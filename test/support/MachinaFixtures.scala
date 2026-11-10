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

  /**
   * The one token/token grid order open on mainnet: 500 FAKU units, its base, then 200 of its token `ebb40eca…`,
   * asking 50 FAKU units a unit and bidding 10. It holds 162,720 nanoERG, the minimum for its own size. Box
   * `84f59301ac49…`, block 1,785,999, still open at 1,913,935.
   */
  lazy val tokenGridOrder: NodeBox = NodeCodecs.indexedBox(json("t2t-grid-order")).box

  /** The ErgoDEX FAKU/`ebb40eca…` token pool, FAKU as X. Box `dd4c3c8c2b6e…`, block 1,912,895. */
  lazy val fakuCigPool: NodeBox = NodeCodecs.indexedBox(json("faku-cig-t2t-pool")).box

  /** The ErgoDEX ERG/FAKU pool, FAKU's deepest ERG pool. Box `730d34e5a5a7…`, block 1,913,076. */
  lazy val fakuErgoDexPool: NodeBox = NodeCodecs.indexedBox(json("faku-ergodex-pool")).box

  /**
   * Two fills of FAKU/SigUSD token/token grids, each by a bot paying ERG through an ErgoDEX ERG pool at input 0, the
   * grid at input 1 and its child at output 1: one buying a SigUSD unit for 1,097 FAKU units (block 1,896,838), one
   * selling a SigUSD unit for 569 (block 1,895,970).
   */
  lazy val tokenGridBuys: Fill = load("t2t-grid-buys")
  lazy val tokenGridSells: Fill = load("t2t-grid-sells")

  /** The context variables a fill's input at `input` was spent with, as the node reports them. */
  def contextVars(fill: Fill, input: Int): Map[String, String] = {
    import scala.collection.JavaConverters._
    val extension = json(fill.name).getAsJsonArray("inputs").get(input).getAsJsonObject
      .getAsJsonObject("spendingProof").getAsJsonObject("extension")
    extension.entrySet().asScala.map(e => e.getKey -> e.getValue.getAsString).toMap
  }

  /**
   * The LithosDex ERG/FAKU pool, the one LithosDex ERG pool of the fixtures' token, under its own deployment.
   * Box `54307bbc9a30…`, block 1,912,934.
   */
  lazy val lithosDexFakuPool: NodeBox = NodeCodecs.indexedBox(json("ld-faku-pool")).box

  /**
   * [[lithosDexFakuPool]] holding 30 ERG in its reserves at `price` nanoERG a unit, its pending fees kept, under
   * the id its new contents give it. Its script is the deployment's own, so a fill through it signs as through the
   * live pool.
   */
  def lithosDexFakuPoolAt(ctx: org.ergoplatform.appkit.BlockchainContext, price: Double): NodeBox = {
    import node.MutationConversions._
    val box = lithosDexFakuPool
    val pool = lithosdex.LDLiquidityPool(box.toInputUTXO(ctx))
    val erg = 30000000000L
    val moved = box.copy(value = erg + pool.pendingX,
      assets = box.assets.updated(1, box.assets(1).copy(amount = (erg / price).toLong + pool.pendingY)))
    moved.copy(boxId = moved.toInputUTXO(ctx).id.toString)
  }
}
