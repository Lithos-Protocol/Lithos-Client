package stats

import configs.{NodeContext, StatsStorageConfig, ZcConfig}
import node.NodeApi
import node.model.{NodeHeader, NodeTransaction}
import org.slf4j.LoggerFactory
import play.api.Configuration
import play.api.libs.json._
import sigmafi.zc.{ZcContracts, ZcMarket}
import state.synchronization.CanonicalBlockSource
import storage.{KeyValueMutation, KeyValueStore, WriteDurability}
import transactions.batching.sigmafi.zc._

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Paths
import java.util.concurrent.Executors
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.Try

/**
 * One completed ZC exchange: a new bond from an order (`kind` "new"), or an ask purchase of a bond unit ("resale")
 * or of shares ("shares"). Values are at the fill: `cost` paid, `face` the repayment it buys, `blocksLeft` to maturity.
 * `id` is the transaction id and the input index of the order or ask, so it is stable.
 */
final case class ZcFill(id: String, txId: String, height: Int, timestamp: Long, market: String, kind: String, via: String,
                        offerId: Option[String], sourceId: String, bondId: String, blocksLeft: Long, cost: Long,
                        face: Long, yieldBps: Option[Long], maturity: Long, coverBps: Option[Long])

object ZcFill {
  implicit val format: OFormat[ZcFill] = Json.format[ZcFill]
}

/** A page of fills, with the index's own standing. */
final case class ZcFillPage(status: String, observedAt: Option[Long], indexedTo: Option[Int], fills: Seq[ZcFill],
                            next: Option[String])

/**
 * The ZC fill history: canonical blocks replayed from `sigmafi.zc.fillsFromHeight` on its own thread, each fill kept in
 * the stats store under its own keys and pruned past `sigmafi.zc.fillsRetentionDays` (default 1460 days, ~4 years; 0
 * keeps fills forever). Live bond and share facts maturing past the cutoff stay so later resales still price. Two node
 * reads a block on average while catching up (a slice of headers, then each block's transactions), plus one read to
 * confirm each fill's order or ask and one for its oracle data input. A reorg is undone block by block from the
 * per-block records.
 *
 * Fills are recognised from outputs: a new bond at OUTPUTS(1) holding two units of INPUTS(0)'s id, or a payout tagged
 * with an input's id in R4 and the units in R5. Bond and share terms learned along the way (`bond/`, `share/`) price
 * later resales without reading the bond again.
 */
@Singleton
final class ZcFills(settings: ZcConfig, openStore: () => ZcFillStore, api: () => NodeApi, worker: ExecutionContext,
                    enabled: Boolean, blocksPerCycle: Int = ZcFills.BlocksPerCycle) {

  @Inject def this(config: Configuration, node: NodeContext) = this(ZcConfig(config),
    () => ZcFillStore.open(StatsStorageConfig(config), ZcConfig(config)), () => node.getNodeApi, ZcFills.ownThread(),
    config.getOptional[Boolean]("stats.enabled").getOrElse(true) && StatsStorageConfig(config).enabled)

  import ZcFills._

  private val logger = LoggerFactory.getLogger("ZcFills")
  private val running = new AtomicBoolean(false)
  private val state = new AtomicReference(Standing(if (!on) "disabled" else if (settings.fillsFromHeight <= 0)
    "not-configured" else "loading", None, None))
  @volatile private var store: Option[ZcFillStore] = None

  private def on: Boolean = enabled && settings.enabled

  def standing: Standing = state.get()

  /** Starts a cycle when none is running and the index is configured. */
  def tick(): Unit = if (on && settings.fillsFromHeight > 0 && running.compareAndSet(false, true)) {
    Try(Future(try cycle() catch {
      case NonFatal(e) =>
        logger.warn(s"ZC fill history could not advance: ${e.getMessage}")
        state.updateAndGet(s => s.copy(status = if (s.indexedTo.isDefined) "stale" else "unavailable"))
    } finally running.set(false))(worker)).failed.foreach(_ => running.set(false))
  }

  /** Fills in `market` from `fromHeight`, newest first, paged by an opaque cursor. */
  def fills(market: ZcMarket, fromHeight: Int, kind: Option[String], via: Option[String], cursor: Option[String],
            limit: Int): ZcFillPage = {
    val s = state.get()
    val db = store
    val all = db.map(_.fillsFrom(fromHeight)).getOrElse(Vector.empty).filter(f => f.market == market.name &&
      kind.forall(_ == f.kind) && via.forall(_ == f.via)).sortBy(f => (-f.height, f.id))
    val from = cursor.flatMap(c => Try(c.toInt).toOption).filter(_ >= 0).getOrElse(0)
    val page = all.slice(from, from + limit)
    ZcFillPage(s.status, s.observedAt, s.indexedTo, page, if (from + limit < all.size) Some((from + limit).toString) else None)
  }

  private def cycle(): Unit = {
    val db = store.getOrElse { val opened = openStore(); store = Some(opened); opened }
    val node = api()
    val source = new CanonicalBlockSource(node)
    val indexed = node.indexedHeight().get.indexedHeight
    // A chain not yet at the start height, such as testnet under the mainnet default, waits rather than failing
    if (db.cursor.isEmpty && indexed < settings.fillsFromHeight) {
      state.set(Standing("loading", Some(System.currentTimeMillis()), None))
      return
    }
    if (db.cursor.isEmpty) {
      val start = settings.fillsFromHeight
      val parent = source.headerAt(start - 1).get
      db.initialize(parent.height, parent.id)
    }
    var work = 0
    // Undo blocks the canonical chain no longer holds
    def canonical(c: (Int, String)): Boolean = c._1 <= indexed && source.headerAt(c._1).toOption.exists(_.id == c._2)
    while (!canonical(db.cursor.get) && work < blocksPerCycle) {
      val (ch, _) = db.cursor.get
      if (ch < settings.fillsFromHeight || !db.hasBlock(ch)) {
        db.clear()
        db.initialize(settings.fillsFromHeight - 1, source.headerAt(settings.fillsFromHeight - 1).get.id)
      } else db.rollback()
      work += 1
    }
    val (at, _) = db.cursor.get
    val to = math.min(indexed, at + (blocksPerCycle - work))
    if (to > at) {
      val headers = source.headerSlice(at + 1, to).get
      headers.foreach { h =>
        require(h.parentId == db.cursor.get._2, s"block ${h.id} does not follow the fill history's cursor")
        val txs = node.blockTransactions(h.id).get.map(_.transactions).getOrElse(
          throw new NoSuchElementException(s"the node has no transactions for block ${h.id}"))
        db.append(h, read(node, db, h, txs))
      }
    }
    val cursor = db.cursor.get._1
    if (settings.fillsRetentionDays > 0) {
      val cutoff = cursor.toLong - settings.fillsRetentionDays.toLong * BlocksPerDay
      if (cutoff > 0L) db.prune(cutoff.min(Int.MaxValue).toInt)
    }
    state.set(Standing(if (cursor < indexed) "catching-up" else "ready", Some(System.currentTimeMillis()), Some(cursor)))
  }

  /** What block `h` adds: its fills, and the bond and share terms it teaches. */
  private def read(node: NodeApi, db: ZcFillStore, h: NodeHeader, txs: Seq[NodeTransaction]): BlockFills = {
    var fills = Vector.empty[ZcFill]
    var bonds = Map.empty[String, BondFacts]
    var shares = Map.empty[String, (String, Long)]
    def bondFacts(asset: String) = bonds.get(asset).orElse(db.bond(asset))
    def minter(boxId: String): Option[ZcRecord] =
      Try(node.indexedBoxById(boxId).get).toOption.flatten.flatMap(b => ZcBoxes.read(b.box))
    def oracleCover(tx: NodeTransaction, bond: ZcBondTerms): Option[Long] =
      tx.dataInputs.headOption.flatMap(d => Try(node.indexedBoxById(d.boxId).get).toOption.flatten)
        .flatMap(b => ZcBoxes.oracle(b.box)).flatMap(o => ZcRules.cover(bond, o.price)).map(ZcPricing.ratioBps)
    def viaOffer(tx: NodeTransaction): Option[String] = {
      val inputs = tx.inputs.map(_.boxId).toSet
      tx.outputs.iterator.flatMap(o => ZcBoxes.read(o)).collectFirst {
        case o: ZcOffer if o.predecessor.exists(inputs.contains) => o.predecessor.get
      }
    }
    txs.foreach { tx =>
      val first = tx.inputs.headOption.map(_.boxId)
      // A new bond: OUTPUTS(1) holds two units of INPUTS(0)'s id, and INPUTS(0) was an order
      tx.outputs.lift(1).flatMap(o => ZcBoxes.read(o)).collect { case b: ZcBond if first.contains(b.asset) => b }.foreach { b =>
        if (minter(b.asset).exists(_.isInstanceOf[ZcOrder])) {
          val principal = if (b.market.tokenId.isEmpty) b.terms(1) else b.principalTokens.headOption.fold(0L)(_.amount)
          val left = b.maturity - h.height
          bonds += b.asset -> BondFacts(b.market.name, b.repayment, b.maturity)
          fills :+= ZcFill(s"${tx.id}:0", tx.id, h.height, h.timestamp, b.market.name, "new",
            if (viaOffer(tx).isDefined) "offer" else "wallet", viaOffer(tx), b.asset, b.asset, left, principal,
            b.repayment, ZcPricing.yieldBps(BigInt(b.repayment), BigInt(principal), left), b.maturity,
            oracleCover(tx, ZcBondTerms.of(b)))
        }
      }
      // A share mint: a share vault holding N+1 of INPUTS(0)'s id
      tx.outputs.iterator.flatMap(o => ZcBoxes.read(o)).collect { case v: ZcShareVault if first.contains(v.shareId) => v }
        .foreach { v =>
          if (minter(v.shareId).exists(_.isInstanceOf[ZcShareRequest])) shares += v.shareId -> (v.bondAsset -> v.shares)
        }
      // Ask purchases: a payout tagged with a spent input's id and the units bought
      val inputIndex = tx.inputs.map(_.boxId).zipWithIndex.toMap
      tx.outputs.flatMap(o => ZcBoxes.payoutTag(o).map(o -> _)).foreach { case (payout, (askId, units)) =>
        inputIndex.get(askId).foreach { index =>
          minter(askId).collect { case a: ZcAsk => a }.foreach { a =>
            val token = a.token.tokenId
            val claim = bondFacts(token).map(f => (token, 1L, f)).orElse((shares.get(token).orElse(db.share(token)))
              .flatMap { case (asset, n) => bondFacts(asset).map(f => (asset, n, f)) })
            claim.foreach { case (asset, whole, facts) =>
              val paid = a.market.tokenId.fold(payout.value)(id => payout.assets.filter(_.tokenId == id).map(_.amount).sum)
              val face = (BigInt(facts.repayment) * units / whole).toLong
              val left = facts.maturity - h.height
              fills :+= ZcFill(s"${tx.id}:$index", tx.id, h.height, h.timestamp, a.market.name,
                if (whole > 1L) "shares" else "resale", if (viaOffer(tx).isDefined) "offer" else "wallet", viaOffer(tx),
                askId, asset, left, paid, face, ZcPricing.yieldBps(BigInt(face), BigInt(paid), left), facts.maturity, None)
            }
          }
        }
      }
    }
    BlockFills(fills, bonds, shares)
  }
}

object ZcFills {

  final val BlocksPerCycle = 200
  /** Expected Ergo blocks per day (2-minute block target), used to convert `fillsRetentionDays` to a height cutoff. */
  final val BlocksPerDay = 720
  /** Recent blocks never pruned so normal chain reorganisations always have per-block undo records. */
  final val RollbackBuffer = 64
  /** Most old block records (and matured fact entries) pruned per cycle, keeping background disk I/O bounded. */
  final val PruneBatchSize = 256

  final case class Standing(status: String, observedAt: Option[Long], indexedTo: Option[Int])

  /** What a bond unit's resale is priced against: its market, repayment and maturity. */
  final case class BondFacts(market: String, repayment: Long, maturity: Long)

  object BondFacts {
    implicit val format: OFormat[BondFacts] = Json.format[BondFacts]
  }

  /** What one block added. */
  final case class BlockFills(fills: Vector[ZcFill], bonds: Map[String, BondFacts], shares: Map[String, (String, Long)])

  def ownThread(): ExecutionContext = ExecutionContext.fromExecutorService(Executors.newSingleThreadExecutor { (r: Runnable) =>
    val t = new Thread(r, "zc-fills")
    t.setDaemon(true)
    t
  })
}

/**
 * The fill history's store: a cursor, a record per block (its id, parent, and the keys it added, so a reorg removes
 * exactly those), `fill/<height>/<id>`, `bond/<asset>` and `share/<id>`. In memory when stats storage is off.
 */
final class ZcFillStore(database: Option[KeyValueStore], identity: JsObject) {

  import KeyValueMutation.{Delete, Put}
  import ZcFills._

  @volatile private var memory = Map.empty[String, Array[Byte]]

  private def key(s: String): Array[Byte] = s.getBytes(UTF_8)
  private def get(name: String): Option[JsValue] = (database match {
    case Some(db) => KeyValueStore.orThrow(db.get(key(name)))
    case None => memory.get(name)
  }).map(Json.parse)
  private def keys(prefix: String): Vector[String] = (database match {
    case Some(db) => KeyValueStore.orThrow(db.scanKeys(key(prefix))).map(new String(_, UTF_8))
    case None => memory.keysIterator.filter(_.startsWith(prefix)).toVector
  }).sorted
  private def write(changes: Seq[KeyValueMutation]): Unit = database match {
    case Some(db) => KeyValueStore.orThrow(db.write(changes, WriteDurability.Synchronous))
    case None => synchronized {
      memory = changes.foldLeft(memory) {
        case (m, Put(k, v)) => m.updated(new String(k, UTF_8), v)
        case (m, Delete(k)) => m - new String(k, UTF_8)
      }
    }
  }
  private def put(name: String, v: JsValue): KeyValueMutation = Put(key(name), Json.stringify(v).getBytes(UTF_8))
  private def blockKey(h: Int) = f"block/$h%010d"

  // A history written for another pin or start height describes other fills: it is replaced, never mixed
  get("identity") match {
    case Some(found) if found == identity =>
    case Some(_) =>
      clear()
      write(Seq(put("identity", identity)))
    case None => write(Seq(put("identity", identity)))
  }

  def cursor: Option[(Int, String)] = get("cursor").map(j => ((j \ "height").as[Int], (j \ "blockId").as[String]))

  def initialize(height: Int, blockId: String): Unit =
    write(Seq(put("cursor", Json.obj("height" -> height, "blockId" -> blockId))))

  def append(h: NodeHeader, added: BlockFills): Unit = {
    val fillKeys = added.fills.map(f => f"fill/${f.height}%010d/${f.id}")
    val bondKeys = added.bonds.keys.map("bond/" + _).toVector
    val shareKeys = added.shares.keys.map("share/" + _).toVector
    write(added.fills.zip(fillKeys).map { case (f, k) => put(k, Json.toJson(f)) } ++
      added.bonds.map { case (a, f) => put("bond/" + a, Json.toJson(f)) } ++
      added.shares.map { case (s, (a, n)) => put("share/" + s, Json.obj("bond" -> a, "n" -> n)) } ++
      Seq(put(blockKey(h.height), Json.obj("id" -> h.id, "parent" -> h.parentId, "keys" -> (fillKeys ++ bondKeys ++ shareKeys))),
        put("cursor", Json.obj("height" -> h.height, "blockId" -> h.id))))
  }

  /** Removes the cursor's block and everything it added, moving the cursor to its parent. */
  def rollback(): Unit = cursor.foreach { case (h, _) =>
    val record = get(blockKey(h)).getOrElse(throw new IllegalStateException(s"no fill history record for block $h"))
    val added = (record \ "keys").as[Seq[String]]
    write(added.map(k => Delete(key(k))) ++ Seq(Delete(key(blockKey(h))),
      put("cursor", Json.obj("height" -> (h - 1), "blockId" -> (record \ "parent").as[String]))))
  }

  def hasBlock(height: Int): Boolean = get(blockKey(height)).isDefined

  /**
   * Prunes per-block undo records, fills, and matured bond/share facts strictly older than `cutoffHeight` (while
   * keeping at least [[ZcFills.RollbackBuffer]] blocks behind the cursor for reorg rollbacks, and preserving live
   * `bond/` and `share/` facts whose bond matures at or after the prune height so later resales still price).
   * Bounded to `limit` blocks / matured entries per call so pruning never stalls a cycle.
   */
  def prune(cutoffHeight: Int, limit: Int = ZcFills.PruneBatchSize): Int = {
    val maxPrune = cursor.map(_._1 - ZcFills.RollbackBuffer).map(math.min(cutoffHeight, _)).getOrElse(0)
    if (maxPrune <= 0 || limit <= 0) 0
    else {
      val deletes = scala.collection.mutable.LinkedHashSet.empty[String]
      val blockCutoff = blockKey(maxPrune)
      keys("block/").iterator.takeWhile(_ < blockCutoff).take(limit).foreach { bk =>
        deletes += bk
        get(bk).foreach { record =>
          (record \ "keys").asOpt[Seq[String]].getOrElse(Seq.empty).foreach { k =>
            if (k.startsWith("fill/")) deletes += k
            else if (k.startsWith("bond/")) {
              if (bond(k.stripPrefix("bond/")).forall(_.maturity < maxPrune)) deletes += k
            } else if (k.startsWith("share/")) {
              if (share(k.stripPrefix("share/")).forall { case (asset, _) => bond(asset).forall(_.maturity < maxPrune) })
                deletes += k
            }
          }
        }
      }

      // Sweep any remaining fill keys older than maxPrune, plus long-dated bonds/shares that have now matured.
      val fillCutoff = f"fill/$maxPrune%010d"
      keys("fill/").iterator.takeWhile(_ < fillCutoff).filterNot(deletes.contains).take(limit).foreach(deletes += _)

      var extraBonds = 0
      keys("bond/").iterator.filterNot(deletes.contains).foreach { bk =>
        if (extraBonds < limit && bond(bk.stripPrefix("bond/")).forall(_.maturity < maxPrune)) {
          deletes += bk
          extraBonds += 1
        }
      }
      var extraShares = 0
      keys("share/").iterator.filterNot(deletes.contains).foreach { sk =>
        if (extraShares < limit) {
          val dead = share(sk.stripPrefix("share/")).forall { case (asset, _) =>
            deletes.contains("bond/" + asset) || bond(asset).forall(_.maturity < maxPrune)
          }
          if (dead) {
            deletes += sk
            extraShares += 1
          }
        }
      }

      if (deletes.nonEmpty) write(deletes.iterator.map(k => Delete(key(k))).toSeq)
      deletes.size
    }
  }

  /** Drops the whole history, for a fork deeper than its start. */
  def clear(): Unit = write(Seq("fill/", "bond/", "share/", "block/").flatMap(keys).map(k => Delete(key(k))) :+ Delete(key("cursor")))

  def bond(asset: String): Option[BondFacts] = get("bond/" + asset).map(_.as[BondFacts])

  def share(id: String): Option[(String, Long)] = get("share/" + id).map(j => ((j \ "bond").as[String], (j \ "n").as[Long]))

  def fillsFrom(height: Int): Vector[ZcFill] =
    keys("fill/").filter(_ >= f"fill/$height%010d").flatMap(k => get(k).map(_.as[ZcFill]))

  def close(): Unit = database.foreach(_.close())
}

object ZcFillStore {

  final val SchemaVersion = 1

  def identity(zc: ZcConfig): JsObject =
    Json.obj("schema" -> SchemaVersion, "pin" -> ZcContracts.PinnedCommit, "from" -> zc.fillsFromHeight)

  def open(storage: StatsStorageConfig, zc: ZcConfig): ZcFillStore = {
    val db = if (storage.enabled)
      Some(KeyValueStore.openOrThrow(storage.backend, Paths.get(storage.path).resolve(s"zc-fills-v$SchemaVersion"))) else None
    new ZcFillStore(db, identity(zc))
  }

  def inMemory(zc: ZcConfig): ZcFillStore = new ZcFillStore(None, identity(zc))
}
