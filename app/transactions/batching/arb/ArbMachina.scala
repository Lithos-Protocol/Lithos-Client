package transactions.batching.arb

import configs.{ArbConfig, BatchingConfig, CandidateSourceConfig, LithosDexBatchingConfig}
import mutations.NodeWallet
import org.ergoplatform.appkit.{BlockchainContext, SignedTransaction}
import org.ergoplatform.sdk.ErgoId
import play.api.Configuration
import transactions.batching.Batcher
import transactions.batching.ergodex.{MachinaContracts, MachinaFill, MachinaKind, MachinaOrder}
import work.lithos.mutations.{Contract, InputUTXO, Token, TxBuilder, UTXO}

import java.util.concurrent.atomic.AtomicReference
import javax.inject.{Inject, Singleton}
import scala.util.Try

/**
 * Machina orders the ErgoDEX batcher passes to the arb stage, which fills them in this miner's own block: the
 * ERG/token orders a LithosDex ERG pool pays more for than any ErgoDEX pool, left out of the batcher's runs, and
 * the token/token grids, which need the arb stake. The batcher's build for a block offers them; the arb stage's
 * build for the same block takes them. Only while arb is active: otherwise the batcher routes every ERG/token
 * order through ErgoDEX and reads no token/token grid.
 *
 * @param deniedPools pool NFTs either batcher never trades against, which no order is routed to
 */
@Singleton
class MachinaHandoff(val active: Boolean, val deniedPools: Set[String]) {

  @Inject def this(config: Configuration) =
    this(ArbConfig.active(config), (BatchingConfig(config, CandidateSourceConfig.ErgoDex).deniedPools ++
      LithosDexBatchingConfig(config).batching.deniedPools).map(_.toLowerCase))

  /** The block height the orders were offered for, and the orders, ERG/token ones routed through their pool. */
  private val held = new AtomicReference(Option.empty[(Int, Vector[MachinaOrder])])

  /** Replaces what is offered with `orders`, for the block at `blockHeight` only. */
  def offer(blockHeight: Int, orders: Seq[MachinaOrder]): Unit =
    held.set(Some(blockHeight -> orders.take(MachinaHandoff.MaxOrders).toVector))

  /** The orders offered for `blockHeight`, or none when the last offer was for another block. */
  def at(blockHeight: Int): Vector[MachinaOrder] =
    held.get.collect { case (height, orders) if height == blockHeight => orders }.getOrElse(Vector.empty)

  /** Box ids of orders the arb stage could not build, until the batcher takes them; at most [[MachinaHandoff.MaxOrders]]. */
  private val refused = new AtomicReference(Set.empty[String])

  /** Notes that the arb stage could not build the order `orderId`, so the batcher leaves it out for a while. */
  def refuse(orderId: String): Unit =
    refused.updateAndGet(ids => if (ids.size >= MachinaHandoff.MaxOrders) ids else ids + orderId)

  /** The orders refused since the last call, which are then forgotten here. */
  def takeRefused(): Set[String] = refused.getAndSet(Set.empty)
}

object MachinaHandoff {
  /** Most orders held for one block, and most refusals held: the ErgoDEX batcher tracks 512 orders by default. */
  final val MaxOrders = 512

  /** A handoff for a client with arb off, which the batcher never offers to. */
  def idle: MachinaHandoff = new MachinaHandoff(active = false, Set.empty)
}

/**
 * Machina Finance orders filled through pools the arb stage holds.
 *
 * An ERG/token order needs no capital: the pool at input 0 and its successor at output 0, the order at input 1 and
 * its child at output 1, the ERG gap at output 2. The order's contract reads only itself and the output its context
 * variable names, and every pool reads only itself at input 0 and its successor at output 0, so a fill through a
 * LithosDex pool is laid out as one through ErgoDEX.
 *
 * A token/token grid trades its base token, at index 0, against its own token, so a fill through a token/token pool
 * leaves its gap in the base token. It runs on the arb stake: the fill carries the stake through at input 1 and
 * output 1, which takes the gap, with the order at input 2 and its child at output 2; a second transaction sells the
 * gap for ERG through the base token's deepest ERG pool, returns the stake whole and sends the ERG on as profit.
 */
object ArbMachina {

  /** Where a token/token grid's child goes, after the pool's successor and the carry. */
  final val GridChildIndex: Int = 2

  /**
   * One fill of `order` priced against `swap.pool`: `tokens` of the order's token bought from the box and sold
   * into the pool when `boxSells`, else bought from the pool and sold to the box. `gap` is what is left between the
   * two in the order's base asset, ERG or a grid's base token, which this miner keeps. The child holds `childValue`
   * nanoERG, `childBase` of a grid's base token and `childTokens` of the order's token, at the owner's script
   * when `toOwner`.
   */
  final case class Fill(order: MachinaOrder, swap: ArbSwap, tokens: Long, boxSells: Boolean, gap: Long,
                        childValue: Long, childTokens: Long, toOwner: Boolean, childBase: Long = 0L)

  /** A token/token grid's fill, and the sale of its gap for ERG that follows it; `profit` is the ERG that leaves. */
  final case class GridCycle(fill: Fill, sale: ArbSwap) {
    def profit: Long = sale.amountOut
  }

  /** What an order trades its token against: ERG for an ERG/token order, else the grid's base token. */
  def baseOf(order: MachinaOrder): ArbAsset = order.base.fold(ArbAsset.Erg)(ArbAsset.token)

  /** The least of its base asset a child may keep: a token/token grid's must hold one unit, being its first token. */
  private def floorOf(order: MachinaOrder, price: Long): Long =
    if (order.base.isEmpty) MachinaContracts.childFloor(order, price) else 1L

  /** `tokens` units of `order`'s token moved through `pool` on the given side, if the order and the pool allow it. */
  def at(order: MachinaOrder, pool: ArbPool, tokens: Long, boxSells: Boolean): Option[Fill] = {
    val (base, quote) = (baseOf(order), ArbAsset.token(order.tokenId))
    if (tokens <= 0L || base == quote || !pool.trades(base) || !pool.trades(quote)) None
    else Try {
      val quoteIn = pool.x == quote
      val held = BigInt(order.baseHeld)
      def fill(swap: ArbSwap, gap: BigInt, kept: BigInt, childTokens: Long, toOwner: Boolean): Option[Fill] =
        if (!gap.isValidLong || !kept.isValidLong) None
        else if (order.base.isEmpty) Some(Fill(order, swap, tokens, boxSells, gap.toLong, kept.toLong, childTokens, toOwner))
        else Some(Fill(order, swap, tokens, boxSells, gap.toLong, order.box.value, childTokens, toOwner, kept.toLong))
      if (boxSells) order.ask.flatMap { price =>
        if (tokens > order.tokens) None
        else pool.swap(tokens, xIn = quoteIn).flatMap { swap =>
          val paid = BigInt(tokens) * price
          // A limit box emptied of its tokens pays out to its owner; a grid box is recreated, empty or not
          fill(swap, BigInt(swap.amountOut) - paid, held + paid, order.tokens - tokens,
            toOwner = order.kind == MachinaKind.Limit && tokens == order.tokens)
        }
      } else order.bid.flatMap { price =>
        pool.swapFor(tokens, xIn = !quoteIn).flatMap { swap =>
          val received = BigInt(tokens) * price
          if (held - received < floorOf(order, price)) None
          else fill(swap, received - swap.amountIn, held - received, Math.addExact(order.tokens, tokens), toOwner = false)
        }
      }
    }.toOption.flatten
  }

  /**
   * The fill of `order` through `pool` that leaves the most of its base asset, if any leaves some. A side is
   * skipped when the pool's rate for a vanishing trade does not beat the order's price, since no size can then
   * leave a gap. Each side's gap is concave in the size, so it is sized by ternary search and the last few sizes
   * tried one by one.
   */
  def best(order: MachinaOrder, pool: ArbPool): Option[Fill] = {
    val (base, quote) = (baseOf(order), ArbAsset.token(order.tokenId))
    if (base == quote || !pool.trades(base) || !pool.trades(quote)) None
    else {
      val quoteIn = pool.x == quote
      val sells = order.ask.filter(price => order.tokens > 0L && pool.marginal(quoteIn) > price.toDouble)
        .flatMap(_ => sized(order.tokens)(at(order, pool, _, boxSells = true)))
      val buys = order.bid.filter(price => pool.marginal(!quoteIn) * price > 1.0)
        .flatMap(price => sized((order.baseHeld - floorOf(order, price)) / price)(at(order, pool, _, boxSells = false)))
      (sells.toSeq ++ buys).filter(_.gap > 0L).sortBy(f => (-f.gap, f.tokens)).headOption
    }
  }

  /** The best of `fill` over sizes 1 to `most`, by gap, ties to the smaller. */
  private def sized(most: Long)(fill: Long => Option[Fill]): Option[Fill] =
    if (most < 1L) None
    else {
      def gap(tokens: Long): Long = fill(tokens).fold(Long.MinValue)(_.gap)
      var (lo, hi) = (1L, most)
      while (hi - lo > 2L) {
        val third = (hi - lo) / 3L
        val (m1, m2) = (lo + third, hi - third)
        if (gap(m1) < gap(m2)) lo = m1 + 1L else hi = m2
      }
      (lo to hi).iterator.flatMap(fill(_)).foldLeft(Option.empty[Fill]) { (kept, f) =>
        if (kept.forall(k => f.gap > k.gap)) Some(f) else kept
      }
    }

  /**
   * The best fill of the token/token grid `order` through any token/token pool of its pair among `pools`, with
   * the sale of its gap through its base token's deepest ERG pool among them, by the ERG the sale returns.
   */
  def gridCycle(order: MachinaOrder, pools: Iterable[ArbPool]): Option[GridCycle] =
    order.base.filter(_ => order.kind == MachinaKind.TokenGrid).flatMap { id =>
      val base = ArbAsset.token(id)
      pools.filter(p => p.x.isErg && p.y == base).toVector.sortBy(p => (-ergDepth(p), p.nft)).headOption.flatMap { erg =>
        pools.filter(p => !p.x.isErg && p.trades(base)).toVector
          .flatMap(pool => best(order.routedThrough(pool.nft), pool))
          .flatMap(fill => erg.swap(fill.gap, xIn = false).map(GridCycle(fill, _)))
          .sortBy(c => (-c.profit, c.fill.swap.pool.nft)).headOption
      }
    }

  /** The ERG an ERG pool trades with, which ranks a token's ERG pools by depth. */
  private def ergDepth(pool: ArbPool): Long = pool match {
    case ErgoDexArbPool(state) if state.xId.isEmpty => state.reservesX
    case LithosDexArbPool(state, _, _) => state.reservesX
    case _ => 0L
  }

  /**
   * The orders a LithosDex ERG pool of their token fills for more than ErgoDEX does, each with that pool's NFT
   * and its gap, best first. `throughErgoDex` is the gap each order's best ErgoDEX pool leaves, for the orders
   * one fills. A LithosDex gap under `minGap` does not count, since its fill pays for a box of its own.
   */
  def preferLithosDex(orders: Seq[MachinaOrder], throughErgoDex: Map[String, Long], pools: Seq[ArbPool],
                      minGap: Long): Vector[(String, String, Long)] = {
    val byToken = pools.filter(_.x.isErg).groupBy(_.y)
    orders.filter(_.base.isEmpty).flatMap { order =>
      byToken.getOrElse(ArbAsset.token(order.tokenId), Seq.empty)
        .flatMap(pool => best(order.routedThrough(pool.nft), pool).filter(_.gap >= minGap).map(f => pool.nft -> f.gap))
        .sortBy { case (nft, gap) => (-gap, nft) }.headOption
        .filter { case (_, gap) => throughErgoDex.get(order.boxId).forall(gap > _) }
        .map { case (nft, gap) => (order.boxId, nft, gap) }
    }.sortBy { case (id, _, gap) => (-gap, id) }.toVector
  }

  /**
   * Signs the ERG/token `fill` against `poolBox`, fee-less, the gap to `takingsTo` at output 2. Built for
   * `blockHeight`, or for the newest input's height when that is later. Throws when the fill is not priced against
   * `poolBox`, the order box does not read back as its id, or a contract refuses it.
   */
  def build(ctx: BlockchainContext, wallet: NodeWallet, fill: Fill, poolBox: InputUTXO, takingsTo: Contract,
            blockHeight: Int): SignedTransaction = {
    val swap = fill.swap
    require(fill.order.base.isEmpty, s"order ${fill.order.boxId} trades a token against a token, which needs a carry")
    val boxInput = orderInput(ctx, fill, poolBox)
    val spent = Seq(swap.pool.input(poolBox),
      MachinaFill.input(boxInput, fill.order, fill.boxSells, MachinaFill.ChildIndex))
    val height = math.max(blockHeight, TxBuilder.newestInput(spent))
    val poolOut = swap.pool.successor(poolBox, swap.after).setCreationHeight(height)
    require(poolOut.value - poolBox.value == swap.poolErgDelta,
      s"the successor moves ${poolOut.value - poolBox.value} nanoERG where the fill moves ${swap.poolErgDelta}")
    val child = MachinaFill.child(boxInput, fill.order, fill.childValue, fill.childTokens, fill.toOwner)
      .setCreationHeight(height)
    val takings = UTXO(takingsTo, fill.gap).setCreationHeight(height)
    signed(ctx, wallet, spent, Seq(poolOut, child, takings))
  }

  /**
   * Signs the token/token grid `fill` against `poolBox`, fee-less, with `carry` passed through at input 1 and
   * output 1 holding the gap besides what it held. Throws as [[build]] does.
   */
  def gridLeg(ctx: BlockchainContext, wallet: NodeWallet, fill: Fill, poolBox: InputUTXO, carry: InputUTXO,
              blockHeight: Int): SignedTransaction = {
    val swap = fill.swap
    val base = fill.order.base.getOrElse(throw new IllegalArgumentException(
      s"order ${fill.order.boxId} trades a token against ERG, which needs no carry"))
    val boxInput = orderInput(ctx, fill, poolBox)
    val spent = Seq(swap.pool.input(poolBox), carry,
      MachinaFill.input(boxInput, fill.order, fill.boxSells, GridChildIndex))
    val height = math.max(blockHeight, TxBuilder.newestInput(spent))
    val poolOut = swap.pool.successor(poolBox, swap.after).setCreationHeight(height)
    require(poolOut.value == poolBox.value, s"a token/token pool's ERG moved from ${poolBox.value} to ${poolOut.value}")
    val held = ArbLeg.holding(Seq(carry))
    val tokens = if (held.tokens.exists(_.id.toString == base))
      held.tokens.map(t => if (t.id.toString == base) t.copy(amount = Math.addExact(t.amount, fill.gap)) else t)
    else held.tokens :+ Token(ErgoId.create(base), fill.gap)
    val carryOut = UTXO(carry.contract, held.value, tokens).setCreationHeight(height)
    val child = MachinaFill.child(boxInput, fill.order, fill.childValue, fill.childTokens, fill.toOwner, fill.childBase)
      .setCreationHeight(height)
    signed(ctx, wallet, spent, Seq(poolOut, carryOut, child))
  }

  /** The order's input, once its box reads back as its own id, and `fill` is priced against `poolBox`. */
  private def orderInput(ctx: BlockchainContext, fill: Fill, poolBox: InputUTXO): InputUTXO = {
    require(poolBox.id.toString == fill.swap.pool.boxId,
      s"fill priced against pool box ${fill.swap.pool.boxId}, not ${poolBox.id}")
    val boxInput = fill.order.boxAsInput(ctx)
    require(boxInput.id.toString == fill.order.boxId, s"the order box ${fill.order.boxId} reads back as ${boxInput.id}")
    boxInput
  }

  /** Signing runs every input's script, so a fill the pool's or the order's contract refuses fails here. */
  private def signed(ctx: BlockchainContext, wallet: NodeWallet, inputs: Seq[InputUTXO],
                     outputs: Seq[UTXO]): SignedTransaction = {
    val unsigned = TxBuilder(ctx).setInputs(inputs: _*).setOutputs(outputs: _*).buildTx(0L, wallet.p2pk)
    Batcher.withNodeMinimums(ctx, wallet.sign(unsigned))
  }
}
