package api.zc

import api.LithosApiErrors.LithosBadRequest
import node.model.{NodeAsset, NodeBox, NodeRegisters}
import org.bouncycastle.util.encoders.Hex
import play.api.libs.json._
import scorex.crypto.hash.Blake2b256
import sigmafi.zc.{ZcContracts, ZcExpression, ZcKind, ZcMarket}
import transactions.batching.sigmafi.zc.ZcPricing._
import transactions.batching.sigmafi.zc.ZcRules._
import transactions.batching.sigmafi.zc._

import java.nio.charset.StandardCharsets.UTF_8
import scala.util.Try

/**
 * `POST /sigmafi/zc/simulate`: a draft order, offer or ask, or a wallet lend, run against a copy of the book. A draft
 * becomes the record its create body would post, held in memory only, and the zcs1 batch runs with it added. Terms
 * zcs1 says never to default (principal, repayment, collateral, maturity, ratio, yields, floors) must be in the body;
 * the rest take the zcs1 defaults.
 */
object ZcDrafts {

  /** The id a draft record carries: no real box id sorts above it, so ties never favour a draft. */
  final val DraftId = "f" * 64

  def hash(body: JsValue): String = Hex.toHexString(Blake2b256.hash(Json.stringify(body).getBytes(UTF_8)))

  def simulate(body: JsValue, matcher: ZcMatcher, json: ZcJson): JsObject = {
    val draft = (body \ "draft").asOpt[JsObject].getOrElse(throw LithosBadRequest("the body needs a draft object"))
    (draft \ "type").asOpt[String] match {
      case Some("walletLend") => walletLend(draft, matcher, json)
      case Some("offer") => offer(draft, matcher)
      case Some("order") => order(draft, matcher)
      case Some("ask") => ask(draft, matcher)
      case other => throw LithosBadRequest(s"draft.type must be order, offer, ask or walletLend, not ${other.getOrElse("missing")}")
    }
  }

  // ─── reading a body ────────────────────────────────────────────────────────

  private def long(j: JsValue, field: String): Option[Long] = (j \ field).toOption.filterNot(_ == JsNull).map {
    case JsString(s) => Try(s.trim.toLong).getOrElse(throw LithosBadRequest(s"$field must be an integer"))
    case JsNumber(n) if n.isValidLong => n.toLongExact
    case _ => throw LithosBadRequest(s"$field must be an integer")
  }

  private def need(j: JsValue, field: String): Long =
    long(j, field).getOrElse(throw LithosBadRequest(s"$field is required: zcs1 never defaults it"))

  private def obj(j: JsValue, field: String): Option[JsObject] = (j \ field).asOpt[JsObject]

  private def marketOf(j: JsValue): ZcMarket = (j \ "market").asOpt[String].flatMap(ZcMarket.named)
    .getOrElse(throw LithosBadRequest("market must be ERG or SigUSD"))

  private def points(j: JsValue): Seq[(Long, Long)] = (j.asOpt[JsArray].map(_.value).getOrElse(Seq.empty)).map { p =>
    (need(p, "bps"), need(p, "blocks"))
  }

  private def tokens(j: JsValue): Seq[NodeAsset] = (j.asOpt[JsArray].map(_.value).getOrElse(Seq.empty)).map { t =>
    val id = (t \ "tokenId").asOpt[String].map(_.toLowerCase).filter(_.length == 64)
      .getOrElse(throw LithosBadRequest("each token needs a 64-character tokenId"))
    NodeAsset(id, need(t, "amount"))
  }

  private def check(ok: Boolean, why: => String): Unit = if (!ok) throw LithosBadRequest(why)

  private def regs: NodeRegisters = NodeRegisters.empty

  private def box(kind: ZcKind, market: ZcMarket, value: Long, assets: Seq[NodeAsset], height: Long): NodeBox =
    NodeBox(DraftId, "", value, 0, height.toInt, ZcContracts.script(kind, Some(market)).ergoTreeHex, assets, regs)

  private def exprHash(e: ZcExpression, m: ZcMarket): String = ZcContracts.expression(e, Some(m)).hash

  // ─── wallet lend ───────────────────────────────────────────────────────────

  /** The wallet buying the best sources now, whole, up to `amount`, `to` blocks out at most, at `coverage` or more. */
  private def walletLend(d: JsObject, matcher: ZcMatcher, json: ZcJson): JsObject = {
    val m = marketOf(d)
    val amount = need(d, "amount")
    val to = long(d, "to").getOrElse(BlocksPerYear)
    val coverage = long(d, "coverage").getOrElse(15000L)
    check(amount > 0L, "amount must be positive")
    val h = matcher.height
    val oracle = matcher.book.oracle
    val buys = matcher.sources(m).iterator.flatMap {
      case o: ZcOrderSource => for {
        mat <- orderMaturity(o.order, h).toOption
        p <- resolvePrincipal(o.order, h, mat).toOption
        bond = ZcBondTerms.prospect(o.order, h, mat, p)
        if guardRefusal(o.order, bond, oracle, h).isEmpty && !oracle.exists(or => belowOwnRatio(bond, or.price))
      } yield (o.boxId, 1L, 1L, BigInt(p), BigInt(o.order.repayment), mat, bond)
      case a: ZcAskSource =>
        val bond = ZcBondTerms.of(a.bond.record)
        askPrice(a.ask, a.ask.token.amount, Some(bond), h).toOption
          .filter(_ => saleGuardRefusal(a.ask, bond, oracle, h).isEmpty && !oracle.exists(or => belowOwnRatio(bond, or.price)))
          .map(p => (a.boxId, a.ask.token.amount, a.whole, BigInt(p), BigInt(bond.repayment), bond.maturity, bond))
    }.filter { case (_, _, _, _, _, mat, bond) =>
      mat - (h + 1L) <= to && json.coverBps(bond).forall(_ >= coverage)
    }.map { case (id, units, whole, cost, repayment, mat, _) =>
      val face = repayment * units
      val rank = ZcPricing.rankYield(face, cost * whole, mat - (h + 1L))
      (id, units, whole, cost, repayment, mat, rank)
    }.toVector.sortBy(x => (-x._7, x._1))
    var left = BigInt(amount)
    val taken = buys.filter { case (_, _, _, cost, _, _, _) => if (cost <= left) { left -= cost; true } else false }
    val filled = BigInt(amount) - left
    Json.obj("outcome" -> (if (taken.isEmpty) "waiting" else if (left == 0) "full" else "partial"),
      "matches" -> taken.map { case (id, units, whole, cost, repayment, mat, _) =>
        val face = repayment * units
        Json.obj("counterpartyId" -> id, "units" -> units.toString,
          "cost" -> cost.toString, "face" -> (face / whole).toString,
          "yieldBps" -> ZcPricing.yieldBps(face, cost * whole, mat - (h + 1L)), "maturity" -> mat) },
      "filled" -> filled.toString, "remaining" -> left.toString, "searched" -> buys.size)
  }

  // ─── offer ─────────────────────────────────────────────────────────────────

  /** The offer box a create-offer body posts, as the matcher reads one. */
  def offerOf(d: JsObject, h: Long): ZcOffer = {
    val m = marketOf(d)
    val capital = need(d, "capital")
    val minYield = need(d, "minYieldBps")
    val floor = need(d, "floorBps")
    val curve = points((d \ "curve").getOrElse(JsArray()))
    val (minB, maxB) = (long(d, "minBlocks").getOrElse(1L), long(d, "maxBlocks").getOrElse(BlocksPerYear))
    val age = long(d, "oracleAge").getOrElse(30L)
    val expiry = long(d, "expiry").getOrElse(h + 5040L)
    val reserve = long(d, "reserveNanoErg").getOrElse(50000000L)
    val minPrice = long(d, "minPrice").getOrElse(1L)
    val bounds = obj(d, "shareBounds")
    val (aFloor, aExpiry) = (bounds.flatMap(long(_, "floorBps")).getOrElse(0L), bounds.flatMap(long(_, "maxExpiry")).getOrElse(0L))
    check(capital > 0L && minYield >= 0L && floor > 0L, "capital and floorBps must be positive, minYieldBps not negative")
    check(reserve >= 0L && minPrice >= 1L && aFloor >= 0L && aExpiry >= 0L,
      "reserveNanoErg and shareBounds cannot be negative, and minPrice must be at least 1")
    check(minB >= 1L && maxB >= minB, "the window needs 1 <= minBlocks <= maxBlocks")
    check(curve.size <= ZcBoxes.MaxCurvePoints && ordered(curve) && curve.forall { case (y, b) => y >= 0L && b >= 1L },
      s"the curve holds at most ${ZcBoxes.MaxCurvePoints} points, blocks strictly increasing")
    check(age >= 1L && age <= MaxOracleAge, "oracleAge must be 1 to 100000")
    check(expiry > h + 1L, "the expiry must be above the current height")
    val delivery = obj(d, "delivery").getOrElse(Json.obj("kind" -> "unit"))
    val (fractional, mintHash) = (delivery \ "kind").asOpt[String].getOrElse("unit") match {
      case "unit" => (IndexedSeq.empty[Long], Array.emptyByteArray)
      case k @ ("selective" | "dutch") =>
        val terms = IndexedSeq(long(delivery, "shareCount").getOrElse(ShareCount), long(delivery, "pullFeeNanoErg").getOrElse(5000000L),
          long(delivery, "mintFeeNanoErg").getOrElse(5000000L), need(delivery, "openingBps"), need(delivery, "floorBps"),
          long(delivery, "length").getOrElse(720L), long(delivery, "expiry").getOrElse(2160L),
          if (k == "dutch") Long.MaxValue else 100L)
        (terms, Hex.decode(ZcContracts.script(ZcKind.DutchAuctionMint, Some(m)).treeHash))
      case other => throw LithosBadRequest(s"delivery.kind must be unit, selective or dutch, not $other")
    }
    val (value, assets, fee, budget) = m.tokenId match {
      case None =>
        val fee = obj(d, "erg").flatMap(long(_, "batcherFeeNanoErg")).getOrElse(5000000L)
        (capital, Seq.empty[NodeAsset], fee, IndexedSeq.empty[Long])
      case Some(id) =>
        val t = obj(d, "token").getOrElse(Json.obj())
        val fee = long(t, "refundFeeNanoErg").getOrElse(5000000L)
        val funding = long(t, "ergFundingNanoErg").getOrElse(500000000L)
        val minFill = long(t, "minFillBps").getOrElse(100L)
        check(minFill >= 100L && minFill <= 10000L, "minFillBps must be 100 to 10000")
        val reward = long(t, "rewardBudgetNanoErg").getOrElse((MinBatchingFee * 10000L + minFill - 1L) / minFill)
        val spend = funding - ZcBoxes.MinBox - fee
        check(reward > 0L && spend > reward, "the ERG funding must cover the reward budget, box funding and the refund fee")
        (funding, Seq(NodeAsset(id, capital)), fee, IndexedSeq(capital, spend, minFill, reward))
    }
    check(fee >= MinBatchingFee, "the batcher fee must be at least 0.002 ERG")
    val terms = IndexedSeq(minYield, minB, maxB, fee, reserve, floor, expiry, minPrice, age, aFloor, aExpiry) ++ budget ++
      curve.flatMap { case (y, b) => Seq(y, b) }
    ZcOffer(box(ZcKind.LendOffer, m, value, assets, h), ZcContracts.script(ZcKind.LendOffer, Some(m)), m, "",
      terms, exprHash(ZcExpression.CollateralPolicy, m), None, fractional, Seq.empty, mintHash)
  }

  private def offer(d: JsObject, matcher: ZcMatcher): JsObject = {
    val o = offerOf(d, matcher.height)
    val state = ZcOfferState.of(o)
    val run = matcher.batch(o.market, extraOffers = Seq(state))
    val taken = run.taken.getOrElse(DraftId, Vector.empty)
    val reachable = matcher.sources(o.market).take(ZcMatcher.MaxSources).flatMap(src =>
      matcher.evaluate(state, src, ignoreYield = true).toOption)
    val filled = taken.map(t => BigInt(t.price)).sum
    Json.obj("outcome" -> (if (taken.isEmpty) "waiting" else if (taken.map(_.price).sum >= o.capital) "full" else "partial"),
      "matches" -> taken.map(matchJson), "filled" -> filled.toString, "remaining" -> (BigInt(o.capital) - filled).toString,
      "askDots" -> reachable.map(mt => Json.obj("askId" -> mt.sourceId, "blocks" -> mt.blocksLeft, "bps" -> mt.yieldBps,
        "requiredBps" -> mt.requiredBps)),
      "askLine" -> reachable.groupBy(_.blocksLeft / Grid).toVector.sortBy(_._1).map { case (_, ms) =>
        val best = ms.maxBy(x => (x.yieldBps, x.sourceId))
        Json.obj("blocks" -> best.blocksLeft, "bps" -> best.yieldBps, "askId" -> best.sourceId) },
      "collateralNeeded" -> JsNull, "rounds" -> run.rounds)
  }

  private def matchJson(mt: ZcMatch): JsObject =
    Json.obj("counterpartyId" -> mt.sourceId, "offerId" -> mt.offerId, "units" -> mt.units.toString,
      "cost" -> mt.cost.toString, "face" -> mt.face.toString, "yieldBps" -> mt.yieldBps, "requiredBps" -> mt.requiredBps,
      "maturity" -> mt.maturity)

  // ─── order ─────────────────────────────────────────────────────────────────

  /** The order box a create-order body posts, as the matcher reads one. */
  def orderOf(d: JsObject, h: Long): ZcOrder = {
    val m = marketOf(d)
    val pricing = (d \ "pricing").asOpt[String].getOrElse("fixed")
    val principal = need(d, "principal")
    val repayment = need(d, "repayment")
    check(principal > 0L && repayment > 0L, "principal and repayment must be positive")
    val timing = obj(d, "timing").getOrElse(throw LithosBadRequest("timing is required: zcs1 never defaults the maturity"))
    val (maturityTerm, mode, fixedMaturity) = (timing \ "mode").asOpt[String] match {
      case Some("fixed") =>
        val target = long(timing, "maturity").orElse(long(timing, "blocks").map(h + _))
          .getOrElse(throw LithosBadRequest("a fixed timing needs maturity or blocks"))
        val mat = if ((timing \ "exact").asOpt[Boolean].contains(true)) target else snapMaturity(target, h)
        check(mat > h + 2L, "the maturity is too close")
        (mat, 0L, Some(mat))
      case Some("term") =>
        val dur = need(timing, "duration")
        check(dur >= 30L, "a term is at least 30 blocks")
        (dur, 1L, None)
      case Some("window") =>
        val lo = need(timing, "minDuration")
        val hi = need(timing, "maxDuration")
        check(lo >= 30L && hi >= lo, "a window needs 30 <= minDuration <= maxDuration")
        check(pricing == "window", "a window order is priced by a curve: pricing window")
        (lo, 2L, None)
      case _ => throw LithosBadRequest("timing.mode must be fixed, term or window")
    }
    val expiry = long(d, "expiry").getOrElse(defaultOrderExpiry(h, fixedMaturity, 5040L))
    val expiryFee = long(d, "expiryFeeNanoErg").getOrElse(5000000L)
    check(expiry > h + 1L, "the expiry must be above the current height")
    check(expiryFee >= MinBatchingFee, "the expiry fee must be at least 0.002 ERG")
    val rule = obj(d, "rule").getOrElse(throw LithosBadRequest("rule is required: oracle with ratioBps, or maturity"))
    val (liq, payload) = (rule \ "kind").asOpt[String] match {
      case Some("maturity") => (ZcContracts.expression(ZcExpression.MaturityLiquidation, None).hash, IndexedSeq.empty[Long])
      case Some("oracle") =>
        val ratio = need(rule, "ratioBps")
        val age = long(rule, "oracleAge").getOrElse(60L)
        check(ratio > 0L && age >= 1L && age <= MaxOracleAge, "the oracle rule needs a positive ratio and an age of 1 to 100000")
        val guard: Option[Long] = (d \ "fillGuard").toOption match {
          case None => Some(ratio)
          case Some(JsNull) => None
          case Some(g) => Some(long(g, "ratioBps").getOrElse(ratio))
        }
        guard.foreach(g => check(g >= ratio, "the fill guard's ratio cannot be under the liquidation ratio"))
        (exprHash(ZcExpression.OracleLiquidation, m), IndexedSeq(ratio, age) ++ guard.toSeq)
      case _ => throw LithosBadRequest("rule.kind must be oracle or maturity")
    }
    val guarded = payload.size == 3
    val col = obj(d, "collateral").getOrElse(throw LithosBadRequest("collateral is required"))
    val colErg = long(col, "nanoErg").getOrElse(0L)
    check(colErg >= ZcBoxes.MinBox, "the collateral must hold at least 0.001 ERG")
    val (principalErg, floorErg, principalTokens) = m.tokenId match {
      case None => (principal, repayment, Seq.empty[NodeAsset])
      case Some(id) => (0L, ZcBoxes.MinBox, Seq(NodeAsset(id, principal)))
    }
    val terms = IndexedSeq(repayment, principalErg, floorErg, maturityTerm, expiry, expiryFee, mode) ++ payload
    val guardHash = if (guarded) Some(exprHash(ZcExpression.FillGuard, m)) else None
    val (kind, pricingHash, pricingTerms) = pricing match {
      case "fixed" => (ZcKind.Order, None, IndexedSeq.empty[Long])
      case "yield" =>
        val y = obj(d, "yield").getOrElse(throw LithosBadRequest("yield pricing needs yield.startBps"))
        val start = need(y, "startBps")
        val line = YieldLine(h, start, long(y, "endHeight").getOrElse(expiry), long(y, "endBps").getOrElse(start))
        check(line.valid, "the yield needs non-negative points and an end above the current height")
        (ZcKind.PricedOrder, Some(exprHash(ZcExpression.YieldOrderPricing, m)),
          IndexedSeq(line.start, line.startBps, line.end, line.endBps))
      case "window" =>
        check(mode == 2L, "window pricing needs a window timing")
        val c = obj(d, "curve").getOrElse(throw LithosBadRequest("window pricing needs curve.capBps"))
        val curve = BorrowCurve(need(c, "capBps"), points((c \ "points").getOrElse(JsArray())))
        check(curve.valid, "the curve needs at most 20 points, blocks strictly increasing, no negative yield")
        (ZcKind.PricedOrder, Some(exprHash(ZcExpression.CurveOrderPricing, m)),
          IndexedSeq(need(timing, "maxDuration"), curve.maxBps) ++ curve.points.flatMap { case (y, b) => Seq(y, b) })
      case other => throw LithosBadRequest(s"pricing must be fixed, yield or window, not $other")
    }
    ZcOrder(box(kind, m, colErg + expiryFee, tokens((col \ "tokens").getOrElse(JsArray())), h),
      ZcContracts.script(kind, Some(m)), m, terms, principalTokens, "", liq, guardHash, pricingHash, pricingTerms,
      if (kind == ZcKind.PricedOrder) 2 else 0)
  }

  private def order(d: JsObject, matcher: ZcMatcher): JsObject = {
    val o = orderOf(d, matcher.height)
    val h = matcher.height
    val source = ZcOrderSource(ZcOpen(o, pending = true))
    val run = matcher.batch(o.market, extraSources = Seq(source))
    val offers = matcher.offerStates(o.market).take(ZcMatcher.MaxOffers)
    val fund = offers.flatMap(s => matcher.evaluate(s, source, ignoreYield = true).toOption.map(s -> _))
    val taker = run.takenBy.get(DraftId)
    val refusal = orderRefusal(o, h)
    Json.obj("outcome" -> (if (refusal.isDefined) "incomplete" else if (taker.isDefined) "full" else "waiting"),
      "refusal" -> refusal, "matches" -> taker.toSeq.map(matchJson),
      "filled" -> taker.fold("0")(_.price.toString),
      "remaining" -> (if (taker.isDefined) "0" else o.principal.toString),
      "bidLine" -> fund.map { case (s, mt) => Json.obj("offerId" -> s.boxId, "blocks" -> mt.blocksLeft, "bps" -> mt.requiredBps) },
      "collateralNeeded" -> collateralNeeded(o, fund.map(_._1), matcher), "rounds" -> run.rounds)
  }

  /**
   * About the least nanoERG of collateral, beside the tokens drafted, that would put the order at its own ratio, its
   * guard and the lowest floor among offers that could otherwise fund it, at the confirmed oracle. None for a
   * maturity-rule order, or with no oracle.
   */
  private def collateralNeeded(o: ZcOrder, funders: Seq[ZcOfferState], matcher: ZcMatcher): Option[String] =
    matcher.book.confirmedOracle.filter(_ => o.payload.nonEmpty).map { or =>
      val target = (o.payload.take(1) ++ o.payload.lift(2).toSeq ++ funders.map(s => floorBps(s.offer)).reduceOption(_ min _).toSeq).max
      val sigUsd = BigInt(o.box.assets.filter(_.tokenId == ZcContracts.SigUsdId).map(_.amount).sum)
      val debt = if (o.market.tokenId.isEmpty) BigInt(o.repayment) * 100 else BigInt(or.price) * o.repayment
      val needed = ((debt * target + 9999) / 10000 - BigInt(or.price) * sigUsd + 99) / 100
      needed.max(BigInt(ZcBoxes.MinBox)).toString
    }

  // ─── ask ───────────────────────────────────────────────────────────────────

  /** The ask box a create-ask body posts. A fixed price with a sale guard is a guarded yield ask at 10^15 bps. */
  def askOf(d: JsObject, matcher: ZcMatcher): ZcAsk = {
    val h = matcher.height
    val token = (d \ "tokenId").asOpt[String].map(_.toLowerCase).filter(_.length == 64)
      .getOrElse(throw LithosBadRequest("tokenId must be a 64-character id"))
    val units = need(d, "units")
    check(units >= 1L, "units must be at least 1")
    val (asset, whole, _) = matcher.claimOf(token).fold(why => throw LithosBadRequest(s"the token is no claim on a ZC bond: $why"), identity)
    val m = matcher.book.bonds.get(asset).map(_.record.market).getOrElse(throw LithosBadRequest("the bond is not live"))
    val expiry = long(d, "expiry").getOrElse(h + 5040L)
    check(expiry > h + 1L, "the expiry must be above the current height")
    val minFill = long(d, "minFill").getOrElse(1L)
    val guard = obj(d, "saleGuard").map(g => (need(g, "ratioBps"), long(g, "oracleAge").getOrElse(30L)))
    val p = obj(d, "pricing").getOrElse(throw LithosBadRequest("pricing is required"))
    val (kind, terms, pricing) = (p \ "kind").asOpt[String] match {
      case Some("fixed") if guard.isEmpty =>
        (ZcKind.SaleOrder, IndexedSeq(need(p, "price"), units, minFill, expiry), None)
      case Some("fixed") =>
        (ZcKind.PricedAsk, IndexedSeq(need(p, "price"), units, minFill, expiry, whole, h, GuardedFixedYield, expiry,
          GuardedFixedYield) ++ guard.toSeq.flatMap(g => Seq(g._1, g._2)), Some(exprHash(ZcExpression.GuardedYieldPricing, m)))
      case Some("yield") =>
        val start = need(p, "startBps")
        val line = YieldLine(h, start, long(p, "endHeight").getOrElse(expiry), long(p, "endBps").getOrElse(start))
        check(line.valid, "the yield needs non-negative points and an end above the current height")
        val (num, den) = long(p, "floor").filter(_ > 0L).map(_ -> units).getOrElse((1L, 1000000000000L))
        (ZcKind.PricedAsk, IndexedSeq(num, den, minFill, expiry, whole, line.start, line.startBps, line.end, line.endBps) ++
          guard.toSeq.flatMap(g => Seq(g._1, g._2)),
          Some(exprHash(if (guard.isDefined) ZcExpression.GuardedYieldPricing else ZcExpression.YieldAskPricing, m)))
      case _ => throw LithosBadRequest("pricing.kind must be fixed or yield")
    }
    ZcAsk(box(kind, m, ZcBoxes.MinBox + 2000000L, Seq(NodeAsset(token, units)), h), ZcContracts.script(kind, Some(m)), m,
      "", terms, pricing, if (kind == ZcKind.PricedAsk) Some(asset) else None, None)
  }

  // ─── lots, shares, wraps ───────────────────────────────────────────────────

  /** A lot a create-lot body posts: `terms` are R5, (opening, floor, start, end, expiry, refund fee). */
  final case class LotDraft(market: ZcMarket, items: Seq[NodeAsset], erg: Long, terms: Seq[Long])

  /** A share mint request's R8, (N, pull fee, mint fee) then any auction's five terms, and that auction. */
  final case class ShareDraft(terms: Seq[Long], auction: Option[ZcMetadata.Auction])

  /** A wrap request: `amount` None wraps everything the wallet holds. */
  final case class WrapDraft(tokenId: String, amount: Option[Long], mintFee: Long, refreshReserve: Long)

  /** zcs1 client defaults: a lot's refund fee, the share fees, a wrap's mint fee and refresh reserve. */
  final val DefaultRefundFee = 2000000L
  final val DefaultShareFee = 5000000L
  final val DefaultWrapFee = 5000000L
  final val DefaultRefreshReserve: Long = 3L * RefreshBounty
  final val DefaultAuctionLength = 720L
  final val DefaultAuctionExpiry = 2160L
  final val SelectiveThreshold = 100L

  def tokensOf(j: JsLookupResult): Seq[NodeAsset] = j.toOption.fold(Seq.empty[NodeAsset])(tokens)

  /** The create-lot body: `market`, `items`, `nanoErg` (token markets only), `opening`, `floor`, `start`, `end`, `expiry`. */
  def lotOf(d: JsObject, h: Long): LotDraft = {
    val m = marketOf(d)
    val items = tokensOf(d \ "items")
    val erg = long(d, "nanoErg").getOrElse(0L)
    val opening = need(d, "opening")
    val floor = need(d, "floor")
    val start = long(d, "start").getOrElse(h)
    val end = need(d, "end")
    val expiry = long(d, "expiry").getOrElse(end)
    val refund = long(d, "refundFeeNanoErg").getOrElse(DefaultRefundFee)
    check(items.nonEmpty || (m.tokenId.isDefined && erg > 0L), "name at least one item" + m.tokenId.fold("")(_ => ", or some nanoErg"))
    check(items.map(_.tokenId).distinct.size == items.size && items.forall(_.amount >= 1L), "list each item once, with a positive amount")
    check(m.tokenId.isDefined || erg == 0L, "an ERG lot sells tokens only: its box's nanoERG return to the seller with the payment")
    check(erg >= 0L && refund >= 0L, "nanoErg and refundFeeNanoErg cannot be negative")
    check(floor >= 1L && floor <= opening, "the lot needs 1 <= floor <= opening")
    check(end > start, "the price must fall over at least one block: end must be above start")
    check(expiry >= end, "the expiry cannot come before the end of the fall")
    check(expiry > h + 1L, "the expiry must be above the current height")
    LotDraft(m, items, erg, Seq(opening, floor, start, end, expiry, refund))
  }

  /**
   * The fractionalize body: `vault` selective (default), dutch or plain; `openingBps` and `floorBps` (zcs1 never
   * defaults a price), `length`, `expiry`, `mintFeeNanoErg`, `pullFeeNanoErg`, `shareCount`.
   */
  def shareRequestOf(d: JsObject): ShareDraft = {
    val n = long(d, "shareCount").getOrElse(ShareCount)
    val pull = long(d, "pullFeeNanoErg").getOrElse(DefaultShareFee)
    val mint = long(d, "mintFeeNanoErg").getOrElse(DefaultShareFee)
    check(n >= 1L && n <= 1000000000L, "shareCount must be 1 to 1000000000")
    check(pull >= MinBatchingFee && mint >= MinBatchingFee, "the pull and mint fees must each be at least 0.002 ERG")
    val auction = (d \ "vault").asOpt[String].getOrElse("selective") match {
      case "plain" => None
      case k @ ("selective" | "dutch") =>
        val length = long(d, "length").getOrElse(DefaultAuctionLength)
        val a = ZcMetadata.Auction(need(d, "openingBps"), need(d, "floorBps"), length,
          long(d, "expiry").getOrElse(math.max(DefaultAuctionExpiry, length)),
          if (k == "dutch") Long.MaxValue else SelectiveThreshold)
        check(a.floorBps >= 0L && a.floorBps <= a.openingBps && a.openingBps <= 1000000L, "the auction needs 0 <= floorBps <= openingBps <= 1000000")
        check(a.length >= 1L && a.length <= 1000000L, "the auction length must be 1 to 1000000 blocks")
        check(a.expiry >= a.length && a.expiry <= 1000000L, "the auction expiry must be from its length to 1000000 blocks")
        Some(a)
      case other => throw LithosBadRequest(s"vault must be selective, dutch or plain, not $other")
    }
    ShareDraft(Seq(n, pull, mint) ++ auction.toSeq.flatMap(a => Seq(a.openingBps, a.floorBps, a.length, a.expiry, a.threshold)),
      auction)
  }

  /** The create-wrap body: `tokenId`, optional `amount`, `mintFeeNanoErg`, `refreshReserveNanoErg`. */
  def wrapOf(d: JsObject): WrapDraft = {
    val token = (d \ "tokenId").asOpt[String].map(_.toLowerCase).filter(_.matches("[0-9a-f]{64}"))
      .getOrElse(throw LithosBadRequest("tokenId must be a 64-character id"))
    val w = WrapDraft(token, long(d, "amount"), long(d, "mintFeeNanoErg").getOrElse(DefaultWrapFee),
      long(d, "refreshReserveNanoErg").getOrElse(DefaultRefreshReserve))
    check(w.mintFee >= MinBatchingFee, "the mint fee must be at least 0.002 ERG")
    check(w.refreshReserve >= 0L, "the refresh reserve cannot be negative")
    w
  }

  private def ask(d: JsObject, matcher: ZcMatcher): JsObject = {
    val a = askOf(d, matcher)
    val source = matcher.askSource(ZcOpen(a, pending = true)).fold(why => throw LithosBadRequest(why), identity)
    val run = matcher.batch(a.market, extraSources = Seq(source))
    val taker = run.takenBy.get(DraftId)
    val takers = matcher.offerStates(a.market).take(ZcMatcher.MaxOffers).flatMap(s => matcher.evaluate(s, source).toOption)
    Json.obj("outcome" -> (if (taker.isDefined) "full" else "waiting"), "matches" -> taker.toSeq.map(matchJson),
      "offers" -> takers.map(matchJson), "filled" -> taker.fold("0")(_.units.toString),
      "remaining" -> (if (taker.isDefined) "0" else a.token.amount.toString), "rounds" -> run.rounds)
  }
}
