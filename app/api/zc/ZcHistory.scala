package api.zc

import api.LithosApiErrors.LithosUnavailable
import configs.{NodeContext, ZcConfig}
import node.NodeApi
import node.model.{IndexedTransaction, NodeAsset, NodeBox, WalletTransaction}
import org.bouncycastle.util.encoders.Hex
import play.api.Configuration
import play.api.libs.json._
import scorex.crypto.hash.Blake2b256
import sigmafi.zc.{ZcContracts, ZcMarket}
import transactions.batching.sigmafi.zc._

import javax.inject.{Inject, Singleton}
import scala.collection.mutable
import scala.util.{Failure, Success}

/**
 * One thing a transaction did for this wallet. `amount` is in `market`'s unit (nanoERG or raw SigUSD): what was paid
 * for an acquisition (`lent`, `bought`), what was received for a disposal (`sold`, `claimed`), the principal of a
 * `borrowed` or `repaid` bond. `units` of `token` moved.
 */
final case class ZcEvent(txId: String, height: Int, index: Int, kind: String, market: Option[ZcMarket],
                         token: Option[String], units: Long, amount: BigInt, refs: Map[String, String]) {
  def id: String = s"$txId:$index"
}

/** What one held token cost: `paid` for `units` raw units, last acquired at `height` in `txId`. */
final case class ZcCost(paid: BigInt, units: Long, market: Option[ZcMarket], height: Int, txId: String,
                        viaOffer: Option[String])

/** Cost basis per held token, and realized P&L per market name, from this wallet's history. */
final case class ZcLedger(costs: Map[String, ZcCost], realized: Map[String, BigInt], status: String)

/**
 * This wallet's ZC history, from its own transactions since `sigmafi.zc.fillsFromHeight`: what `GET
 * /sigmafi/zc/activity` lists, and the cost basis and realized P&L the portfolio marks against. One reader serves both.
 *
 * Each refresh reads the wallet's transactions from the last height read, less [[ZcHistory.Reorg]] blocks, in windows
 * of [[ZcHistory.Window]] blocks, so a shallow reorg is read again. A transaction is looked at closer only when its
 * outputs carry a ZC mark (a ZC tree, or a register tag naming a box) or it spends a box of this wallet's holding a ZC
 * token: one indexed read, for its input boxes, kept for good. Everything else costs nothing past the wallet read.
 */
@Singleton
final class ZcHistory(api: () => NodeApi, walletHashes: () => Set[String], tracker: ZcTracker, startHeight: Int,
                      maxEvents: Int = ZcHistory.MaxEvents) {

  @Inject def this(node: NodeContext, tracker: ZcTracker, config: Configuration) =
    this(() => node.getNodeApi, () => node.getNodeWallet.signableTrees.map(t => Hex.toHexString(Blake2b256.hash(Hex.decode(t)))),
      tracker, ZcConfig(config).fillsFromHeight)

  import ZcHistory._

  private var events = Vector.empty[ZcEvent]
  private var readTo = 0
  private var refreshedAt = 0L
  private var readAtHeight = -1
  /** Classified transactions, by id: none for one looked at and found to do nothing for ZC. */
  private val classified = mutable.LinkedHashMap.empty[String, Seq[ZcEvent]]
  /** This wallet's boxes holding a ZC token, from the transactions that made them. */
  private val tokenBoxes = mutable.LinkedHashMap.empty[String, Set[String]]

  def status: String = if (startHeight <= 0) "not-configured" else if (readTo == 0) "loading" else "ready"

  /** Every event, newest first, after a refresh when the chain moved. */
  def all(): Vector[ZcEvent] = synchronized { refresh(); events }

  /** Cost basis and realized P&L, oldest event first. */
  def ledger(): ZcLedger = synchronized {
    refresh()
    ZcHistory.ledger(events.reverse, status)
  }

  /** Reads what is new since the last refresh. A failed read leaves the history as it was and says so. */
  private def refresh(): Unit = if (startHeight > 0) {
    val tip = tracker.view.map(_.book.height).getOrElse(0)
    val now = System.currentTimeMillis()
    val from = math.max(startHeight, readTo - Reorg)
    if (tip >= from && (tip != readAtHeight || now - refreshedAt > RefreshMs)) {
      val hashes = walletHashes()
      var at = from
      var fresh = Vector.empty[ZcEvent]
      var failed = false
      while (!failed && at <= tip) {
        val to = math.min(at + Window - 1, tip)
        api().walletTransactions(Some(at), Some(to)) match {
          case Success(txs) =>
            txs.sortBy(t => (t.inclusionHeight, t.id)).foreach(t => fresh ++= look(t, hashes))
            at = to + 1
          case Failure(ex) =>
            if (readTo == 0) throw LithosUnavailable(s"could not read this wallet's transactions: ${ex.getMessage}", ex)
            failed = true
        }
      }
      if (!failed) {
        events = (fresh.reverse ++ events.filter(_.height < from)).take(maxEvents)
        readTo = tip
        readAtHeight = tip
        refreshedAt = now
      }
    }
  }

  /** The events of `t`, reading its inputs only when it shows a ZC mark or spends a ZC token box of this wallet's. */
  private def look(t: WalletTransaction, hashes: Set[String]): Seq[ZcEvent] =
    classified.getOrElse(t.id, {
      if (!marked(t)) Seq.empty
      else api().indexedTransactionById(t.id) match {
        case Success(Some(full)) =>
          val found = classify(full, hashes)
          remember(t.id, found, full, hashes)
          found
        case _ => Seq.empty
      }
    })

  private def marked(t: WalletTransaction): Boolean =
    t.outputs.exists(o => ZcContracts.classify(o.ergoTree).isDefined || tagged(o)) ||
      t.inputs.exists(i => tokenBoxes.contains(i.boxId))

  private def remember(txId: String, found: Seq[ZcEvent], full: IndexedTransaction, hashes: Set[String]): Unit = {
    if (classified.size >= MaxTxs) classified.remove(classified.head._1)
    classified(txId) = found
    val carried = full.inputs.flatMap(i => tokenBoxes.get(i.box.boxId)).flatten.toSet
    val lotTokens = full.inputs.map(_.box).flatMap(b => ZcBoxes.read(b)).collect { case l: ZcLot => l.box.assets.map(_.tokenId) }.flatten
    val tokens = found.flatMap(e => e.token.toSeq ++ e.refs.get("bondId")).toSet ++ carried ++ lotTokens
    def watch(boxId: String, ids: Set[String]): Unit = {
      if (tokenBoxes.size >= MaxTxs) tokenBoxes.remove(tokenBoxes.head._1)
      tokenBoxes(boxId) = ids
    }
    full.outputs.map(_.box).foreach { b =>
      if (hashes(treeHash(b)) && b.assets.exists(a => tokens(a.tokenId)))
        watch(b.boxId, b.assets.map(_.tokenId).toSet.intersect(tokens))
      else ZcBoxes.read(b) match {
        case Some(bond: ZcBond) if hashes(bond.borrowerHash) => watch(b.boxId, Set(bond.asset))
        case _ =>
      }
    }
  }
}

object ZcHistory {

  /** Blocks read again each refresh, for a shallow reorg. */
  final val Reorg = 30
  /** Blocks one wallet read covers. */
  final val Window = 720
  final val RefreshMs = 10000L
  final val MaxEvents = 10000
  final val MaxTxs = 20000

  private def treeHash(b: NodeBox): String = Hex.toHexString(Blake2b256.hash(Hex.decode(b.ergoTree)))

  /** An output a ZC contract tags with the 32-byte id of the box it settles: principal, payout, refund, return. */
  def tagged(b: NodeBox): Boolean = b.additionalRegisters.values.size <= 2 &&
    b.additionalRegisters.get(4).exists(h => h.startsWith("0e20") && h.length == 68)

  private def tag(b: NodeBox): Option[String] = b.additionalRegisters.get(4).filter(h => h.startsWith("0e20") && h.length == 68)
    .map(_.drop(4))

  private def marketOfTokens(assets: Seq[NodeAsset]): ZcMarket =
    if (assets.exists(_.tokenId == ZcContracts.SigUsdId)) ZcMarket.SigUsd else ZcMarket.Erg

  /** What `b` holds of `m`'s asset. */
  private def holds(b: NodeBox, m: ZcMarket): Long = m.tokenId.fold(b.value)(id => b.assets.filter(_.tokenId == id).map(_.amount).sum)

  /**
   * What one transaction did for the wallet whose trees hash to `wallet`, from its input and output boxes: fills,
   * purchases and sales, lots, repayments and liquidations, claims, share and wrap mints, unwraps, posts and returns.
   */
  def classify(tx: IndexedTransaction, wallet: Set[String]): Seq[ZcEvent] = {
    val ins = tx.inputs.map(_.box)
    val outs = tx.outputs.map(_.box)
    val zin = ins.map(ZcBoxes.read)
    val zout = outs.map(ZcBoxes.read)
    def mine(b: NodeBox): Boolean = wallet(treeHash(b))
    def net(asset: Option[String]): BigInt = {
      def of(b: NodeBox) = BigInt(asset.fold(b.value)(id => b.assets.filter(_.tokenId == id).map(_.amount).sum))
      outs.filter(mine).map(of).sum - ins.filter(mine).map(of).sum
    }
    val found = mutable.ArrayBuffer.empty[ZcEvent]
    def ev(kind: String, market: Option[ZcMarket], token: Option[String], units: Long, amount: BigInt,
           refs: (String, String)*): Unit =
      found += ZcEvent(tx.id, tx.inclusionHeight, found.size, kind, market, token, units, amount, refs.toMap)
    val offerIn = zin.collectFirst { case Some(o: ZcOffer) if wallet(o.ownerHash) => o }
    val via = offerIn.map(o => "viaOffer" -> o.boxId).toSeq
    val consumed = mutable.Set.empty[String]

    // a new bond: INPUTS(0) the order, OUTPUTS(1) the bond holding its two units
    (zin.headOption.flatten, zout.lift(1).flatten) match {
      case (Some(o: ZcOrder), Some(b: ZcBond)) if b.asset == o.boxId =>
        consumed += o.boxId
        offerIn.foreach(f => consumed += f.boxId)
        val principal = if (b.market.tokenId.isEmpty) b.terms(1) else b.principalTokens.headOption.fold(0L)(_.amount)
        if (outs.headOption.exists(u => mine(u) && u.assets.exists(a => a.tokenId == b.asset && a.amount == 1L)))
          ev("lent", Some(b.market), Some(b.asset), 1L, principal, (Seq("assetId" -> b.asset, "orderBoxId" -> o.boxId) ++ via): _*)
        if (wallet(o.borrowerHash))
          ev("borrowed", Some(b.market), Some(b.asset), 0L, principal, "assetId" -> b.asset, "orderBoxId" -> o.boxId,
            "maturity" -> b.maturity.toString)
      case _ =>
    }
    // asks: a payout tagged with the ask's id and the units
    zin.flatten.collect { case a: ZcAsk => a }.foreach { a =>
      consumed += a.boxId
      outs.find(o => ZcBoxes.payoutTag(o).exists(_._1 == a.boxId)) match {
        case Some(p) =>
          val units = ZcBoxes.payoutTag(p).get._2
          val paid = BigInt(holds(p, a.market))
          if (wallet(a.sellerHash)) ev("sold", Some(a.market), Some(a.token.tokenId), units, paid, "askBoxId" -> a.boxId)
          if (net(Some(a.token.tokenId)) > 0)
            ev("bought", Some(a.market), Some(a.token.tokenId), units, paid, (Seq("askBoxId" -> a.boxId) ++ via): _*)
        case None => if (wallet(a.sellerHash)) ev("returned", Some(a.market), Some(a.token.tokenId), a.token.amount, 0, "boxId" -> a.boxId)
      }
    }
    // lots: the payout or the return is tagged with the lot's id. A sale pays the seller the market's asset: its token
    // on a token market, the lot's own nanoERG and more on an ERG market, which a return never does.
    zin.flatten.collect { case l: ZcLot => l }.foreach { l =>
      consumed += l.boxId
      val sale = outs.find(o => tag(o).contains(l.boxId) && (l.market.tokenId match {
        case Some(id) => o.assets.exists(a => a.tokenId == id && a.amount > 0L)
        case None => o.value > l.box.value
      }))
      sale match {
        case Some(p) =>
          val price = BigInt(holds(p, l.market)) - (if (l.market.tokenId.isEmpty) l.box.value else 0L)
          if (wallet(l.sellerHash)) ev("lotSold", Some(l.market), None, 0L, price, "lotBoxId" -> l.boxId)
          // The buyer took the lot's first item, or for a lot of nanoERG alone, paid the token it is sold for.
          val bought = l.box.assets.headOption match {
            case Some(a) => net(Some(a.tokenId)) > 0
            case None => l.market.tokenId.exists(id => net(Some(id)) < 0)
          }
          if (bought) ev("lotBought", Some(l.market), None, 0L, price, "lotBoxId" -> l.boxId)
        case None => if (wallet(l.sellerHash)) ev("returned", Some(l.market), None, 0L, 0, "boxId" -> l.boxId)
      }
    }
    // settlements: a repayment or a liquidation of a bond spent here
    zout.flatten.collect { case s: ZcSettlement => s }.foreach { s =>
      zin.flatten.collectFirst { case b: ZcBond if b.boxId == s.bondBoxId => b }.foreach { b =>
        consumed += b.boxId
        if (!s.liquidated && wallet(b.borrowerHash))
          ev("repaid", Some(b.market), Some(b.asset), 0L, b.repayment, "assetId" -> b.asset)
        if (s.liquidated && wallet(b.borrowerHash)) ev("liquidated", Some(b.market), Some(b.asset), 0L, 0, "assetId" -> b.asset,
          "role" -> "borrower")
        else if (s.liquidated) ev("liquidated", Some(b.market), Some(b.asset), 0L, net(None), "assetId" -> b.asset, "role" -> "runner")
      }
    }
    // a bond recollateralized or extinguished (not settled into a vault)
    zin.flatten.collect { case b: ZcBond => b }.filterNot(b => consumed(b.boxId)).foreach { b =>
      if (zout.flatten.exists { case n: ZcBond => n.asset == b.asset; case _ => false } && wallet(b.borrowerHash))
        ev("recollateralized", Some(b.market), Some(b.asset), 0L, -net(None), "assetId" -> b.asset)
      else if (!outs.exists(_.assets.exists(_.tokenId == b.asset)) && wallet(b.borrowerHash))
        ev("extinguished", Some(b.market), Some(b.asset), 1L, b.repayment, "assetId" -> b.asset, "bondBoxId" -> b.boxId)
    }
    // claims: a settlement or a share vault spent while this wallet's tokens burn
    zin.flatten.collect { case s: ZcSettlement => s }.foreach { s =>
      val m = marketOfTokens(s.proceeds)
      if (!outs.exists(_.assets.exists(_.tokenId == s.asset)) && ins.exists(b => mine(b) && b.assets.exists(_.tokenId == s.asset)))
        ev("claimed", Some(m), Some(s.asset), 1L, net(m.tokenId), "assetId" -> s.asset, "liquidated" -> s.liquidated.toString)
    }
    zin.flatten.collect { case v: ZcShareVault => v }.foreach { v =>
      val held = ins.flatMap(_.assets).filter(_.tokenId == v.shareId).map(_.amount).sum
      val left = outs.flatMap(_.assets).filter(_.tokenId == v.shareId).map(_.amount).sum
      val mineBurned = ins.filter(mine).flatMap(_.assets).filter(_.tokenId == v.shareId).map(_.amount).sum -
        outs.filter(mine).flatMap(_.assets).filter(_.tokenId == v.shareId).map(_.amount).sum
      val m = ZcMarket.all.find(_.tokenId == ZcRules.vaultCurrency(v)).getOrElse(ZcMarket.Erg)
      if (held > left && mineBurned > 0L) {
        val unfrac = v.holding && outs.exists(o => mine(o) && o.assets.exists(_.tokenId == v.bondAsset))
        val refs = Seq("vaultBoxId" -> v.boxId, "bondId" -> v.bondAsset) ++
          (if (unfrac) Seq("unfractionalized" -> "true") else Seq.empty)
        ev("claimed", Some(m), Some(v.shareId), math.min(mineBurned, v.outstanding), net(m.tokenId), refs: _*)
      } else if (v.holding && zin.flatten.exists(_.isInstanceOf[ZcSettlement]) && net(None) > 0)
        ev("pulled", None, Some(v.shareId), 0L, net(None), "vaultBoxId" -> v.boxId)
    }
    // mints and requests
    zin.flatten.collect { case r: ZcShareRequest => r }.foreach { r =>
      consumed += r.boxId
      val minted = zout.flatten.exists { case v: ZcShareVault => v.shareId == r.shareId; case _ => false }
      if (minted && wallet(r.ownerHash))
        ev("sharesMinted", None, Some(r.shareId), r.terms.head, 0, "bondId" -> r.bondAsset, "requestBoxId" -> r.boxId)
      else if (!minted && wallet(r.ownerHash)) ev("returned", None, Some(r.bondAsset), 1L, 0, "boxId" -> r.boxId)
    }
    zin.flatten.collect { case r: ZcWrapRequest => r }.foreach { r =>
      consumed += r.boxId
      val minted = zout.flatten.exists { case v: ZcWrapVault => v.receiptId == r.receiptId; case _ => false }
      if (minted && wallet(r.ownerHash)) ev("wrapped", None, Some(r.receiptId), 1L, 0, "tokenId" -> r.wrapped.tokenId)
      else if (!minted && wallet(r.ownerHash)) ev("returned", None, Some(r.wrapped.tokenId), r.wrapped.amount, 0, "boxId" -> r.boxId)
    }
    zin.flatten.collect { case v: ZcWrapVault => v }.foreach { v =>
      if (!outs.exists(_.assets.exists(_.tokenId == v.receiptId)) && net(Some(v.wrapped.tokenId)) > 0)
        ev("unwrapped", None, Some(v.receiptId), 1L, 0, "tokenId" -> v.wrapped.tokenId)
    }
    // orders and offers returned to this wallet, unfilled
    zin.flatten.foreach {
      case o: ZcOrder if !consumed(o.boxId) && wallet(o.borrowerHash) =>
        ev("returned", Some(o.market), None, 0L, 0, "boxId" -> o.boxId)
      case o: ZcOffer if !consumed(o.boxId) && wallet(o.ownerHash) &&
        !zout.flatten.exists { case n: ZcOffer => n.predecessor.contains(o.boxId); case _ => false } =>
        ev("returned", Some(o.market), None, 0L, 0, "boxId" -> o.boxId)
      case _ =>
    }
    // posts: a box naming this wallet that continues nothing spent here
    val spentIds = ins.map(_.boxId).toSet
    zout.flatten.foreach { r =>
      val continues = r match {
        case o: ZcOffer => o.predecessor.exists(spentIds)
        case a: ZcAsk => a.predecessor.exists(spentIds)
        case _ => false
      }
      val named = r match {
        case _: ZcOrder | _: ZcOffer | _: ZcAsk | _: ZcLot | _: ZcShareRequest | _: ZcWrapRequest => r.namedHash.exists(wallet)
        case _ => false
      }
      if (named && !continues) ev("posted", r.script.market, None, 0L, 0, "boxId" -> r.boxId, "contract" -> r.script.kind.name)
    }
    found.toVector
  }

  /**
   * Cost basis and realized P&L from `events`, oldest first. An acquisition adds what was paid; a disposal takes the
   * average cost of the units it gives up and realizes the rest; a share mint or an un-fractionalized final claim
   * carries the cost across the unit and its shares; an extinguish clears the burned unit's cost without realizing
   * lending P&L. Units with no recorded acquisition have no cost, and their disposals realize nothing.
   */
  def ledger(events: Seq[ZcEvent], status: String): ZcLedger = {
    val costs = mutable.Map.empty[String, ZcCost]
    val realized = mutable.Map.empty[String, BigInt]
    events.foreach { e =>
      (e.kind, e.token) match {
        case ("lent" | "bought", Some(t)) =>
          val before = costs.get(t)
          costs(t) = ZcCost(before.fold(BigInt(0))(_.paid) + e.amount, before.fold(0L)(_.units) + e.units, e.market,
            e.height, e.txId, e.refs.get("viaOffer"))
        case ("claimed", Some(shares)) if e.refs.get("unfractionalized").contains("true") =>
          e.refs.get("bondId").foreach { bondId =>
            costs.remove(shares).foreach(c => costs(bondId) = c.copy(units = 1L, height = e.height, txId = e.txId))
          }
        case ("sold" | "claimed", Some(t)) =>
          costs.get(t).filter(_.units > 0L).foreach { c =>
            val u = math.min(e.units, c.units)
            val part = c.paid * u / c.units
            e.market.foreach(m => realized(m.name) = realized.getOrElse(m.name, BigInt(0)) + e.amount - part)
            if (c.units - u <= 0L) costs.remove(t) else costs(t) = c.copy(paid = c.paid - part, units = c.units - u)
          }
        case ("extinguished", Some(t)) =>
          costs.get(t).filter(_.units > 0L).foreach { c =>
            val u = math.min(e.units, c.units)
            if (c.units - u <= 0L) costs.remove(t)
            else {
              val part = c.paid * u / c.units
              costs(t) = c.copy(paid = c.paid - part, units = c.units - u)
            }
          }
        case ("sharesMinted", Some(shares)) =>
          e.refs.get("bondId").flatMap(costs.remove).foreach(c => costs(shares) = c.copy(units = e.units, height = e.height,
            txId = e.txId))
        case _ =>
      }
    }
    ZcLedger(costs.toMap, realized.toMap, status)
  }

  def json(e: ZcEvent, status: String): JsObject = Json.obj("id" -> e.id, "height" -> e.height, "txId" -> e.txId,
    "type" -> e.kind, "status" -> status, "market" -> e.market.map(_.name), "refs" -> e.refs,
    "amounts" -> Json.obj("tokenId" -> e.token, "units" -> e.units.toString, "amount" -> e.amount.toString))
}
