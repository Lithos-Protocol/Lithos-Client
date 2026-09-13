package transactions.batching.ergodex

import configs.BatchingConfig
import node.model.NodeBox
import state.synchronization.CompleteMempool

import scala.annotation.tailrec

/** Order selection and mempool dependency checks for the batching source. */
object ErgoDexBatching {

  /** Orders held between scans, as order box id to the pool NFT that order names. */
  type Tracked = Map[String, String]

  /** Box id to every unconfirmed transaction spending it. */
  type Spenders = Map[String, Vector[CompleteMempool.MempoolTx]]

  /** Tracks profitable orders against the newest allowed pools, within the configured limits. */
  def track(orders: Seq[ErgoDexOrder], pools: Seq[(ErgoDexPool, Int)], tip: Int,
            config: BatchingConfig): Tracked = {
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

  /** Index one observation by the boxes its transactions spend. Built once per build pass. */
  def spenders(snapshot: CompleteMempool.Snapshot): Spenders =
    snapshot.transactions
      .flatMap(tx => tx.body.inputs.map(_.boxId -> tx))
      .groupBy(_._1)
      .map { case (boxId, claims) => boxId -> claims.map(_._2) }

  /** Box id to the unconfirmed transaction that creates it, for every output in this observation. */
  def creators(snapshot: CompleteMempool.Snapshot): Map[String, CompleteMempool.MempoolTx] =
    snapshot.transactions.flatMap(tx => tx.body.outputs.map(_.boxId -> tx)).toMap

  /** Parses executable orders from unconfirmed outputs, excluding denied pools and limiting the result. */
  def unconfirmedOrders(snapshot: CompleteMempool.Snapshot, deniedPools: Set[String],
                        limit: Int): Vector[ErgoDexOrder] = {
    val templates = ErgoDexContracts.orders.filter(_.executable).map(_.templateHex)
    snapshot.transactions.iterator
      .flatMap(_.body.outputs)
      .filter(box => templates.exists(box.ergoTree.endsWith))
      .flatMap(box => ErgoDexOrder.parse(box))
      .filterNot(order => deniedPools.contains(order.poolNft))
      .take(math.max(0, limit))
      .toVector
  }

  /** Inputs a placing transaction may spend. Its execution cost is unknown to the package budget. */
  final val MaxPlacementInputs = 16

  private val P2PK = "0008cd[0-9a-f]{66}".r

  /**
   * Requires uncontested P2PK inputs, no data inputs and no pool output.
   * Missing input boxes fail the check.
   */
  def placedByWallet(tx: CompleteMempool.MempoolTx, inputBoxes: Map[String, NodeBox],
                     spenders: Spenders): Boolean = {
    val inputs = tx.body.inputs.map(_.boxId)
    inputs.nonEmpty && inputs.size <= MaxPlacementInputs && tx.body.dataInputs.isEmpty &&
      inputs.distinct.size == inputs.size &&
      // A competing spend may cancel the placement or one of its ancestors.
      inputs.forall(id => spenders.get(id).exists(claims => claims.size == 1 && claims.head.id == tx.id) &&
        inputBoxes.get(id).exists(box => P2PK.pattern.matcher(box.ergoTree).matches())) &&
      !tx.body.outputs.exists(box =>
        box.ergoTree == ErgoDexContracts.NativePoolErgoTree || box.ergoTree == ErgoDexContracts.TokenPoolErgoTree)
  }

  /** Collect a placement and all its parents in spend order, within the available transaction slots. */
  def placementChain(tx: CompleteMempool.MempoolTx,
                      creators: Map[String, CompleteMempool.MempoolTx],
                      limit: Int): Option[Vector[CompleteMempool.MempoolTx]] = {
    var visited = Set.empty[String]
    var visiting = Set.empty[String]
    var ordered = Vector.empty[CompleteMempool.MempoolTx]
    def visit(current: CompleteMempool.MempoolTx): Boolean =
      if (visiting.contains(current.id)) false
      else if (visited.contains(current.id)) true
      else if (visited.size >= limit || current.body.inputs.isEmpty ||
        current.body.inputs.size > MaxPlacementInputs || current.body.dataInputs.nonEmpty) false
      else {
        visited += current.id
        visiting += current.id
        val valid = current.body.inputs.flatMap(input => creators.get(input.boxId)).forall(visit)
        visiting -= current.id
        if (valid) ordered :+= current
        valid
      }
    if (visit(tx)) Some(ordered) else None
  }

  /** Keeps a prefix that fits alongside its complete ancestry, counting shared ancestors once. */
  def fitting(fills: Vector[ErgoDexFill], slots: Int,
              placementOf: ErgoDexOrder => Vector[CompleteMempool.MempoolTx],
              alreadyCarried: Set[String] = Set.empty)
  : (Vector[ErgoDexFill], Vector[CompleteMempool.MempoolTx]) = {
    var taken = Vector.empty[ErgoDexFill]
    var carried = Vector.empty[CompleteMempool.MempoolTx]
    var open = true
    fills.foreach { fill =>
      if (open) {
        val added = placementOf(fill.order).filterNot(tx => carried.exists(_.id == tx.id))
        val newCount = (carried ++ added).count(tx => !alreadyCarried.contains(tx.id))
        if (taken.size + 1 + newCount <= slots) {
          taken :+= fill
          carried ++= added
        } else open = false
      }
    }
    (taken, carried)
  }

  /** Unconfirmed transactions spending boxes claimed by this run. */
  def competitors(spenders: Spenders, boxIds: Seq[String]): Set[String] =
    boxIds.flatMap(id => spenders.getOrElse(id, Vector.empty).map(_.id)).toSet

  /** Treats an order as withdrawn when a spender recreates no box carrying its pool NFT. */
  def withdrawn(order: ErgoDexOrder, spenders: Spenders): Boolean =
    spenders.getOrElse(order.boxId, Vector.empty).exists(tx =>
      !tx.body.outputs.exists(_.assets.exists(_.tokenId == order.poolNft)))

  /** Follows unconfirmed pool spends until the tip, rejecting conflicts or a missing successor. */
  def poolTip(confirmed: NodeBox, nft: String, spenders: Spenders): Option[NodeBox] = {
    @tailrec
    def follow(box: NodeBox, depth: Int): Option[NodeBox] =
      spenders.get(box.boxId) match {
        case None => Some(box)
        case Some(claims) if claims.size == 1 && depth < CompleteMempool.MaxTransactions =>
          claims.head.body.outputs.find(_.assets.headOption.exists(_.tokenId == nft)) match {
            case Some(next) => follow(next, depth + 1)
            case None => None
          }
        case Some(_) => None
      }
    follow(confirmed, 0)
  }
}
