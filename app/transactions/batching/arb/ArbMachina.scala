package transactions.batching.arb

import configs.{ArbConfig, BatchingConfig, CandidateSourceConfig, LithosDexBatchingConfig}
import mutations.NodeWallet
import org.ergoplatform.appkit.{BlockchainContext, SignedTransaction}
import play.api.Configuration
import transactions.batching.Batcher
import transactions.batching.ergodex.{MachinaContracts, MachinaFill, MachinaKind, MachinaOrder}
import work.lithos.mutations.{Contract, InputUTXO, TxBuilder, UTXO}

import java.util.concurrent.atomic.AtomicReference
import javax.inject.{Inject, Singleton}
import scala.util.Try

/**
 * Machina orders the ErgoDEX batcher found a LithosDex ERG pool paying more for than any ErgoDEX pool, left out
 * of its runs and passed to the arb stage, which fills them in this miner's own block. The batcher's build for a
 * block offers them; the arb stage's build for the same block takes them. Only while arb is active: otherwise
 * the batcher routes every order through ErgoDEX.
 *
 * @param deniedPools pool NFTs either batcher never trades against, which no order is routed to
 */
@Singleton
class MachinaHandoff(val active: Boolean, val deniedPools: Set[String]) {

  @Inject def this(config: Configuration) =
    this(ArbConfig.active(config), (BatchingConfig(config, CandidateSourceConfig.ErgoDex).deniedPools ++
      LithosDexBatchingConfig(config).batching.deniedPools).map(_.toLowerCase))

  /** The block height the orders were offered for, and the orders, each routed through its LithosDex pool. */
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
 * Machina Finance orders filled through a pool the arb stage holds, with no capital: the pool at input 0 and its
 * successor at output 0, the order at input 1 and its child at output 1, the ERG gap at output 2. The order's
 * contract reads only itself and the output its context variable names, and every pool reads only itself at
 * input 0 and its successor at output 0, so a fill through a LithosDex pool is laid out as one through ErgoDEX.
 */
object ArbMachina {

  /**
   * One fill of `order` priced against `swap.pool`: `tokens` of the order's token bought from the box and sold
   * into the pool when `boxSells`, else bought from the pool and sold to the box. `gap` is the ERG left between
   * the two, which this miner keeps. The child holds `childValue` and `childTokens`, at the owner's script when
   * `toOwner`.
   */
  final case class Fill(order: MachinaOrder, swap: ArbSwap, tokens: Long, boxSells: Boolean, gap: Long,
                        childValue: Long, childTokens: Long, toOwner: Boolean)

  /** `tokens` units of `order`'s token moved through `pool` on the given side, if the order and the pool allow it. */
  def at(order: MachinaOrder, pool: ArbPool, tokens: Long, boxSells: Boolean): Option[Fill] =
    if (tokens <= 0L || !pool.x.isErg || pool.y != ArbAsset.token(order.tokenId)) None
    else Try {
      if (boxSells) order.ask.flatMap { price =>
        if (tokens > order.tokens) None
        else pool.swap(tokens, xIn = false).flatMap { swap =>
          val paid = BigInt(tokens) * price
          val child = BigInt(order.box.value) + paid
          val gap = BigInt(swap.amountOut) - paid
          // A limit box emptied of its tokens pays out to its owner; a grid box is recreated, empty or not
          if (!child.isValidLong || !gap.isValidLong) None
          else Some(Fill(order, swap, tokens, boxSells = true, gap.toLong, child.toLong, order.tokens - tokens,
            toOwner = order.kind == MachinaKind.Limit && tokens == order.tokens))
        }
      } else order.bid.flatMap { price =>
        pool.swapFor(tokens, xIn = true).flatMap { swap =>
          val received = BigInt(tokens) * price
          val child = BigInt(order.box.value) - received
          if (child < MachinaContracts.childFloor(order, price)) None
          else Some(Fill(order, swap, tokens, boxSells = false, (received - swap.amountIn).toLong, child.toLong,
            Math.addExact(order.tokens, tokens), toOwner = false))
        }
      }
    }.toOption.flatten

  /**
   * The fill of `order` through `pool` that leaves the most ERG, if any leaves some. A side is skipped when the
   * pool's rate for a vanishing trade does not beat the order's price, since no size can then leave a gap. Each
   * side's gap is concave in the size, so it is sized by ternary search and the last few sizes tried one by one.
   */
  def best(order: MachinaOrder, pool: ArbPool): Option[Fill] = {
    val sells = order.ask.filter(price => order.tokens > 0L && pool.marginal(xIn = false) > price.toDouble)
      .flatMap(_ => sized(order.tokens)(at(order, pool, _, boxSells = true)))
    val buys = order.bid.filter(price => pool.marginal(xIn = true) * price > 1.0)
      .flatMap(price => sized((order.box.value - MachinaContracts.childFloor(order, price)) / price)(
        at(order, pool, _, boxSells = false)))
    (sells.toSeq ++ buys).filter(_.gap > 0L).sortBy(f => (-f.gap, f.tokens)).headOption
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
   * The orders a LithosDex ERG pool of their token fills for more than ErgoDEX does, each with that pool's NFT
   * and its gap, best first. `throughErgoDex` is the gap each order's best ErgoDEX pool leaves, for the orders
   * one fills. A LithosDex gap under `minGap` does not count, since its fill pays for a box of its own.
   */
  def preferLithosDex(orders: Seq[MachinaOrder], throughErgoDex: Map[String, Long], pools: Seq[ArbPool],
                      minGap: Long): Vector[(String, String, Long)] = {
    val byToken = pools.filter(_.x.isErg).groupBy(_.y)
    orders.flatMap { order =>
      byToken.getOrElse(ArbAsset.token(order.tokenId), Seq.empty)
        .flatMap(pool => best(order.routedThrough(pool.nft), pool).filter(_.gap >= minGap).map(f => pool.nft -> f.gap))
        .sortBy { case (nft, gap) => (-gap, nft) }.headOption
        .filter { case (_, gap) => throughErgoDex.get(order.boxId).forall(gap > _) }
        .map { case (nft, gap) => (order.boxId, nft, gap) }
    }.sortBy { case (id, _, gap) => (-gap, id) }.toVector
  }

  /**
   * Signs `fill` against `poolBox`, fee-less, the gap to `takingsTo` at output 2. Built for `blockHeight`, or for
   * the newest input's height when that is later. Throws when the fill is not priced against `poolBox`, the order
   * box does not read back as its id, or a contract refuses it.
   */
  def build(ctx: BlockchainContext, wallet: NodeWallet, fill: Fill, poolBox: InputUTXO, takingsTo: Contract,
            blockHeight: Int): SignedTransaction = {
    val swap = fill.swap
    require(poolBox.id.toString == swap.pool.boxId, s"fill priced against pool box ${swap.pool.boxId}, not ${poolBox.id}")
    val order = fill.order
    val boxInput = order.boxAsInput(ctx)
    require(boxInput.id.toString == order.boxId, s"the order box ${order.boxId} reads back as ${boxInput.id}")
    val spent = Seq(swap.pool.input(poolBox),
      MachinaFill.input(boxInput, order, fill.boxSells, MachinaFill.ChildIndex))
    val height = math.max(blockHeight, TxBuilder.newestInput(spent))
    val poolOut = swap.pool.successor(poolBox, swap.after).setCreationHeight(height)
    require(poolOut.value - poolBox.value == swap.poolErgDelta,
      s"the successor moves ${poolOut.value - poolBox.value} nanoERG where the fill moves ${swap.poolErgDelta}")
    val child = MachinaFill.child(boxInput, order, fill.childValue, fill.childTokens, fill.toOwner)
      .setCreationHeight(height)
    val takings = UTXO(takingsTo, fill.gap).setCreationHeight(height)
    val unsigned = TxBuilder(ctx)
      .setInputs(spent: _*)
      .setOutputs(poolOut, child, takings)
      .buildTx(0L, wallet.p2pk)
    // Signing runs the pool's and the order's scripts, so a fill either contract refuses fails here
    Batcher.withNodeMinimums(ctx, wallet.sign(unsigned))
  }
}
