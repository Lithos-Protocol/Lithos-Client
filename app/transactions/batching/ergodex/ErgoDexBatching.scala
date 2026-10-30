package transactions.batching.ergodex

import configs.BatchingConfig
import node.model.NodeBox
import state.synchronization.CompleteMempool
import transactions.batching.{Batcher, BatchingMempool}

/** ErgoDEX order selection. The mempool checks every adapter shares are in [[BatchingMempool]]. */
object ErgoDexBatching {

  /** Tracks profitable orders against the newest allowed pools, within the configured limits. */
  def track(orders: Seq[PoolOrder], pools: Seq[(ErgoDexPool, Int)], tip: Int,
            config: BatchingConfig): Batcher.Tracked = {
    val live = allowed(pools, tip, config)
      .sortBy { case (pool, included) => (-included, pool.nft) }
      .take(config.maxTrackedPools)
      .map { case (pool, _) => pool.nft -> pool }
      .toMap
    orders
      .groupBy(_.boxId).values.map(_.head).toSeq
      .flatMap(order => live.get(order.poolNft).flatMap(pool =>
        ErgoDexExecution.price(order, pool, config.minRevenueNanoErg, fundsItsOwnBox = false)))
      .sortBy(fill => (-fill.revenue, fill.order.boxId))
      .take(config.maxTrackedOrders)
      .map(fill => fill.order.boxId -> fill.order.poolNft)
      .toMap
  }

  /** Pools recent enough and not denied. */
  private def allowed(pools: Seq[(ErgoDexPool, Int)], tip: Int, config: BatchingConfig): Seq[(ErgoDexPool, Int)] =
    pools.filter { case (pool, included) =>
      tip - included <= config.maxPoolAgeBlocks && !config.deniedPools.contains(pool.nft) }

  /**
   * Each Machina order routed through the native pool of its token that pays most, with that pool; orders no
   * allowed pool fills profitably are left out.
   */
  def route(orders: Seq[MachinaOrder], pools: Seq[(ErgoDexPool, Int)], tip: Int,
            config: BatchingConfig): Seq[(MachinaOrder, (ErgoDexPool, Int))] = {
    val byToken = allowed(pools, tip, config).filter(_._1.xId.isEmpty).groupBy(_._1.yId)
    orders.flatMap { order =>
      byToken.getOrElse(order.tokenId, Seq.empty)
        .flatMap { case entry @ (pool, _) =>
          ErgoDexExecution.price(order.routedThrough(pool.nft), pool, config.minRevenueNanoErg, fundsItsOwnBox = false)
            .map(fill => (fill, entry)) }
        .sortBy { case (fill, (pool, _)) => (-fill.revenue, pool.nft) }
        .headOption
        .map { case (_, entry @ (pool, _)) => order.routedThrough(pool.nft) -> entry }
    }
  }

  /** An ErgoDEX order, or a Machina order routed through `routedThrough` when one was chosen for it. */
  def parse(box: NodeBox, routedThrough: Option[String]): Option[PoolOrder] =
    ErgoDexOrder.parse(box).orElse(routedThrough.flatMap(nft => MachinaOrder.parse(box).map(_.routedThrough(nft))))

  /**
   * Whether an unconfirmed transaction takes the order away rather than executing it. An ErgoDEX execution
   * recreates the order's own pool; a Machina fill may go through any pool, so it recreates some pool.
   */
  def withdrawn(order: PoolOrder, spenders: BatchingMempool.Spenders): Boolean = order match {
    case _: MachinaOrder => spenders.getOrElse(order.boxId, Vector.empty).exists(tx => !tx.body.outputs.exists(isPool))
    case _ => BatchingMempool.withdrawn(order.boxId, order.poolNft, spenders)
  }

  /** Executable order templates, hoisted: the mempool scan tests every unconfirmed output against them. */
  private lazy val executableTemplates: Seq[String] = ErgoDexContracts.orders.filter(_.executable).map(_.templateHex)

  /** Whether a box's script ends with an executable order template. Cheap: no deserialization. */
  def orderShaped(box: NodeBox): Boolean = executableTemplates.exists(box.ergoTree.endsWith)

  /** Parses executable orders from the unconfirmed outputs [[Batcher.mempoolOrderBoxes]] selects, less denied pools. */
  def unconfirmedOrders(snapshot: CompleteMempool.Snapshot, deniedPools: Set[String],
                        limit: Int, perTx: Int): Vector[ErgoDexOrder] =
    Batcher.mempoolOrderBoxes(snapshot, orderShaped, limit, perTx)
      .flatMap(ErgoDexOrder.parse)
      .filterNot(order => deniedPools.contains(order.poolNft))

  /** Whether a box sits at either ErgoDEX pool script. */
  def isPool(box: NodeBox): Boolean =
    box.ergoTree == ErgoDexContracts.NativePoolErgoTree || box.ergoTree == ErgoDexContracts.TokenPoolErgoTree

  /** [[BatchingMempool.placedByWallet]], refusing a placement that creates an ErgoDEX pool. */
  def placedByWallet(tx: CompleteMempool.MempoolTx, inputBoxes: Map[String, NodeBox],
                     spenders: BatchingMempool.Spenders): Boolean =
    BatchingMempool.placedByWallet(tx, inputBoxes, spenders, isPool)
}
