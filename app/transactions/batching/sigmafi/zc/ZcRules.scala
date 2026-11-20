package transactions.batching.sigmafi.zc

import node.model.NodeAsset
import sigmafi.zc.{ZcContracts, ZcExpression, ZcKind, ZcMarket}

import scala.util.Try

/**
 * The terms a bond's liquidation rule and an offer's policy read: those of a live bond, or of the bond a fill of an
 * order would make (`prospect`), whose collateral is the order's box less its expiry fee. `principalErg` is R4(1):
 * the nanoERG a fill pays the borrower, which is the whole principal on an ERG bond.
 */
final case class ZcBondTerms(market: ZcMarket, repayment: Long, principalErg: Long, payload: IndexedSeq[Long],
                             liqHash: String, collateralErg: Long, collateral: Seq[NodeAsset], start: Long, maturity: Long)

object ZcBondTerms {
  def of(b: ZcBond): ZcBondTerms = ZcBondTerms(b.market, b.repayment, b.terms(1), b.terms.drop(7), b.liqHash,
    b.collateralErg, b.collateral, b.start, b.maturity)

  /** The bond a fill of `o` at `start` paying `principal` would make. */
  def prospect(o: ZcOrder, start: Long, maturity: Long, principal: Long): ZcBondTerms =
    ZcBondTerms(o.market, o.repayment, if (o.market.tokenId.isEmpty) principal else o.principalErg, o.payload, o.liqHash,
      o.box.value - o.expiryFee, o.box.assets, start, maturity)
}

/** How a box's liquidation rule reads: the shared maturity rule, its own market's oracle rule, or anything else. */
sealed abstract class ZcRule(val name: String)

object ZcRule {
  case object Maturity extends ZcRule("maturity")
  case object Oracle extends ZcRule("oracle")
  case object Other extends ZcRule("other")
}

/**
 * An offer as a match sees it: the box, and its capital and nanoERG after the fills a simulation already gave it. A
 * real box has `capital` and `value` as the box holds them.
 */
final case class ZcOfferState(offer: ZcOffer, capital: Long, value: Long) {
  def boxId: String = offer.boxId
  def market: ZcMarket = offer.market
}

object ZcOfferState {
  def of(o: ZcOffer): ZcOfferState = ZcOfferState(o, o.capital, o.box.value)
}

/**
 * zcs1's rules for quoting, checking and matching ZC boxes, ported from the reference CLI and exact to one raw unit.
 * Every function is pure: heights and the oracle are arguments. A refusal is a reason, never an exception; an
 * unquotable box answers `Left` with why. `height` is where the signer runs scripts; a transaction lands at
 * `height + 1` or later.
 */
object ZcRules {

  import ZcPricing._

  private def hashOf(e: ZcExpression, m: ZcMarket): String =
    ZcContracts.expression(e, if (e.perMarket) Some(m) else None).hash

  /** The rule `liqHash` names for a box of market `m`. */
  def rule(liqHash: String, m: ZcMarket): ZcRule =
    if (liqHash == hashOf(ZcExpression.MaturityLiquidation, m)) ZcRule.Maturity
    else if (liqHash == hashOf(ZcExpression.OracleLiquidation, m)) ZcRule.Oracle
    else ZcRule.Other

  /** Whether `liqHash` is the oracle rule of either market: such a rule may liquidate early. */
  private def anyOracleRule(liqHash: String): Boolean =
    ZcMarket.all.exists(m => liqHash == hashOf(ZcExpression.OracleLiquidation, m))

  /** The bond's own early-liquidation ratio, when its rule is an oracle rule carrying one. */
  def ownRatio(b: ZcBondTerms): Option[Long] =
    if (anyOracleRule(b.liqHash) && b.payload.nonEmpty) Some(b.payload.head) else None

  def cover(b: ZcBondTerms, price: Long): Option[(BigInt, BigInt)] =
    coverOf(b.market, b.collateralErg, b.collateral, b.repayment, price)

  /** Whether the bond's collateral is strictly below its own oracle ratio at `price`: anyone may liquidate it. */
  def belowOwnRatio(b: ZcBondTerms, price: Long): Boolean =
    ownRatio(b).exists(r => cover(b, price).exists { case (c, d) => c * 10000 < d * r })

  /** Whether `oracle` is fresh enough for an age limit of `age` blocks at a landing height of `height + 1`. */
  def fresh(oracle: ZcOracle, age: Long, height: Long): Boolean = height + 1L - oracle.box.creationHeight <= age

  // ─── orders ────────────────────────────────────────────────────────────────

  def yieldOrder(o: ZcOrder): Boolean = o.pricingHash.contains(hashOf(ZcExpression.YieldOrderPricing, o.market))
  def curveOrder(o: ZcOrder): Boolean = o.pricingHash.contains(hashOf(ZcExpression.CurveOrderPricing, o.market))
  def window(o: ZcOrder): Boolean = o.mode == 2L
  def onClose(o: ZcOrder): Boolean = o.mode == 1L

  /** A window order's durations from the start: R4(3) the least, R9(0) the most. */
  def orderWindow(o: ZcOrder): Option[(Long, Long)] =
    if (!window(o) || o.pricingTerms.isEmpty) None else Some(o.terms(3) -> o.pricingTerms.head)

  /** A curve order's curve: R9 = (most duration, most yield, then `(bps, blocks)` points). */
  def borrowCurve(o: ZcOrder): Option[BorrowCurve] =
    if (!curveOrder(o) || o.pricingTerms.size < 2 || o.pricingTerms.size % 2 != 0) None
    else Some(BorrowCurve(o.pricingTerms(1), pairs(o.pricingTerms.drop(2))))

  /** A yield order's two points: R9 = (start height, start yield, end height, end yield). */
  def yieldLine(o: ZcOrder): Option[YieldLine] =
    if (!yieldOrder(o) || o.pricingTerms.size != 4) None
    else Some(YieldLine(o.pricingTerms(0), o.pricingTerms(1), o.pricingTerms(2), o.pricingTerms(3)))

  /** The fill guard's least ratio, at payload index 2, when the order carries the guard. */
  def fillRatio(o: ZcOrder): Option[Long] = o.guardHash.flatMap(_ => o.payload.lift(2))

  /** zcs1's default maturity for a wallet filling a window order: the earliest grid height in it, else its shortest. */
  def defaultWindowMaturity(o: ZcOrder, start: Long): Option[Long] =
    orderWindow(o).map { case (lo, hi) => gridBetween(start + lo, start + hi).headOption.getOrElse(start + lo) }

  /**
   * The maturity a fill at `start` gives the bond: the fixed height, the start plus an on-close duration, or for a
   * window order `chosen` (by default zcs1's), which must lie in the window.
   */
  def orderMaturity(o: ZcOrder, start: Long, chosen: Option[Long] = None): Either[String, Long] =
    if (window(o)) orderWindow(o) match {
      case None => Left("the window order has no most duration")
      case Some((lo, hi)) =>
        val m = chosen.orElse(defaultWindowMaturity(o, start)).get
        if (m - start < lo || m - start > hi) Left(s"maturity $m is outside the window ${start + lo} to ${start + hi}")
        else Right(m)
    } else {
      val fixed = if (onClose(o)) Try(Math.addExact(start, o.terms(3))).toOption else Some(o.terms(3))
      fixed match {
        case None => Left("the order's duration overflows")
        case Some(f) if chosen.exists(_ != f) => Left(s"only a window order lets the lender choose; this one matures at $f")
        case Some(f) => Right(f)
      }
    }

  /** The least principal at a landing height `at`, before fees, for a fill at `start` with `maturity`. */
  def orderPrincipal(o: ZcOrder, at: Long, maturity: Long): Either[String, Long] =
    if (!o.priced) Right(o.principal)
    else if (!yieldOrder(o) && !curveOrder(o)) Left("the order names an unknown pricing expression")
    else if (maturity <= at) Left("no blocks left to maturity")
    else {
      val price =
        if (curveOrder(o)) borrowCurve(o).filter(_.valid).map(_.price(o.repayment, maturity - at))
        else yieldLine(o).filter(_.valid).map(_.price(o.repayment, 1L, 1L, maturity - at, at))
      price match {
        case None => Left("the order's pricing terms are invalid")
        case Some(p) =>
          val result = p.max(BigInt(o.principal))
          if (result <= 0 || !result.isValidLong) Left("the priced principal is out of range") else Right(result.toLong)
      }
    }

  /** The principal a fill pays: the most the order asks at any landing height from `height` to three blocks on. */
  def resolvePrincipal(o: ZcOrder, height: Long, maturity: Long): Either[String, Long] =
    (0L to QuoteBlocks).foldLeft[Either[String, Long]](Right(0L)) { (acc, d) =>
      for (best <- acc; p <- orderPrincipal(o, height + d, maturity)) yield math.max(best, p)
    }

  /** Why no fill of `o` can be built now, or None: the CLI's own checks before any offer or guard. */
  def orderRefusal(o: ZcOrder, height: Long): Option[String] = {
    val windowIssue =
      if (window(o)) {
        if (!o.priced || !curveOrder(o)) Some("a window order not priced by CurveOrder_Pricing")
        else orderWindow(o) match {
          case Some((lo, hi)) if lo >= 30L && hi >= lo => None
          case _ => Some("the window order's window is malformed")
        }
      } else if (o.priced && curveOrder(o) && o.pricingTerms.isEmpty) Some("the curve order has no pricing terms")
      else None
    lazy val oneAsset = o.market.tokenId match {
      case None => o.principalTokens.isEmpty && o.principalErg > 0L && o.terms(2) <= o.repayment
      case Some(id) => o.principalTokens.map(_.tokenId) == Seq(id) && o.principalErg <= ZcBoxes.MinBox &&
        o.terms(2) <= ZcBoxes.MinBox
    }
    windowIssue
      .orElse(if (o.priced && (o.policies != 2 || !o.pricingHash.exists(_.length == 64) ||
        o.guardHash.exists(_.length != 64) || o.pricingTerms.size > 64)) Some("the priced order has malformed policy commitments")
        else None)
      .orElse(if (o.priced) orderMaturity(o, height).flatMap(m => orderPrincipal(o, height + QuoteBlocks, m)).left.toOption
        else orderMaturity(o, height).left.toOption)
      .orElse(if (!oneAsset) Some(s"the order does not lend and repay in ${o.market} alone") else None)
      .orElse(if (o.repayment <= 0L || o.terms(2) <= 0L || o.principalErg < 0L) Some("the order has invalid amounts") else None)
      .orElse(if (o.mode != 0L && o.mode != 1L && !(o.mode == 2L && o.priced)) Some("unknown maturity mode") else None)
      .orElse(if (o.expiryFee < MinBatchingFee || o.expiryFee > o.box.value - ZcBoxes.MinBox)
        Some("the expiry fee is out of bounds") else None)
      .orElse(if (height + 1L >= o.expiry) Some(s"the order expired at ${o.expiry}") else None)
      .orElse(if (onClose(o) && o.terms(3) < 30L) Some("the duration is under 30 blocks") else None)
      .orElse(if (o.mode == 0L && o.terms(3) <= height + 2L) Some(s"the order matures at ${o.terms(3)}, too close to fill")
        else None)
  }

  /**
   * Why the order's fill guard would refuse a fill making `bond`, or None when it has none or would pass. The guard must
   * be the market's, its terms whole, and the confirmed oracle fresh enough with the bond's cover at the guard's ratio.
   */
  def guardRefusal(o: ZcOrder, bond: ZcBondTerms, oracle: Option[ZcOracle], height: Long): Option[String] =
    o.guardHash.flatMap { hash =>
      val p = o.payload
      if (hash != hashOf(ZcExpression.FillGuard, o.market)) Some("the order names an unknown fill guard")
      else if (p.size < 3 || p(0) <= 0L || p(2) < p(0) || p(1) < 1L || p(1) > MaxOracleAge)
        Some("the order's fill guard terms are malformed")
      else oracle match {
        case None => Some("no usable oracle")
        case Some(or) if !fresh(or, p(1), height) => Some(s"the oracle is older than the order's ${p(1)} blocks")
        case Some(or) if !cover(bond, or.price).exists(atLeast(_, p(2))) => Some("under the order's fill guard")
        case _ => None
      }
    }

  // ─── auctions ──────────────────────────────────────────────────────────────

  /** A lot's terms: 1 ≤ floor ≤ opening, a fall of at least one block, ending by the expiry. */
  def lotValid(l: ZcLot): Boolean = {
    val Seq(opening, floor, start, end, expiry, _) = l.terms.take(6)
    floor >= 1L && floor <= opening && start >= 0L && start < end && end <= expiry
  }

  /** The least a lot sells for at `height`: the opening, falling in a straight line to the floor, rounded up. */
  def lotPrice(l: ZcLot, height: Long): Long = {
    val Seq(opening, floor, start, end, _, _) = l.terms.take(6)
    val at = height.max(start).min(end)
    val span = BigInt(end) - BigInt(start)
    if (span <= 0) opening
    else {
      val owed = BigInt(opening) * span - (BigInt(opening) - BigInt(floor)) * (BigInt(at) - BigInt(start))
      ((owed + span - 1) / span).max(BigInt(0)).min(BigInt(Long.MaxValue)).toLong
    }
  }

  def lotRefundFee(l: ZcLot): Long = l.terms(5)

  /** A Dutch vault's currency: None for ERG, else the token R8 names. */
  def vaultCurrency(v: ZcShareVault): Option[String] =
    v.currency.filter(_.length == 32).map(org.bouncycastle.util.encoders.Hex.toHexString)

  /** Whether the vault's auction is open: a pulled Dutch vault in state 1 (floor) or 4 (excess). */
  def auctionOpen(v: ZcShareVault): Boolean =
    v.dutch && !v.holding && v.auction.size >= 7 && v.auction(3) >= 1L &&
      (v.state.contains(1.toByte) || v.state.contains(4.toByte))

  def excessAuction(v: ZcShareVault): Boolean = auctionOpen(v) && v.state.contains(4.toByte)

  def threshold(v: ZcShareVault): Long = v.auction(5)

  /** The first height holders may claim an unsold lot in kind: a floor auction's expiry, or an excess fall's end. */
  def claimableFrom(v: ZcShareVault): Long = {
    val sum = BigInt(v.auction(6)) + BigInt(if (excessAuction(v)) v.auction(3) else v.auction(4))
    sum.max(BigInt(0)).min(BigInt(Long.MaxValue)).toLong
  }

  /** What the vault holds of its currency now. */
  def currencyHeld(v: ZcShareVault): Long =
    vaultCurrency(v).fold(v.box.value)(id => v.box.assets.drop(1).filter(_.tokenId == id).map(_.amount).sum)

  /** The least currency a floor auction's vault must hold after a sale at `at`, rounded up. */
  def lotTarget(v: ZcShareVault, at: Long): Long = {
    val Seq(repayment, opening, floor, length, _, _, began) = v.auction.take(7)
    if (length < 1L || repayment <= 0L) 0L
    else {
      val since = (BigInt(at) - BigInt(began)).max(BigInt(0)).min(BigInt(length))
      val num = BigInt(repayment) * (BigInt(opening) * BigInt(length) - (BigInt(opening) - BigInt(floor)) * since)
      val den = BigInt(10000) * BigInt(length)
      ((num + den - 1) / den).max(BigInt(0)).min(BigInt(Long.MaxValue)).toLong
    }
  }

  /** What an excess auction's buyer adds at `at`, rounded up; None once the fall has ended. */
  def excessPrice(v: ZcShareVault, at: Long): Option[Long] = {
    val Seq(repayment, opening, _, length, _, _, began) = v.auction.take(7)
    if (length < 1L || repayment <= 0L || opening <= 0L) None
    else {
      val since = (BigInt(at) - BigInt(began)).max(BigInt(0))
      if (since >= BigInt(length)) None
      else {
        val num = BigInt(repayment) * BigInt(opening) * (BigInt(length) - since)
        val den = BigInt(10000) * BigInt(length)
        val p = (num + den - 1) / den
        if (p <= 0 || !p.isValidLong) None else Some(p.toLong)
      }
    }
  }

  /** The lot tokens an excess sale leaves for pro rata claims: those at or above the in-kind threshold. */
  def keptBySale(v: ZcShareVault): Seq[NodeAsset] =
    if (!excessAuction(v)) Seq.empty
    else v.box.assets.drop(1).filter(t => !vaultCurrency(v).contains(t.tokenId) && t.amount >= threshold(v))

  /** Whether a sale leaves a token currency's nanoERG in the vault: an excess sale does, unless it is thin. */
  def saleKeepsErg(v: ZcShareVault): Boolean =
    excessAuction(v) && vaultCurrency(v).isDefined && v.box.value - ZcBoxes.MinBox >= threshold(v)

  // ─── asks ──────────────────────────────────────────────────────────────────

  def guardedAsk(a: ZcAsk): Boolean = a.pricingHash.contains(hashOf(ZcExpression.GuardedYieldPricing, a.market))

  def yieldAsk(a: ZcAsk): Boolean =
    guardedAsk(a) || a.pricingHash.contains(hashOf(ZcExpression.YieldAskPricing, a.market))

  def wholeUnits(a: ZcAsk): Long = if (a.priced) a.terms(4) else 1L

  /** A yield ask's two points, R5(5..8). */
  def askLine(a: ZcAsk): Option[YieldLine] =
    if (!yieldAsk(a) || a.terms.size < 9) None else Some(YieldLine(a.terms(5), a.terms(6), a.terms(7), a.terms(8)))

  /** A guarded ask's (least cover bps, oracle age), R5(9..10). */
  def saleGuard(a: ZcAsk): Option[(Long, Long)] =
    if (!guardedAsk(a) || a.terms.size < 11) None else Some(a.terms(9) -> a.terms(10))

  /** The fixed price (a sale order's price, a priced ask's floor) of `units` raw units, rounded up. */
  def fixedPrice(a: ZcAsk, units: Long): BigInt = (BigInt(units) * a.terms(0) + a.terms(1) - 1) / a.terms(1)

  /**
   * What a buyer pays for `units` now: a sale order's fixed price, or a priced ask's most over the next three blocks
   * against `bond`, the live bond its R7 names, never under its floor.
   */
  def askPrice(a: ZcAsk, units: Long, bond: Option[ZcBondTerms], height: Long): Either[String, Long] =
    if (!a.priced) {
      val p = fixedPrice(a, units)
      if (p <= 0 || !p.isValidLong) Left("the ask price is out of range") else Right(p.toLong)
    } else if (!yieldAsk(a)) Left("the ask names an unknown pricing expression")
    else bond match {
      case None => Left("the bond the ask prices is not live")
      case Some(b) => askLine(a).filter(_.valid) match {
        case None => Left("the ask's yield terms are invalid")
        case Some(_) if wholeUnits(a) < 1L || wholeUnits(a) > 1000000000L => Left("invalid units per bond")
        case Some(_) if b.maturity <= height + QuoteBlocks => Left("no blocks left to maturity")
        case Some(line) =>
          val raw = (0L to QuoteBlocks).map(d => line.price(b.repayment, units, wholeUnits(a), b.maturity - (height + d),
            height + d)).max
          val owed = raw.max(fixedPrice(a, units))
          if (owed <= 0 || !owed.isValidLong) Left("the ask price is out of range") else Right(owed.toLong)
      }
    }

  /** Why the ask's sale guard would refuse a sale of `bond`'s claim now, or None. */
  def saleGuardRefusal(a: ZcAsk, bond: ZcBondTerms, oracle: Option[ZcOracle], height: Long): Option[String] =
    if (!guardedAsk(a)) None
    else saleGuard(a) match {
      case None => Some("the ask's sale guard terms are malformed")
      case Some((minBps, age)) if minBps <= 0L || age < 1L || age > MaxOracleAge =>
        Some("the ask's sale guard terms are malformed")
      case Some((minBps, age)) => oracle match {
        case None => Some("no usable oracle")
        case Some(or) if !fresh(or, age, height) => Some(s"the oracle is older than the ask's $age blocks")
        case Some(or) if !cover(bond, or.price).exists(atLeast(_, minBps)) => Some("under the seller's sale guard")
        case _ => None
      }
    }

  /** Why buying `units` from the ask cannot happen now, or None. */
  def fillRefusal(a: ZcAsk, units: Long, height: Long): Option[String] =
    if (height + 1L >= a.expiry) Some(s"the ask expired at ${a.expiry}")
    else if (units < 1L || units > a.token.amount) Some(s"the ask holds ${a.token.amount}")
    else if (units < minFill(a) && units != a.token.amount) Some(s"under the ask's least fill of ${minFill(a)}")
    else None

  def minFill(a: ZcAsk): Long = a.terms(2)

  // ─── offers ────────────────────────────────────────────────────────────────

  def offerCurve(o: ZcOffer): YieldCurve = YieldCurve(o.terms(0), o.curve)
  def minBlocks(o: ZcOffer): Long = o.terms(1)
  def maxBlocks(o: ZcOffer): Long = o.terms(2)
  def batcherFee(o: ZcOffer): Long = o.terms(3)
  def feeReserve(o: ZcOffer): Long = o.terms(4)
  def floorBps(o: ZcOffer): Long = o.terms(5)
  def minPrice(o: ZcOffer): Long = o.terms(7)
  def maxAge(o: ZcOffer): Long = o.terms(8)
  def token(o: ZcOffer): Boolean = o.market.tokenId.isDefined
  def originalCapital(s: ZcOfferState): Long = if (token(s.offer)) s.offer.terms(11) else s.value
  def spendBudget(o: ZcOffer): Long = if (token(o)) o.terms(12) else 0L
  def minFillBps(o: ZcOffer): Long = if (token(o)) o.terms(13) else 0L
  def rewardBudget(o: ZcOffer): Long = if (token(o)) o.terms(14) else 0L
  def buysShares(o: ZcOffer): Boolean = o.terms(10) > 0L
  def delivers(o: ZcOffer): Boolean = o.fractional.nonEmpty

  /** Whether `o`'s delivery settings (if delivering into a share mint) are well-formed and name a pinned mint script. */
  def deliveryValid(o: ZcOffer): Boolean =
    if (!delivers(o)) true
    else {
      val targetHex = org.bouncycastle.util.encoders.Hex.toHexString(o.mintHash)
      val isFrac = targetHex == ZcContracts.script(ZcKind.FractionalMint, None).treeHash
      val isDutch = targetHex == ZcContracts.script(ZcKind.DutchAuctionMint, Some(o.market)).treeHash
      val needed = if (isDutch) 8 else 3
      (isFrac || isDutch) && o.fractional.size == needed && o.metadata.size == 3 &&
        o.fractional(0) >= 1L && o.fractional(0) <= 1000000000L &&
        o.fractional(1) >= MinBatchingFee && o.fractional(2) >= MinBatchingFee &&
        deliveryErgBig(o, shares = false).isValidLong
    }

  /** Whether `o`'s collateral policy commitment equals its market's pinned `CollateralPolicy` expression hash. */
  def policyValid(o: ZcOffer): Boolean = o.policyHash == hashOf(ZcExpression.CollateralPolicy, o.market)

  /** The batcher's reward for a fill at `price`: an ERG offer's fee, or a token offer's share of its reward budget. */
  def fillFee(s: ZcOfferState, price: Long): Long =
    if (!token(s.offer)) batcherFee(s.offer)
    else {
      val orig = originalCapital(s)
      val budget = rewardBudget(s.offer)
      if (orig <= 0L || budget <= 0L || price <= 0L || price > orig) 0L else (BigInt(budget) * price / orig).toLong
    }

  /** Exact BigInt nanoERG funding a fill's delivery (never overflows Long). */
  def deliveryErgBig(o: ZcOffer, shares: Boolean): BigInt =
    if (!delivers(o) || shares) BigInt(ZcBoxes.MinBox)
    else {
      val pull = BigInt(o.fractional.lift(1).getOrElse(0L))
      val mint = BigInt(o.fractional.lift(2).getOrElse(0L))
      BigInt(2L * ZcBoxes.MinBox) + pull + mint
    }

  /**
   * The nanoERG funding a fill's delivery: one box minimum for shares or a unit to the owner, or a unit delivered into
   * the share mint request the offer names, with that request's two box minimums and its pull and mint fees.
   */
  def deliveryErg(o: ZcOffer, shares: Boolean): Long =
    deliveryErgBig(o, shares).max(BigInt(ZcBoxes.MinBox)).min(BigInt(Long.MaxValue)).toLong

  /** What the offer pays per fill: the price, plus the batcher fee on an ERG offer. */
  def cost(s: ZcOfferState, price: Long): BigInt =
    if (token(s.offer)) BigInt(price) else BigInt(price) + fillFee(s, price)

  /**
   * Why the offer would refuse paying `price` for `units` of `whole` of `bond`, or None: expiry, its window, least
   * price, the yield test, capital, a token offer's budgets, the policy at the oracle, and zcs1's refusal of a bond
   * under its own ratio. `primary` is a new bond from an order; `shares` a share purchase.
   */
  def offerRefusal(s: ZcOfferState, bond: ZcBondTerms, price: Long, units: Long, whole: Long, oracle: Option[ZcOracle],
                   height: Long, primary: Boolean = false, shares: Boolean = false,
                   ignoreYield: Boolean = false): Option[String] = {
    val o = s.offer
    val at = height + 1L
    val remaining = bond.maturity - at
    val fee = fillFee(s, price)
    val c = cost(s, price)
    val delivery = deliveryErgBig(o, shares)
    // nanoERG leaving the offer: the fee, the delivery, and a new bond's reserve and principal, or an ask's payout box
    val ergOut = BigInt(fee) + delivery + (if (primary) BigInt(feeReserve(o)) else BigInt(0)) +
      (if (primary) BigInt(bond.principalErg) else BigInt(ZcBoxes.MinBox))
    lazy val coverNow = oracle.flatMap(p => cover(bond, p.price))
    if (at >= o.expiry) Some(s"the offer expired at ${o.expiry}")
    else if (!policyValid(o)) Some("the offer names an unknown collateral policy")
    else if (feeReserve(o) < 0L || !deliveryValid(o)) Some("the offer's reserve or delivery terms are invalid")
    else if (minBlocks(o) < 1L || maxBlocks(o) < minBlocks(o)) Some("the offer's maturity bounds are invalid")
    else if (o.market != bond.market) Some(s"the offer lends ${o.market}, the bond repays ${bond.market}")
    else if (remaining <= 0L || remaining < minBlocks(o) || remaining > maxBlocks(o))
      Some(s"$remaining blocks to maturity, outside the offer's ${minBlocks(o)} to ${maxBlocks(o)}")
    else if (price <= 0L || price < minPrice(o)) Some("under the offer's least price")
    else if (!offerCurve(o).ordered || o.terms(0) < 0L) Some("the offer's curve is malformed")
    else if (!c.isValidLong || (!ignoreYield && !offerCurve(o).accepts(bond.repayment, units, c.toLong, whole, remaining)))
      Some(s"under the offer's ${offerCurve(o).bpsAt(remaining)} bps at $remaining blocks")
    else if (s.capital < price) Some("the offer's capital is short")
    else if (!ergOut.isValidLong || ergOut < 0) Some("the fill's ERG requirement overflows")
    else if (token(o) && (originalCapital(s) <= 0L || s.capital > originalCapital(s) || spendBudget(o) <= 0L ||
      rewardBudget(o) <= 0L || rewardBudget(o) >= spendBudget(o) || minFillBps(o) < 100L || minFillBps(o) > 10000L ||
      batcherFee(o) < MinBatchingFee)) Some("the token offer's budget terms are invalid")
    else if (token(o) && BigInt(price) * 10000L < BigInt(originalCapital(s)) * minFillBps(o))
      Some("under the offer's least fill")
    else if (fee < MinBatchingFee) Some("the batcher reward is under 0.002 ERG")
    else if (token(o) && ergOut * originalCapital(s) > BigInt(spendBudget(o)) * price) Some("over the offer's ERG budget")
    else if (token(o) && BigInt(s.value) - ergOut < BigInt(ZcBoxes.MinBox) + batcherFee(o))
      Some("the fill would spend the offer's refund reserve")
    else if (bond.liqHash != hashOf(ZcExpression.OracleLiquidation, o.market)) Some("the bond's rule is not the oracle rule")
    else if (bond.payload.size < 2 || bond.payload(0) < floorBps(o) || bond.payload(1) < maxAge(o) ||
      bond.payload(1) > MaxOracleAge) Some("the bond's ratio or oracle age is under the offer's")
    else if (oracle.isEmpty) Some("no usable oracle")
    else if (!fresh(oracle.get, maxAge(o), height)) Some(s"the oracle is older than the offer's ${maxAge(o)} blocks")
    else if (!coverNow.exists(atLeast(_, floorBps(o)))) Some("the collateral is under the offer's floor")
    else if (belowOwnRatio(bond, oracle.get.price)) Some("the bond is under its own ratio: liquidate it instead")
    else None
  }

  /**
   * Why offer `o` would refuse shares minted as `mint` records, or None: it buys only Selective or Dutch shares, only
   * when it buys shares at all, and only with an auction floor and expiry inside its bounds.
   */
  def shareRefusal(o: ZcOffer, mint: ZcOrigin): Option[String] =
    if (mint.kind != ZcKind.DutchAuctionMint || mint.terms.size < 8) Some("offers buy only Selective or Dutch shares")
    else if (!buysShares(o)) Some("the offer buys no shares")
    else if (mint.terms(4) < o.terms(9) || mint.terms(6) > o.terms(10)) Some("the shares' auction is outside the offer's bounds")
    else None

  /**
   * zcs1's maturity for filling window order `order` from offer `s`, or None when none suits both. The candidates are
   * the ends of the heights both accept, both curves' breakpoints and the grid heights between, since both yields are
   * straight lines between those. Of the ones the offer and the guard accept: a grid height first, then the widest gap
   * between the yield earned and the yield required, then the shorter.
   */
  def windowMaturity(s: ZcOfferState, order: ZcOrder, oracle: Option[ZcOracle], height: Long,
                     ignoreYield: Boolean = false): Option[Long] = {
    val o = s.offer
    val start = height
    val at = start + 1L
    (orderWindow(order), borrowCurve(order)) match {
      case (Some((lo, hi)), Some(borrow)) if minBlocks(o) >= 1L && maxBlocks(o) >= minBlocks(o) =>
        val fromBig = (BigInt(start) + BigInt(lo)).max(BigInt(at) + BigInt(minBlocks(o)))
        val toBig = (BigInt(start) + BigInt(hi)).min(BigInt(at) + BigInt(maxBlocks(o))).min(BigInt(Long.MaxValue))
        if (!fromBig.isValidLong || fromBig > toBig) None
        else {
          val from = fromBig.toLong
          val to = toBig.toLong
          val bPoints = borrow.breakpoints.flatMap(d => Seq(BigInt(start) + BigInt(d), BigInt(at) + BigInt(d)))
          val oPoints = o.curve.map(p => BigInt(at) + BigInt(p._2))
          val candidates = (Seq(BigInt(from), BigInt(to)) ++ bPoints ++ oPoints ++
            gridBetween(from, to).map(BigInt(_)))
            .filter(m => m >= fromBig && m <= toBig && m.isValidLong).map(_.toLong).distinct
          val feasible = candidates.flatMap { m =>
            resolvePrincipal(order, height, m).toOption.flatMap { principal =>
              val bond = ZcBondTerms.prospect(order, start, m, principal)
              if (offerRefusal(s, bond, principal, 1L, 1L, oracle, height, primary = true, ignoreYield = ignoreYield).isEmpty &&
                guardRefusal(order, bond, oracle, height).isEmpty) {
                val (rate, span) = offerCurve(o).at(m - at)
                Some(m -> (rankYield(BigInt(order.repayment), cost(s, principal), m - at) - rate * 1000000 / span))
              } else None
            }
          }
          feasible.sortBy { case (m, gap) => (if (m % Grid == 0L) 0 else 1, -gap, m) }.headOption.map(_._1)
        }
      case _ => None
    }
  }
}
