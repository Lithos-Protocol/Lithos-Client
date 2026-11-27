package api.zc

import api.LithosApiErrors.{LithosBadRequest, LithosNotFound, LithosUnavailable}
import node.NodeApi
import node.model.NodeAsset
import org.ergoplatform.appkit.{Address, NetworkType}
import play.api.libs.json._
import sigmafi.zc.{ZcContracts, ZcKind, ZcMarket}
import transactions.batching.sigmafi.zc.ZcPricing._
import transactions.batching.sigmafi.zc.ZcRules._
import transactions.batching.sigmafi.zc._

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import java.util.concurrent.{ConcurrentHashMap, Executors}
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/** The rate book's filters, as `asks`, `offers`, `curves` and `orderbook` take them. */
final case class ZcFilters(coverage: Long = 15000L, kind: String = "all", matchable: Boolean = true, grid: Boolean = false) {
  require(Set("all", "new", "resale", "shares").contains(kind), s"kind must be all, new, resale or shares, not $kind")
  require(coverage >= 0L, "coverage cannot be negative")
}

/**
 * The `/sigmafi/zc` reads, each answered from one tracker snapshot. Nothing here waits on the tracker's thread: a read
 * takes the published view as it is. What the tracker cannot know (an origin it did not see minted, a script it did
 * not see disclosed, a chain's first box) is looked up through the [[ZcResolver]], at most [[ZcReads.ReadsPerRequest]]
 * node reads a request; past that, a record says "not looked up yet" rather than wait. Curves, order books and batch
 * simulations are cached by snapshot and filters.
 *
 * @param walletHashes the hashes of the trees this node's wallet signs for, for `mine` and the portfolio
 * @param walletBalance this wallet's confirmed and pending nanoERG and tokens, for the portfolio
 */
class ZcReads(tracker: ZcTracker, resolver: ZcResolver, node: () => NodeApi, walletHashes: () => Set[String],
                    walletBalance: () => (Long, Seq[NodeAsset]), network: NetworkType = NetworkType.MAINNET,
                    readsPerRequest: Int = ZcReads.ReadsPerRequest, warmReads: Int = ZcReads.WarmReads,
                    warmer: Option[ExecutionContext] = Some(ZcReads.ownThread()), history: Option[ZcHistory] = None) {

  import ZcReads._

  /** One snapshot and what was derived from it; replaced when the tracker's revision or the resolver's cache moves. */
  private final class Snap(val view: ZcView, val generation: Long) {
    val book: ZcMarketBook = ZcMarketBook.of(view)
    val matcher = new ZcMatcher(book, resolver.knownOrigin, excluded = r => resolver.knownDisclosed(r).contains(false))
    private val runs = new ConcurrentHashMap[ZcMarket, ZcBatchRun]()
    def run(m: ZcMarket): ZcBatchRun = runs.computeIfAbsent(m, mm => matcher.batch(mm))
    val derived = new Lru[String, JsObject](CacheEntries)
    val pricedAsks = new Lru[(ZcMarket, Set[String]), Vector[AskRow]](16)
    val pricedOffers = new Lru[ZcMarket, Vector[(ZcOpen[ZcOffer], ZcStanding)]](8)
  }

  private val current = new AtomicReference[Option[Snap]](None)
  private val snapLock = new Object
  private val warming = new AtomicBoolean(false)

  /** The snapshot for the tracker's current view, built once per revision. 503 before the first seed. */
  private def snap(): Snap = {
    if (!tracker.enabled) throw LithosUnavailable("ZC tracking is off (sigmafi.zc.enabled)")
    val view = tracker.view.getOrElse(throw LithosUnavailable("ZC tracking has not finished its first scan"))
    val gen = resolver.generation
    def matches(s: Snap): Boolean =
      s.view.revision == view.revision && s.view.book.headerId == view.book.headerId && s.generation == gen
    current.get() match {
      case Some(s) if matches(s) => s
      case _ =>
        var built: Option[Snap] = None
        val result = snapLock.synchronized {
          current.get() match {
            case Some(s) if matches(s) => s
            case _ =>
              val s = new Snap(view, gen)
              current.set(Some(s))
              built = Some(s)
              s
          }
        }
        built.foreach(warm)
        result
    }
  }

  /** The revision a caller polls: 304 when it already has it. */
  def revision: Option[Long] = if (tracker.enabled) tracker.view.map(_.revision) else None

  /** What one request may look up, and what it found, so a page's records agree with each other. */
  private final class Lookups(s: Snap, presetHashes: Option[Set[String]] = None,
                              val budget: ZcBudget = new ZcBudget(readsPerRequest)) {
    private val origins = mutable.Map.empty[String, Option[ZcOrigin]]
    private val chains = mutable.Map.empty[String, Option[(String, Long)]]
    private lazy val mine: Set[String] = presetHashes.getOrElse(Try(walletHashes()).getOrElse(Set.empty))
    def origin(t: String): Option[ZcOrigin] = origins.getOrElseUpdate(t, resolver.origin(t, budget))
    def chain(r: ZcRecord): Option[(String, Long)] = chains.getOrElseUpdate(r.boxId, resolver.chainRoot(r, budget))
    def disclosed(r: ZcRecord): Option[Boolean] = resolver.knownDisclosed(r).orElse {
      val pending = s.view.book.entry(r.boxId).isEmpty
      resolver.script(r, pending, budget).map(_ => true).orElse(resolver.knownDisclosed(r))
    }
    val known: ZcKnown = ZcKnown(origin, disclosed, h => mine.contains(h), chain)
    /** A matcher that also sees what this request looked up. */
    lazy val matcher = new ZcMatcher(s.book, t => origin(t), excluded = r => resolver.knownDisclosed(r).contains(false))
    lazy val json = new ZcJson(matcher, known)
  }

  /**
   * Looks up, in the background, the origins and scripts the open book still lacks, at most `warmReads` reads a
   * snapshot, so curves and matches cover boxes the tracker seeded. One pass at a time, on its own thread.
   */
  private def warm(s: Snap): Unit = warmer.foreach { ec =>
    if (warming.compareAndSet(false, true)) Future {
      try {
        val budget = new ZcBudget(warmReads)
        val tokens = s.book.asks.iterator.map(_.record.token.tokenId) ++ s.book.shareVaults.valuesIterator
          .flatMap(v => Iterator(v.record.shareId, v.record.bondAsset)) ++ s.book.bonds.keysIterator
        tokens.takeWhile(_ => budget.remaining > 0).foreach(t => resolver.origin(t, budget))
        val named = (s.book.orders.iterator ++ s.book.asks.iterator ++ s.book.offers.iterator).filter(o =>
          resolver.knownDisclosed(o.record).isEmpty)
        named.takeWhile(_ => budget.remaining > 0).foreach(o => resolver.script(o.record, o.pending, budget))
      } finally warming.set(false)
    }(ec)
  }

  private def market(name: String): ZcMarket =
    ZcMarket.named(name).getOrElse(throw LithosBadRequest(s"unknown market $name: ERG or SigUSD"))

  private def withAt(s: Snap, body: JsObject): JsObject = ZcJson.at(s.book) ++ body

  // ─── 1. info ───────────────────────────────────────────────────────────────

  def info(): JsObject = {
    val s = snap()
    val o = s.book.currentOracle
    val markets = ZcMarket.all.map { m =>
      val contracts = ZcContracts.scripts.filter(c => c.market.forall(_ == m)).map { c =>
        c.kind.name -> Json.obj("treeHash" -> c.treeHash, "templateHash" -> c.templateHash,
          "address" -> Try(Address.fromErgoTree(c.contract.ergoTree, network).toString).toOption)
      }
      val exprs = ZcContracts.expressions.filter(e => e.market.forall(_ == m)).map(e => e.expression.name -> JsString(e.hash))
      Json.obj("market" -> m.name, "tokenId" -> m.tokenId, "decimals" -> (if (m.tokenId.isEmpty) 9 else 2),
        "contracts" -> JsObject(contracts), "expressions" -> JsObject(exprs))
    }
    withAt(s, Json.obj(
      "network" -> (if (network == NetworkType.MAINNET) "mainnet" else "testnet"), "profile" -> "zcs1",
      "pinnedCommit" -> ZcContracts.PinnedCommit, "deployed" -> s.view.book.boxes.nonEmpty,
      "oracle" -> o.map(or => Json.obj("feed" -> "erg-usd", "boxId" -> or.box.boxId, "nanoErgPerUsd" -> or.price.toString,
        "usdPerErg" -> usdPerErg(BigInt(or.price)), "updatedHeight" -> or.box.creationHeight,
        "age" -> (s.book.height - or.box.creationHeight), "pending" -> s.book.oraclePending,
        "refreshPending" -> s.book.refreshPending)),
      "markets" -> markets, "defaults" -> Defaults))
  }

  // ─── 2, 3. asks and offers ─────────────────────────────────────────────────

  private def computeAskRows(l: Lookups, s: Snap, m: ZcMarket): Vector[AskRow] = {
    val j = l.json
    val orders = s.book.orders.filter(_.record.market == m).map { o =>
      val q = j.orderQuote(o.record)
      val window = orderWindow(o.record).exists { case (lo, hi) =>
        gridBetween(s.book.height + lo, s.book.height + hi).nonEmpty }
      AskRow(o, q.map(_._2), q.map(_._3), q.flatMap(x => (x._1 \ "cost").asOpt[String]).map(BigInt(_))
        .getOrElse(BigInt(0)), j.defaultProspect(o.record).flatMap(j.coverBps), "new", j.standing(o.record),
        (o.record.mode == 0L && o.record.terms(3) % Grid == 0L) || window, l.known.mine(o.record.borrowerHash))
    }
    val asks = s.book.asks.filter(_.record.market == m).map { a =>
      val claim = l.matcher.claimOf(a.record.token.tokenId).toOption
      val bond = claim.flatMap(c => s.book.bonds.get(c._1)).map(_.record)
      val q = j.askQuote(a.record, bond, claim.map(_._2).getOrElse(1L))
      AskRow(a, q.map(_._2), q.map(_._3), q.flatMap(x => (x._1 \ "cost").asOpt[String]).map(BigInt(_))
        .getOrElse(BigInt(0)), bond.map(ZcBondTerms.of).flatMap(j.coverBps),
        if (claim.exists(_._2 > 1L)) "shares" else "resale", j.standing(a.record), bond.exists(_.maturity % Grid == 0L),
        bond.exists(b => l.known.mine(b.borrowerHash)))
    }
    orders ++ asks
  }

  /**
   * Priced ask rows for `m` at snapshot `s`, cached on `s` once all origins and disclosures fit within the request's
   * read budget (coalescing concurrent misses). When the budget is exhausted mid-scan, the partial pass is returned
   * uncached so later requests continue resolving remaining boxes.
   */
  private def cachedAskRows(s: Snap, m: ZcMarket, hashes: Set[String], budget: ZcBudget): Vector[AskRow] =
    s.pricedAsks.getIf((m, hashes), () => {
      val l = new Lookups(s, Some(hashes), budget)
      val rows = computeAskRows(l, s, m)
      (rows, l.budget.remaining > 0)
    })

  /** Priced offer rows for `m` at snapshot `s`, cached on `s` under the same budget rule as [[cachedAskRows]]. */
  private def cachedOfferRows(s: Snap, m: ZcMarket, budget: ZcBudget): Vector[(ZcOpen[ZcOffer], ZcStanding)] =
    s.pricedOffers.getIf(m, () => {
      val l = new Lookups(s, Some(Set.empty), budget)
      val rows = s.book.offers.filter(_.record.market == m).map(o => o -> l.json.standing(o.record))
      (rows, l.budget.remaining > 0)
    })

  private def renderAsk(l: Lookups)(r: AskRow): JsObject = r.open.record match {
    case _: ZcOrder => l.json.orderJson(r.open.asInstanceOf[ZcOpen[ZcOrder]])
    case _: ZcAsk => l.json.askJson(r.open.asInstanceOf[ZcOpen[ZcAsk]])
    case other => throw new IllegalStateException(s"unexpected ask row ${other.getClass.getSimpleName}")
  }

  private def keep(r: AskRow, f: ZcFilters, from: Option[Long], to: Option[Long]): Boolean =
    (!f.matchable || r.standing.ok) && r.cover.forall(_ >= f.coverage) && (!f.grid || r.grid) &&
      (f.kind match {
        case "all" => true
        case "new" => r.kind == "new"
        case "resale" => r.kind == "resale" || r.kind == "shares"
        case _ => r.kind == "shares"
      }) && from.forall(x => r.blocksLeft.exists(_ >= x)) && to.forall(x => r.blocksLeft.exists(_ <= x))

  def asks(marketName: String, f: ZcFilters, from: Option[Long], to: Option[Long], sort: Option[String],
           cursor: Option[String], limit: Option[Int]): JsObject = {
    val s = snap()
    val m = market(marketName)
    val hashes = Try(walletHashes()).getOrElse(Set.empty)
    val budget = new ZcBudget(readsPerRequest)
    val rows = cachedAskRows(s, m, hashes, budget).filter(keep(_, f, from, to))
    val sorted = sort.getOrElse("apr") match {
      case "apr" => rows.sortBy(r => (-r.yieldBps.getOrElse(Long.MinValue + 1), r.open.boxId))
      case "maturity" => rows.sortBy(r => (r.blocksLeft.getOrElse(Long.MaxValue), r.open.boxId))
      case "size" => rows.sortBy(r => (-r.cost, r.open.boxId))
      case other => throw LithosBadRequest(s"sort must be apr, maturity or size, not $other")
    }
    val renderLookups = new Lookups(s, Some(hashes), budget)
    withAt(s, paged(sorted, cursor, limit, (r: AskRow) => r.open.pending)(renderAsk(renderLookups)))
  }

  def offers(marketName: String, f: ZcFilters, from: Option[Long], to: Option[Long], sort: Option[String],
             cursor: Option[String], limit: Option[Int]): JsObject = {
    val s = snap()
    val m = market(marketName)
    val hashes = Try(walletHashes()).getOrElse(Set.empty)
    val budget = new ZcBudget(readsPerRequest)
    val rows = cachedOfferRows(s, m, budget).filter { case (o, st) =>
      val r = o.record
      (!f.matchable || st.ok) && floorBps(r) <= f.coverage && (f.kind != "shares" || buysShares(r)) &&
        from.forall(x => maxBlocks(r) >= x) && to.forall(x => minBlocks(r) <= x)
    }.map(_._1)
    val sorted = sort.getOrElse("apr") match {
      case "apr" => rows.sortBy(o => (o.record.terms(0), o.boxId))
      case "maturity" => rows.sortBy(o => (minBlocks(o.record), o.boxId))
      case "size" => rows.sortBy(o => (-o.record.capital, o.boxId))
      case other => throw LithosBadRequest(s"sort must be apr, maturity or size, not $other")
    }
    val renderLookups = new Lookups(s, Some(hashes), budget)
    withAt(s, paged(sorted, cursor, limit, (o: ZcOpen[ZcOffer]) => o.pending)(renderLookups.json.offerJson))
  }

  /**
   * A page of `rows`. Pending rows are not ranked by anything a spammer pays for, so a page carries at most
   * [[MaxPendingRows]] of them and says how many it left out.
   */
  private def paged[A](rows: Vector[A], cursor: Option[String], limit: Option[Int], pending: A => Boolean)
                      (render: A => JsObject): JsObject = {
    val from = cursor.map(c => Try(c.toInt).filter(_ >= 0).getOrElse(throw LithosBadRequest("cursor must be a number"))).getOrElse(0)
    val size = limit.getOrElse(DefaultPage)
    if (size < 1 || size > MaxPage) throw LithosBadRequest(s"limit must be 1 to $MaxPage")
    var pendingSeen = 0
    val (kept, leftOut) = rows.partition { r => !pending(r) || { pendingSeen += 1; pendingSeen <= MaxPendingRows } }
    val page = kept.slice(from, from + size)
    Json.obj("items" -> page.map(render), "total" -> kept.size,
      "next" -> (if (from + size < kept.size) Some((from + size).toString) else None),
      "pendingLeftOut" -> leftOut.size)
  }

  // ─── 4. curves ─────────────────────────────────────────────────────────────

  def curves(marketName: String, f: ZcFilters): JsObject = {
    val s = snap()
    val m = market(marketName)
    val hashes = Try(walletHashes()).getOrElse(Set.empty)
    val wf = hashes.hashCode
    s.derived.get(s"curves:$m:$f:$wf", () => {
      val budget = new ZcBudget(readsPerRequest)
      val offers = cachedOfferRows(s, m, budget)
      val matchableOffers = offers.filter { case (o, st) => st.ok && floorBps(o.record) <= f.coverage }
      val bidders = (if (f.matchable) matchableOffers else offers.filter(x => floorBps(x._1.record) <= f.coverage))
        .map(_._1.record).sortBy(o => (o.terms(0), o.boxId)).take(MaxCurveOffers)
      val samples = (Iterator.iterate(0L)(_ + SampleStep).takeWhile(_ <= BlocksPerYear) ++
        bidders.iterator.flatMap(o => Iterator(minBlocks(o), maxBlocks(o)) ++ o.curve.iterator.map(_._2)))
        .filter(d => d >= 0L && d <= BlocksPerYear).toVector.distinct.sorted.take(MaxSamples)
      val bidLine = samples.map { d =>
        val best = bidders.iterator.filter(o => d >= minBlocks(o) && d <= maxBlocks(o))
          .map(o => o -> offerCurve(o).bpsAt(d)).foldLeft(Option.empty[(ZcOffer, Long)]) { (b, x) =>
            if (b.forall(y => x._2 < y._2)) Some(x) else b }
        Json.obj("blocks" -> d, "bps" -> best.map(_._2), "offerId" -> best.map(_._1.boxId))
      }
      val rows = cachedAskRows(s, m, hashes, budget)
      val shown = rows.filter(keep(_, f, None, None)).filter(_.yieldBps.isDefined).sortBy(r => (-r.yieldBps.get, r.open.boxId))
      val dots = shown.take(MaxDots)
      val askLine = dots.groupBy(r => r.blocksLeft.getOrElse(0L) / Grid).toVector.sortBy(_._1).map { case (_, rs) =>
        val best = rs.maxBy(r => (r.yieldBps.get, r.open.boxId))
        Json.obj("blocks" -> best.blocksLeft, "bps" -> best.yieldBps, "askId" -> best.open.boxId)
      }.sortBy(j => (j \ "blocks").as[Long])
      val windows = s.book.orders.filter(o => o.record.market == m && window(o.record)).take(MaxDots).flatMap { o =>
        for { c <- borrowCurve(o.record); (lo, hi) <- orderWindow(o.record) } yield Json.obj("askId" -> o.boxId,
          "minBlocks" -> lo, "maxBlocks" -> hi, "capBps" -> c.maxBps,
          "points" -> c.points.map { case (y, d) => Json.obj("blocks" -> d, "bps" -> y) })
      }
      val matchableAsks = rows.filter(r => r.standing.ok && r.cover.forall(_ >= f.coverage))
      withAt(s, Json.obj("filters" -> filtersJson(f), "domain" -> Seq(0L, BlocksPerYear), "bidLine" -> bidLine,
        "askLine" -> askLine, "askDots" -> dots.map(r => Json.obj("askId" -> r.open.boxId, "kind" -> r.kind, "blocks" -> r.blocksLeft,
          "bps" -> r.yieldBps, "ok" -> r.standing.ok, "pending" -> r.open.pending, "borrowedByMe" -> r.borrowedByMe)),
        "windows" -> windows,
        "totals" -> Json.obj(
          "asks" -> Json.obj("matchable" -> matchableAsks.size, "count" -> rows.size, "cost" -> matchableAsks.map(_.cost).sum.toString),
          "offers" -> Json.obj("matchable" -> matchableOffers.size, "count" -> offers.size,
            "capital" -> matchableOffers.map(x => BigInt(x._1.record.capital)).sum.toString),
          "searched" -> Json.obj("asks" -> dots.size, "offers" -> bidders.size))))
    })
  }

  private def filtersJson(f: ZcFilters): JsObject =
    Json.obj("coverage" -> f.coverage, "kind" -> f.kind, "matchable" -> f.matchable, "grid" -> f.grid)

  // ─── 5. orderbook ──────────────────────────────────────────────────────────

  /**
   * The order book over blocks-left range [lo, hi], read at one anchor maturity: `to` in up-to mode, `around` in
   * around mode, `hi` for the whole book. A bid and an ask are only ever compared at the same maturity: each ask
   * carries the best bid at its own maturity (`bidAtBps`) and the gap to it (`spreadBps`), and the top-of-book
   * spread is the tightest of those gaps, with the maturity it occurs at. Asks and bids that share no maturity in
   * range give no spread rather than one made up across maturities. Each bid row reads its offer at the anchor,
   * with its lowest and highest yield in range beside it.
   */
  def orderbook(marketName: String, f: ZcFilters, to: Option[Long], around: Option[Long], band: Option[Long]): JsObject = {
    val s = snap()
    val m = market(marketName)
    val (lo, hi, anchor) = (to, around) match {
      case (Some(t), None) if t >= 720L => (720L, t, t)
      case (None, Some(a)) if a > 0L =>
        band.foreach(b => if (b < 0L) throw LithosBadRequest("band cannot be negative"))
        val b = band.getOrElse(math.max(720L, a / 10L))
        val hi = (BigInt(a) + b).min(BigInt(Long.MaxValue)).toLong
        (math.max(1L, a - b), hi, a)
      case (None, None) => (720L, BlocksPerYear, BlocksPerYear)
      case _ => throw LithosBadRequest("give either to (at least 720) or around, not both")
    }
    val hashes = Try(walletHashes()).getOrElse(Set.empty)
    val wf = hashes.hashCode
    s.derived.get(s"book:$m:$f:$lo:$hi:$anchor:$wf", () => {
      val budget = new ZcBudget(readsPerRequest)
      val h = s.book.height.toLong
      val allBidders = cachedOfferRows(s, m, budget)
        .filter { case (o, st) => (!f.matchable || st.ok) && floorBps(o.record) <= f.coverage &&
          maxBlocks(o.record) >= lo && minBlocks(o.record) <= hi }
        .map { case (o, st) => (o, st, offerCurve(o.record)) }
      val bidders = allBidders.sortBy { case (o, _, _) => (o.record.terms(0), o.boxId) }.take(MaxCurveOffers)
      // Precompute/memoize best bid per distinct maturity so window spreads never rescan all bidders repeatedly.
      val bidCache = mutable.HashMap.empty[Long, Option[Long]]
      def bidAt(d: Long): Option[Long] = bidCache.getOrElseUpdate(d, bidders.iterator.collect {
        case (o, _, c) if d >= minBlocks(o.record) && d <= maxBlocks(o.record) => c.bpsAt(d)
      }.reduceOption(_ min _))
      val bidBlocks = bidders.iterator.flatMap { case (o, _, _) =>
        Iterator(minBlocks(o.record), maxBlocks(o.record)) ++ o.record.curve.iterator.map(_._2)
      }.filter(d => d >= lo && d <= hi).toVector.distinct.sorted.take(MaxSamples)
      bidBlocks.foreach(bidAt)

      val rows = cachedAskRows(s, m, hashes, budget).filter(keep(_, f, None, None))
      val (windowRows, plainRows) = rows.partition(r => r.open.record match {
        case o: ZcOrder => window(o)
        case _ => false
      })
      val topWindows = windowRows.flatMap { r =>
        val o = r.open.record.asInstanceOf[ZcOrder]
        bestWindowQuote(o, h, lo, hi).map(q => (r, o, q))
      }.sortBy { case (r, _, (_, bps, _)) => (-bps, r.open.boxId) }.take(MaxDots)
      val pricedWindows = topWindows.map { case (r, o, (blocks, bps, cost)) =>
        val gap = bestWindowSpread(o, h, lo, hi, bidAt, bidBlocks)
        (Json.obj("boxId" -> r.open.boxId, "kind" -> r.kind, "bps" -> bps, "size" -> cost.toString, "blocks" -> blocks,
          "window" -> true, "ok" -> r.standing.ok, "reason" -> r.standing.reasons.headOption,
          "borrowedByMe" -> r.borrowedByMe, "bidAtBps" -> gap.map(_.bid), "spreadBps" -> gap.map(_.gap)), gap)
      }
      val pricedPlain = plainRows.flatMap { r =>
        r.blocksLeft.filter(b => b >= lo && b <= hi).map { b =>
          val bid = bidAt(b)
          val gap = for (x <- bid; y <- r.yieldBps) yield Gap(b, x, y)
          (Json.obj("boxId" -> r.open.boxId, "kind" -> r.kind, "bps" -> r.yieldBps, "size" -> r.cost.toString,
            "blocks" -> b, "window" -> false, "ok" -> r.standing.ok, "reason" -> r.standing.reasons.headOption,
            "borrowedByMe" -> r.borrowedByMe, "bidAtBps" -> bid, "spreadBps" -> gap.map(_.gap)), gap)
        }
      }
      val priced = pricedWindows ++ pricedPlain
      val asks = priced.map(_._1)
        .sortBy(j => (-(j \ "bps").asOpt[Long].getOrElse(Long.MinValue + 1), (j \ "boxId").as[String])).take(MaxBookRows)
      val tightest = priced.flatMap(_._2).sortBy(_.rank).headOption

      val bids = allBidders.map { case (o, st, c) =>
        val r = o.record
        val (a, b) = (math.max(lo, minBlocks(r)), math.min(hi, maxBlocks(r)))
        val ys = (Seq(a, b) ++ r.curve.map(_._2).filter(d => d >= a && d <= b)).map(c.bpsAt)
        val atY = c.bpsAt(math.min(b, math.max(a, anchor)))
        (atY, Json.obj("boxId" -> o.boxId, "bps" -> atY, "minBps" -> ys.min, "maxBps" -> ys.max,
          "size" -> r.capital.toString, "range" -> Seq(a, b), "ok" -> st.ok, "reason" -> st.reasons.headOption))
      }.sortBy { case (y, j) => (y, (j \ "boxId").as[String]) }.take(MaxBookRows).map(_._2)

      val anchorBid = bidAt(anchor)
      val bestAsk = asks.flatMap(j => (j \ "bps").asOpt[Long]).reduceOption(_ max _)
      val bestBid = anchorBid.orElse(bids.map(j => (j \ "bps").as[Long]).reduceOption(_ min _))
      withAt(s, Json.obj("filters" -> filtersJson(f), "range" -> Json.obj("from" -> lo, "to" -> hi),
        "anchorBlocks" -> anchor, "anchorBidBps" -> anchorBid, "asks" -> asks, "bids" -> bids,
        "bestAskBps" -> bestAsk, "bestBidBps" -> bestBid, "spreadBps" -> tightest.map(_.gap),
        "spreadBlocks" -> tightest.map(_.blocks), "spreadBidBps" -> tightest.map(_.bid),
        "spreadAskBps" -> tightest.map(_.ask)))
    })
  }

  /**
   * A window order's tightest same-maturity gap to the bid curve in blocks-left range [lo, hi]: the maturity where
   * the best bid most nearly meets (or falls under) what the order pays. Searches the window's ends, its curve's
   * breakpoints, the eligible offers' bounds and breakpoints (`bidBlocks`) and the grid.
   */
  private def bestWindowSpread(o: ZcOrder, h: Long, lo: Long, hi: Long, bidAt: Long => Option[Long],
                               bidBlocks: Seq[Long]): Option[Gap] =
    for {
      (wlo, whi) <- orderWindow(o)
      c <- borrowCurve(o)
      at = h + 1L
      from = math.max(h + wlo, at + lo)
      until = math.min(h + whi, at + hi)
      if from <= until
      best <- (Seq(from, until) ++ c.breakpoints.map(at + _) ++ bidBlocks.map(at + _) ++ gridBetween(from, until))
        .filter(x => x >= from && x <= until).distinct
        .flatMap { mat =>
          val blocks = mat - at
          for {
            bid <- bidAt(blocks)
            p <- resolvePrincipal(o, h, mat).toOption
            y <- ZcPricing.yieldBps(BigInt(o.repayment), BigInt(p), blocks)
          } yield Gap(blocks, bid, y)
        }.sortBy(_.rank).headOption
    } yield best

  /** A window order's best maturity in blocks-left range [lo, hi] for whoever fills it: the highest yield it pays. */
  private def bestWindowQuote(o: ZcOrder, h: Long, lo: Long, hi: Long): Option[(Long, Long, BigInt)] =
    for {
      (wlo, whi) <- orderWindow(o)
      c <- borrowCurve(o)
      at = h + 1L
      from = math.max(h + wlo, at + lo)
      until = math.min(h + whi, at + hi)
      if from <= until
      best <- (Seq(from, until) ++ c.breakpoints.map(at + _) ++ gridBetween(from, until)).filter(x => x >= from && x <= until)
        .distinct.flatMap(mat => resolvePrincipal(o, h, mat).toOption.map(p =>
          (mat - at, ZcPricing.yieldBps(BigInt(o.repayment), BigInt(p), mat - at).getOrElse(0L), BigInt(p))))
        .sortBy(x => (-x._2, x._1)).headOption
    } yield best

  // ─── 6. matches ────────────────────────────────────────────────────────────

  def matches(id: String): JsObject = {
    val s = snap()
    val l = new Lookups(s)
    val rec = s.view.record(id).getOrElse(throw LithosNotFound(s"$id is not an open ZC box"))
    rec._1 match {
      case o: ZcOffer =>
        val state = ZcOfferState.of(o)
        val sources = l.matcher.sources(o.market).take(ZcMatcher.MaxSources)
        val buys = l.matcher.candidates(state, sources)
        val run = s.run(o.market)
        val taken = run.taken.getOrElse(o.boxId, Vector.empty)
        val spent = taken.map(t => BigInt(t.price)).sum
        withAt(s, Json.obj("type" -> "offer", "offerId" -> o.boxId,
          "buys" -> buys.take(MaxMatchRows).map(mt => matchJson(s, l, mt, sourceKey = "askId")),
          "next" -> Json.obj("taken" -> taken.map(_.sourceId), "spent" -> spent.toString,
            "avgYieldBps" -> weightedYield(taken), "rounds" -> run.rounds),
          "searched" -> searchedJson(run.searched, sources.size)))
      case o: ZcOrder => askMatches(s, l, id, o.market, Right(ZcOrderSource(ZcOpen(o, rec._2))))
      case a: ZcAsk => askMatches(s, l, id, a.market, l.matcher.askSource(ZcOpen(a, rec._2)))
      case _ => throw LithosNotFound(s"$id is not an open offer, order or ask")
    }
  }

  /** The offers that would take an order or ask now, and which one zcs1's matching order gives it to. */
  private def askMatches(s: Snap, l: Lookups, id: String, market: ZcMarket, source: Either[String, ZcSource]): JsObject = {
    val offers = l.matcher.offerStates(market).take(ZcMatcher.MaxOffers)
    val takers = source.toOption.toVector.flatMap(src => offers.flatMap(st => l.matcher.evaluate(st, src).toOption))
    val run = s.run(market)
    withAt(s, Json.obj("type" -> "ask", "askId" -> id, "notMatchable" -> source.left.toOption,
      "offers" -> takers.take(MaxMatchRows).map(mt => Json.obj("offerId" -> mt.offerId, "units" -> mt.units.toString,
        "cost" -> mt.cost.toString, "yieldBps" -> mt.yieldBps, "requiredBps" -> mt.requiredBps)),
      "first" -> run.takenBy.get(id).map(_.offerId),
      "searched" -> searchedJson(run.searched, offers.size)))
  }

  private def borrowedAsset(s: Snap, l: Lookups, tokenId: String): Option[String] =
    s.book.bonds.get(tokenId)
      .orElse(s.book.shareVaults.get(tokenId).flatMap(v => s.book.bonds.get(v.record.bondAsset)))
      .filter(b => l.known.mine(b.record.borrowerHash))
      .map(_.record.asset)

  private def matchJson(s: Snap, l: Lookups, mt: ZcMatch, sourceKey: String): JsObject = {
    val rec = s.view.record(mt.sourceId).map(_._1)
    val coverNow = s.book.currentOracle.flatMap { or =>
      rec.flatMap {
        case o: ZcOrder => resolvePrincipal(o, s.book.height, mt.maturity).toOption
          .flatMap(p => cover(ZcBondTerms.prospect(o, s.book.height, mt.maturity, p), or.price))
        case a: ZcAsk => s.book.bonds.values.find(b => b.record.asset == a.token.tokenId || a.pricedAsset.contains(b.record.asset))
          .flatMap(b => cover(ZcBondTerms.of(b.record), or.price))
        case _ => None
      }.map(ratioBps)
    }
    val borrowed = rec.exists {
      case o: ZcOrder => l.known.mine(o.borrowerHash)
      case a: ZcAsk => borrowedAsset(s, l, a.token.tokenId).isDefined
      case _ => false
    }
    Json.obj(sourceKey -> mt.sourceId, "units" -> mt.units.toString, "cost" -> mt.cost.toString, "face" -> mt.face.toString,
      "yieldBps" -> mt.yieldBps, "requiredBps" -> mt.requiredBps, "maturity" -> mt.maturity, "coverBps" -> coverNow,
      "borrowedByMe" -> borrowed)
  }

  private def weightedYield(ms: Seq[ZcMatch]): Option[Long] = {
    val total = ms.map(_.cost).sum
    if (total <= 0) None else Some((ms.map(mt => mt.cost * mt.yieldBps).sum / total).toLong)
  }

  private def searchedJson(sr: ZcSearched, here: Int): JsObject =
    Json.obj("offers" -> sr.offers, "offersSearched" -> sr.offersSearched, "sources" -> sr.sources,
      "sourcesSearched" -> sr.sourcesSearched, "candidates" -> here)

  // ─── 7. simulate ───────────────────────────────────────────────────────────

  /** What a draft would do against the book now. The draft joins a copy of the book, never the tracker's. */
  def simulate(body: JsValue): JsObject = {
    val s = snap()
    val l = new Lookups(s)
    s.derived.get("sim:" + ZcDrafts.hash(body), () => withAt(s, ZcDrafts.simulate(body, l.matcher, l.json)))
  }

  // ─── 9. auctions ───────────────────────────────────────────────────────────

  def auctions(marketName: Option[String]): JsObject = {
    val s = snap()
    val l = new Lookups(s)
    val m = marketName.map(market)
    val h = s.book.height.toLong
    val lots = s.book.lots.filter(o => m.forall(_ == o.record.market)).take(MaxPage).map { o =>
      val lot = o.record
      val Seq(opening, floor, start, end, expiry, refund) = lot.terms.take(6)
      val valid = lotValid(lot)
      val price = if (!valid) None else Some(BigInt(lotPrice(lot, h)))
      val borrowedBondIds = lot.box.assets.flatMap(a => borrowedAsset(s, l, a.tokenId)).distinct
      Json.obj("boxId" -> lot.boxId, "chainId" -> lot.boxId, "pending" -> o.pending, "mine" -> l.known.mine(lot.sellerHash),
        "borrowedByMe" -> borrowedBondIds.nonEmpty, "borrowedBondIds" -> borrowedBondIds,
        "sellerHash" -> lot.sellerHash, "market" -> lot.market.name, "items" -> l.json.tokens(lot.box.assets),
        "nanoErg" -> lot.box.value.toString, "opening" -> opening.toString, "floor" -> floor.toString, "start" -> start,
        "end" -> end, "expiry" -> expiry, "refundFeeNanoErg" -> refund.toString, "priceNow" -> price.map(_.toString),
        "valid" -> valid,
        "phase" -> (if (h + 1L >= expiry) "expired" else if (h < start) "before" else if (h < end) "falling" else "floor"))
    }
    val vaults = s.book.shareVaults.values.toVector.flatMap { o =>
      val v = o.record
      if (!v.dutch || v.holding || !(v.state.contains(1.toByte) || v.state.contains(4.toByte)) || v.auction.size < 7) None
      else {
        val currency = vaultCurrency(v)
        val vm = ZcMarket.all.find(_.tokenId == currency)
        if (m.exists(x => !vm.contains(x))) None else Some((o, currency, vm))
      }
    }.sortBy(_._1.boxId).take(MaxPage).map { case (o, currency, vm) =>
      val v = o.record
      val Seq(repayment, opening, floor, length, expiry, threshold, began) = v.auction.take(7)
      val held = currencyHeld(v)
      val excess = v.state.contains(4.toByte)
      val quote = if (length < 1L) Json.obj("done" -> true)
        else if (excess) {
          val done = BigInt(h) + 1 >= BigInt(began) + BigInt(length)
          val priceOpt = if (done) None else excessPrice(v, h)
          priceOpt match {
            case None => Json.obj("priceRaw" -> JsNull, "done" -> true)
            case Some(p) => Json.obj("priceRaw" -> p.toString, "done" -> false)
          }
        } else {
          val since = (BigInt(h) - BigInt(began)).max(BigInt(0)).min(BigInt(length))
          val target = lotTarget(v, h).fold(BigInt(Long.MaxValue))(BigInt(_)).max(BigInt(held))
          val bps = (BigInt(opening) * BigInt(length) - (BigInt(opening) - BigInt(floor)) * since) / BigInt(length)
          val done = BigInt(h) >= BigInt(began) + BigInt(expiry)
          Json.obj("targetRaw" -> target.toString, "payRaw" -> (target - BigInt(held)).toString,
            "bps" -> bps, "done" -> done)
        }
      val items = v.box.assets.drop(1).filterNot(t => currency.contains(t.tokenId))
        .filter(t => !excess || t.amount < threshold)
      Json.obj("boxId" -> v.boxId, "shareTokenId" -> v.shareId, "bondId" -> v.bondAsset, "market" -> vm.map(_.name),
        "type" -> (if (threshold == Long.MaxValue) "dutch" else "selective"), "state" -> v.state.map(_.toInt),
        "repayment" -> repayment.toString, "openingBps" -> opening, "floorBps" -> floor, "length" -> length,
        "expiry" -> expiry, "auctionStart" -> began, "currencyHeld" -> held.toString, "items" -> l.json.tokens(items),
        "quote" -> quote, "pending" -> o.pending)
    }
    withAt(s, Json.obj("lots" -> lots, "vaults" -> vaults))
  }

  // ─── 10. lookup ────────────────────────────────────────────────────────────

  def lookup(id0: String): JsObject = {
    val id = hexId(id0)
    val s = snap()
    val l = new Lookups(s)
    def recordJson(r: ZcRecord, pending: Boolean): (String, JsObject) = r match {
      case o: ZcOrder => "order" -> l.json.orderJson(ZcOpen(o, pending))
      case a: ZcAsk => "ask" -> l.json.askJson(ZcOpen(a, pending))
      case o: ZcOffer => "offer" -> l.json.offerJson(ZcOpen(o, pending))
      case b: ZcBond => "bond" -> l.json.bondJson(b.asset).getOrElse(Json.obj("assetId" -> b.asset))
      case v: ZcSettlement => "bond" -> l.json.bondJson(v.asset).getOrElse(Json.obj("assetId" -> v.asset))
      case v: ZcShareVault => "shares" -> l.json.sharesJson(v.shareId).getOrElse(Json.obj("tokenId" -> v.shareId))
      case lot: ZcLot => "lot" -> Json.obj("boxId" -> lot.boxId)
      case r: ZcShareRequest => "mintRequest" -> Json.obj("boxId" -> r.boxId, "bondId" -> r.bondAsset,
        "terms" -> r.terms.map(_.toString), "mine" -> l.known.mine(r.ownerHash))
      case r: ZcWrapRequest => "wrapRequest" -> Json.obj("boxId" -> r.boxId, "token" -> r.wrapped.tokenId,
        "amount" -> r.wrapped.amount.toString, "mintFeeNanoErg" -> r.mintFee.toString, "mine" -> l.known.mine(r.ownerHash))
      case v: ZcWrapVault => "wrap" -> wrapJson(v)
    }
    def answer(kind: String, record: JsObject, current: Option[String], spentBy: Option[String],
               successor: Option[String], note: Option[String] = None): JsObject =
      withAt(s, Json.obj("type" -> kind, "record" -> record, "current" -> current, "spentBy" -> spentBy,
        "successorId" -> successor, "note" -> note))
    s.view.record(id) match {
      case Some((r, pending)) =>
        val (kind, json) = recordJson(r, pending)
        answer(kind, json, Some(r.boxId), s.view.spentBy(id), None)
      case None =>
        s.view.spentBy(id).flatMap(txId => s.view.book.entry(id).map(_ -> txId)) match {
          case Some((entry, spentTx)) =>
            val (kind, json) = recordJson(entry.record, pending = false)
            val pendingSucc = s.view.mempool.flatMap(_.pending.values.find { p =>
              p.txId == spentTx && (p.record match {
                case o: ZcOffer => o.predecessor.contains(id)
                case a: ZcAsk => a.predecessor.contains(id)
                case _ => false
              })
            }).map(_.record.boxId)
            answer(kind, json, pendingSucc, Some(spentTx), pendingSucc)
          case None =>
            if (s.book.bonds.contains(id) || s.book.settlements.contains(id))
              answer("bond", l.json.bondJson(id).get, s.book.bonds.get(id).orElse(s.book.settlements.get(id)).map(_.boxId), None, None)
            else if (s.book.shareVaults.contains(id))
              answer("shares", l.json.sharesJson(id).get, s.book.shareVaults.get(id).map(_.boxId), None, None)
            else if (s.book.wrapVaults.contains(id))
              answer("wrap", wrapJson(s.book.wrapVaults(id).record), s.book.wrapVaults.get(id).map(_.boxId), None, None)
            else followSpent(s, l, id, answer)
        }
    }
  }

  /**
   * An id the book does not hold: read from the index, and when spent, followed forward through the transactions
   * that spent it to the box that continues it, at most [[MaxLookupHops]] hops within the request's reads.
   */
  private def followSpent(s: Snap, l: Lookups, id: String,
                          answer: (String, JsObject, Option[String], Option[String], Option[String], Option[String]) => JsObject): JsObject = {
    val first = if (!l.budget.take()) throw LithosUnavailable("the lookup ran out of node reads")
      else Try(node().indexedBoxById(id).get).getOrElse(throw LithosUnavailable("the node's index could not be read"))
    val found = first.getOrElse(throw LithosNotFound(s"$id is not a ZC box, bond asset, share or receipt"))
    val record = ZcBoxes.read(found.box).getOrElse(throw LithosNotFound(s"$id is not a ZC box"))
    val kind = record match {
      case _: ZcOrder => "order"; case _: ZcAsk => "ask"; case _: ZcOffer => "offer"; case _: ZcBond => "bond"
      case _: ZcLot => "lot"; case _: ZcShareRequest => "mintRequest"; case _: ZcWrapRequest => "wrapRequest"; case _ => "vault"
    }
    val base = Json.obj("boxId" -> id, "contract" -> record.script.kind.name, "txId" -> found.box.transactionId,
      "created" -> found.inclusionHeight, "spentTx" -> found.spentTransactionId)
    record match {
      case o: ZcOrder if found.spentTransactionId.isDefined && (s.book.bonds.contains(o.boxId) || s.book.settlements.contains(o.boxId)) =>
        answer("bond", l.json.bondJson(o.boxId).get, None, found.spentTransactionId, None, Some("filled"))
      case _ if found.spentTransactionId.isEmpty =>
        answer(kind, base, None, None, None, Some("unspent, but past the tracker's cap"))
      case _ =>
        var hops = 0
        var at = id
        var spending = found.spentTransactionId
        var successor = Option.empty[String]
        var current = Option.empty[String]
        while (spending.isDefined && hops < MaxLookupHops && l.budget.take()) {
          hops += 1
          val next = Try(node().indexedTransactionById(spending.get).get).toOption.flatten.flatMap { tx =>
            tx.outputs.find(o => ZcBoxes.read(o.box).exists {
              case x: ZcOffer => x.predecessor.contains(at)
              case x: ZcAsk => x.predecessor.contains(at)
              case _ => false
            })
          }
          next match {
            case Some(out) =>
              successor = successor.orElse(Some(out.boxId))
              at = out.boxId
              if (s.view.record(at).isDefined) { current = Some(at); spending = None }
              else spending = out.spentTransactionId
            case None => spending = None
          }
        }
        val note = if (current.isDefined) None else if (successor.isEmpty) Some("spent without a continuation: filled whole, " +
          "cancelled or refunded") else Some("the chain continues past what this lookup followed")
        answer(kind, base ++ current.flatMap(c => s.view.record(c)).map { case (r, p) => Json.obj("current" -> (r match {
          case o: ZcOffer => l.json.offerJson(ZcOpen(o, p)); case a: ZcAsk => l.json.askJson(ZcOpen(a, p))
          case other => Json.obj("boxId" -> other.boxId) })) }.getOrElse(Json.obj()), current, found.spentTransactionId,
          successor, note)
    }
  }

  private def wrapJson(v: ZcWrapVault): JsObject =
    Json.obj("receiptId" -> v.receiptId, "token" -> v.wrapped.tokenId, "amount" -> v.wrapped.amount.toString,
      "vaultBoxId" -> v.boxId, "state" -> "wrapped", "refreshFrom" -> (v.box.creationHeight + RefreshAge + 1L))

  // ─── 11. search ────────────────────────────────────────────────────────────

  def search(q0: String): JsObject = {
    val q = q0.trim.toLowerCase
    if (q.length < 6 || !q.forall(c => Character.digit(c, 16) >= 0)) throw LithosBadRequest("q needs at least 6 hex characters")
    val s = snap()
    val l = new Lookups(s)
    val hits = mutable.LinkedHashMap.empty[String, JsObject]
    def hit(id: String, kind: String, mk: Option[ZcMarket], title: String, status: => Option[String], y: => Option[Long],
            borrowed: => Boolean = false): Unit =
      if (hits.size < MaxSearchHits && id.startsWith(q) && !hits.contains(id))
        hits(id) = Json.obj("id" -> id, "type" -> kind, "market" -> mk.map(_.name), "title" -> title, "status" -> status,
          "yieldBps" -> y, "borrowedByMe" -> borrowed)
    s.book.bonds.foreach { case (asset, b) => hit(asset, "bond", Some(b.record.market), "Bond", Some("live"), None,
      l.known.mine(b.record.borrowerHash)) }
    s.book.settlements.foreach { case (asset, v) => hit(asset, "bond", None, "Bond", Some(if (v.record.liquidated) "liquidated" else "repaid"), None) }
    s.book.shareVaults.keys.foreach(t => hit(t, "shares", None, "Shares", None, None, borrowedAsset(s, l, t).isDefined))
    s.book.wrapVaults.keys.foreach(t => hit(t, "wrap", None, "Wrap receipt", None, None))
    s.book.orders.foreach(o => hit(o.boxId, "order", Some(o.record.market), "Borrow order",
      Some(l.json.standing(o.record).status), l.json.orderQuote(o.record).map(_._2), l.known.mine(o.record.borrowerHash)))
    s.book.asks.foreach(a => hit(a.boxId, "ask", Some(a.record.market), "Ask", Some(l.json.standing(a.record).status), None,
      borrowedAsset(s, l, a.record.token.tokenId).isDefined))
    s.book.offers.foreach(o => hit(o.boxId, "offer", Some(o.record.market), "Lend offer",
      Some(l.json.standing(o.record).status), Some(o.record.terms(0))))
    s.book.lots.foreach(o => hit(o.boxId, "lot", Some(o.record.market), "Lot auction", None, None,
      o.record.box.assets.exists(a => borrowedAsset(s, l, a.tokenId).isDefined)))
    withAt(s, Json.obj("q" -> q, "hits" -> hits.values.toSeq))
  }

  // ─── 12. assets ────────────────────────────────────────────────────────────

  def assets(tokenIds: Seq[String]): JsObject = {
    if (tokenIds.size > MaxAssets) throw LithosBadRequest(s"at most $MaxAssets token ids")
    val s = snap()
    val l = new Lookups(s)
    withAt(s, Json.obj("tokens" -> tokensOf(l, s, tokenIds.map(hexId).distinct)))
  }

  /** TokenInfo for each id, read from chain proofs, never from names. Names come from the index within the budget. */
  private def tokensOf(l: Lookups, s: Snap, ids: Seq[String]): JsObject = JsObject(ids.map { id =>
    val origin = l.origin(id)
    val info = if (l.budget.take()) Try(node().tokenById(id).get).toOption.flatten else None
    val mintedExists = s.book.bonds.contains(id) || s.book.settlements.contains(id) ||
      s.book.shareVaults.contains(id) || s.book.wrapVaults.contains(id) || info.isDefined
    val kind = if (!mintedExists) "token" else origin.map(_.kind) match {
      case Some(ZcKind.Order) | Some(ZcKind.PricedOrder) => "bondUnit"
      case Some(ZcKind.FractionalMint) | Some(ZcKind.DutchAuctionMint) => "shares"
      case Some(ZcKind.WrapMint) => "wrapReceipt"
      case _ => "token"
    }
    id -> Json.obj("id" -> id, "name" -> info.map(_.name), "decimals" -> info.map(_.decimals),
      "verified" -> (kind != "token"), "kind" -> kind,
      "bond" -> (if (kind == "bondUnit") l.json.bondJson(id) else None),
      "shares" -> (if (kind == "shares") l.json.sharesJson(id) else None),
      "wrap" -> (if (kind == "wrapReceipt") s.book.wrapVaults.get(id).map(v => wrapJson(v.record))
        .orElse(origin.flatMap(_.held).map(t => Json.obj("token" -> t.tokenId, "amount" -> t.amount.toString,
          "state" -> "requested"))) else None),
      "provision" -> JsNull, "displayPrice" -> JsNull)
  })

  // ─── 13. portfolio ─────────────────────────────────────────────────────────

  def portfolio(): JsObject = {
    val s = snap()
    val l = new Lookups(s)
    val hashes = Try(walletHashes()).getOrElse(throw LithosUnavailable("the node wallet is not readable"))
    val (nanoErg, held) = Try(walletBalance()).getOrElse(throw LithosUnavailable("the node wallet's balance is not readable"))
    val h = s.book.height.toLong
    val ledger = history.map(hs => Try(hs.ledger()).getOrElse(ZcLedger(Map.empty, Map.empty, "unavailable")))
    val lending = held.flatMap { t =>
      val origin = l.origin(t.tokenId)
      val cost = ledger.flatMap(_.costs.get(t.tokenId))
      origin.map(_.kind) match {
        case Some(ZcKind.Order) | Some(ZcKind.PricedOrder) => Some(position(s, l, hashes, t, t.tokenId, 1L, None, cost))
        case Some(ZcKind.FractionalMint) | Some(ZcKind.DutchAuctionMint) =>
          s.book.shareVaults.get(t.tokenId).map(v => position(s, l, hashes, t, v.record.bondAsset, v.record.shares, Some(v.record), cost))
        case _ => None
      }
    }
    val unrealized = lending.flatMap(p => for {
      m <- (p \ "bond" \ "market").asOpt[String]
      pnl <- (p \ "mark" \ "pnl").asOpt[String]
    } yield m -> BigInt(pnl)).groupBy(_._1).map { case (m, xs) => m -> xs.map(_._2).sum.toString }
    val borrowing = s.book.bonds.values.toVector.filter(b => hashes.contains(b.record.borrowerHash)).sortBy(_.record.maturity)
      .map { b =>
        val r = b.record
        val due = h >= r.maturity || confirmedBelow(s, ZcBondTerms.of(r))
        val holdsUnit = held.exists(t => t.tokenId == r.asset && t.amount >= 1L)
        Json.obj("bond" -> l.json.bondJson(r.asset), "status" -> (if (due) "liquidatable" else "live"),
          "holdsUnit" -> holdsUnit,
          "actions" -> Seq(
            action("repay", h + 1L < r.maturity, if (h + 1L >= r.maturity) Some("the bond has reached maturity") else None),
            action("recollateralize", h + 1L < r.maturity && h >= r.box.creationHeight + RecollateralizeWait,
              Some(if (h + 1L >= r.maturity) "the bond has reached maturity" else s"opens at #${r.box.creationHeight + RecollateralizeWait}")),
            action("extinguish", holdsUnit, Some("buy or hold the circulating bond unit first"))))
      }
    val named = s.book.view.open.filter { case (r, _) => r.namedHash.exists(hashes.contains) }.toVector
    def openOf[A](pick: PartialFunction[(ZcRecord, Boolean), JsObject]): Seq[JsObject] = named.collect(pick)
    val open = Json.obj(
      "orders" -> openOf { case (o: ZcOrder, p) => l.json.orderJson(ZcOpen(o, p)) },
      "asks" -> openOf { case (a: ZcAsk, p) => l.json.askJson(ZcOpen(a, p)) },
      "offers" -> openOf { case (o: ZcOffer, p) => l.json.offerJson(ZcOpen(o, p)) },
      "lots" -> openOf { case (o: ZcLot, p) => Json.obj("boxId" -> o.boxId, "pending" -> p) },
      "mintRequests" -> openOf { case (o: ZcShareRequest, p) => Json.obj("boxId" -> o.boxId, "bondId" -> o.bondAsset, "pending" -> p) },
      "wrapRequests" -> openOf { case (o: ZcWrapRequest, p) => Json.obj("boxId" -> o.boxId, "token" -> o.wrapped.tokenId, "pending" -> p) })
    val wraps = held.flatMap(t => s.book.wrapVaults.get(t.tokenId).map(v => wrapJson(v.record) ++ Json.obj("inWallet" -> true)))
    val attention = borrowing.count(j => (j \ "status").as[String] != "live" ||
      (j \ "bond" \ "maturity").asOpt[Long].exists(_ - h <= AttentionBlocks) ||
      (j \ "bond" \ "coverBps").asOpt[Long].exists(_ < AttentionCoverBps))
    withAt(s, Json.obj(
      "wallet" -> Json.obj("nanoErg" -> nanoErg.toString, "tokens" -> held.map(t => Json.obj("tokenId" -> t.tokenId,
        "amount" -> t.amount.toString, "kind" -> l.origin(t.tokenId).fold("token")(o => kindName(o.kind))))),
      "lending" -> lending, "borrowing" -> borrowing, "open" -> open, "wraps" -> wraps,
      "attention" -> Json.obj("borrowing" -> attention),
      "unrealized" -> unrealized,
      "realized" -> ledger.fold(Map.empty[String, String])(_.realized.map { case (m, v) => m -> v.toString }),
      "history" -> ledger.fold("off")(_.status)))
  }

  /**
   * GET /sigmafi/zc/activity: this wallet's ZC events, newest first, `limit` a page. The first page leads with what the
   * mempool holds: boxes naming this wallet that are unconfirmed, and confirmed ones an unconfirmed transaction spends.
   */
  def activity(cursor: Option[String], limit: Option[Int]): JsObject = {
    val hs = history.getOrElse(throw LithosUnavailable("wallet history is not available"))
    val s = snap()
    val hashes = Try(walletHashes()).getOrElse(throw LithosUnavailable("the node wallet is not readable"))
    val size = limit.getOrElse(DefaultPage)
    if (size < 1 || size > MaxPage) throw LithosBadRequest(s"limit must be 1 to $MaxPage")
    val offset = cursor.map(c => Try(c.toInt).toOption.filter(_ >= 0)
      .getOrElse(throw LithosBadRequest("cursor must be the next value of an earlier page"))).getOrElse(0)
    def pendingJson(txId: String, kind: String, r: ZcRecord): JsObject = Json.obj("id" -> s"$txId:${r.boxId}",
      "height" -> JsNull, "txId" -> txId, "type" -> kind, "status" -> "pending",
      "refs" -> Json.obj("boxId" -> r.boxId, "contract" -> r.script.kind.name), "amounts" -> Json.obj())
    val pending = if (offset > 0) Seq.empty else s.view.mempool.toSeq.flatMap { mp =>
      mp.pending.values.toSeq.filter(_.record.namedHash.exists(hashes)).sortBy(_.record.boxId)
        .map(p => pendingJson(p.txId, "posted", p.record)) ++
        hashes.toSeq.flatMap(s.view.book.namedBy).sortBy(_.boxId)
          .flatMap(e => mp.spentBy.get(e.boxId).map(tx => pendingJson(tx, "spending", e.record)))
    }
    val confirmed = hs.all()
    val page = confirmed.slice(offset, offset + size)
    withAt(s, Json.obj("status" -> hs.status, "items" -> (pending ++ page.map(e => ZcHistory.json(e, "confirmed"))),
      "next" -> (if (offset + size < confirmed.size) Some((offset + size).toString) else None)))
  }

  private def kindName(k: ZcKind): String = k match {
    case ZcKind.Order | ZcKind.PricedOrder => "bondUnit"
    case ZcKind.WrapMint => "wrapReceipt"
    case _ => "shares"
  }

  private def confirmedBelow(s: Snap, b: ZcBondTerms): Boolean =
    s.book.currentOracle.exists(o => fresh(o, b.payload.lift(1).getOrElse(0L), s.book.height) && belowOwnRatio(b, o.price))

  private def action(name: String, enabled: Boolean, reason: Option[String]): JsObject =
    Json.obj("name" -> name, "enabled" -> enabled, "reason" -> (if (enabled) None else reason))

  /**
   * One lending position: `units` of a token that is `whole` units of bond `asset`, marked at the best bid for the
   * maturity left (basis `bid`), at its accrued value when no bid prices it (`accrual`), or at its settlement.
   */
  private def position(s: Snap, l: Lookups, hashes: Set[String], t: NodeAsset, asset: String, whole: Long,
                       vault: Option[ZcShareVault], cost: Option[ZcCost]): JsObject = {
    // the cost of the units held, when history recorded at least as many
    val paid = cost.filter(c => c.units >= t.amount && c.units > 0L).map(c => c.paid * t.amount / c.units)
    def pnl(value: BigInt): JsValue = paid.fold[JsValue](JsNull)(p => JsString((value - p).toString))
    val h = s.book.height.toLong
    val live = s.book.bonds.get(asset).map(_.record)
    val settled = s.book.settlements.get(asset).map(_.record)
    val borrowedByMe = live.exists(b => hashes.contains(b.borrowerHash))
    val canUnfrac = vault.exists(v => v.holding && t.amount >= v.outstanding)
    val status = (live, settled, vault) match {
      case (Some(b), _, _) if h >= b.maturity => "maturedUnpaid"
      case (Some(_), _, _) => "live"
      case (None, Some(_), Some(v)) if v.holding => "pullNeeded"
      case (None, _, Some(v)) if auctionOpen(v) && h < claimableFrom(v) => "auction"
      case (None, Some(st), None) => if (st.liquidated) "liquidated" else "repaid"
      case _ => "claimable"
    }
    val mark = live.map { b =>
      val left = b.maturity - (h + 1L)
      val entryTerm = cost.map(c => b.maturity - c.height).filter(_ > 0L).getOrElse(left)
      val face = BigInt(b.repayment) * t.amount / whole
      val cover = l.json.coverBps(ZcBondTerms.of(b))
      val bid = s.book.offers.iterator.map(_.record).filter(o => o.market == b.market && left >= minBlocks(o) &&
        left <= maxBlocks(o) && cover.forall(_ >= floorBps(o)) && height(o, h) &&
        (whole == 1L || buysShares(o)) && l.json.standing(o).ok)
        .map(o => offerCurve(o).bpsAt(left)).filter(_ >= 0L)
        .reduceOption(_ min _)
      bid match {
        case Some(bps) if left > 0L && Year + BigInt(bps) * left > 0 =>
          val value = face * Year / (Year + BigInt(bps) * left)
          Json.obj("basis" -> "bid", "bidBps" -> bps, "value" -> value.toString, "atMaturity" -> face.toString,
            "pnl" -> pnl(value), "entryBps" -> paid.flatMap(p => yieldBps(face, p, entryTerm)))
        case _ =>
          val span = math.max(b.maturity - b.start, 1L)
          val principal = BigInt(if (b.market.tokenId.isEmpty) b.terms(1) else b.principalTokens.headOption.fold(0L)(_.amount)) *
            t.amount / whole
          val accrued = principal + (face - principal) * math.min(math.max(h - b.start, 0L), span) / span
          Json.obj("basis" -> "accrual", "value" -> accrued.toString, "atMaturity" -> face.toString, "pnl" -> pnl(accrued),
            "entryBps" -> paid.flatMap(p => yieldBps(face, p, entryTerm)))
      }
    }.orElse(settled.map { st =>
      val sigUsdMarket = l.origin(asset).flatMap(_.market).exists(_.tokenId.isDefined)
      val sigUsd = st.proceeds.filter(_.tokenId == sigmafi.zc.ZcContracts.SigUsdId).map(_.amount).sum
      val value = BigInt(if (sigUsdMarket) sigUsd else st.box.value) * t.amount / whole
      Json.obj("basis" -> "settled", "nanoErg" -> st.box.value.toString, "value" -> value.toString, "pnl" -> pnl(value))
    })
    Json.obj("tokenId" -> t.tokenId, "kind" -> (if (whole > 1L) "shares" else "bondUnit"), "units" -> t.amount.toString,
      "borrowedByMe" -> borrowedByMe,
      "bond" -> l.json.bondJson(asset), "shares" -> vault.flatMap(v => l.json.sharesJson(v.shareId)), "status" -> status,
      "cost" -> (for (c <- cost; p <- paid) yield Json.obj("paid" -> p.toString, "height" -> c.height, "txId" -> c.txId,
        "viaOffer" -> c.viaOffer)),
      "mark" -> mark,
      "actions" -> Seq(
        action("sell", live.isDefined, Some("the bond is not live")),
        action("claim", status == "claimable" || status == "repaid" || status == "liquidated" || canUnfrac, Some("not settled")),
        action("pull", status == "pullNeeded", Some("nothing to pull")),
        action("liquidate", live.exists(b => h >= b.maturity || confirmedBelow(s, ZcBondTerms.of(b))), Some("not due")),
        action("extinguish", borrowedByMe && whole == 1L && live.isDefined, Some(
          if (!borrowedByMe) "not your borrowed bond"
          else if (whole > 1L) "final-claim all shares first to recover the bond unit"
          else "the bond is not live"))))
  }

  private def height(o: ZcOffer, h: Long): Boolean = h + 1L < o.expiry

  private def hexId(id: String): String = {
    val x = id.trim.toLowerCase
    if (x.length != 64 || !x.forall(c => Character.digit(c, 16) >= 0)) throw LithosBadRequest(s"$id is not a 64-character hex id")
    x
  }
}

object ZcReads {

  /** One ask row: its record, and what sorting and filtering read from it. Immutable so it can be cached per snapshot. */
  private[zc] final case class AskRow(open: ZcOpen[_ <: ZcRecord], yieldBps: Option[Long],
                                      blocksLeft: Option[Long], cost: BigInt, cover: Option[Long], kind: String,
                                      standing: ZcStanding, grid: Boolean, borrowedByMe: Boolean)

  /**
   * A bid and an ask read at the same maturity, `blocks` left: `gap` is bid minus ask, so zero or less means the ask
   * pays at least what the bid curve asks there. Ranked tightest first, then the higher ask, then the shorter maturity.
   */
  private[zc] final case class Gap(blocks: Long, bid: Long, ask: Long) {
    def gap: Long = bid - ask
    def rank: (Long, Long, Long) = (gap, -ask, blocks)
  }

  /** Node reads one request may make for origins, scripts, chains and names. */
  final val ReadsPerRequest = 16

  /** Node reads a background pass makes per snapshot to fill in what the tracker did not see. */
  final val WarmReads = 64

  final val DefaultPage = 50
  final val MaxPage = 200
  final val MaxPendingRows = 50
  final val MaxCurveOffers = 200
  final val MaxDots = 500
  final val MaxBookRows = 100
  final val MaxMatchRows = 50
  final val MaxSamples = 400
  final val SampleStep = 1800L
  final val MaxSearchHits = 20
  final val MaxAssets = 50
  final val MaxLookupHops = 8
  final val CacheEntries = 256
  final val AttentionBlocks = 2160L
  final val AttentionCoverBps = 12500L

  /** The zcs1 defaults table, so the UI hardcodes none of it. */
  val Defaults: JsObject = Json.obj(
    "minerFeeNanoErg" -> "1000000", "gridBlocks" -> Grid, "blocksPerYear" -> BlocksPerYear,
    "orderExpiryBlocks" -> 5040, "orderExpiryFeeNanoErg" -> "5000000", "orderOracleAge" -> 60, "offerOracleAge" -> 30,
    "offerMinBlocks" -> 1, "offerMaxBlocks" -> BlocksPerYear, "offerExpiryBlocks" -> 5040, "askExpiryBlocks" -> 5040,
    "batcherFeeNanoErg" -> "5000000", "tokenOfferMinFillBps" -> 100, "tokenOfferRewardBudgetNanoErg" -> "200000000",
    "reserveNanoErg" -> "50000000", "mintFeeNanoErg" -> "5000000", "pullFeeNanoErg" -> "5000000",
    "askRefundFeeNanoErg" -> "2000000", "tokenOfferErgFundingNanoErg" -> "500000000", "askMinFill" -> "1",
    "offerMinPrice" -> "1", "fillGuard" -> "on at the order's own ratio", "saleGuardOracleAge" -> 30,
    "shareCount" -> ShareCount.toString, "shareDecimals" -> 6, "shareVault" -> "selective", "selectiveThreshold" -> "100",
    "auctionLength" -> 720, "auctionExpiry" -> 2160, "offerMaxAuctionExpiry" -> 0, "lotFallBlocks" -> 720,
    "lotHoldBlocks" -> 2160, "lotRefundFeeNanoErg" -> "2000000", "wrapMintFeeNanoErg" -> "5000000",
    "wrapRefreshReserveNanoErg" -> "30000000", "refreshAgeBlocks" -> RefreshAge, "refreshBountyNanoErg" -> RefreshBounty.toString,
    "quoteBlocks" -> QuoteBlocks, "minBatchingFeeNanoErg" -> MinBatchingFee.toString)

  /** The reads' own daemon thread for background lookups, so a slow node never holds a request or an actor. */
  def ownThread(): ExecutionContext = ExecutionContext.fromExecutorService(Executors.newSingleThreadExecutor { (r: Runnable) =>
    val t = new Thread(r, "zc-reads")
    t.setDaemon(true)
    t
  })
}

/** A bounded least-recently-used cache, coalescing concurrent misses per key. */
final class Lru[K, V](max: Int) {
  private val map = new java.util.LinkedHashMap[K, V](16, 0.75f, true) {
    override def removeEldestEntry(e: java.util.Map.Entry[K, V]): Boolean = size() > max
  }
  private val inFlight = new ConcurrentHashMap[K, java.util.concurrent.CompletableFuture[V]]()

  def get(key: K, compute: () => V): V = getIf(key, () => (compute(), true))

  /** Computes `key`, coalescing concurrent misses, and stores the result only when `compute()` flags it cacheable. */
  def getIf(key: K, compute: () => (V, Boolean)): V = {
    val hit = map.synchronized(Option(map.get(key)))
    hit.getOrElse {
      val mine = new java.util.concurrent.CompletableFuture[V]()
      val existing = inFlight.putIfAbsent(key, mine)
      if (existing != null) {
        try existing.get()
        catch {
          case e: java.util.concurrent.ExecutionException if e.getCause != null => throw e.getCause
        }
      } else {
        try {
          val recheck = map.synchronized(Option(map.get(key)))
          val v = recheck.getOrElse {
            val (computed, cacheable) = compute()
            if (cacheable) map.synchronized(map.put(key, computed))
            computed
          }
          mine.complete(v)
          v
        } catch {
          case ex: Throwable =>
            mine.completeExceptionally(ex)
            throw ex
        } finally inFlight.remove(key, mine)
      }
    }
  }
  def size: Int = map.synchronized(map.size())
}
