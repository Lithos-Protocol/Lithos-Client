package transactions.batching.sigmafi.zc

import node.model.{NodeAsset, NodeBox, NodeTransaction}
import org.bouncycastle.util.encoders.Hex
import scorex.crypto.hash.Blake2b256
import sigmafi.zc.{ZcContracts, ZcKind, ZcMarket}
import state.messages.{BlockTx, TxOutput}
import state.synchronization.CompleteMempool
import transactions.batching.tracking.BlockKept

import scala.collection.immutable.TreeSet
import scala.util.Try

/**
 * One transaction as the book reads it: the boxes it spends, and only the outputs that can matter (a ZC tree or
 * the oracle NFT), each keeping its own index. `carried` lists the scripts it discloses, by hash, and is built
 * only when one of its new boxes names a hash the client cannot resolve yet: variable 64 on any input, then its
 * input and output trees, each at most [[ZcTx.MaxScriptChars]] long.
 */
final class ZcTx(val id: String, val inputs: IndexedSeq[String], val outputs: Seq[NodeBox],
                 extensions: () => Seq[Map[String, String]], trees: () => Seq[String]) {
  lazy val carried: Map[String, String] =
    (extensions().flatMap(_.get(ZcTx.DisclosureVar).toSeq.flatMap(ZcBoxes.disclosedTrees)) ++ trees())
      .filter(t => t.length <= ZcTx.MaxScriptChars)
      .flatMap(t => Try(Hex.toHexString(Blake2b256.hash(Hex.decode(t)))).toOption.map(_ -> t.toLowerCase)).toMap
}

object ZcTx {

  /** The context variable zcs1 script disclosure writes, on `INPUTS(0)` by writers and on any input by readers. */
  final val DisclosureVar = "64"

  /** The longest script kept: a box script is at most 4,096 bytes. */
  final val MaxScriptChars = 8192

  def relevant(box: NodeBox): Boolean = ZcContracts.classify(box.ergoTree).isDefined || ZcBoxes.holdsOracleNft(box)

  /** A transaction read from the node, whose input trees it does not carry. */
  def of(tx: NodeTransaction): ZcTx =
    new ZcTx(tx.id, tx.inputs.map(_.boxId).toIndexedSeq, tx.outputs.filter(relevant),
      () => tx.inputs.map(_.spendingProof.extension), () => tx.outputs.map(_.ergoTree))

  /** A transaction of an indexed block, with the input boxes the block resolved. */
  def of(tx: BlockTx, resolved: Map[String, TxOutput]): ZcTx =
    new ZcTx(tx.id, tx.inputs.map(_.id).toIndexedSeq, tx.outputs.filter(relevantOutput).map(nodeBox),
      () => tx.inputs.flatMap(_.spendingProof.map(_.ext)),
      () => tx.inputs.flatMap(i => resolved.get(i.id).map(_.ergoTree)) ++ tx.outputs.map(_.ergoTree))

  private val oracleNftBytes = Hex.decode(ZcContracts.OracleNft)

  private def relevantOutput(o: TxOutput): Boolean =
    ZcContracts.classify(o.ergoTree).isDefined || o.assets.exists(t => java.util.Arrays.equals(t.id.getBytes, oracleNftBytes))

  private def nodeBox(o: TxOutput): NodeBox =
    NodeBox(o.id, o.txId, o.value, o.index, o.creationHeight, o.ergoTree,
      o.assets.map(t => NodeAsset(t.id.toString, t.amount)),
      node.model.NodeRegisters(o.registers.zipWithIndex.map { case (r, i) => s"R${i + 4}" -> r }.toMap))
}

/** A ZC box in the confirmed book, and the height of the block that created it. */
final case class ZcEntry(record: ZcRecord, height: Int) {
  def boxId: String = record.boxId
  def value: Long = record.box.value
}

/**
 * The box whose id is `token` was spent as INPUTS(0) of `txId` under a minting contract and the token exists:
 * zcs1's origin proof for a bond asset (`Order`, `PricedOrder`), shares (a share mint request) or a receipt
 * (`WrapMint`). For shares `terms` is the request's R8 (N, fees, auction) and `held` the bond unit it held; for a
 * receipt `held` is the wrapped token. `height` is None while the transaction is in the mempool.
 */
final case class ZcOrigin(token: String, kind: ZcKind, market: Option[ZcMarket], txId: String, height: Option[Int],
                          terms: IndexedSeq[Long], held: Option[NodeAsset])

object ZcOrigin {
  def of(minter: ZcRecord, txId: String, height: Option[Int]): ZcOrigin = minter match {
    case r: ZcShareRequest => ZcOrigin(r.boxId, r.script.kind, r.script.market, txId, height, r.terms, r.box.assets.headOption)
    case r: ZcWrapRequest => ZcOrigin(r.boxId, r.script.kind, None, txId, height, IndexedSeq.empty, Some(r.wrapped))
    case r => ZcOrigin(r.boxId, r.script.kind, r.script.market, txId, height, IndexedSeq.empty, None)
  }
}

/** What applying one block changed, so it can be undone after a fork. Evicted boxes count as removed. */
final case class ZcApplied(height: Int, headerId: String, parentId: String, removed: Vector[ZcEntry], added: Vector[String],
                           oracleRemoved: Vector[(NodeBox, Int)], oracleAdded: Vector[String],
                           originsAdded: Vector[String], originsDropped: Vector[ZcOrigin])

/**
 * Every unspent ZC box as of block `headerId` at `height`, kept from blocks. Boxes are indexed by their first token
 * (a bond's or vault's reserve, an ask's token for sale), by the party hash they name, and by value, which decides
 * what stays when the book is full: a box is admitted past `cap` only by evicting a box holding less nanoERG.
 * `oracleBoxes` holds every box carrying the oracle NFT; the oracle is read only when exactly one does. `origins`
 * holds the mints seen for tokens whose state box is in the book. `cut` says a box was evicted or left out.
 */
final case class ZcBook(height: Int, headerId: String, boxes: Map[String, ZcEntry], byToken: Map[String, Set[String]],
                        byParty: Map[String, Set[String]], byValue: TreeSet[(Long, String)],
                        oracleBoxes: Map[String, (NodeBox, Int)], origins: Map[String, ZcOrigin],
                        history: Vector[ZcApplied], cut: Boolean, scannedAt: Int) extends BlockKept[ZcBook] {

  import ZcBook._

  /** The oracle pool's state, when exactly one confirmed box carries its NFT and reads. */
  lazy val oracle: Option[ZcOracle] =
    if (oracleBoxes.size == 1) ZcBoxes.oracle(oracleBoxes.head._2._1) else None

  def entry(boxId: String): Option[ZcEntry] = boxes.get(boxId)

  /** Every box whose first token is `token`. */
  def holding(token: String): Seq[ZcEntry] = byToken.getOrElse(token, Set.empty).toSeq.flatMap(boxes.get)

  /** Every box naming `partyHash` as its owner, borrower or seller. */
  def namedBy(partyHash: String): Seq[ZcEntry] = byParty.getOrElse(partyHash, Set.empty).toSeq.flatMap(boxes.get)

  /**
   * The state box of a bond asset, share or receipt: the one live bond or settlement holding two units, the
   * share vault holding N+1 shares, or the wrap vault holding two receipts. None when no box or several do.
   */
  def stateOf(token: String): Option[ZcEntry] = holding(token).filter(isState) match {
    case Seq(one) => Some(one)
    case _ => None
  }

  def idAt(at: Int): Option[String] =
    if (at == height) Some(headerId)
    else history.find(_.height == at).map(_.headerId).orElse(history.find(_.height == at + 1).map(_.parentId))

  def base: Int = history.headOption.map(_.height - 1).getOrElse(height)

  /**
   * This book with block `blockId` applied. Each transaction in order drops the boxes it spends and adds the ZC
   * boxes it creates, a state box only when the transaction moves its token from a tracked state box or mints it.
   * A mint whose INPUTS(0) is a tracked order or request records the token's origin, and a token left with no
   * state box loses its origin. Returns the scripts disclosed for hashes the new boxes name that `known` lacks.
   */
  def applied(blockHeight: Int, blockId: String, parentId: String, txs: Seq[ZcTx], cap: Int,
              known: String => Boolean): (ZcBook, Map[String, String]) = {
    require(parentId == headerId && blockHeight == height + 1,
      s"block $blockId at $blockHeight does not follow $headerId at $height")
    val b = new Builder(this)
    var found = Map.empty[String, String]
    var removed = Vector.empty[ZcEntry]
    var added = Vector.empty[String]
    var oracleRemoved = Vector.empty[(NodeBox, Int)]
    var oracleAdded = Vector.empty[String]
    var minted = Vector.empty[String]
    var emptied = Set.empty[String]
    txs.foreach { tx =>
      val minter = tx.inputs.headOption.flatMap(b.boxes.get).map(_.record).filter(isMinter)
      // The tokens whose state box this transaction spends: the only way two units or N+1 shares can move
      val moved = tx.inputs.flatMap(b.boxes.get).filter(isState).flatMap(firstToken).toSet
      tx.inputs.foreach { in =>
        b.boxes.get(in).foreach { gone =>
          b.remove(gone)
          removed :+= gone
          if (isState(gone)) firstToken(gone).foreach(emptied += _)
        }
        b.oracleBoxes.get(in).foreach { gone => b.oracleBoxes -= in; oracleRemoved :+= gone }
      }
      tx.outputs.foreach { out =>
        if (ZcBoxes.holdsOracleNft(out) && !b.oracleBoxes.contains(out.boxId)) {
          b.oracleBoxes += out.boxId -> (out -> blockHeight)
          oracleAdded :+= out.boxId
        }
        ZcBoxes.read(out).filter(r => admissible(r, moved, minter, b.cut)).foreach { record =>
          val entry = ZcEntry(record, blockHeight)
          val admitted = if (b.boxes.contains(record.boxId)) None else b.admit(entry, cap)
          admitted.foreach { evicted =>
            evicted.foreach { e =>
              removed :+= e
              if (isState(e)) firstToken(e).foreach(emptied += _)
            }
            added :+= record.boxId
            record.namedHash.filterNot(h => known(h) || found.contains(h))
              .foreach(h => tx.carried.get(h).foreach(tree => found += h -> tree))
          }
        }
      }
      minter.filter(m => !b.origins.contains(m.boxId) && tx.outputs.exists(_.assets.exists(_.tokenId == m.boxId)))
        .foreach { m =>
          b.origins += m.boxId -> ZcOrigin.of(m, tx.id, Some(blockHeight))
          minted :+= m.boxId
        }
    }
    // A token whose state box left the book and was not replaced has nothing left for its origin to prove
    val dropped = emptied.toVector.filter(t => b.origins.contains(t) && !b.holdsState(t)).map(b.origins)
    dropped.foreach(o => b.origins -= o.token)
    val applied = ZcApplied(blockHeight, blockId, parentId, removed, added, oracleRemoved, oracleAdded, minted, dropped)
    (b.result(blockHeight, blockId, trimmed(history :+ applied, cap)), found)
  }

  /** This book as it stood at `at`, undoing retained blocks above it, or None when `at` is below [[base]]. */
  def rolledBackTo(at: Int): Option[ZcBook] =
    if (at < base || at > height) None
    else Some(history.reverseIterator.takeWhile(_.height > at).foldLeft(this) { (state, block) =>
      val b = new Builder(state)
      val added = block.added.toSet
      block.added.foreach(id => b.boxes.get(id).foreach(b.remove))
      // A box the block both added and spent was never in the book before it
      block.removed.filterNot(e => added.contains(e.boxId)).foreach(b.add)
      val oracleAdded = block.oracleAdded.toSet
      block.oracleAdded.foreach(id => b.oracleBoxes -= id)
      block.oracleRemoved.filterNot(o => oracleAdded.contains(o._1.boxId)).foreach(o => b.oracleBoxes += o._1.boxId -> o)
      block.originsAdded.foreach(t => b.origins -= t)
      block.originsDropped.foreach(o => b.origins += o.token -> o)
      b.result(block.height - 1, block.parentId, state.history.dropRight(1))
    })
}

object ZcBook {

  /** Blocks kept for undoing a fork. Deeper forks rescan. */
  final val MaxHistory = 16

  /** The kinds whose box is a token's state box. */
  val StateKinds: Set[ZcKind] = Set(ZcKind.Bond, ZcKind.BondVault, ZcKind.FractionalVault, ZcKind.DutchAuctionVault,
    ZcKind.WrapVault)

  def isState(e: ZcEntry): Boolean = StateKinds.contains(e.record.script.kind)

  private def firstToken(e: ZcEntry): Option[String] = e.record.box.assets.headOption.map(_.tokenId)

  /** The kinds whose spending as INPUTS(0) mints a token named after the spent box. */
  def isMinter(r: ZcRecord): Boolean = r match {
    case _: ZcOrder | _: ZcShareRequest | _: ZcWrapRequest => true
    case _ => false
  }

  /**
   * Whether a new box may join the book. A state box's reserve can only come from the token's previous state box
   * or its mint, so one made any other way is hand-made and proves nothing. Once the book is cut a previous state
   * box may have been evicted, so a state box then joins on value alone.
   */
  private[zc] def admissible(r: ZcRecord, moved: Set[String], minter: Option[ZcRecord], cut: Boolean): Boolean =
    !StateKinds.contains(r.script.kind) || cut || r.box.assets.headOption.exists { t =>
      moved.contains(t.tokenId) || minter.exists(_.boxId == t.tokenId)
    }

  /**
   * `history` with its oldest blocks dropped while the boxes it holds for undoing exceed `cap`, keeping the newest
   * block. A fork deeper than what is left rescans instead.
   */
  private def trimmed(history: Vector[ZcApplied], cap: Int): Vector[ZcApplied] = {
    var kept = history.takeRight(MaxHistory)
    var held = kept.map(_.removed.size).sum
    while (kept.size > 1 && held > cap) {
      held -= kept.head.removed.size
      kept = kept.tail
    }
    kept
  }

  /**
   * A book read from a scan at `height`, with no history. `found` is already within the cap; a box seen twice
   * (one tree scanned across a moving page) is kept once.
   */
  def seeded(height: Int, headerId: String, found: Seq[ZcEntry], oracle: Seq[(NodeBox, Int)], cut: Boolean): ZcBook = {
    val b = new Builder(ZcBook(height, headerId, Map.empty, Map.empty, Map.empty, TreeSet.empty, Map.empty, Map.empty,
      Vector.empty, cut, height))
    found.foreach(e => if (!b.boxes.contains(e.boxId)) b.add(e))
    oracle.foreach(o => b.oracleBoxes += o._1.boxId -> o)
    b.result(height, headerId, Vector.empty)
  }

  /** The book's maps under change, so one block's edits allocate one new book. */
  private final class Builder(from: ZcBook) {
    var boxes: Map[String, ZcEntry] = from.boxes
    var byToken: Map[String, Set[String]] = from.byToken
    var byParty: Map[String, Set[String]] = from.byParty
    var byValue: TreeSet[(Long, String)] = from.byValue
    var oracleBoxes: Map[String, (NodeBox, Int)] = from.oracleBoxes
    var origins: Map[String, ZcOrigin] = from.origins
    var cut: Boolean = from.cut

    def add(e: ZcEntry): Unit = {
      val id = e.boxId
      boxes += id -> e
      byValue += e.value -> id
      firstToken(e).foreach(t => byToken = plus(byToken, t, id))
      e.record.namedHash.foreach(p => byParty = plus(byParty, p, id))
    }

    def remove(e: ZcEntry): Unit = {
      val id = e.boxId
      boxes -= id
      byValue -= e.value -> id
      firstToken(e).foreach(t => byToken = minus(byToken, t, id))
      e.record.namedHash.foreach(p => byParty = minus(byParty, p, id))
    }

    /**
     * Adds `e` while the book is under `cap`, or in place of the box holding least nanoERG when `e` holds more.
     * Returns None when `e` was left out, else the box evicted for it, if any. Either way past the cap sets `cut`.
     */
    def admit(e: ZcEntry, cap: Int): Option[Option[ZcEntry]] =
      if (boxes.size < cap) { add(e); Some(None) }
      else {
        cut = true
        byValue.headOption.filter(_._1 < e.value).flatMap(least => boxes.get(least._2)).map { evicted =>
          remove(evicted)
          add(e)
          Some(evicted)
        }
      }

    def holdsState(token: String): Boolean = byToken.getOrElse(token, Set.empty).exists(id => boxes.get(id).exists(isState))

    def result(height: Int, headerId: String, history: Vector[ZcApplied]): ZcBook =
      ZcBook(height, headerId, boxes, byToken, byParty, byValue, oracleBoxes, origins, history, cut, from.scannedAt)
  }

  private def plus(m: Map[String, Set[String]], key: String, id: String): Map[String, Set[String]] =
    m.updated(key, m.getOrElse(key, Set.empty) + id)

  private def minus(m: Map[String, Set[String]], key: String, id: String): Map[String, Set[String]] = {
    val left = m.getOrElse(key, Set.empty) - id
    if (left.isEmpty) m - key else m.updated(key, left)
  }
}

/** A ZC box a mempool transaction creates and no other mempool transaction spends. */
final case class ZcPending(record: ZcRecord, txId: String)

/**
 * The mempool laid over a book: which confirmed ZC boxes a mempool transaction spends (and which one), the ZC boxes
 * mempool transactions create that nothing in the mempool spends, the mints among them, and the transaction spending
 * the oracle box. Built only from an observation taken on the book's own block. `read` holds every ZC box the
 * mempool creates, so the next observation parses only boxes it has not seen.
 */
final case class ZcMempool(anchor: String, observedAt: Long, spentBy: Map[String, String],
                           pending: Map[String, ZcPending], origins: Map[String, ZcOrigin],
                           oracleSpentBy: Option[String], read: Map[String, ZcPending])

object ZcMempool {

  /** `snapshot` over `book`, reusing what `previous` parsed, or None when the snapshot was taken on another block. */
  def over(book: ZcBook, snapshot: CompleteMempool.Snapshot, previous: Option[ZcMempool]): Option[ZcMempool] =
    if (!snapshot.anchor.equalsIgnoreCase(book.headerId)) None
    else {
      val txs = snapshot.transactions.map(_.body)
      val seen = previous.map(_.read).getOrElse(Map.empty)
      var spentBy = Map.empty[String, String]
      var oracleSpentBy = Option.empty[String]
      val oracleBox = book.oracle.map(_.box.boxId)
      txs.foreach(tx => tx.inputs.foreach { in =>
        if (book.boxes.contains(in.boxId) && !spentBy.contains(in.boxId)) spentBy += in.boxId -> tx.id
        if (oracleBox.contains(in.boxId) && oracleSpentBy.isEmpty) oracleSpentBy = Some(tx.id)
      })
      // Every ZC box the mempool creates, chained ones included, so a mint of a pending request is still seen
      val read: Map[String, ZcPending] = txs.flatMap { tx =>
        val minter = tx.inputs.headOption.flatMap(in => book.boxes.get(in.boxId)).map(_.record)
        val moved = tx.inputs.flatMap(in => book.boxes.get(in.boxId)).filter(ZcBook.isState)
          .flatMap(_.record.box.assets.headOption.map(_.tokenId)).toSet
        tx.outputs.flatMap { o =>
          seen.get(o.boxId).orElse(ZcBoxes.read(o).map(ZcPending(_, tx.id)))
            .filter(p => ZcBook.admissible(p.record, moved, minter, book.cut)).map(o.boxId -> _)
        }
      }.toMap
      val pending = read.filter { case (id, _) => !snapshot.spent.contains(id) && !book.boxes.contains(id) }
      val origins = txs.flatMap { tx =>
        tx.inputs.headOption.map(_.boxId).flatMap(id => book.boxes.get(id).map(_.record).orElse(read.get(id).map(_.record)))
          .filter(m => ZcBook.isMinter(m) && !book.origins.contains(m.boxId) &&
            tx.outputs.exists(_.assets.exists(_.tokenId == m.boxId)))
          .map(m => m.boxId -> ZcOrigin.of(m, tx.id, None))
      }.toMap
      Some(ZcMempool(snapshot.anchor, snapshot.observedAt, spentBy, pending, origins, oracleSpentBy, read))
    }
}

/**
 * One published snapshot of the ZC market: the confirmed book, the mempool over it when an observation on the
 * book's block exists, and `revision`, which changes only when either changes what a reader would see.
 */
final case class ZcView(book: ZcBook, mempool: Option[ZcMempool], revision: Long) {

  /** Every open ZC box: confirmed ones no mempool transaction spends, then pending ones, with a pending flag. */
  def open: Iterator[(ZcRecord, Boolean)] =
    book.boxes.valuesIterator.filterNot(e => mempool.exists(_.spentBy.contains(e.boxId))).map(_.record -> false) ++
      mempool.iterator.flatMap(_.pending.valuesIterator.map(_.record -> true))

  /** The open box `boxId`, with its pending flag. */
  def record(boxId: String): Option[(ZcRecord, Boolean)] =
    book.entry(boxId).filterNot(_ => mempool.exists(_.spentBy.contains(boxId))).map(_.record -> false)
      .orElse(mempool.flatMap(_.pending.get(boxId)).map(_.record -> true))

  /** The mempool transaction spending `boxId`, when one does. */
  def spentBy(boxId: String): Option[String] = mempool.flatMap(_.spentBy.get(boxId))

  def origin(token: String): Option[ZcOrigin] = book.origins.get(token).orElse(mempool.flatMap(_.origins.get(token)))

  /** Whether a mempool transaction spends the confirmed oracle box, so a trade reading it waits for the next block. */
  def refreshPending: Boolean = mempool.exists(_.oracleSpentBy.isDefined)

  /** What `revision` follows: the block, and the mempool's effect on the market. */
  def fingerprint: ZcView.Fingerprint =
    ZcView.Fingerprint(book.headerId, mempool.map(_.spentBy.keySet).getOrElse(Set.empty),
      mempool.map(_.pending.keySet).getOrElse(Set.empty), refreshPending)
}

object ZcView {
  final case class Fingerprint(headerId: String, spent: Set[String], pending: Set[String], refreshPending: Boolean)
}
