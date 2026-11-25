package api.zc

import node.model.NodeAsset
import org.bouncycastle.util.encoders.Hex
import play.api.libs.json._
import sigmafi.zc.{ZcContracts, ZcExpression, ZcMarket}
import transactions.batching.sigmafi.zc.ZcPricing._
import transactions.batching.sigmafi.zc.ZcRules._
import transactions.batching.sigmafi.zc._

/** One check a reader ran on a record, as the UI lists it. `ok` None: not checked, for want of a node read. */
final case class ZcCheck(key: String, ok: Option[Boolean], detail: String)

/** A record's standing for a buyer: `ok`, `paused`, `expired` or `flagged`, with the reasons. */
final case class ZcStanding(status: String, reasons: Seq[String], checks: Seq[ZcCheck]) {
  def ok: Boolean = status == "ok"
  /** Checks that failed without changing the status, for the record's `flags`. */
  def flags: Seq[String] = checks.collect { case c if c.ok.contains(false) => c.key }.distinct
}

/**
 * What one read knows beyond the market book: the proven mint behind a token, whether a box's named script is
 * disclosed (None: not looked up), which party hashes are this wallet's, and an offer or ask chain's first box.
 */
final case class ZcKnown(origin: String => Option[ZcOrigin], disclosed: ZcRecord => Option[Boolean],
                         mine: String => Boolean, chain: ZcRecord => Option[(String, Long)])

/**
 * The `/sigmafi/zc` records: Ask, Offer, Bond, Shares and the small ones, each with its status and checks. Every figure
 * a user signs is a raw integer string; yields and ratios are integer basis points.
 */
final class ZcJson(matcher: ZcMatcher, known: ZcKnown) {

  private val book = matcher.book
  private val height: Long = book.height.toLong
  private val oracle = book.oracle
  private val currentOracle = book.currentOracle

  def str(n: Long): JsString = JsString(n.toString)
  def str(n: BigInt): JsString = JsString(n.toString)

  def at: JsObject = ZcJson.at(book)

  def tokens(ts: Seq[NodeAsset]): JsArray = JsArray(ts.map(t => Json.obj("tokenId" -> t.tokenId, "amount" -> str(t.amount))))

  def collateral(nanoErg: Long, ts: Seq[NodeAsset]): JsObject = Json.obj("nanoErg" -> str(nanoErg), "tokens" -> tokens(ts))

  private def created(r: ZcRecord, pending: Boolean): JsValue =
    if (pending) JsNull else book.view.book.entry(r.boxId).map(e => JsNumber(e.height)).getOrElse(JsNull)

  def coverBps(b: ZcBondTerms): Option[Long] = currentOracle.flatMap(o => cover(b, o.price)).map(ratioBps)

  def rule(liqHash: String, market: ZcMarket, payload: IndexedSeq[Long]): JsObject = {
    val kind = ZcRules.rule(liqHash, market)
    Json.obj("kind" -> kind.name, "hash" -> liqHash) ++
      (if (kind == ZcRule.Oracle && payload.size >= 2) Json.obj("ratioBps" -> payload(0), "oracleAge" -> payload(1))
       else Json.obj())
  }

  private def yieldJson(l: YieldLine): JsObject = Json.obj("h0" -> l.start, "y0" -> l.startBps, "h1" -> l.end, "y1" -> l.endBps)

  private def points(ps: Seq[(Long, Long)]): JsArray = JsArray(ps.map { case (y, d) => Json.obj("blocks" -> d, "bps" -> y) })

  // ─── standing ──────────────────────────────────────────────────────────────

  /** Disclosure as a check: ok, broken, or not looked up yet. */
  private def disclosure(r: ZcRecord): ZcCheck = known.disclosed(r) match {
    case Some(true) => ZcCheck("disclosure", Some(true), "the named script is disclosed")
    case Some(false) => ZcCheck("disclosure", Some(false), "the creating transaction does not disclose the named script")
    case None => ZcCheck("disclosure", None, "not looked up yet")
  }

  /** The oracle a box that reads it would get now, as a pause reason when it cannot. */
  private def oraclePause(age: Long): Option[String] =
    if (book.refreshPending) Some("oracleRefreshPending")
    else currentOracle match {
      case None => Some("oracleStale")
      case Some(o) if !fresh(o, age, height) => Some("oracleStale")
      case _ => None
    }

  def standing(o: ZcOrder): ZcStanding = {
    val expr = (o.priced && !yieldOrder(o) && !curveOrder(o)) || ZcRules.rule(o.liqHash, o.market) == ZcRule.Other ||
      o.guardHash.exists(_ != ZcContracts.expression(ZcExpression.FillGuard, Some(o.market)).hash)
    val disc = disclosure(o)
    val refusal = orderRefusal(o, height)
    val checks = Seq(
      ZcCheck("origin", Some(true), s"${o.script.kind} script, standard"),
      ZcCheck("expression", Some(!expr), if (expr) "names an expression outside the pinned set" else "pinned expressions"),
      disc) ++ refusal.map(r => ZcCheck("terms", Some(false), r)).toSeq
    if (height + 1L >= o.expiry) ZcStanding("expired", Seq.empty, checks)
    else if (expr || disc.ok.contains(false)) ZcStanding("flagged",
      (if (expr) Seq("expression") else Seq.empty) ++ (if (disc.ok.contains(false)) Seq("disclosure") else Seq.empty), checks)
    else if (refusal.isDefined) ZcStanding("flagged", Seq("terms"), checks)
    else {
      val bond = defaultProspect(o)
      val guardAge = if (o.guardHash.isDefined) o.payload.lift(1) else None
      val pauses = guardAge.flatMap(oraclePause).toSeq ++
        bond.toSeq.flatMap(b => guardRefusal(o, b, oracle, height).filter(_ => oracle.isDefined).map(_ => "guard")) ++
        bond.toSeq.flatMap(b => currentOracle.filter(or => belowOwnRatio(b, or.price)).map(_ => "underRatio"))
      ZcStanding(if (pauses.isEmpty) "ok" else "paused", pauses.distinct, checks)
    }
  }

  /** The bond a fill now at the order's default maturity would make. */
  def defaultProspect(o: ZcOrder): Option[ZcBondTerms] = (for {
    m <- orderMaturity(o, height)
    p <- resolvePrincipal(o, height, m)
  } yield ZcBondTerms.prospect(o, height, m, p)).toOption

  def standing(a: ZcAsk): ZcStanding = {
    val claim = matcher.claimOf(a.token.tokenId)
    val source = matcher.askSource(ZcOpen(a, pending = false))
    val guardBad = guardedAsk(a) && !saleGuard(a).exists { case (minBps, age) => minBps > 0L && age >= 1L && age <= MaxOracleAge }
    val expr = (a.priced && !yieldAsk(a)) || guardBad
    val disc = disclosure(a)
    val checks = Seq(
      ZcCheck("origin", Some(claim.isRight), claim.fold(identity, c => if (c._2 > 1L) "shares of a standard mint" else "a standard bond unit")),
      ZcCheck("state", Some(source.isRight), source.fold(identity, _ => "the bond is live")),
      ZcCheck("expression", Some(!expr), if (guardBad) "malformed sale guard terms" else if (expr) "names an unknown pricing expression" else "pinned expressions"),
      disc) ++ claim.toOption.filter(_._2 > 1L).map(c => ZcCheck("shareCount", Some(c._2 == ShareCount),
      s"${c._2} raw shares")).toSeq
    if (height + 1L >= a.expiry) ZcStanding("expired", Seq.empty, checks)
    else if (claim.isLeft || expr || disc.ok.contains(false)) ZcStanding("flagged",
      (if (claim.isLeft) Seq("origin") else Seq.empty) ++ (if (expr) Seq("expression") else Seq.empty) ++
        (if (disc.ok.contains(false)) Seq("disclosure") else Seq.empty), checks)
    else source match {
      case Left(_) => ZcStanding("paused", Seq("settled"), checks)
      case Right(s) =>
        val bond = ZcBondTerms.of(s.bond.record)
        val pauses = saleGuard(a).flatMap { case (_, age) => oraclePause(age) }.toSeq ++
          saleGuardRefusal(a, bond, oracle, height).filter(_ => oracle.isDefined).map(_ => "guard").toSeq ++
          currentOracle.filter(or => belowOwnRatio(bond, or.price)).map(_ => "underRatio").toSeq
        ZcStanding(if (pauses.isEmpty) "ok" else "paused", pauses.distinct, checks)
    }
  }

  def standing(o: ZcOffer): ZcStanding = {
    val s = ZcOfferState.of(o)
    val policy = policyValid(o)
    val curveOk = offerCurve(o).ordered && o.terms(0) >= 0L && minBlocks(o) >= 1L && maxBlocks(o) >= minBlocks(o) &&
      feeReserve(o) >= 0L && deliveryValid(o)
    val disc = disclosure(o)
    val ergShort = !token(o) && BigInt(s.value) <= BigInt(batcherFee(o)) + BigInt(ZcBoxes.MinBox) +
      deliveryErgBig(o, shares = buysShares(o))
    val budgetBad = ergShort || (token(o) && (originalCapital(s) <= 0L || s.capital <= 0L ||
      s.capital > originalCapital(s) || spendBudget(o) <= 0L || rewardBudget(o) <= 0L ||
      rewardBudget(o) >= spendBudget(o) || minFillBps(o) < 100L || minFillBps(o) > 10000L ||
      BigInt(s.value) < BigInt(ZcBoxes.MinBox) + BigInt(batcherFee(o)) + BigInt(MinBatchingFee)))
    val checks = Seq(
      ZcCheck("origin", Some(true), "LendOffer script, standard"),
      ZcCheck("expression", Some(policy), if (policy) "the market's collateral policy" else "an unknown collateral policy"),
      ZcCheck("curve", Some(curveOk), "points strictly increasing"),
      disc)
    if (height + 1L >= o.expiry) ZcStanding("expired", Seq.empty, checks)
    else if (!policy || !curveOk || disc.ok.contains(false)) ZcStanding("flagged",
      (if (!policy || !curveOk) Seq("expression") else Seq.empty) ++
        (if (disc.ok.contains(false)) Seq("disclosure") else Seq.empty), checks)
    else {
      val pauses = oraclePause(maxAge(o)).toSeq ++
        (if (batcherFee(o) < MinBatchingFee || fillFee(s, s.capital) <= ZcMatcher.MinerFee) Seq("feeBelowFloor") else Seq.empty) ++
        (if (budgetBad) Seq("budget") else Seq.empty)
      ZcStanding(if (pauses.isEmpty) "ok" else "paused", pauses.distinct, checks)
    }
  }

  private def standingJson(s: ZcStanding): JsObject =
    Json.obj("status" -> s.status, "reasons" -> s.reasons, "flags" -> s.flags,
      "checks" -> s.checks.map(c => Json.obj("key" -> c.key, "ok" -> c.ok, "detail" -> c.detail)))

  // ─── records ───────────────────────────────────────────────────────────────

  private def base(r: ZcRecord, pending: Boolean, chain: Boolean): JsObject =
    Json.obj("boxId" -> r.boxId,
      "chainId" -> (if (chain) known.chain(r).fold[JsValue](JsNull)(c => JsString(c._1)) else JsString(r.boxId)),
      "txId" -> r.box.transactionId, "created" -> created(r, pending), "pending" -> pending,
      "mine" -> r.namedHash.exists(known.mine), "contract" -> r.script.kind.name)

  /** An order's quote at its default maturity, and its own committed yield now. */
  def orderQuote(o: ZcOrder): Option[(JsObject, Long, Long)] = (for {
    m <- orderMaturity(o, height)
    p <- resolvePrincipal(o, height, m)
  } yield {
    val left = m - (height + 1L)
    val y = ZcPricing.yieldBps(BigInt(o.repayment), BigInt(p), left).getOrElse(0L)
    val quoted = yieldLine(o).map(_.bpsAt(height)).orElse(borrowCurve(o).map(_.bpsAt(left))).getOrElse(y)
    (Json.obj("height" -> height, "maturity" -> m, "blocksLeft" -> left, "cost" -> str(p), "face" -> str(o.repayment),
      "yieldBps" -> y, "quotedYieldBps" -> quoted, "validFrom" -> height,
      "validThrough" -> (if (o.priced) height + QuoteBlocks else o.expiry - 2L)), y, left)
  }).toOption

  def orderJson(open: ZcOpen[ZcOrder]): JsObject = {
    val o = open.record
    val s = standing(o)
    val timing = o.mode match {
      case 0L => Json.obj("mode" -> "fixed", "maturity" -> o.terms(3))
      case 1L => Json.obj("mode" -> "term", "duration" -> o.terms(3))
      case _ => Json.obj("mode" -> "window", "minDuration" -> o.terms(3), "maxDuration" -> o.pricingTerms.headOption) ++
        borrowCurve(o).map(c => Json.obj("curve" -> Json.obj("capBps" -> c.maxBps, "points" -> points(c.points))))
          .getOrElse(Json.obj())
    }
    val pricing = if (!o.priced) "fixed" else if (yieldOrder(o)) "yield" else if (curveOrder(o)) "window" else "other"
    base(o, open.pending, chain = false) ++ Json.obj(
      "market" -> o.market.name, "kind" -> "order", "borrowerHash" -> o.borrowerHash,
      "borrowedByMe" -> known.mine(o.borrowerHash), "pricing" -> pricing,
      "timing" -> timing, "principal" -> str(o.principal), "repayment" -> str(o.repayment),
      "yield" -> yieldLine(o).map(yieldJson),
      "collateral" -> collateral(o.box.value - o.expiryFee, o.box.assets),
      "rule" -> rule(o.liqHash, o.market, o.payload),
      "fillGuard" -> o.guardHash.map(h => Json.obj("hash" -> h, "ratioBps" -> o.payload.lift(2), "oracleAge" -> o.payload.lift(1))),
      "expiry" -> o.expiry, "expiryFeeNanoErg" -> str(o.expiryFee),
      "quote" -> orderQuote(o).map(_._1), "coverBps" -> defaultProspect(o).flatMap(coverBps)) ++ standingJson(s)
  }

  /** A resale ask's quote for every unit it holds. */
  def askQuote(a: ZcAsk, bond: Option[ZcBond], whole: Long): Option[(JsObject, Long, Long)] = {
    val terms = bond.map(ZcBondTerms.of)
    askPrice(a, a.token.amount, terms, height).toOption.flatMap { price =>
      terms.map { b =>
        val left = b.maturity - (height + 1L)
        val face = BigInt(b.repayment) * a.token.amount / whole.max(1L)
        val y = ZcPricing.yieldBps(BigInt(b.repayment) * a.token.amount, BigInt(price) * whole, left).getOrElse(0L)
        val quoted = askLine(a).map(_.bpsAt(height)).getOrElse(y)
        (Json.obj("height" -> height, "maturity" -> b.maturity, "blocksLeft" -> left, "cost" -> str(price),
          "face" -> str(face), "yieldBps" -> y, "quotedYieldBps" -> quoted, "validFrom" -> height,
          "validThrough" -> (if (a.priced) height + QuoteBlocks else a.expiry - 2L)), y, left)
      }
    }
  }

  def askJson(open: ZcOpen[ZcAsk]): JsObject = {
    val a = open.record
    val s = standing(a)
    val claim = matcher.claimOf(a.token.tokenId).toOption
    val whole = claim.map(_._2).getOrElse(wholeUnits(a))
    val bond = claim.flatMap(c => book.bonds.get(c._1)).map(_.record)
    val guard = saleGuard(a).filterNot(_ => askLine(a).exists(_.startBps == GuardedFixedYield))
    val fixedGuard = saleGuard(a).filter(_ => askLine(a).exists(_.startBps == GuardedFixedYield))
    val pricing = if (!a.priced || fixedGuard.isDefined) "fixed" else "yield"
    val priceTerms =
      if (!a.priced) Json.obj("price" -> Json.obj("num" -> str(a.terms(0)), "den" -> str(a.terms(1))))
      else Json.obj("floor" -> Json.obj("num" -> str(a.terms(0)), "den" -> str(a.terms(1))),
        "yield" -> askLine(a).filter(_ => fixedGuard.isEmpty).map(yieldJson))
    base(a, open.pending, chain = true) ++ Json.obj(
      "market" -> a.market.name, "kind" -> (if (whole > 1L) "shares" else "unit"), "sellerHash" -> a.sellerHash,
      "borrowedByMe" -> bond.exists(b => known.mine(b.borrowerHash)),
      "pricing" -> pricing, "tokenId" -> a.token.tokenId, "units" -> str(a.token.amount), "minFill" -> str(minFill(a)),
      "saleGuard" -> (guard orElse fixedGuard).map { case (bps, age) => Json.obj("ratioBps" -> bps, "oracleAge" -> age) },
      "expiry" -> a.expiry, "bondId" -> claim.map(_._1),
      "bond" -> claim.flatMap(c => bondJson(c._1)), "shares" -> claim.filter(_._2 > 1L).flatMap(_ => sharesJson(a.token.tokenId)),
      "quote" -> askQuote(a, bond, whole).map(_._1), "coverBps" -> bond.map(ZcBondTerms.of).flatMap(coverBps)) ++
      priceTerms ++ standingJson(s)
  }

  def offerJson(open: ZcOpen[ZcOffer]): JsObject = {
    val o = open.record
    val s = standing(o)
    val st = ZcOfferState.of(o)
    val chain = known.chain(o)
    val auction = if (o.fractional.size >= 8) Some(Json.obj("openingBps" -> o.fractional(3), "floorBps" -> o.fractional(4),
      "length" -> o.fractional(5), "expiry" -> o.fractional(6), "threshold" -> str(o.fractional(7)))) else None
    val delivery = if (!delivers(o)) Json.obj("kind" -> "unit")
      else Json.obj("kind" -> deliveryKind(o), "mintHash" -> Hex.toHexString(o.mintHash),
        "shareCount" -> o.fractional.headOption.map(str), "pullFeeNanoErg" -> o.fractional.lift(1).map(str),
        "mintFeeNanoErg" -> o.fractional.lift(2).map(str), "auction" -> auction)
    base(o, open.pending, chain = true) ++ Json.obj(
      "predecessorId" -> o.predecessor, "market" -> o.market.name, "ownerHash" -> o.ownerHash,
      "capital" -> str(o.capital), "boxNanoErg" -> str(o.box.value),
      "minYieldBps" -> o.terms(0), "minBlocks" -> minBlocks(o), "maxBlocks" -> maxBlocks(o), "curve" -> points(o.curve),
      "policy" -> Json.obj("hash" -> o.policyHash, "floorBps" -> floorBps(o), "oracleAge" -> maxAge(o)),
      "batcherFeeNanoErg" -> str(batcherFee(o)), "reserveNanoErg" -> str(feeReserve(o)), "minPrice" -> str(minPrice(o)),
      "expiry" -> o.expiry,
      "shareBounds" -> (if (buysShares(o)) Some(Json.obj("floorBps" -> o.terms(9), "maxExpiry" -> o.terms(10))) else None),
      "tokenBudget" -> (if (token(o)) Some(Json.obj("originalCapital" -> str(o.terms(11)),
        "totalErgBudget" -> str(o.terms(12)), "minFillBps" -> o.terms(13), "rewardBudget" -> str(o.terms(14)))) else None),
      "rewardNowNanoErg" -> str(fillFee(st, st.capital)),
      "delivery" -> delivery,
      "originalCapital" -> (if (token(o)) Some(str(o.terms(11))) else chain.map(c => str(c._2)))) ++ standingJson(s)
  }

  private def deliveryKind(o: ZcOffer): String = {
    val mint = Hex.toHexString(o.mintHash)
    val fractional = ZcContracts.script(sigmafi.zc.ZcKind.FractionalMint, None).treeHash
    val dutch = ZcContracts.script(sigmafi.zc.ZcKind.DutchAuctionMint, Some(o.market)).treeHash
    if (mint == fractional) "plain"
    else if (mint == dutch) (if (o.fractional.lift(7).contains(Long.MaxValue)) "dutch" else "selective")
    else "other"
  }

  /** A bond asset's record: its live bond, or its settlement. None when the book holds neither. */
  def bondJson(asset: String): Option[JsObject] = {
    val live = book.bonds.get(asset).map(_.record)
    val settled = book.settlements.get(asset)
    val originJson = known.origin(asset).map(o => Json.obj("orderBoxId" -> o.token, "contract" -> o.kind.name,
      "fillTxId" -> o.txId, "height" -> o.height))
    val originCheck = known.origin(asset) match {
      case Some(o) if o.kind == sigmafi.zc.ZcKind.Order || o.kind == sigmafi.zc.ZcKind.PricedOrder =>
        ZcCheck("origin", Some(live.forall(b => o.market.contains(b.market))), s"minted by a standard ${o.kind}")
      case Some(_) => ZcCheck("origin", Some(false), "not minted by a standard order")
      case None => ZcCheck("origin", None, "not looked up yet")
    }
    live.map { b =>
      val terms = ZcBondTerms.of(b)
      Json.obj("assetId" -> asset, "stateBoxId" -> b.boxId, "stateCreated" -> b.box.creationHeight, "state" -> "live",
        "market" -> b.market.name, "borrowerHash" -> b.borrowerHash, "principal" -> str(principalOf(b)),
        "repayment" -> str(b.repayment), "start" -> b.start, "maturity" -> b.maturity,
        "timing" -> (b.terms(6) match { case 0L => "fixed"; case 1L => "term"; case _ => "window" }),
        "reserveNanoErg" -> str(b.feeReserve), "collateral" -> collateral(b.collateralErg, b.collateral),
        "rule" -> rule(b.liqHash, b.market, b.terms.drop(7)), "coverBps" -> coverBps(terms),
        "recollateralizeFrom" -> (b.box.creationHeight + RecollateralizeWait),
        "refreshFrom" -> (b.box.creationHeight + RefreshAge + 1L),
        "origin" -> originJson, "settlement" -> JsNull, "pending" -> book.bonds(asset).pending,
        "mine" -> known.mine(b.borrowerHash), "borrowedByMe" -> known.mine(b.borrowerHash),
        "checks" -> Seq(originCheck, collateralCheck(b.collateral.size))
          .map(c => Json.obj("key" -> c.key, "ok" -> c.ok, "detail" -> c.detail)))
    }.orElse(settled.map { s =>
      val v = s.record
      Json.obj("assetId" -> asset, "stateBoxId" -> v.boxId, "state" -> (if (v.liquidated) "liquidated" else "repaid"),
        "settledBondBoxId" -> v.bondBoxId, "origin" -> originJson, "pending" -> s.pending,
        "settlement" -> Json.obj("vaultBoxId" -> v.boxId, "kind" -> (if (v.liquidated) "liquidated" else "repaid"),
          "nanoErg" -> str(v.box.value), "tokens" -> tokens(v.proceeds)),
        "checks" -> Seq(originCheck).map(c => Json.obj("key" -> c.key, "ok" -> c.ok, "detail" -> c.detail)))
    })
  }

  /** Whether a bond's collateral fits a Dutch vault: one that holds more cannot be split into Dutch shares. */
  private def collateralCheck(n: Int): ZcCheck =
    ZcCheck("collateralTokens", Some(n <= ZcSizing.MaxCollateralTokens),
      s"$n collateral tokens; a Dutch vault takes at most ${ZcSizing.MaxCollateralTokens}")

  private def principalOf(b: ZcBond): Long = if (b.market.tokenId.isEmpty) b.terms(1) else b.principalTokens.headOption.fold(0L)(_.amount)

  /** A share token's record: its vault and its mint terms. None when no vault holds its reserve. */
  def sharesJson(shareId: String): Option[JsObject] = book.shareVaults.get(shareId).map { open =>
    val v = open.record
    val mint = known.origin(shareId)
    val claim = matcher.claimOf(shareId)
    val repayment = book.bonds.get(v.bondAsset).map(_.record.repayment)
    val auction = if (v.dutch && v.auction.size >= 7) Some(Json.obj("openingBps" -> v.auction(1), "floorBps" -> v.auction(2),
      "length" -> v.auction(3), "expiry" -> v.auction(4), "threshold" -> str(v.auction(5)))) else None
    Json.obj("tokenId" -> shareId, "bondId" -> v.bondAsset, "mint" -> mint.map(_.kind.name),
      "borrowedByMe" -> book.bonds.get(v.bondAsset).exists(b => known.mine(b.record.borrowerHash)),
      "type" -> (if (!v.dutch) "plain" else if (v.auction.lift(5).contains(Long.MaxValue)) "dutch" else "selective"),
      "shareCount" -> str(v.shares), "decimals" -> 6, "auction" -> auction,
      "vault" -> Json.obj("boxId" -> v.boxId, "contract" -> v.script.kind.name, "state" -> v.state.map(_.toInt),
        "pulled" -> !v.holding, "outstanding" -> str(v.outstanding), "nanoErg" -> str(v.box.value),
        "tokens" -> tokens(v.box.assets.drop(1)), "auctionStart" -> v.auction.lift(6).filter(_ > 0L), "pending" -> open.pending),
      "minLot" -> repayment.filter(_ > 0L).map(r => ((BigInt(v.shares) + r - 1) / r).max(BigInt(1)).toString),
      "checks" -> (Seq(
        ZcCheck("origin", if (mint.isEmpty) None else Some(claim.isRight), claim.fold(identity, _ => "a standard share mint")),
        ZcCheck("shareCount", Some(v.shares == ShareCount), s"${v.shares} raw shares")) ++
        book.bonds.get(v.bondAsset).filter(_ => v.dutch).map(b => collateralCheck(b.record.collateral.size)).toSeq)
        .map(c => Json.obj("key" -> c.key, "ok" -> c.ok, "detail" -> c.detail)))
  }
}

object ZcJson {
  def at(book: ZcMarketBook): JsObject = Json.obj("at" -> Json.obj("height" -> book.height,
    "blockId" -> book.view.book.headerId, "indexedHeight" -> book.height, "revision" -> book.revision,
    "cut" -> book.view.book.cut, "mempool" -> book.view.mempool.isDefined))
}
