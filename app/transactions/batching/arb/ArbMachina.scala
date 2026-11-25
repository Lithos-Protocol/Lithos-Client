package transactions.batching.arb

import configs.{ArbConfig, BatchingConfig, CandidateSourceConfig, LithosDexBatchingConfig}
import play.api.Configuration
import transactions.batching.ergodex.{MachinaFill, MachinaKind, MachinaOrder}

import java.util.concurrent.atomic.AtomicReference
import javax.inject.{Inject, Singleton}

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
 * The arb stage's side of Machina Finance orders. ERG/token orders a LithosDex ERG pool pays more for than ErgoDEX are
 * filled fee-less in this miner's own block, priced and built by [[MachinaFill]]. A token/token grid leaves its gap in
 * its base token, so it runs on the arb stake: the fill carries the stake through, and a second transaction sells the
 * gap for ERG through the base token's deepest ERG pool, returns the stake whole and sends the ERG on as profit.
 */
object ArbMachina {

  /** A token/token grid's fill, and the sale of its gap for ERG that follows it; `profit` is the ERG that leaves. */
  final case class GridCycle(fill: MachinaFill, sale: ArbSwap) {
    def profit: Long = sale.amountOut
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
          .flatMap(pool => MachinaFill.best(order.routedThrough(pool.nft), pool))
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
        .flatMap(pool => MachinaFill.best(order.routedThrough(pool.nft), pool).filter(_.gap >= minGap).map(f => pool.nft -> f.gap))
        .sortBy { case (nft, gap) => (-gap, nft) }.headOption
        .filter { case (_, gap) => throughErgoDex.get(order.boxId).forall(gap > _) }
        .map { case (nft, gap) => (order.boxId, nft, gap) }
    }.sortBy { case (id, _, gap) => (-gap, id) }.toVector
  }
}
