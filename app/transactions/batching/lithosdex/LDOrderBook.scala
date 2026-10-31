package transactions.batching.lithosdex

import lithosdex.LDHelpers
import lithosdex.contracts.{LDOrderContracts, LDOrderKind, LDOrderTemplate, LDT2TOrderKind}
import node.NodeApi
import node.model.{IndexedBox, MempoolOptions, NodeBox, Paging, SortDirection}
import org.bouncycastle.util.encoders.Hex
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
  final case class Owned[+O](order: O, placedHeight: Option[Int], status: Status, spendingTxId: Option[String],
                             placementTxId: String)

  /** One kind of pool's orders: the templates they are found by, and how a box reads as one. */
  final class Family[O] private (val templates: Seq[(String, LDOrderTemplate)], val parse: NodeBox => Option[O],
                                 val terms: O => LithosDexTerms, val box: O => NodeBox) {
    /** A tree ends with its template, so matching the tail skips parsing every other mempool output. */
    private[LDOrderBook] lazy val templatesHex: Seq[String] =
      templates.map { case (_, t) => Hex.toHexString(t.templateBytes) }
  }

  object Family {
    val ErgToken: Family[LithosDexOrder] = new Family(
      LDOrderKind.all.map(kind => kind.scriptName -> LDOrderContracts.template(kind)), LithosDexOrder.parse,
      (o: LithosDexOrder) => o.terms, (o: LithosDexOrder) => o.box)

    val TokenToken: Family[LDT2TOrder] = new Family(
      LDT2TOrderKind.all.map(kind => kind.scriptName -> LDOrderContracts.template(kind)), LDT2TOrder.parse,
      (o: LDT2TOrder) => o.terms, (o: LDT2TOrder) => o.box)
  }

  /** Every order naming this network's canonical pool whose owner tree is in `owners`, as [[ownedOn]] reads them. */
  def owned(ctx: BlockchainContext, nodeApi: NodeApi, owners: Set[String], snapshot: CompleteMempool.Snapshot,
            held: Seq[CompleteMempool.MempoolTx]): Seq[Owned[LithosDexOrder]] =
    ownedOn(nodeApi, Family.ErgToken, LDHelpers.getPoolNFT(ctx.getNetworkType).toString, owners, snapshot, held)

  /**
   * Every order of `family` naming `poolNft` whose owner tree is in `owners`, unconfirmed placements first
   * and then newest first. Throws rather than answering partially when a template holds more boxes than
   * a scan reads, or when the node cannot answer.
   *
   * @param held placements a batcher carried into candidates, which the node may no longer hold
   */
  def ownedOn[O](nodeApi: NodeApi, family: Family[O], poolNft: String, owners: Set[String],
                 snapshot: CompleteMempool.Snapshot, held: Seq[CompleteMempool.MempoolTx]): Seq[Owned[O]] = {
    def mine(order: O): Boolean = {
      val terms = family.terms(order)
      terms.poolNft == poolNft && owners.contains(terms.redeemerTree)
    }
    def boxId(order: O): String = family.box(order).boxId

    val listed = family.templates.flatMap { case (name, template) => scan(nodeApi, name, template, MempoolOptions.ConfirmedOnly) }
      .flatMap(indexed => family.parse(indexed.box).filter(mine).map(_ -> indexed.inclusionHeight))
      .groupBy(o => boxId(o._1)).values.map(_.head).toVector
    // The index can list a box the latest block spent
    val current = unspent(nodeApi, listed.map(o => boxId(o._1)))
    val confirmed = listed.filter { case (order, _) => current.contains(boxId(order)) }
    val confirmedIds = confirmed.map(o => boxId(o._1)).toSet

    val missing = held.toVector.filterNot(tx => snapshot.ids.contains(tx.id))
    val restored = EvictedPlacements.carryable(missing, snapshot,
      unspent(nodeApi, missing.flatMap(_.body.inputs.map(_.boxId)).distinct))
    // Taken with the creating transaction's id, since a mempool output need not report its own
    val unconfirmed = (snapshot.transactions ++ restored).iterator
      .flatMap(tx => tx.body.outputs.map(_ -> tx.id))
      .filter { case (box, _) => family.templatesHex.exists(box.ergoTree.endsWith) }
      .flatMap { case (box, txId) => family.parse(box).filter(mine).map(_ -> txId) }
      .filterNot { case (order, _) => confirmedIds.contains(boxId(order)) }
      .toVector.groupBy(o => boxId(o._1)).values.map(_.head).toVector

    val spenders = BatchingMempool.spenders(snapshot)
    def owned(order: O, placedHeight: Option[Int], placementTxId: String): Owned[O] =
      spenders.getOrElse(boxId(order), Vector.empty) match {
        case claims if claims.isEmpty =>
          Owned(order, placedHeight, if (placedHeight.isEmpty) Status.Pending else Status.Open, None, placementTxId)
        case claims =>
          claims.find(_.body.outputs.exists(_.assets.exists(_.tokenId == poolNft))) match {
            case Some(fill) => Owned(order, placedHeight, Status.Filling, Some(fill.id), placementTxId)
            case None => Owned(order, placedHeight, Status.Cancelling, Some(claims.head.id), placementTxId)
          }
      }

    (confirmed.map { case (order, height) => owned(order, Some(height), family.box(order).transactionId) } ++
      unconfirmed.map { case (order, txId) => owned(order, None, txId) })
      .sortBy(o => (o.placedHeight.isDefined, -o.placedHeight.getOrElse(0), boxId(o.order)))
  }

  /**
   * Ownership NFT to redeem order box id, for the redeem orders naming this network's pool whose owner
   * tree is in `owners`. Mempool-aware: an order a fill or a cancel already spends is left out, and one
   * an unconfirmed placement creates is included.
   */
  def redeemOrders(ctx: BlockchainContext, nodeApi: NodeApi, owners: Set[String]): Map[String, String] =
    redeemOrdersOn(nodeApi, LDHelpers.getPoolNFT(ctx.getNetworkType).toString, owners, tokenToken = false)

  /** The same for any pool; `tokenToken` reads the token/token redeem template instead of the ERG/token one. */
  def redeemOrdersOn(nodeApi: NodeApi, poolNft: String, owners: Set[String], tokenToken: Boolean): Map[String, String] = {
    def mine(terms: LithosDexTerms): Boolean = terms.poolNft == poolNft && owners.contains(terms.redeemerTree)
    if (tokenToken)
      scan(nodeApi, LDT2TOrderKind.Redeem.scriptName, LDOrderContracts.template(LDT2TOrderKind.Redeem),
        MempoolOptions.WithMempool)
        .flatMap(indexed => LDT2TOrder.parse(indexed.box))
        .collect { case order: LDT2TOrder.Redeem if mine(order.terms) => order.ownerNft -> order.boxId }.toMap
    else
      scan(nodeApi, LDOrderKind.Redeem.scriptName, LDOrderContracts.template(LDOrderKind.Redeem), MempoolOptions.WithMempool)
        .flatMap(indexed => LithosDexOrder.parse(indexed.box))
        .collect { case order: LithosDexOrder.Redeem if mine(order.terms) => order.ownerNft -> order.boxId }.toMap
  }

  /** Every box at one order template, up to [[Batcher.MaxScannedPerTemplate]]. */
  private def scan(nodeApi: NodeApi, name: String, template: LDOrderTemplate, mempool: MempoolOptions): Vector[IndexedBox] = {
    var offset = 0
    var exhausted = false
    var found = Vector.empty[IndexedBox]
    while (!exhausted && offset < Batcher.MaxScannedPerTemplate) {
      val page = nodeApi.unspentBoxesByTemplateHash(template.templateHash, Paging(offset, Batcher.PageSize),
        SortDirection.Desc, mempool) match {
        case Success(boxes) => boxes
        case Failure(ex) => throw new LDBoxes.IndexUnavailableException(
          s"could not reach the node index reading $name orders: is extraIndex on? (${ex.getMessage})", ex)
      }
      found ++= page
      exhausted = page.size < Batcher.PageSize
      offset += page.size
    }
    if (!exhausted)
      throw new LDBoxes.IndexTruncatedException(
        s"$name orders exceed the ${Batcher.MaxScannedPerTemplate}-box scan ceiling, so this answer would be partial")
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
