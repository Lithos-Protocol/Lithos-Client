package transactions.batching.sigmafi.zc

import node.model.NodeAsset
import transactions.batching.arb.{ArbAsset, ArbPool, ArbPoolKind, ArbSwap}

/**
 * How one collateral asset in a ZC basket is converted into ERG.
 *
 * AMM swaps (`ZcAmmLiquidation`) for fungible tokens in ErgoDEX or LithosDex pools, and a SigmaUSD bank redeem
 * (`BankAssetLiquidation`) for SigUSD. Future support for bond units, shares, and wrapped LithosDex position NFTs plugs in by adding
 * new subtypes here and corresponding [[ZcAssetPricer]] implementations without changing basket evaluation or arb
 * orchestration.
 */
trait ZcAssetLiquidation {
  def tokenId: String
  def amount: Long
  /** Exact nanoERG guaranteed by this liquidation plan on the pool states it was priced against. */
  def ergOut: Long
  /** Number of block transactions this liquidation consumes. */
  def legs: Int
  /** The swaps that carry it out, in order, the last paying ERG. */
  def swaps: Vector[ArbSwap]
}

/** Liquidates `amount` raw units of a fungible token into ERG via 1 or 2 AMM swaps. */
final case class ZcAmmLiquidation(tokenId: String, amount: Long, swaps: Vector[ArbSwap]) extends ZcAssetLiquidation {
  require(swaps.nonEmpty && swaps.last.assetOut.isErg, "an AMM liquidation must end in ERG")
  override def ergOut: Long = swaps.last.amountOut
  override def legs: Int = swaps.size
}

/** Liquidates `amount` of a SigmaUSD coin by one redeem at the bank. */
final case class BankAssetLiquidation(tokenId: String, amount: Long, swap: ArbSwap) extends ZcAssetLiquidation {
  require(swap.pool.kind == ArbPoolKind.SigmaUsdBank && swap.assetOut.isErg, "a bank liquidation is one redeem")
  override def ergOut: Long = swap.amountOut
  override def legs: Int = 1
  override def swaps: Vector[ArbSwap] = Vector(swap)
}

/**
 * Evaluates and plans the conversion of a single collateral token into ERG against live pool states.
 *
 * Returning `None` marks the token unpriced (treated as **0 ERG value**). Implementations must use exact integer
 * arithmetic and return updated `pools` reflecting every swap consumed, so multi-token baskets sharing an AMM pool
 * never double-count liquidity.
 */
trait ZcAssetPricer {
  def price(tokenId: String, amount: Long, pools: Map[String, ArbPool], maxLegs: Int,
            minProceeds: Long): Option[(ZcAssetLiquidation, Map[String, ArbPool])]
}

object ZcAssetPricer {

  /**
   * Composes pricers in priority order: the first pricer returning `Some` owns that token. Future pricers for bond
   * tokens, share tokens and wrapped LD-NFTs are prepended ahead of [[AmmAssetPricer]].
   */
  def chain(pricers: Seq[ZcAssetPricer]): ZcAssetPricer = new ZcAssetPricer {
    override def price(tokenId: String, amount: Long, pools: Map[String, ArbPool], maxLegs: Int,
                       minProceeds: Long): Option[(ZcAssetLiquidation, Map[String, ArbPool])] =
      pricers.iterator.flatMap(_.price(tokenId, amount, pools, maxLegs, minProceeds)).take(1).toSeq.headOption
  }

  /** Shipped default: the bank where it pays more for a SigmaUSD coin, AMM pools otherwise; other tokens have 0 value. */
  val Default: ZcAssetPricer = chain(Seq(BankAssetPricer, AmmAssetPricer))
}

/**
 * Pre-indexed view of live AMM and SigmaUSD bank pools by asset, built once per pricing pass so token and basket
 * evaluations avoid scanning or grouping the entire pool map on every asset.
 */
final case class ZcPoolIndex(pools: Map[String, ArbPool],
                             directByAsset: Map[ArbAsset, Vector[ArbPool]],
                             t2tByAsset: Map[ArbAsset, Vector[ArbPool]],
                             bankByAsset: Map[ArbAsset, Vector[ArbPool]]) {

  /** Whether `tokenId` has at least one direct ERG/bank route or a 2-hop T2T -> ERG route in this index. */
  def hasRoute(tokenId: String): Boolean = {
    val asset = ArbAsset.token(tokenId)
    directByAsset.contains(asset) || bankByAsset.contains(asset) ||
      t2tByAsset.getOrElse(asset, Vector.empty).exists { p =>
        val other = if (p.x == asset) p.y else p.x
        directByAsset.contains(other)
      }
  }

  /** Returns a copy with `after` (and any states sharing its box) replacing their prior pool entries. */
  def moved(after: ArbPool): ZcPoolIndex = {
    val changed = after +: after.sharing
    val nextPools = pools ++ changed.map(p => p.nft -> p)
    var nextDirect = directByAsset
    var nextT2t = t2tByAsset
    var nextBank = bankByAsset
    def replace(m: Map[ArbAsset, Vector[ArbPool]], key: ArbAsset, p: ArbPool): Map[ArbAsset, Vector[ArbPool]] =
      m.get(key) match {
        case Some(vec) =>
          val i = vec.indexWhere(_.nft == p.nft)
          m.updated(key, if (i >= 0) vec.updated(i, p) else vec :+ p)
        case None => m.updated(key, Vector(p))
      }
    changed.foreach { p =>
      if (p.kind == ArbPoolKind.SigmaUsdBank) nextBank = replace(nextBank, p.y, p)
      else if (p.x.isErg) nextDirect = replace(nextDirect, p.y, p)
      else {
        nextT2t = replace(nextT2t, p.x, p)
        nextT2t = replace(nextT2t, p.y, p)
      }
    }
    ZcPoolIndex(nextPools, nextDirect, nextT2t, nextBank)
  }

  /** Prices `amount` of `tokenId` through AMM pools (1-hop or 2-hop), updating only moved pools. */
  def priceAmm(tokenId: String, amount: Long, maxLegs: Int, minProceeds: Long): Option[(ZcAssetLiquidation, ZcPoolIndex)] =
    if (amount <= 0L || maxLegs < 1) None
    else {
      val asset = ArbAsset.token(tokenId)
      var best: Option[(Vector[ArbSwap], Long)] = None
      def consider(swaps: Vector[ArbSwap]): Unit = {
        val out = swaps.last.amountOut
        if (out >= minProceeds) {
          val beats = best.forall { case (prev, prevOut) =>
            out > prevOut || (out == prevOut && (swaps.size < prev.size ||
              (swaps.size == prev.size && swaps.head.pool.nft < prev.head.pool.nft)))
          }
          if (beats) best = Some(swaps -> out)
        }
      }

      // 1-hop: direct Token -> ERG pools
      directByAsset.getOrElse(asset, Vector.empty).iterator.take(AmmAssetPricer.MaxPoolsPerAsset)
        .foreach(p => p.swap(amount, xIn = false).foreach(s => consider(Vector(s))))

      // 2-hop: Token -> Mid (T2T pool) -> ERG (direct ERG pool of Mid)
      if (maxLegs >= 2) {
        t2tByAsset.getOrElse(asset, Vector.empty).iterator.take(AmmAssetPricer.MaxPoolsPerAsset).foreach { p1 =>
          val xIn = p1.x == asset
          p1.swap(amount, xIn).foreach { s1 =>
            val mid = s1.assetOut
            directByAsset.getOrElse(mid, Vector.empty).iterator.filter(_.nft != p1.nft)
              .take(AmmAssetPricer.MaxPoolsPerAsset).foreach { p2 =>
                p2.swap(s1.amountOut, xIn = false).foreach(s2 => consider(Vector(s1, s2)))
              }
          }
        }
      }

      best.map { case (swaps, _) =>
        val updated = swaps.foldLeft(this)((idx, s) => idx.moved(s.after))
        ZcAmmLiquidation(tokenId, amount, swaps) -> updated
      }
    }

  /** Prices `amount` of `tokenId` using the shipped Default policy (bank when it beats or ties AMM, else AMM). */
  def priceDefault(tokenId: String, amount: Long, maxLegs: Int, minProceeds: Long): Option[(ZcAssetLiquidation, ZcPoolIndex)] =
    if (amount <= 0L || maxLegs < 1) None
    else {
      val asset = ArbAsset.token(tokenId)
      val amm = priceAmm(tokenId, amount, maxLegs, minProceeds)
      val redeem = bankByAsset.getOrElse(asset, Vector.empty).iterator
        .flatMap(_.swap(amount, xIn = false)).filter(_.amountOut >= minProceeds)
        .foldLeft(Option.empty[ArbSwap])((best, s) => if (best.forall(_.amountOut < s.amountOut)) Some(s) else best)
      redeem.filter(s => amm.forall(_._1.ergOut <= s.amountOut))
        .map(s => BankAssetLiquidation(tokenId, amount, s) -> moved(s.after))
        .orElse(amm)
    }

  /** Cheapest ERG -> `tokenId` exact-out swap (direct ERG pools or bank mint). */
  def acquireToken(tokenId: String, amountOut: Long): Option[(ArbSwap, ZcPoolIndex)] =
    if (amountOut <= 0L) None
    else {
      val target = ArbAsset.token(tokenId)
      val candidates = directByAsset.getOrElse(target, Vector.empty).iterator ++
        bankByAsset.getOrElse(target, Vector.empty).iterator
      candidates.flatMap(_.swapFor(amountOut, xIn = true))
        .foldLeft(Option.empty[ArbSwap]) { (best, s) =>
          if (best.forall(b => s.amountIn < b.amountIn || (s.amountIn == b.amountIn && s.pool.nft < b.pool.nft)))
            Some(s)
          else best
        }.map(s => s -> moved(s.after))
    }
}

object ZcPoolIndex {
  def of(pools: Map[String, ArbPool]): ZcPoolIndex = {
    val direct = scala.collection.mutable.HashMap.empty[ArbAsset, scala.collection.mutable.ArrayBuffer[ArbPool]]
    val t2t = scala.collection.mutable.HashMap.empty[ArbAsset, scala.collection.mutable.ArrayBuffer[ArbPool]]
    val bank = scala.collection.mutable.HashMap.empty[ArbAsset, scala.collection.mutable.ArrayBuffer[ArbPool]]
    def add(m: scala.collection.mutable.HashMap[ArbAsset, scala.collection.mutable.ArrayBuffer[ArbPool]],
            k: ArbAsset, p: ArbPool): Unit =
      m.getOrElseUpdate(k, scala.collection.mutable.ArrayBuffer.empty) += p
    pools.valuesIterator.foreach { p =>
      if (p.kind == ArbPoolKind.SigmaUsdBank) add(bank, p.y, p)
      else if (p.x.isErg) add(direct, p.y, p)
      else { add(t2t, p.x, p); add(t2t, p.y, p) }
    }
    ZcPoolIndex(pools, direct.map { case (k, v) => k -> v.toVector }.toMap,
      t2t.map { case (k, v) => k -> v.toVector }.toMap,
      bank.map { case (k, v) => k -> v.toVector }.toMap)
  }
}

/**
 * Prices normal fungible tokens that trade in ErgoDEX or LithosDex AMM pools (1-hop `Token -> ERG`, or 2-hop
 * `Token -> Mid -> ERG` when `maxLegs >= 2`). Uses each pool's exact integer `swap` math; returns `None` for any
 * token without a liquid route yielding at least `minProceeds`. The SigmaUSD bank is [[BankAssetPricer]]'s, not this.
 */
object AmmAssetPricer extends ZcAssetPricer {

  /** Most candidate pools inspected per hop, keeping valuation bounded even on dense pool graphs. */
  final val MaxPoolsPerAsset = 32

  override def price(tokenId: String, amount: Long, all: Map[String, ArbPool], maxLegs: Int,
                     minProceeds: Long): Option[(ZcAssetLiquidation, Map[String, ArbPool])] =
    ZcPoolIndex.of(all).priceAmm(tokenId, amount, maxLegs, minProceeds).map { case (liq, idx) => liq -> idx.pools }
}

/**
 * Prices a SigmaUSD coin at what the bank's redeem pays for the whole amount, and owns it only when that is at least
 * what [[AmmAssetPricer]] gets from the pools. An amount the bank cannot redeem (the ratio, the coins in
 * circulation), or one the pools pay more for, is left to the pools.
 */
object BankAssetPricer extends ZcAssetPricer {

  override def price(tokenId: String, amount: Long, pools: Map[String, ArbPool], maxLegs: Int,
                     minProceeds: Long): Option[(ZcAssetLiquidation, Map[String, ArbPool])] =
    if (amount <= 0L || maxLegs < 1) None
    else {
      val idx = ZcPoolIndex.of(pools)
      val asset = ArbAsset.token(tokenId)
      val redeem = idx.bankByAsset.getOrElse(asset, Vector.empty).iterator
        .flatMap(_.swap(amount, xIn = false)).filter(_.amountOut >= minProceeds)
        .foldLeft(Option.empty[ArbSwap])((best, s) => if (best.forall(_.amountOut < s.amountOut)) Some(s) else best)
      redeem.filter { s =>
        idx.priceAmm(tokenId, amount, maxLegs, minProceeds).forall(_._1.ergOut <= s.amountOut)
      }.map(s => BankAssetLiquidation(tokenId, amount, s) -> ArbPool.moved(pools, s.after))
    }
}

/**
 * Exact valuation of a collateral basket `(ergInBasket, tokens)` into ERG.
 *
 * @param ergInBasket  ERG directly contained in the acquired lot/vault (0 for ERG-denominated auctions)
 * @param liquidations tokens with confirmed AMM/asset liquidation plans, in execution order
 * @param unpriced     tokens treated as 0 value (no pricer, dust below threshold, or past leg cap), swept to wallet
 * @param totalErgOut  guaranteed ERG recovered (`ergInBasket + liquidations.map(_.ergOut).sum`)
 * @param sweepErgCost ERG required for the wallet sweep box holding `unpriced` (`MinBox` if non-empty, else 0)
 * @param poolsAfter   pool states after every swap in `liquidations`
 */
final case class ZcBasketValuation(ergInBasket: Long, liquidations: Vector[ZcAssetLiquidation],
                                   unpriced: Vector[NodeAsset], totalErgOut: Long, sweepErgCost: Long,
                                   poolsAfter: Map[String, ArbPool]) {
  def liquidationLegs: Int = liquidations.map(_.legs).sum
}

/**
 * Evaluates multi-token collateral baskets sequentially against live pool states, bounding work against spam
 * baskets containing dozens of dust tokens.
 */
object ZcCollateralPricing {

  /** Most distinct tokens priced per basket; any further tokens are treated as 0 value and swept. */
  final val MaxBasketTokens = 16

  /** Most AMM/liquidation swap legs per basket, keeping one arb bundle well within block slot and size budgets. */
  final val MaxLiquidationLegs = 6

  /**
   * Finds the cheapest ERG -> `tokenId` exact-out swap for `amountOut` raw units (used to acquire SigUSD to pay a
   * SigUSD-denominated auction), returning the swap and updated pool states.
   */
  def acquireToken(tokenId: String, amountOut: Long, pools: Map[String, ArbPool]): Option[(ArbSwap, Map[String, ArbPool])] =
    if (amountOut <= 0L) None
    else {
      val target = ArbAsset.token(tokenId)
      pools.valuesIterator.filter(p => p.x.isErg && p.y == target)
        .flatMap(_.swapFor(amountOut, xIn = true))
        .foldLeft(Option.empty[ArbSwap]) { (best, s) =>
          if (best.forall(b => s.amountIn < b.amountIn || (s.amountIn == b.amountIn && s.pool.nft < b.pool.nft)))
            Some(s)
          else best
        }.map(s => s -> ArbPool.moved(pools, s.after))
    }

  /**
   * Evaluates a basket of `ergInBasket` + `tokens` against `pools` using `pricer`. Duplicate token ids are merged
   * first; with the default AMM/bank pricer, tokens with no pool route are separated out before [[MaxBasketTokens]]
   * so untraded dust tokens cannot crowd out traded collateral. Tokens are ordered by single-token preview proceeds
   * descending so high-value collateral claims the leg budget ahead of dust.
   */
  def evaluateBasket(ergInBasket: Long, tokens: Seq[NodeAsset], pools: Map[String, ArbPool],
                     pricer: ZcAssetPricer = ZcAssetPricer.Default, maxTotalLegs: Int = MaxLiquidationLegs,
                     minLegProceeds: Long = 100000L, poolIndex: Option[ZcPoolIndex] = None): ZcBasketValuation = {
    val merged: Vector[NodeAsset] = tokens.foldLeft(Vector.empty[NodeAsset]) { (acc, a) =>
      if (a.amount <= 0L) acc
      else {
        val idx = acc.indexWhere(_.tokenId == a.tokenId)
        if (idx < 0) acc :+ a else acc.updated(idx, a.copy(amount = Math.addExact(acc(idx).amount, a.amount)))
      }
    }
    val useDefault = pricer eq ZcAssetPricer.Default
    val initialIndex = if (useDefault) Some(poolIndex.getOrElse(ZcPoolIndex.of(pools))) else None
    val (candidates, untraded) = initialIndex match {
      case Some(idx) => merged.partition(a => idx.hasRoute(a.tokenId))
      case None => (merged, Vector.empty[NodeAsset])
    }
    val (inspected, overflow) = candidates.splitAt(MaxBasketTokens)
    // Preview each token independently to rank highest proceeds first, then price sequentially against live pools.
    val ranked = inspected.map { asset =>
      val est = initialIndex match {
        case Some(idx) => idx.priceDefault(asset.tokenId, asset.amount, math.min(2, maxTotalLegs), minLegProceeds)
          .fold(0L)(_._1.ergOut)
        case None => pricer.price(asset.tokenId, asset.amount, pools, math.min(2, maxTotalLegs), minLegProceeds)
          .fold(0L)(_._1.ergOut)
      }
      asset -> est
    }.sortBy { case (a, est) => (-est, a.tokenId) }

    var curPools = pools
    var curIndex = initialIndex
    var legsLeft = maxTotalLegs
    val liquidations = Vector.newBuilder[ZcAssetLiquidation]
    val unpriced = Vector.newBuilder[NodeAsset]
    var recovered = ergInBasket

    ranked.foreach { case (asset, est) =>
      if (est <= 0L || legsLeft <= 0) unpriced += asset
      else curIndex match {
        case Some(idx) =>
          idx.priceDefault(asset.tokenId, asset.amount, math.min(2, legsLeft), minLegProceeds) match {
            case Some((plan, nextIdx)) if plan.legs <= legsLeft =>
              liquidations += plan
              curIndex = Some(nextIdx)
              curPools = nextIdx.pools
              legsLeft -= plan.legs
              recovered = Math.addExact(recovered, plan.ergOut)
            case _ =>
              unpriced += asset
          }
        case None =>
          pricer.price(asset.tokenId, asset.amount, curPools, math.min(2, legsLeft), minLegProceeds) match {
            case Some((plan, nextPools)) if plan.legs <= legsLeft =>
              liquidations += plan
              curPools = nextPools
              legsLeft -= plan.legs
              recovered = Math.addExact(recovered, plan.ergOut)
            case _ =>
              unpriced += asset
          }
      }
    }
    unpriced ++= overflow
    unpriced ++= untraded

    val unpricedVec = unpriced.result()
    val sweepCost = if (unpricedVec.isEmpty) 0L else ZcBoxes.MinBox
    ZcBasketValuation(ergInBasket, liquidations.result(), unpricedVec, recovered, sweepCost, curPools)
  }
}
