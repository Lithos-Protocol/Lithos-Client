package transactions.batching.arb

import node.model.NodeBox
import state.synchronization.CompleteMempool
import transactions.batching.BatchingMempool
import transactions.candidate.BlockTxMessages.CandidateTx
import work.lithos.mutations.Contract

import scala.collection.mutable

/**
 * The pending spends of tracked pool boxes in one mempool observation, split as the arb stage treats them.
 *
 * A pending transaction that spends a pool box is **superseded** when it is part of a closed ERG cycle: every
 * input that is not a pool and every output that is neither a pool nor the miner fee sits at one script, the
 * transactions linked through that script's boxes are netted together, the script ends with more ERG, and no
 * token's amount at it changed. A cycle displaced this way costs nobody but the arber. Everything else is
 * **carried**: a pool's chain of carried transactions, their ancestors ahead of them, is placed before any cycle
 * through that pool, so arb only ever back-runs a pending swap.
 *
 * @param anchor     the block the observation was taken on; valid only for candidates built on it
 * @param revision   the observation's revision, so a build on the same one reuses this
 * @param chains     by pool NFT, each pool whose confirmed box a carried chain moves
 * @param superseded the closed-cycle transactions the chain walks stopped at
 * @param spenders   pool box id to the pending transactions spending it, confirmed boxes and pending pool outputs
 * @param boxes      confirmed input boxes read for the closed-cycle test, kept for the next observation
 */
final case class ArbPending(anchor: String, revision: Long, chains: Map[String, ArbPending.Chain],
                            superseded: Set[String], spenders: Map[String, Vector[String]],
                            boxes: Map[String, NodeBox]) {

  /** Every pending transaction this view carries or stops at, which a build's inputs include. */
  lazy val key: Vector[String] =
    (chains.valuesIterator.flatMap(_.members.map(_.id)) ++ superseded.iterator).toVector.distinct.sorted

  /** The pending transactions that spend any of `boxIds`. */
  def competitors(boxIds: Iterable[String]): Set[String] =
    boxIds.iterator.flatMap(id => spenders.getOrElse(id, Vector.empty)).toSet
}

object ArbPending {

  /**
   * A pool's carried chain: the confirmed box it starts from, the transactions to carry in spend order (ancestors
   * included), and the pool box the last one creates, which cycles through the pool spend.
   */
  final case class Chain(confirmedId: String, members: Vector[CandidateTx], tip: NodeBox) {
    def tipTx: String = members.last.id
  }

  val Empty: ArbPending = ArbPending("", -1L, Map.empty, Set.empty, Map.empty, Map.empty)

  /** Most transactions one pool's chain carries, ancestors included; a longer chain leaves the pool confirmed. */
  final val MaxCarried = 4

  /** Most transactions one closed cycle may link; a longer one is carried. */
  final val MaxCycleTxs = 64

  /** Most confirmed input boxes read for one observation; a transaction whose inputs were not read is carried. */
  final val MaxInputReads = 64

  /** Most confirmed input boxes kept between observations. */
  final val MaxKeptBoxes = 1024

  private val FeeTree: String = Contract.FEE.ergoTreeHex

  /**
   * The view of `snapshot` for blocks built on its anchor.
   *
   * @param pools  every tracked pool's confirmed box, by NFT; a box at a pool's tree carrying its NFT first is that
   *               pool's box
   * @param read   reads confirmed boxes by id, for the inputs the closed-cycle test needs; a box it leaves out
   *               makes its transaction carried
   * @param known  boxes an earlier view already read
   * @param paid   scripts a pool spend pays besides the trader's own and the miner fee: the ErgoDEX CL protocol fee's
   *               key, which a cycle through a CL position pays
   */
  def of(snapshot: CompleteMempool.Snapshot, revision: Long, pools: Map[String, NodeBox],
         read: Seq[String] => Map[String, NodeBox], known: Map[String, NodeBox] = Map.empty,
         paid: Set[String] = Set.empty): ArbPending = {
    val txs = snapshot.transactions
    val spenders = BatchingMempool.spenders(snapshot)
    val creators = BatchingMempool.creators(snapshot)
    val outputs: Map[String, NodeBox] = txs.iterator.flatMap(_.body.outputs.map(b => b.boxId -> b)).toMap
    val poolIds = pools.valuesIterator.map(_.boxId).toSet
    val trees = pools.map { case (nft, box) => nft -> box.ergoTree.toLowerCase }

    def isPool(box: NodeBox): Boolean =
      box.assets.headOption.exists(a => trees.get(a.tokenId).contains(box.ergoTree.toLowerCase))
    def poolInput(id: String): Boolean = poolIds.contains(id) || outputs.get(id).exists(isPool)
    def spendsPool(tx: CompleteMempool.MempoolTx): Boolean = tx.body.inputs.exists(i => poolInput(i.boxId))
    val paidTrees = paid.map(_.toLowerCase)
    // The one script every output but pools, the fee and the paid scripts pays, when there is one
    def outputScript(tx: CompleteMempool.MempoolTx): Option[String] = {
      val others = tx.body.outputs.filterNot(b => isPool(b) || b.ergoTree.equalsIgnoreCase(FeeTree) ||
        paidTrees.contains(b.ergoTree.toLowerCase))
        .map(_.ergoTree.toLowerCase).distinct
      if (others.size == 1) others.headOption else None
    }

    // Confirmed non-pool inputs of the pool spends whose outputs could close a cycle, read once
    val wanted = txs.iterator.filter(tx => spendsPool(tx) && outputScript(tx).isDefined)
      .flatMap(_.body.inputs.map(_.boxId))
      .filterNot(id => poolInput(id) || outputs.contains(id) || known.contains(id))
      .toVector.distinct.take(MaxInputReads)
    val fetched = if (wanted.isEmpty) Map.empty[String, NodeBox]
      else scala.util.Try(read(wanted)).getOrElse(Map.empty).filter { case (id, box) => box.boxId == id }
    val kept = known ++ fetched
    val boxes = if (kept.size <= MaxKeptBoxes) kept else fetched
    def inputBox(id: String): Option[NodeBox] = outputs.get(id).orElse(boxes.get(id))

    // A pool spend's script, when every non-pool input and every output but pools and the fee sits at it
    val shapes = mutable.Map.empty[String, Option[String]]
    def shape(tx: CompleteMempool.MempoolTx): Option[String] = shapes.getOrElseUpdate(tx.id,
      if (!spendsPool(tx)) None
      else outputScript(tx).filter { script =>
        val others = tx.body.inputs.map(_.boxId).filterNot(poolInput)
        others.nonEmpty && others.forall(id => inputBox(id).exists(_.ergoTree.equalsIgnoreCase(script)))
      })

    val closedCache = mutable.Map.empty[String, Boolean]
    def closed(tx: CompleteMempool.MempoolTx): Boolean = closedCache.getOrElseUpdate(tx.id, shape(tx) match {
      case None => false
      case Some(script) =>
        // Every pool spend linked to `tx` through boxes at `script`, each of the same shape
        var members = Map(tx.id -> tx)
        var frontier = Vector(tx)
        var broken = false
        while (frontier.nonEmpty && !broken) {
          val next = frontier.flatMap { member =>
            val forward = member.body.outputs.filter(_.ergoTree.equalsIgnoreCase(script))
              .flatMap(o => spenders.getOrElse(o.boxId, Vector.empty))
            val back = member.body.inputs.map(_.boxId).filterNot(poolInput).flatMap(creators.get)
            (forward ++ back).filter(spendsPool)
          }.filterNot(n => members.contains(n.id)).distinct
          next.foreach { n =>
            if (shape(n).contains(script)) members += n.id -> n else broken = true
          }
          if (members.size > MaxCycleTxs) broken = true
          frontier = next
        }
        !broken && {
          var erg = BigInt(0)
          val tokens = mutable.Map.empty[String, BigInt].withDefaultValue(BigInt(0))
          members.valuesIterator.foreach { m =>
            m.body.outputs.filter(_.ergoTree.equalsIgnoreCase(script)).foreach { o =>
              erg += o.value
              o.assets.foreach(a => tokens(a.tokenId) += a.amount)
            }
            m.body.inputs.map(_.boxId).filterNot(poolInput).flatMap(inputBox).foreach { i =>
              erg -= i.value
              i.assets.foreach(a => tokens(a.tokenId) -= a.amount)
            }
          }
          erg > 0 && tokens.valuesIterator.forall(_ == 0)
        }
    })

    // The pending ancestors `tx` needs that `carried` does not hold, in spend order; None when one is contested,
    // spends a pool, or the chain would pass its bound
    def ancestors(tx: CompleteMempool.MempoolTx, carried: Set[String], room: Int)
    : Option[Vector[CompleteMempool.MempoolTx]] = {
      var ordered = Vector.empty[CompleteMempool.MempoolTx]
      var seen = carried + tx.id
      def visit(current: CompleteMempool.MempoolTx): Boolean =
        current.body.inputs.flatMap(i => creators.get(i.boxId)).distinct.forall { parent =>
          seen.contains(parent.id) || {
            seen += parent.id
            !spendsPool(parent) && BatchingMempool.uncontested(parent, spenders) && visit(parent) && {
              ordered :+= parent
              ordered.size <= room
            }
          }
        }
      if (visit(tx)) Some(ordered) else None
    }

    var superseded = Set.empty[String]
    // Follows one pool from its confirmed box: carries each single non-cycle spend, stops at closed cycles, and
    // gives up (the pool stays confirmed) at a contested spend or past the bound
    def walk(nft: String, confirmed: NodeBox): Option[Chain] = {
      var at = confirmed
      var carried = Vector.empty[CompleteMempool.MempoolTx]
      def chain: Option[Chain] =
        if (carried.isEmpty) None else Some(Chain(confirmed.boxId, carried.map(t => CandidateTx.ancestor(t.body)), at))
      var result: Option[Option[Chain]] = None
      while (result.isEmpty) {
        val claims = spenders.getOrElse(at.boxId, Vector.empty)
        if (claims.isEmpty) result = Some(chain)
        else if (claims.forall(closed)) {
          superseded ++= claims.map(_.id)
          result = Some(chain)
        } else if (claims.size > 1 || !BatchingMempool.uncontested(claims.head, spenders)) result = Some(None)
        else {
          val swap = claims.head
          val room = MaxCarried - carried.size - 1
          val parents = if (room < 0) None else ancestors(swap, carried.map(_.id).toSet, room)
          val next = swap.body.outputs.find(o => o.assets.headOption.exists(_.tokenId == nft) &&
            o.ergoTree.equalsIgnoreCase(confirmed.ergoTree))
          (parents, next) match {
            case (Some(ps), Some(box)) =>
              carried = carried ++ ps :+ swap
              at = box
            case _ => result = Some(None)
          }
        }
      }
      result.flatten
    }
    val chains = pools.flatMap { case (nft, confirmed) => walk(nft, confirmed).map(nft -> _) }
    ArbPending(snapshot.anchor, revision, chains, superseded,
      spenders.collect { case (id, claims) if poolInput(id) => id -> claims.map(_.id) }, boxes)
  }
}
