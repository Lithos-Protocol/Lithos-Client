package transactions.batching.sigmafi.zc

import node.model.NodeAsset
import transactions.batching.arb.{ArbAsset, ArbPool, ArbSwap}

/**
 * How one collateral asset in a ZC basket is converted into ERG.
 *
 * Currently only AMM swaps (`ZcAmmLiquidation`) are implemented for normal fungible tokens living in ErgoDEX or
 * LithosDex pools. Future support for bond units, shares, and wrapped LithosDex position NFTs plugs in by adding
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
}

/** Liquidates `amount` raw units of a fungible token into ERG via 1 or 2 AMM swaps. */
final case class ZcAmmLiquidation(tokenId: String, amount: Long, swaps: Vector[ArbSwap]) extends ZcAssetLiquidation {
  require(swaps.nonEmpty && swaps.last.assetOut.isErg, "an AMM liquidation must end in ERG")
  override def ergOut: Long = swaps.last.amountOut
  override def legs: Int = swaps.size
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

  /** Shipped default: normal fungible tokens traded in AMMs only; all other tokens have 0 value. */
  val Default: ZcAssetPricer = AmmAssetPricer
}

/**
 * Prices normal fungible tokens that trade in ErgoDEX or LithosDex AMM pools (1-hop `Token -> ERG`, or 2-hop
 * `Token -> Mid -> ERG` when `maxLegs >= 2`). Uses each pool's exact integer `swap` math; returns `None` for any
 * token without a liquid route yielding at least `minProceeds`.
 */
object AmmAssetPricer extends ZcAssetPricer {

  /** Most candidate pools inspected per hop, keeping valuation bounded even on dense pool graphs. */
  final val MaxPoolsPerAsset = 32

  override def price(tokenId: String, amount: Long, pools: Map[String, ArbPool], maxLegs: Int,
                     minProceeds: Long): Option[(ZcAssetLiquidation, Map[String, ArbPool])] =
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
      val direct = pools.valuesIterator.filter(p => p.x.isErg && p.y == asset).take(MaxPoolsPerAsset)
      direct.foreach(p => p.swap(amount, xIn = false).foreach(s => consider(Vector(s))))

      // 2-hop: Token -> Mid (T2T pool) -> ERG (direct ERG pool of Mid)
      if (maxLegs >= 2) {
        val ergByToken = pools.valuesIterator.filter(_.x.isErg).toVector.groupBy(_.y)
        val t2t = pools.valuesIterator.filter(p => !p.x.isErg && p.trades(asset)).take(MaxPoolsPerAsset)
        t2t.foreach { p1 =>
          val xIn = p1.x == asset
          p1.swap(amount, xIn).foreach { s1 =>
            val mid = s1.assetOut
            ergByToken.getOrElse(mid, Vector.empty).iterator.filter(_.nft != p1.nft).take(MaxPoolsPerAsset).foreach { p2 =>
              p2.swap(s1.amountOut, xIn = false).foreach(s2 => consider(Vector(s1, s2)))
            }
          }
        }
      }

      best.map { case (swaps, _) =>
        val updated = swaps.foldLeft(pools)((ps, s) => ps.updated(s.pool.nft, s.after))
        ZcAmmLiquidation(tokenId, amount, swaps) -> updated
      }
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
        }.map(s => s -> pools.updated(s.pool.nft, s.after))
    }

  /**
   * Evaluates a basket of `ergInBasket` + `tokens` against `pools` using `pricer`. Duplicate token ids are merged
   * first; tokens are ordered by single-token preview proceeds descending so high-value collateral claims the leg
   * budget ahead of dust. Each chosen liquidation updates `pools` before the next token is priced.
   */
  def evaluateBasket(ergInBasket: Long, tokens: Seq[NodeAsset], pools: Map[String, ArbPool],
                     pricer: ZcAssetPricer = ZcAssetPricer.Default, maxTotalLegs: Int = MaxLiquidationLegs,
                     minLegProceeds: Long = 100000L): ZcBasketValuation = {
    val merged: Vector[NodeAsset] = tokens.foldLeft(Vector.empty[NodeAsset]) { (acc, a) =>
      if (a.amount <= 0L) acc
      else {
        val idx = acc.indexWhere(_.tokenId == a.tokenId)
        if (idx < 0) acc :+ a else acc.updated(idx, a.copy(amount = Math.addExact(acc(idx).amount, a.amount)))
      }
    }
    val (inspected, overflow) = merged.splitAt(MaxBasketTokens)
    // Preview each token independently to rank highest proceeds first, then price sequentially against live pools.
    val ranked = inspected.map { asset =>
      val est = pricer.price(asset.tokenId, asset.amount, pools, math.min(2, maxTotalLegs), minLegProceeds)
        .fold(0L)(_._1.ergOut)
      asset -> est
    }.sortBy { case (a, est) => (-est, a.tokenId) }

    var curPools = pools
    var legsLeft = maxTotalLegs
    val liquidations = Vector.newBuilder[ZcAssetLiquidation]
    val unpriced = Vector.newBuilder[NodeAsset]
    var recovered = ergInBasket

    ranked.foreach { case (asset, est) =>
      if (est <= 0L || legsLeft <= 0) unpriced += asset
      else pricer.price(asset.tokenId, asset.amount, curPools, math.min(2, legsLeft), minLegProceeds) match {
        case Some((plan, nextPools)) if plan.legs <= legsLeft =>
          liquidations += plan
          curPools = nextPools
          legsLeft -= plan.legs
          recovered = Math.addExact(recovered, plan.ergOut)
        case _ =>
          unpriced += asset
      }
    }
    unpriced ++= overflow

    val unpricedVec = unpriced.result()
    val sweepCost = if (unpricedVec.isEmpty) 0L else ZcBoxes.MinBox
    ZcBasketValuation(ergInBasket, liquidations.result(), unpricedVec, recovered, sweepCost, curPools)
  }
}
