package transactions.batching

import mutations.NodeWallet
import node.model.NodeBox
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.ErgoTreePredef
import sigma.crypto.CryptoConstants
import sigma.data.ProveDlog
import sigma.serialization.GroupElementSerializer
import state.synchronization.CompleteMempool
import work.lithos.mutations.Contract

import scala.annotation.tailrec

/** Mempool dependency checks every batching adapter shares: placements, competitors and pool tips. */
object BatchingMempool {

  /** Box id to every unconfirmed transaction spending it. */
  type Spenders = Map[String, Vector[CompleteMempool.MempoolTx]]

  /** Index one observation by the boxes its transactions spend. Built once per build pass. */
  def spenders(snapshot: CompleteMempool.Snapshot): Spenders =
    snapshot.transactions
      .flatMap(tx => tx.body.inputs.map(_.boxId -> tx))
      .groupBy(_._1)
      .map { case (boxId, claims) => boxId -> claims.map(_._2) }

  /** Box id to the unconfirmed transaction that creates it, for every output in this observation. */
  def creators(snapshot: CompleteMempool.Snapshot): Map[String, CompleteMempool.MempoolTx] =
    snapshot.transactions.flatMap(tx => tx.body.outputs.map(_.boxId -> tx)).toMap

  /** Inputs a placing transaction may spend, which bounds its cost when the node reports none. */
  final val MaxPlacementInputs = 16

  private val P2PK = "0008cd[0-9a-f]{66}".r

  /**
   * The script a block reward pays, with its key left free, so a coinbase differs from it only in the key.
   * Its height guard only ever opens, so a coinbase the mempool accepted stays spendable.
   */
  private val Coinbase = {
    val key = CryptoConstants.dlogGroup.generator
    val keyHex = Hex.toHexString(GroupElementSerializer.toBytes(key))
    val tree = Contract(ErgoTreePredef.rewardOutputScript(NodeWallet.MINER_REWARD_DELAY, ProveDlog(key))).ergoTreeHex
    val at = tree.indexOf(keyHex)
    (tree.take(at) + "[0-9a-f]{66}" + tree.drop(at + keyHex.length)).r
  }

  /** A box one key spends on its own: P2PK, or a coinbase paid to one key. */
  def spentByKey(box: NodeBox): Boolean =
    P2PK.pattern.matcher(box.ergoTree).matches() || Coinbase.pattern.matcher(box.ergoTree).matches()

  /** Distinct inputs within [[MaxPlacementInputs]], no data inputs, and no other unconfirmed spend of any input. */
  def uncontested(tx: CompleteMempool.MempoolTx, spenders: Spenders): Boolean = {
    val inputs = tx.body.inputs.map(_.boxId)
    inputs.nonEmpty && inputs.size <= MaxPlacementInputs && tx.body.dataInputs.isEmpty &&
      inputs.distinct.size == inputs.size &&
      // A competing spend may cancel the placement or one of its ancestors.
      inputs.forall(id => spenders.get(id).exists(claims => claims.size == 1 && claims.head.id == tx.id))
  }

  /**
   * Requires [[uncontested]] inputs that are all [[spentByKey]], and no output `createsPool` recognises.
   * Missing input boxes fail the check.
   */
  def placedByWallet(tx: CompleteMempool.MempoolTx, inputBoxes: Map[String, NodeBox],
                     spenders: Spenders, createsPool: NodeBox => Boolean): Boolean =
    uncontested(tx, spenders) &&
      tx.body.inputs.forall(input => inputBoxes.get(input.boxId).exists(spentByKey)) &&
      !tx.body.outputs.exists(createsPool)

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

  /** Keeps a prefix of fills that fits alongside its complete ancestry, counting shared ancestors once. */
  def fitting[F](fills: Vector[F], slots: Int,
                 placementOf: F => Vector[CompleteMempool.MempoolTx],
                 alreadyCarried: Set[String] = Set.empty)
  : (Vector[F], Vector[CompleteMempool.MempoolTx]) = {
    var taken = Vector.empty[F]
    var carried = Vector.empty[CompleteMempool.MempoolTx]
    var open = true
    fills.foreach { fill =>
      if (open) {
        val added = placementOf(fill).filterNot(tx => carried.exists(_.id == tx.id))
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
  def withdrawn(orderBoxId: String, poolNft: String, spenders: Spenders): Boolean =
    spenders.getOrElse(orderBoxId, Vector.empty).exists(tx =>
      !tx.body.outputs.exists(_.assets.exists(_.tokenId == poolNft)))

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
