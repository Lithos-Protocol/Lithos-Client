package transactions.batching.ergodex

import configs.BatchingConfig
import node.model.NodeBox
import state.synchronization.CompleteMempool
import transactions.batching.{Batcher, BatchingMempool}

/** ErgoDEX order selection. The mempool checks every adapter shares are in [[BatchingMempool]]. */
object ErgoDexBatching {

  /** Tracks profitable orders against the newest allowed pools, within the configured limits. */
  def track(orders: Seq[ErgoDexOrder], pools: Seq[(ErgoDexPool, Int)], tip: Int,
            config: BatchingConfig): Batcher.Tracked = {
    val live = pools
      .filter { case (pool, included) =>
        tip - included <= config.maxPoolAgeBlocks && !config.deniedPools.contains(pool.nft) }
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
