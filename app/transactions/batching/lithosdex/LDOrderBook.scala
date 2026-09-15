package transactions.batching.lithosdex

import lithosdex.LDHelpers
import lithosdex.contracts.{LDOrderContracts, LDOrderKind}
import node.NodeApi
import node.model.{IndexedBox, MempoolOptions, Paging, SortDirection}
import org.ergoplatform.appkit.BlockchainContext
import state.synchronization.CompleteMempool
import transactions.batching.{Batcher, BatchingMempool, EvictedPlacements}

import scala.util.{Failure, Success}

/**
 * The LithosDex orders an owner holds, confirmed and unconfirmed, and what is happening to each.
 *
 * Orders are found by template, since the owner is a constant inside each order's tree and no address
 * index can reach it. Confirmed orders come from the index and are rechecked against the node's UTXO set
 * and mempool outputs, which still include a box an unconfirmed transaction spends. Unconfirmed ones come
 * from a complete mempool observation, plus placements the node evicted after a candidate carried them.
 */
object LDOrderBook {

  sealed abstract class Status(val name: String)

  object Status {
    /** The placement is unconfirmed and nothing spends the order yet. */
    case object Pending extends Status("PENDING")
    case object Open extends Status("OPEN")
    /** An unconfirmed transaction spends the order and recreates the pool: an execution. */
    case object Filling extends Status("FILLING")
    /** An unconfirmed transaction spends the order without the pool: a refund. */
    case object Cancelling extends Status("CANCELLING")
  }

  /**
   * @param placedHeight  height the placement confirmed at, or None while it is unconfirmed
   * @param spendingTxId  the unconfirmed transaction filling or cancelling the order
   * @param placementTxId the transaction that created the order box
   */
  final case class Owned(order: LithosDexOrder, placedHeight: Option[Int], status: Status, spendingTxId: Option[String],
                         placementTxId: String)

  /**
   * Every order naming this network's pool whose owner tree is in `owners`, unconfirmed placements first
   * and then newest first. Throws rather than answering partially when a template holds more boxes than
   * a scan reads, or when the node cannot answer.
   *
   * @param held placements a batcher carried into candidates, which the node may no longer hold
   */
  def owned(ctx: BlockchainContext, nodeApi: NodeApi, owners: Set[String], snapshot: CompleteMempool.Snapshot,
            held: Seq[CompleteMempool.MempoolTx]): Seq[Owned] = {
    val poolNft = LDHelpers.getPoolNFT(ctx.getNetworkType).toString
    def mine(order: LithosDexOrder): Boolean = order.poolNft == poolNft && owners.contains(order.terms.redeemerTree)

    val listed = LDOrderKind.all.flatMap(kind => scan(nodeApi, kind, MempoolOptions.ConfirmedOnly))
      .flatMap(indexed => LithosDexOrder.parse(indexed.box).filter(mine).map(_ -> indexed.inclusionHeight))
      .groupBy(_._1.boxId).values.map(_.head).toVector
    // The index can list a box the latest block spent
    val current = unspent(nodeApi, listed.map(_._1.boxId))
    val confirmed = listed.filter { case (order, _) => current.contains(order.boxId) }
    val confirmedIds = confirmed.map(_._1.boxId).toSet

    val missing = held.toVector.filterNot(tx => snapshot.ids.contains(tx.id))
    val restored = EvictedPlacements.carryable(missing, snapshot,
      unspent(nodeApi, missing.flatMap(_.body.inputs.map(_.boxId)).distinct))
    // Taken with the creating transaction's id, since a mempool output need not report its own
    val unconfirmed = (snapshot.transactions ++ restored).iterator
      .flatMap(tx => tx.body.outputs.map(_ -> tx.id))
      // A tree ends with its template, so this skips parsing every other output in the mempool
      .filter { case (box, _) => templatesHex.exists(box.ergoTree.endsWith) }
      .flatMap { case (box, txId) => LithosDexOrder.parse(box).filter(mine).map(_ -> txId) }
      .filterNot { case (order, _) => confirmedIds.contains(order.boxId) }
      .toVector.groupBy(_._1.boxId).values.map(_.head).toVector

    val spenders = BatchingMempool.spenders(snapshot)
    def owned(order: LithosDexOrder, placedHeight: Option[Int], placementTxId: String): Owned =
      spenders.getOrElse(order.boxId, Vector.empty) match {
        case claims if claims.isEmpty =>
          Owned(order, placedHeight, if (placedHeight.isEmpty) Status.Pending else Status.Open, None, placementTxId)
        case claims =>
          claims.find(_.body.outputs.exists(_.assets.exists(_.tokenId == poolNft))) match {
            case Some(fill) => Owned(order, placedHeight, Status.Filling, Some(fill.id), placementTxId)
            case None => Owned(order, placedHeight, Status.Cancelling, Some(claims.head.id), placementTxId)
          }
      }

    (confirmed.map { case (order, height) => owned(order, Some(height), order.box.transactionId) } ++
      unconfirmed.map { case (order, txId) => owned(order, None, txId) })
      .sortBy(o => (o.placedHeight.isDefined, -o.placedHeight.getOrElse(0), o.order.boxId))
  }

  /**
   * Ownership NFT to redeem order box id, for the redeem orders naming this network's pool whose owner
   * tree is in `owners`. Mempool-aware: an order a fill or a cancel already spends is left out, and one
   * an unconfirmed placement creates is included.
   */
  def redeemOrders(ctx: BlockchainContext, nodeApi: NodeApi, owners: Set[String]): Map[String, String] = {
    val poolNft = LDHelpers.getPoolNFT(ctx.getNetworkType).toString
    scan(nodeApi, LDOrderKind.Redeem, MempoolOptions.WithMempool)
      .flatMap(indexed => LithosDexOrder.parse(indexed.box))
      .collect { case order: LithosDexOrder.Redeem if order.poolNft == poolNft && owners.contains(order.terms.redeemerTree) =>
        order.ownerNft -> order.boxId
      }.toMap
  }

  private lazy val templatesHex: Seq[String] =
    LDOrderKind.all.map(kind => org.bouncycastle.util.encoders.Hex.toHexString(LDOrderContracts.template(kind).templateBytes))

  /** Every box at one order template, up to [[Batcher.MaxScannedPerTemplate]]. */
  private def scan(nodeApi: NodeApi, kind: LDOrderKind, mempool: MempoolOptions): Vector[IndexedBox] = {
    val hash = LDOrderContracts.template(kind).templateHash
    var offset = 0
    var exhausted = false
    var found = Vector.empty[IndexedBox]
    while (!exhausted && offset < Batcher.MaxScannedPerTemplate) {
      val page = nodeApi.unspentBoxesByTemplateHash(hash, Paging(offset, Batcher.PageSize), SortDirection.Desc, mempool) match {
        case Success(boxes) => boxes
        case Failure(ex) => throw new LDBoxes.IndexUnavailableException(
          s"could not reach the node index reading ${kind.scriptName} orders: is extraIndex on? (${ex.getMessage})", ex)
      }
      found ++= page
      exhausted = page.size < Batcher.PageSize
      offset += page.size
    }
    if (!exhausted)
      throw new LDBoxes.IndexTruncatedException(
        s"${kind.scriptName} orders exceed the ${Batcher.MaxScannedPerTemplate}-box scan ceiling, so this answer would be partial")
    found
  }

  /** The ids among `boxIds` the node holds in its UTXO set or as mempool outputs. */
  private def unspent(nodeApi: NodeApi, boxIds: Seq[String]): Set[String] =
    if (boxIds.isEmpty) Set.empty
    else nodeApi.boxesWithPoolByIds(boxIds) match {
      case Success(boxes) => boxes.map(_.boxId).toSet
      case Failure(ex) => throw new LDBoxes.IndexUnavailableException(
        s"could not reach the node reading ${boxIds.size} box(es): ${ex.getMessage}", ex)
    }
}
