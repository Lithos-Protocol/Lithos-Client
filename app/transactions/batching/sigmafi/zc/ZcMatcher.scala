package transactions.batching.sigmafi.zc

import sigmafi.zc.{ZcKind, ZcMarket}

/** An open box and whether its creating transaction is still in the mempool. */
final case class ZcOpen[A <: ZcRecord](record: A, pending: Boolean) {
  def boxId: String = record.boxId
}

/**
 * One tracker snapshot sorted for the market reads: every open box by kind, the state box of each bond asset, share
 * and receipt, and the oracle. `oracle` is the confirmed pool box a trade may read; it is None while a mempool
 * refresh spends it (`refreshPending`), since anything reading it would fail once that refresh confirms.
 */
final case class ZcMarketBook(view: ZcView, orders: Vector[ZcOpen[ZcOrder]], asks: Vector[ZcOpen[ZcAsk]],
                              offers: Vector[ZcOpen[ZcOffer]], lots: Vector[ZcOpen[ZcLot]],
                              shareRequests: Vector[ZcOpen[ZcShareRequest]], wrapRequests: Vector[ZcOpen[ZcWrapRequest]],
                              bonds: Map[String, ZcOpen[ZcBond]], settlements: Map[String, ZcOpen[ZcSettlement]],
                              shareVaults: Map[String, ZcOpen[ZcShareVault]], wrapVaults: Map[String, ZcOpen[ZcWrapVault]]) {
  def height: Int = view.book.height
  def revision: Long = view.revision
  def confirmedOracle: Option[ZcOracle] = view.book.oracle
  def refreshPending: Boolean = view.refreshPending
  def oracle: Option[ZcOracle] = if (refreshPending) None else confirmedOracle
}

object ZcMarketBook {

  /** `view` sorted once. A token two boxes claim as its state box has none, as in the book. */
  def of(view: ZcView): ZcMarketBook = {
    val orders = Vector.newBuilder[ZcOpen[ZcOrder]]
    val asks = Vector.newBuilder[ZcOpen[ZcAsk]]
    val offers = Vector.newBuilder[ZcOpen[ZcOffer]]
    val lots = Vector.newBuilder[ZcOpen[ZcLot]]
    val shareRequests = Vector.newBuilder[ZcOpen[ZcShareRequest]]
    val wrapRequests = Vector.newBuilder[ZcOpen[ZcWrapRequest]]
    val states = Vector.newBuilder[(String, ZcOpen[ZcRecord])]
    view.open.foreach { case (record, pending) =>
      record match {
        case r: ZcOrder => orders += ZcOpen(r, pending)
        case r: ZcAsk => asks += ZcOpen(r, pending)
        case r: ZcOffer => offers += ZcOpen(r, pending)
        case r: ZcLot => lots += ZcOpen(r, pending)
        case r: ZcShareRequest => shareRequests += ZcOpen(r, pending)
        case r: ZcWrapRequest => wrapRequests += ZcOpen(r, pending)
        case r if ZcBook.StateKinds.contains(r.script.kind) =>
          r.box.assets.headOption.foreach(t => states += t.tokenId -> ZcOpen(r, pending))
        case _ =>
      }
    }
    val single = states.result().groupBy(_._1).collect { case (t, Seq((_, one))) => t -> one }
    def only[A <: ZcRecord](pick: PartialFunction[ZcRecord, A]): Map[String, ZcOpen[A]] =
      single.collect { case (t, o) if pick.isDefinedAt(o.record) => t -> ZcOpen(pick(o.record), o.pending) }
    def byId[A <: ZcRecord](b: scala.collection.mutable.Builder[ZcOpen[A], Vector[ZcOpen[A]]]): Vector[ZcOpen[A]] =
      b.result().sortBy(_.boxId)
    ZcMarketBook(view, byId(orders), byId(asks), byId(offers), byId(lots), byId(shareRequests), byId(wrapRequests),
      only { case b: ZcBond => b }, only { case s: ZcSettlement => s }, only { case v: ZcShareVault => v },
      only { case v: ZcWrapVault => v })
  }
}

/**
 * Something an offer can buy: a borrow order, which becomes a new bond, or an ask selling a bond unit or shares of
 * one. An ask is a source only once its token is proven a claim on a live bond (`asset`, `whole` units make one).
 */
sealed trait ZcSource {
  def open: ZcOpen[_ <: ZcRecord]
  def boxId: String = open.boxId
  def pending: Boolean = open.pending
  def market: ZcMarket
}

final case class ZcOrderSource(open: ZcOpen[ZcOrder]) extends ZcSource {
  def order: ZcOrder = open.record
  def market: ZcMarket = order.market
}

/** `mint` is the share mint request's record when the ask sells shares. */
final case class ZcAskSource(open: ZcOpen[ZcAsk], asset: String, whole: Long, bond: ZcOpen[ZcBond],
                             mint: Option[ZcOrigin]) extends ZcSource {
  def ask: ZcAsk = open.record
  def market: ZcMarket = ask.market
  def shares: Boolean = whole > 1L
  /** An offer buys one bond unit, or a share ask whole. */
  def units: Long = if (shares) ask.token.amount else 1L
}

/** One purchase: what an offer pays for a source, and the yields that rank and bound it. `maturity` is the bond's. */
final case class ZcMatch(offerId: String, sourceId: String, units: Long, price: Long, cost: BigInt, face: BigInt,
                         maturity: Long, blocksLeft: Long, yieldBps: Long, requiredBps: Long, rank: BigInt, fee: Long,
                         ergOut: Long)

/** What a batch simulation found over several rounds: each offer's purchases in order, and who took each source. */
final case class ZcBatchRun(rounds: Int, taken: Map[String, Vector[ZcMatch]], takenBy: Map[String, ZcMatch],
                            searched: ZcSearched)

/** How much of the book a computation looked at: the matchable boxes, and the ones it searched within its caps. */
final case class ZcSearched(offers: Int, offersSearched: Int, sources: Int, sourcesSearched: Int)

/**
 * zcs1's matching over one market book, pure and deterministic. Offers go by most remaining batcher reward, then box
 * id; each takes the source giving it the highest yield `(R / P - 1) × 262,800 / D`, ties to the lower box id; a source
 * goes to one offer a round. Priced orders and yield asks are priced at the most they ask over the next three blocks,
 * window orders at zcs1's maturity rule. Nothing reads the node: `origin` answers from the tracker and the API's cache,
 * and a token it cannot prove is no source.
 *
 * Work is bounded: per market, at most `maxOffers` offers and `maxSources` sources are searched, each list taken in its
 * own priority order after the checks that need no offer, so a box failing those costs nothing past its own check.
 */
final class ZcMatcher(val book: ZcMarketBook, origin: String => Option[ZcOrigin],
                      maxOffers: Int = ZcMatcher.MaxOffers, maxSources: Int = ZcMatcher.MaxSources,
                      excluded: ZcRecord => Boolean = _ => false) {

  import ZcRules._

  val height: Long = book.height.toLong
  private val oracle = book.oracle

  /** The bond asset and units per bond a token is a proven claim on: a bond unit, or shares of a proven mint. */
  def claimOf(token: String): Either[String, (String, Long, Option[ZcOrigin])] =
    origin(token) match {
      case Some(o) if o.kind == ZcKind.Order || o.kind == ZcKind.PricedOrder => Right((token, 1L, None))
      case Some(o) if o.kind == ZcKind.FractionalMint || o.kind == ZcKind.DutchAuctionMint =>
        val vault = book.shareVaults.get(token).map(_.record)
        (vault, o.held) match {
          case (Some(v), Some(unit)) if unit.tokenId == v.bondAsset && o.terms.headOption.contains(v.shares) &&
            v.dutch == (o.kind == ZcKind.DutchAuctionMint) && (!v.dutch || currencyMatches(v, o)) =>
            origin(v.bondAsset) match {
              case Some(b) if b.kind == ZcKind.Order || b.kind == ZcKind.PricedOrder => Right((v.bondAsset, v.shares, Some(o)))
              case Some(_) => Left("the shares' bond asset was not minted by a standard order")
              case None => Left("the shares' bond asset is unverified")
            }
          case (None, _) => Left("no share vault holds this token's reserve")
          case _ => Left("the shares' vault does not match their mint request")
        }
      case Some(_) => Left("the token was not minted as a bond unit or shares")
      case None => Left("the token's origin is unverified")
    }

  /** A Dutch vault's R8 names its currency: empty for ERG, the token id otherwise; its mint request's market must agree. */
  private def currencyMatches(v: ZcShareVault, mint: ZcOrigin): Boolean = {
    val currency = v.currency.filter(_.length == 32).map(org.bouncycastle.util.encoders.Hex.toHexString)
    mint.market.exists(_.tokenId == currency)
  }

  /** The ask as a source, or why it is not one. */
  def askSource(a: ZcOpen[ZcAsk]): Either[String, ZcAskSource] =
    claimOf(a.record.token.tokenId).flatMap { case (asset, whole, mint) =>
      book.bonds.get(asset) match {
        case None => Left("the bond is not live")
        case Some(bond) if excluded(bond.record) => Left("the bond is excluded")
        case Some(bond) if bond.record.market != a.record.market => Left("the ask is paid in another market than its bond")
        case Some(_) if a.record.priced && !a.record.pricedAsset.contains(asset) => Left("the ask prices another bond")
        case Some(bond) => Right(ZcAskSource(a, asset, whole, bond, mint))
      }
    }

  // ─── what an offer pays for a source ───────────────────────────────────────

  /**
   * The purchase offer `s` would make of `source` now, or why it would not. `ignoreYield` skips only the offer's yield
   * test, for the lines of what an offer could buy, or which offers could fund an order, apart from price.
   */
  def evaluate(s: ZcOfferState, source: ZcSource, ignoreYield: Boolean = false): Either[String, ZcMatch] =
    if (s.market != source.market) Left("another market")
    else source match {
      case o: ZcOrderSource => evaluateOrder(s, o.order, ignoreYield)
      case a: ZcAskSource => evaluateAsk(s, a, ignoreYield)
    }

  private def evaluateOrder(s: ZcOfferState, order: ZcOrder, ignoreYield: Boolean): Either[String, ZcMatch] = {
    val maturity =
      if (window(order)) windowMaturity(s, order, oracle, height, ignoreYield).toRight("no maturity in the window suits the offer")
      else orderMaturity(order, height)
    for {
      m <- maturity
      principal <- resolvePrincipal(order, height, m)
      bond = ZcBondTerms.prospect(order, height, m, principal)
      _ <- offerRefusal(s, bond, principal, 1L, 1L, oracle, height, primary = true, ignoreYield = ignoreYield).toLeft(())
      _ <- guardRefusal(order, bond, oracle, height).toLeft(())
      fee = fillFee(s, principal)
      ergOutBig = BigInt(fee) + deliveryErgBig(s.offer, shares = false) + BigInt(feeReserve(s.offer)) + BigInt(bond.principalErg)
      ergOut <- if (ergOutBig >= 0 && ergOutBig.isValidLong) Right(ergOutBig.toLong) else Left("the fill's ERG requirement overflows")
      matched <- purchase(s, order.boxId, 1L, 1L, principal, order.repayment, m, fee, ergOut)
    } yield matched
  }

  private def evaluateAsk(s: ZcOfferState, a: ZcAskSource, ignoreYield: Boolean): Either[String, ZcMatch] = {
    val bond = ZcBondTerms.of(a.bond.record)
    for {
      _ <- fillRefusal(a.ask, a.units, height).toLeft(())
      _ <- (if (a.shares) a.mint.toRight("the shares' mint is unknown").flatMap(m => shareRefusal(s.offer, m).toLeft(()))
        else Right(()))
      quoted <- askPrice(a.ask, a.units, Some(bond), height)
      price = if (!token(s.offer)) math.max(quoted, ZcBoxes.MinBox) else quoted
      _ <- offerRefusal(s, bond, price, a.units, a.whole, oracle, height, shares = a.shares, ignoreYield = ignoreYield).toLeft(())
      _ <- saleGuardRefusal(a.ask, bond, oracle, height).toLeft(())
      fee = fillFee(s, price)
      ergOutBig = BigInt(fee) + deliveryErgBig(s.offer, a.shares) + BigInt(if (token(s.offer)) ZcBoxes.MinBox else price)
      ergOut <- if (ergOutBig >= 0 && ergOutBig.isValidLong) Right(ergOutBig.toLong) else Left("the fill's ERG requirement overflows")
      matched <- purchase(s, a.boxId, a.units, a.whole, price, bond.repayment, bond.maturity, fee, ergOut)
    } yield matched
  }

  /**
   * The purchase of `units` of `whole` of a bond repaying `repayment` for `price`, ranked as zcs1 ranks it: the face
   * `R × units` against the cost `(price + an ERG offer's fee) × whole`. The offer's continuation must keep a box
   * minimum after `ergOut` leaves it, or the transaction cannot be built.
   */
  private def purchase(s: ZcOfferState, sourceId: String, units: Long, whole: Long, price: Long, repayment: Long,
                       maturity: Long, fee: Long, ergOut: Long): Either[String, ZcMatch] =
    if (fee <= ZcMatcher.MinerFee) Left("the batcher reward does not cover a miner fee")
    else if (ergOut < 0L || BigInt(s.value) - BigInt(ergOut) < BigInt(ZcBoxes.MinBox))
      Left("the offer cannot keep a box minimum after this fill")
    else {
      val c = cost(s, price)
      val face = BigInt(repayment) * units
      val left = maturity - (height + 1L)
      Right(ZcMatch(s.boxId, sourceId, units, price, c, face / whole, maturity, left,
        ZcPricing.yieldBps(face, c * whole, left).getOrElse(0L), offerCurve(s.offer).bpsAt(left),
        ZcPricing.rankYield(face, c * whole, left), fee, ergOut))
    }

  // ─── the book's matchable sides ────────────────────────────────────────────

  /** Offers a batcher would run now: unexpired, valid policy/curve/budgets, funded for at least one fill, by priority. */
  def offerStates(market: ZcMarket): Vector[ZcOfferState] =
    book.offers.iterator.filter { op =>
      val o = op.record
      val s = ZcOfferState.of(o)
      o.market == market && height + 1L < o.expiry && !excluded(o) &&
        policyValid(o) && offerCurve(o).ordered && o.terms(0) >= 0L &&
        minBlocks(o) >= 1L && maxBlocks(o) >= minBlocks(o) &&
        feeReserve(o) >= 0L && deliveryValid(o) && batcherFee(o) >= ZcPricing.MinBatchingFee &&
        (if (!token(o)) {
          val minDelivery = deliveryErgBig(o, shares = buysShares(o))
          BigInt(s.value) > BigInt(batcherFee(o)) + BigInt(ZcBoxes.MinBox) + minDelivery
        } else {
          originalCapital(s) > 0L && s.capital > 0L && s.capital <= originalCapital(s) &&
            spendBudget(o) > 0L && rewardBudget(o) > 0L && rewardBudget(o) < spendBudget(o) &&
            minFillBps(o) >= 100L && minFillBps(o) <= 10000L &&
            BigInt(s.value) >= BigInt(ZcBoxes.MinBox) + BigInt(batcherFee(o)) + BigInt(ZcPricing.MinBatchingFee)
        })
    }.map(o => ZcOfferState.of(o.record)).filter(s => fillFee(s, s.capital) > ZcMatcher.MinerFee)
      .toVector.sortBy(s => (-fillFee(s, s.capital), s.boxId))

  /**
   * Sources a batcher could serve now, before any offer's own test, best own yield first. `excluded` drops boxes known
   * to break script disclosure, which zcs1 batchers skip. Only oracle-rule orders/bonds qualify because lend offers
   * match only oracle-rule bonds.
   */
  def sources(market: ZcMarket): Vector[ZcSource] = {
    val orders = book.orders.iterator.filter { o =>
      o.record.market == market && !excluded(o.record) &&
        rule(o.record.liqHash, market) == ZcRule.Oracle &&
        orderRefusal(o.record, height).isEmpty
    }.map(ZcOrderSource)
    val asks = book.asks.iterator.filter(a => a.record.market == market && height + 1L < a.record.expiry && !excluded(a.record))
      .flatMap(a => askSource(a).toOption)
      .filter(a => rule(a.bond.record.liqHash, market) == ZcRule.Oracle &&
        !oracle.exists(or => belowOwnRatio(ZcBondTerms.of(a.bond.record), or.price)))
    (orders ++ asks).map(s => s -> ownYield(s)).toVector.sortBy { case (s, y) => (-y, s.boxId) }.map(_._1)
  }

  /** A source's own asking yield at its default maturity, for ordering the search; unpriceable ones go last. */
  def ownYield(source: ZcSource): BigInt = source match {
    case o: ZcOrderSource =>
      (for {
        m <- orderMaturity(o.order, height)
        p <- resolvePrincipal(o.order, height, m)
      } yield ZcPricing.rankYield(BigInt(o.order.repayment), BigInt(p), m - (height + 1L))).getOrElse(BigInt(Long.MinValue))
    case a: ZcAskSource =>
      val bond = ZcBondTerms.of(a.bond.record)
      askPrice(a.ask, a.units, Some(bond), height).toOption
        .map(p => ZcPricing.rankYield(BigInt(bond.repayment) * a.units, BigInt(p) * a.whole, bond.maturity - (height + 1L)))
        .getOrElse(BigInt(Long.MinValue))
  }

  /** Every purchase `s` would make now from `sources`, best for it first. */
  def candidates(s: ZcOfferState, from: Seq[ZcSource]): Vector[ZcMatch] =
    from.iterator.flatMap(src => evaluate(s, src).toOption).toVector.sortBy(m => (-m.rank, m.sourceId))

  /**
   * Up to `rounds` zcs1 batch rounds over `market`: each offer in priority order takes its best untaken source, then
   * continues with what is left for the next round. Stops at the first round taking nothing.
   */
  def batch(market: ZcMarket, rounds: Int = ZcMatcher.Rounds, extraOffers: Seq[ZcOfferState] = Seq.empty,
            extraSources: Seq[ZcSource] = Seq.empty): ZcBatchRun = {
    val allOffers = (offerStates(market) ++ extraOffers.filter(_.market == market))
      .sortBy(s => (-fillFee(s, s.capital), s.boxId))
    val base = sources(market)
    val extra = extraSources.filter(_.market == market)
    val allSources = if (extra.isEmpty) base
      else (base ++ extra).map(s => s -> ownYield(s)).sortBy { case (s, y) => (-y, s.boxId) }.map(_._1)
    val searchedOffers = allOffers.take(maxOffers)
    val searchedSources = allSources.take(maxSources)
    var states = searchedOffers
    var open = searchedSources
    var taken = Map.empty[String, Vector[ZcMatch]]
    var takenBy = Map.empty[String, ZcMatch]
    var round = 0
    var progress = true
    while (progress && round < rounds) {
      round += 1
      progress = false
      val used = scala.collection.mutable.HashSet.empty[String]
      states = states.map { s =>
        val best = open.iterator.filterNot(src => used.contains(src.boxId)).flatMap(src => evaluate(s, src).toOption)
          .foldLeft(Option.empty[ZcMatch]) { (b, m) =>
            if (b.forall(x => m.rank > x.rank || (m.rank == x.rank && m.sourceId < x.sourceId))) Some(m) else b
          }
        best match {
          case Some(m) =>
            used += m.sourceId
            taken = taken.updated(s.boxId, taken.getOrElse(s.boxId, Vector.empty) :+ m)
            takenBy += m.sourceId -> m
            progress = true
            // An ERG offer's capital is its box: everything that left it, not only the price
            val value = s.value - m.ergOut
            s.copy(capital = if (token(s.offer)) s.capital - m.price else value, value = value)
          case None => s
        }
      }
      open = open.filterNot(src => used.contains(src.boxId))
    }
    ZcBatchRun(round, taken, takenBy, ZcSearched(allOffers.size, searchedOffers.size, allSources.size, searchedSources.size))
  }
}

object ZcMatcher {
  /** The miner fee zcs1 batchers pay: work must earn more than this to run alone. */
  final val MinerFee = 1000000L

  /** Offers and sources searched per market, each in its own priority order. */
  final val MaxOffers = 200
  final val MaxSources = 1000

  /** Batch rounds a simulation runs: an offer can take one source a round. */
  final val Rounds = 10
}
