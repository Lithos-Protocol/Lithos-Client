package transactions.batching.sigmafi.zc

import mutations.NodeWallet
import node.model.{NodeAsset, NodeBox}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.scalaapi.scalaByteType
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue, SignedTransaction}
import org.ergoplatform.sdk.ErgoId
import org.slf4j.LoggerFactory
import sigma.Colls
import sigma.ast.ErgoTree
import sigmafi.zc.{ZcContracts, ZcMarket}
import transactions.batching.Batcher
import transactions.batching.arb._
import transactions.batching.sigmafi.SigmaFiBoxes
import transactions.candidate.BlockTxMessages.{CandidateTx, ChainFromPackage}
import transactions.candidate.{CandidateBundle, CapitalEntry, CapitalOrigin}
import work.lithos.mutations.{Contract, InputUTXO, Token, TxBuilder, UTXO}

import scala.concurrent.duration.Deadline
import scala.util.{Failure, Success, Try}

/** What ZC box a collateral arb bundle purchases, and its contract-exact purchase terms. */
sealed trait ZcArbTarget {
  def boxId: String
  def box: NodeBox
  def market: ZcMarket
  def priceInMarket: Long
}

final case class ZcLotTarget(lot: ZcLot, seller: Contract, priceInMarket: Long) extends ZcArbTarget {
  def boxId: String = lot.boxId
  def box: NodeBox = lot.box
  def market: ZcMarket = lot.market
}

final case class ZcVaultTarget(vault: ZcShareVault, market: ZcMarket, priceInMarket: Long,
                               afterCurrency: Long) extends ZcArbTarget {
  def boxId: String = vault.boxId
  def box: NodeBox = vault.box
}

final case class ZcSaleOrderTarget(ask: ZcAsk, seller: Contract, units: Long, priceInMarket: Long) extends ZcArbTarget {
  def boxId: String = ask.boxId
  def box: NodeBox = ask.box
  def market: ZcMarket = ask.market
  def full: Boolean = units >= ask.token.amount
}

/**
 * A priced, confirmed-profit ZC collateral arbitrage plan:
 * - optional `acquireSwap` (`ERG -> SigUSD`) when the ZC target is paid in SigUSD
 * - the ZC purchase itself (`target`)
 * - `valuation`: exact AMM liquidation plan(s) for priced tokens + any unpriced tokens swept to the wallet
 * - `netProfit`: strictly guaranteed ERG profit after purchase cost and any unpriced-token sweep minimum
 */
final case class ZcArbPlan(target: ZcArbTarget, acquireSwap: Option[ArbSwap], valuation: ZcBasketValuation,
                           ergCost: Long, netProfit: Long, poolsAfter: Map[String, ArbPool]) {
  def legs: Int = (if (acquireSwap.isDefined) 1 else 0) + 1 + valuation.liquidationLegs
  def allSwaps: Vector[ArbSwap] = acquireSwap.toVector ++ valuation.liquidations.collect {
    case a: ZcAmmLiquidation => a.swaps
  }.flatten
}

/**
 * Evaluates and atomically executes arbitrage across SigmaFi ZC collateral contracts on this miner's own block
 * (never broadcast):
 * - `LotAuction` (`ZcLot`, ERG and SigUSD markets)
 * - `DutchAuctionVault` (`ZcShareVault`, state 1 floor and state 4 excess, ERG and SigUSD markets)
 * - `SaleOrder` (`ZcAsk`, non-priced fixed-price asks selling AMM-priced tokens, ERG and SigUSD markets)
 *
 * Every bundle starts from the `ArbStake` box, returns the exact stake value with zero tokens to `stake.contract`,
 * sweeps any unpriced (0-value) tokens to the miner's wallet (deducting `MinBox` from profit up front), and emits
 * `profit >= minProfit` to `profitTo` — re-verified on the actual signed outputs before offering.
 */
object ZcCollateralArb {

  private val logger = LoggerFactory.getLogger("ZcCollateralArb")

  /** Most ZC auction/ask boxes evaluated per block build, protecting the candidate path against spam floods. */
  final val MaxEvaluatedTargets = 200
  /** Most resolver reads per arb build when looking up seller scripts not yet in the tracker. */
  final val MaxResolverReads = 32
  /** Most transactions in one ZC arb bundle (acquire + purchase + up to 6 liquidation legs). */
  final val MaxLegsPerBundle = 8

  private final val MinBox: Long = ZcBoxes.MinBox

  private def bytes(hex: String): ErgoValue[_] = ErgoValue.of(Colls.fromArray(Hex.decode(hex)), scalaByteType)
  private def int(i: Int): ErgoValue[_] = ErgoValue.of(i)
  private def byte(b: Byte): ErgoValue[_] = ErgoValue.of(b)
  private def long(l: Long): ErgoValue[_] = ErgoValue.of(l)
  private def token(id: String, n: Long): Token = Token(ErgoId.create(id), n)

  private def contractOf(treeHex: String): Option[Contract] = Try {
    sigma.VersionContext.withVersions(sigma.VersionContext.V6SoftForkVersion, sigma.VersionContext.V6SoftForkVersion) {
      Contract(ErgoTree.fromHex(treeHex))
    }
  }.toOption

  /**
   * Finds the single most profitable ZC collateral arb opportunity on `book` (excluding `spentBoxes`) given current
   * `pools`, `maxErgIn`, `minProfit` and remaining `slots`. Pure apart from bounded script cache reads.
   */
  def bestPlan(book: ZcBook, createdInBasis: Seq[(String, ZcRecord)], pools: Map[String, ArbPool],
               resolveScript: ZcRecord => Option[Contract], blockHeight: Int, maxErgIn: Long, minProfit: Long,
               slots: Int, minLegProceeds: Long, spentBoxes: Set[String],
               pricer: ZcAssetPricer = ZcAssetPricer.Default): Option[(ZcArbPlan, Option[String])] = {
    if (slots < 1 || maxErgIn < MinBox || minProfit < ArbStage.MinProfitOutput) None
    else {
      val h = blockHeight.toLong
      // O(|pools|) upper-bound ERG spot map for the shipped AMM pricer: filters unpriced spam in O(1) and ranks
      // high-value collateral ahead of dust spam before applying MaxEvaluatedTargets.
      val spotErg: Option[Map[String, Double]] =
        if (pricer eq ZcAssetPricer.Default) {
          val direct = scala.collection.mutable.HashMap.empty[String, Double]
          pools.valuesIterator.filter(_.x.isErg).foreach { p =>
            p.y.tokenId.foreach { id =>
              val r = p.marginal(xIn = false)
              if (r > direct.getOrElse(id, 0.0)) direct.update(id, r)
            }
          }
          val combined = scala.collection.mutable.HashMap.empty[String, Double] ++= direct
          pools.valuesIterator.filter(!_.x.isErg).foreach { p =>
            for (xId <- p.x.tokenId; yId <- p.y.tokenId) {
              direct.get(yId).foreach { mid =>
                val r = p.marginal(xIn = true) * mid
                if (r > combined.getOrElse(xId, 0.0)) combined.update(xId, r)
              }
              direct.get(xId).foreach { mid =>
                val r = p.marginal(xIn = false) * mid
                if (r > combined.getOrElse(yId, 0.0)) combined.update(yId, r)
              }
            }
          }
          Some(combined.toMap)
        } else None

      def scoreCandidate(r: ZcRecord): Option[Double] = r match {
        case l: ZcLot if ZcRules.lotValid(l) && h < l.expiry =>
          spotErg match {
            case Some(spot) =>
              val ergVal = if (l.market.tokenId.isDefined) math.max(0L, l.box.value - MinBox).toDouble else 0.0
              var tokVal = 0.0
              var hasPriced = false
              l.box.assets.foreach { t =>
                spot.get(t.tokenId.toLowerCase).foreach { rate =>
                  if (t.amount > 0L && rate > 0.0) { hasPriced = true; tokVal += t.amount.toDouble * rate }
                }
              }
              if (hasPriced || ergVal > 0.0) Some(ergVal + tokVal) else None
            case None => Some(l.box.value.toDouble)
          }
        case v: ZcShareVault if ZcRules.auctionOpen(v) =>
          spotErg match {
            case Some(spot) =>
              val cur = ZcRules.vaultCurrency(v)
              val ergVal = if (cur.isDefined && !ZcRules.saleKeepsErg(v)) math.max(0L, v.box.value - MinBox).toDouble else 0.0
              var tokVal = 0.0
              var hasPriced = false
              v.box.assets.drop(1).foreach { t =>
                if (!cur.contains(t.tokenId)) {
                  spot.get(t.tokenId.toLowerCase).foreach { rate =>
                    if (t.amount > 0L && rate > 0.0) { hasPriced = true; tokVal += t.amount.toDouble * rate }
                  }
                }
              }
              if (hasPriced || ergVal > 0.0) Some(ergVal + tokVal) else None
            case None => Some(v.box.value.toDouble)
          }
        case a: ZcAsk if !a.priced && h < a.expiry && a.token.amount > 0L =>
          spotErg match {
            case Some(spot) =>
              spot.get(a.token.tokenId.toLowerCase).filter(_ > 0.0)
                .map(rate => a.box.value.toDouble + a.token.amount.toDouble * rate)
            case None => Some(a.box.value.toDouble)
          }
        case _ => None
      }

      val candidates = Vector.newBuilder[(ZcRecord, Option[String], Double)]
      book.boxes.valuesIterator.filterNot(e => spentBoxes.contains(e.boxId)).foreach { e =>
        scoreCandidate(e.record).foreach(s => candidates += ((e.record, None, s)))
      }
      createdInBasis.foreach { case (txId, r) =>
        if (!spentBoxes.contains(r.boxId)) scoreCandidate(r).foreach(s => candidates += ((r, Some(txId), s)))
      }

      // Rank highest estimated gross ERG first and evaluate at most MaxEvaluatedTargets boxes.
      val legCap = math.min(MaxLegsPerBundle, slots)
      var best: Option[(ZcArbPlan, Option[String])] = None
      def consider(plan: ZcArbPlan, parentTx: Option[String]): Unit =
        if (plan.netProfit >= minProfit && plan.legs <= legCap) {
          val beats = best.forall { case (prev, _) =>
            plan.netProfit > prev.netProfit || (plan.netProfit == prev.netProfit &&
              (plan.legs < prev.legs || (plan.legs == prev.legs && plan.target.boxId < prev.target.boxId)))
          }
          if (beats) best = Some(plan -> parentTx)
        }

      candidates.result().sortBy { case (rec, _, score) => (-score, rec.boxId) }
        .take(MaxEvaluatedTargets).foreach { case (rec, parentTx, _) =>
          val evaluated = rec match {
            case l: ZcLot => evaluateLot(l, pools, resolveScript, h, maxErgIn, legCap, minLegProceeds, pricer)
            case v: ZcShareVault => evaluateVault(v, pools, h, maxErgIn, legCap, minLegProceeds, pricer)
            case a: ZcAsk => evaluateSaleOrder(a, pools, resolveScript, h, maxErgIn, legCap, minLegProceeds, pricer)
            case _ => None
          }
          evaluated.foreach(consider(_, parentTx))
        }
      best
    }
  }

  /** Evaluates a `LotAuction` box at `height` (ERG or SigUSD market). */
  def evaluateLot(lot: ZcLot, pools: Map[String, ArbPool], resolveScript: ZcRecord => Option[Contract],
                  height: Long, maxErgIn: Long, maxLegs: Int, minLegProceeds: Long,
                  pricer: ZcAssetPricer): Option[ZcArbPlan] = {
    if (!ZcRules.lotValid(lot) || height >= lot.expiry) return None
    // Contract refuses a token-market lot that holds its own payment currency.
    if (lot.market.tokenId.exists(id => lot.box.assets.exists(_.tokenId == id))) return None
    val price = ZcRules.lotPrice(lot, height)
    if (price <= 0L) return None

    lot.market.tokenId match {
      case None =>
        if (price > maxErgIn || maxLegs < 2 || Try(Math.addExact(lot.box.value, price)).isFailure) None
        else {
          val valn = ZcCollateralPricing.evaluateBasket(0L, lot.box.assets, pools, pricer,
            math.min(ZcCollateralPricing.MaxLiquidationLegs, maxLegs - 1), minLegProceeds)
          val net = valn.totalErgOut - price - valn.sweepErgCost
          if (net <= 0L || Try(Math.addExact(price, valn.sweepErgCost)).getOrElse(Long.MaxValue) > maxErgIn) None
          else resolveScript(lot).map(seller =>
            ZcArbPlan(ZcLotTarget(lot, seller, price), None, valn, price, net, valn.poolsAfter))
        }

      case Some(currencyId) =>
        if (maxLegs < 2 || lot.box.value < MinBox) None
        else ZcCollateralPricing.acquireToken(currencyId, price, pools).flatMap { case (acq, afterAcq) =>
          if (acq.amountIn > maxErgIn) None
          else {
            val ergFromLot = lot.box.value - MinBox
            val valn = ZcCollateralPricing.evaluateBasket(ergFromLot, lot.box.assets, afterAcq, pricer,
              math.min(ZcCollateralPricing.MaxLiquidationLegs, maxLegs - 2), minLegProceeds)
            val afterPurchaseDraw = Try(Math.addExact(acq.amountIn, valn.sweepErgCost - ergFromLot)).getOrElse(Long.MaxValue)
            val peakErg = math.max(acq.amountIn, afterPurchaseDraw)
            val net = valn.totalErgOut - acq.amountIn - valn.sweepErgCost
            if (net <= 0L || peakErg > maxErgIn) None
            else resolveScript(lot).map(seller =>
              ZcArbPlan(ZcLotTarget(lot, seller, price), Some(acq), valn, acq.amountIn, net, valn.poolsAfter))
          }
        }
    }
  }

  /** Evaluates a `DutchAuctionVault` box with an open floor (state 1) or excess (state 4) auction at `height`. */
  def evaluateVault(v: ZcShareVault, pools: Map[String, ArbPool], height: Long, maxErgIn: Long, maxLegs: Int,
                    minLegProceeds: Long, pricer: ZcAssetPricer): Option[ZcArbPlan] = {
    if (!ZcRules.auctionOpen(v) || v.auction(3) < 1L) return None
    val currency = ZcRules.vaultCurrency(v)
    val market = ZcMarket.all.find(_.tokenId == currency).getOrElse(return None)
    val before = ZcRules.currencyHeld(v)
    val excess = ZcRules.excessAuction(v)
    val (after, price) = if (excess) {
      if (height >= v.auction(6) + v.auction(3)) return None
      val p = ZcRules.excessPrice(v, height).getOrElse(return None)
      if (p <= 0L) return None
      (Try(Math.addExact(before, p)).getOrElse(return None), p)
    } else {
      val target = ZcRules.lotTarget(v, height)
      val aft = math.max(target, before)
      (aft, aft - before)
    }
    if ((currency.isEmpty && after < MinBox) || (currency.isDefined && after <= 0L)) return None

    val threshold = ZcRules.threshold(v)
    val lotTokens = v.box.assets.drop(1).filter { t =>
      !currency.contains(t.tokenId) && (!excess || t.amount < threshold)
    }
    val keepsErg = ZcRules.saleKeepsErg(v)
    if (currency.isDefined && !keepsErg && v.box.value < MinBox) return None
    val lotErg = if (currency.isEmpty || keepsErg) 0L else v.box.value - MinBox
    if (lotTokens.isEmpty && lotErg <= 0L) return None

    currency match {
      case None =>
        if (price > maxErgIn || maxLegs < 1) None
        else {
          val valn = ZcCollateralPricing.evaluateBasket(0L, lotTokens, pools, pricer,
            math.min(ZcCollateralPricing.MaxLiquidationLegs, maxLegs - 1), minLegProceeds)
          val net = valn.totalErgOut - price - valn.sweepErgCost
          if (net <= 0L || Try(Math.addExact(price, valn.sweepErgCost)).getOrElse(Long.MaxValue) > maxErgIn) None
          else Some(ZcArbPlan(ZcVaultTarget(v, market, price, after), None, valn, price, net, valn.poolsAfter))
        }

      case Some(currencyId) =>
        val acqOpt = if (price == 0L) Some(None -> pools)
          else ZcCollateralPricing.acquireToken(currencyId, price, pools).map { case (s, p) => Some(s) -> p }
        acqOpt.flatMap { case (acq, afterAcq) =>
          val acqCost = acq.fold(0L)(_.amountIn)
          val baseLegs = 1 + (if (acq.isDefined) 1 else 0)
          if (acqCost > maxErgIn || maxLegs < baseLegs) None
          else {
            val valn = ZcCollateralPricing.evaluateBasket(lotErg, lotTokens, afterAcq, pricer,
              math.min(ZcCollateralPricing.MaxLiquidationLegs, maxLegs - baseLegs), minLegProceeds)
            // On saleKeepsErg (or MinBox-value) vaults, sweeping unpriced tokens draws MinBox from carry in addition to acqCost.
            val afterPurchaseDraw = Try(Math.addExact(acqCost, valn.sweepErgCost - lotErg)).getOrElse(Long.MaxValue)
            val peakErg = math.max(acqCost, afterPurchaseDraw)
            val net = valn.totalErgOut - acqCost - valn.sweepErgCost
            if (net <= 0L || peakErg > maxErgIn) None
            else Some(ZcArbPlan(ZcVaultTarget(v, market, price, after), acq, valn, acqCost, net, valn.poolsAfter))
          }
        }
    }
  }

  /**
   * Evaluates a non-priced `SaleOrder` selling an AMM-priced token. Checks both full fill (which also recovers
   * `ask.box.value` ERG) and optimal partial fill (ternary search over `[minFill, amount - 1]`).
   */
  def evaluateSaleOrder(ask: ZcAsk, pools: Map[String, ArbPool], resolveScript: ZcRecord => Option[Contract],
                        height: Long, maxErgIn: Long, maxLegs: Int, minLegProceeds: Long,
                        pricer: ZcAssetPricer): Option[ZcArbPlan] = {
    if (ask.priced || height >= ask.expiry || ask.token.amount < 1L || maxLegs < 2) return None
    val tokenId = ask.token.tokenId
    // Fast-path reject: unpriced tokens (bond units, shares, wraps for now) return None in O(1) with zero reads.
    val quick = pricer.price(tokenId, ask.token.amount, pools, math.min(2, maxLegs - 1), minLegProceeds)
    if (quick.isEmpty) return None

    def tryUnits(units: Long): Option[(Long, Option[ArbSwap], ZcBasketValuation, Long)] = {
      if (units < 1L || units > ask.token.amount) return None
      val full = units == ask.token.amount
      if (!full && units < ZcRules.minFill(ask)) return None
      val priceBig = ZcRules.fixedPrice(ask, units)
      if (priceBig <= 0 || !priceBig.isValidLong) return None
      val rawPrice = priceBig.toLong
      val recoveredAskErg = if (full) ask.box.value else 0L

      ask.market.tokenId match {
        case None =>
          val paidErg = math.max(rawPrice, MinBox)
          // Peak ERG needed before the sale completes: paidErg - recoveredAskErg
          val netErgOutFromCarry = paidErg - recoveredAskErg
          if (netErgOutFromCarry > maxErgIn || maxLegs < 2) None
          else {
            val valn = ZcCollateralPricing.evaluateBasket(0L, Seq(NodeAsset(tokenId, units)), pools, pricer,
              math.min(2, maxLegs - 1), minLegProceeds)
            if (valn.liquidations.isEmpty ||
              Try(Math.addExact(netErgOutFromCarry, valn.sweepErgCost)).getOrElse(Long.MaxValue) > maxErgIn) None
            else {
              val net = valn.totalErgOut - netErgOutFromCarry - valn.sweepErgCost
              if (net > 0L) Some((rawPrice, None, valn, net)) else None
            }
          }

        case Some(currencyId) =>
          if (maxLegs < 3) None
          else ZcCollateralPricing.acquireToken(currencyId, rawPrice, pools).flatMap { case (acq, afterAcq) =>
            if (acq.amountIn > maxErgIn) None
            else {
              val ergCost = acq.amountIn + MinBox - recoveredAskErg
              val valn = ZcCollateralPricing.evaluateBasket(0L, Seq(NodeAsset(tokenId, units)), afterAcq, pricer,
                math.min(2, maxLegs - 2), minLegProceeds)
              val afterPurchaseDraw = Try(Math.addExact(ergCost, valn.sweepErgCost)).getOrElse(Long.MaxValue)
              val peakErg = math.max(acq.amountIn, afterPurchaseDraw)
              if (valn.liquidations.isEmpty || peakErg > maxErgIn) None
              else {
                val net = valn.totalErgOut - ergCost - valn.sweepErgCost
                if (net > 0L) Some((rawPrice, Some(acq), valn, net)) else None
              }
            }
          }
      }
    }

    // Check full fill, plus ternary search over partial fills when allowed and amount > minFill
    var bestUnits: Option[(Long, Long, Option[ArbSwap], ZcBasketValuation, Long)] =
      tryUnits(ask.token.amount).map { case (p, acq, v, net) => (ask.token.amount, p, acq, v, net) }

    val minF = math.max(1L, ZcRules.minFill(ask))
    if (ask.token.amount > minF) {
      var lo = minF
      var hi = ask.token.amount - 1L
      def score(u: Long): Long = tryUnits(u).fold(Long.MinValue)(_._4)
      while (hi - lo > 2L) {
        val third = (hi - lo) / 3L
        val (m1, m2) = (lo + third, hi - third)
        if (score(m1) < score(m2)) lo = m1 + 1L else hi = m2
      }
      (lo to hi).foreach { u =>
        tryUnits(u).foreach { case (p, acq, v, net) =>
          if (bestUnits.forall(b => net > b._5)) bestUnits = Some((u, p, acq, v, net))
        }
      }
    }

    bestUnits.flatMap { case (units, rawPrice, acq, valn, net) =>
      val ergCost = valn.totalErgOut - net - valn.sweepErgCost
      resolveScript(ask).map(seller =>
        ZcArbPlan(ZcSaleOrderTarget(ask, seller, units, rawPrice), acq, valn, ergCost, net, valn.poolsAfter))
    }
  }

  /**
   * Signs the entire atomic transaction chain for `plan` starting from `stake`:
   * 1. Optional SigUSD acquisition swap (`ERG -> SigUSD`)
   * 2. ZC purchase (`LotAuction`, `DutchAuctionVault` or `SaleOrder`), sweeping any unpriced tokens to `wallet.contract`
   * 3. Each AMM liquidation swap (`Token -> ERG`, or 2-hop `Token -> Mid -> ERG`), the final leg returning the exact
   *    `stake` box (zero tokens, `stake.value` ERG) and emitting `profit >= minProfit` to `profitTo`.
   *
   * Strictly verifies the final signed outputs (`stake` intact and `profit >= minProfit`) before returning.
   */
  def sign(ctx: BlockchainContext, wallet: NodeWallet, plan: ZcArbPlan,
           poolBoxOf: String => InputUTXO, stake: InputUTXO, blockHeight: Int,
           profitTo: Contract, minProfit: Long): Try[(Vector[SignedTransaction], Vector[(String, InputUTXO, String)], InputUTXO, InputUTXO)] = Try {
    var carry = stake
    val legs = Vector.newBuilder[SignedTransaction]
    val movedPools = Vector.newBuilder[(String, InputUTXO, String)]
    var localPools = Map.empty[String, InputUTXO]
    def curPoolBox(nft: String): InputUTXO = localPools.getOrElse(nft, poolBoxOf(nft))
    val allSwaps = plan.valuation.liquidations.collect { case a: ZcAmmLiquidation => a.swaps }.flatten

    // 1. Optional SigUSD acquisition leg
    plan.acquireSwap.foreach { planned =>
      val poolBox = curPoolBox(planned.pool.nft)
      val swap = planned.pool.withBox(poolBox.id.toString).swap(planned.amountIn, planned.xIn)
        .filter(_.amountOut == planned.amountOut)
        .getOrElse(throw new IllegalStateException(s"pool ${planned.pool.nft} no longer supplies SigUSD as priced"))
      val tx = ArbLeg.build(ctx, wallet, swap, poolBox, Seq(carry), stake.contract, blockHeight)
      val id = CandidateTx.txId(tx)
      val succ = InputUTXO(tx.getOutputsToSpend.get(0))
      localPools += planned.pool.nft -> succ
      movedPools += ((planned.pool.nft, succ, id))
      carry = InputUTXO(tx.getOutputsToSpend.get(1))
      legs += tx
    }

    // 2. ZC purchase leg (if no liquidation swaps follow, this leg ALSO splits off the profit!)
    val isFinalOnPurchase = allSwaps.isEmpty
    val zcInput = SigmaFiBoxes.input(plan.target.box, ctx)
      .filter(_.id.toString == plan.target.boxId)
      .getOrElse(throw new IllegalStateException(s"ZC target box ${plan.target.boxId} does not read back"))
    val (purchaseTx, nextCarry, directProfit) = buildPurchaseLeg(ctx, wallet, plan, zcInput, carry, stake,
      blockHeight, profitTo, isFinalOnPurchase)
    legs += purchaseTx
    carry = nextCarry

    // 3. AMM liquidation legs (selling each priced token into ERG)
    var profitBox = directProfit
    allSwaps.zipWithIndex.foreach { case (planned, i) =>
      val last = i == allSwaps.size - 1
      val poolBox = curPoolBox(planned.pool.nft)
      val swap = planned.pool.withBox(poolBox.id.toString).swap(planned.amountIn, planned.xIn)
        .filter(_.amountOut == planned.amountOut)
        .getOrElse(throw new IllegalStateException(s"pool ${planned.pool.nft} no longer liquidates token as priced"))
      val tx = ArbLeg.build(ctx, wallet, swap, poolBox, Seq(carry), stake.contract, blockHeight,
        profit = if (last) Some(profitTo -> plan.netProfit) else None)
      val id = CandidateTx.txId(tx)
      val succ = InputUTXO(tx.getOutputsToSpend.get(0))
      localPools += planned.pool.nft -> succ
      movedPools += ((planned.pool.nft, succ, id))
      carry = InputUTXO(tx.getOutputsToSpend.get(1))
      if (last) profitBox = Some(InputUTXO(tx.getOutputsToSpend.get(2)))
      legs += tx
    }

    // 4. Hard post-signing profit & stake-preservation invariant (NEVER bypassed!)
    val profit = profitBox.getOrElse(throw new IllegalStateException("ZC arb produced no profit output"))
    require(carry.value == stake.value && carry.tokens.isEmpty &&
      carry.contract.ergoTreeHex == stake.contract.ergoTreeHex,
      s"ZC arb returned ${carry.value} nanoERG and ${carry.tokens.size} token(s) where stake was ${stake.value}")
    require(profit.value >= minProfit && profit.tokens.isEmpty &&
      profit.contract.ergoTreeHex == profitTo.ergoTreeHex,
      s"ZC arb profit ${profit.value} is below floor $minProfit")

    (legs.result(), movedPools.result(), carry, profit)
  }

  /** Builds and signs the ZC purchase transaction spending `[zcInput, carry]`. */
  private def buildPurchaseLeg(ctx: BlockchainContext, wallet: NodeWallet, plan: ZcArbPlan, zcInput: InputUTXO,
                               carry: InputUTXO, stake: InputUTXO, blockHeight: Int, profitTo: Contract,
                               emitProfitNow: Boolean): (SignedTransaction, InputUTXO, Option[InputUTXO]) = {
    val (spentZc, protoOutputs): (InputUTXO, Seq[UTXO]) = plan.target match {
      case ZcLotTarget(lot, seller, price) =>
        val tag = Seq(bytes(lot.boxId))
        val payout = lot.market.tokenId match {
          case None => UTXO(seller, Math.addExact(lot.box.value, price), Seq.empty, tag)
          case Some(id) => UTXO(seller, MinBox, Seq(token(id, price)), tag)
        }
        (zcInput.withCtxVar(0.toByte, int(0)), Seq(payout))

      case ZcVaultTarget(v, market, _, after) =>
        val regs = zcInput.registers.updated(2, byte(2.toByte))
        val kept = ZcRules.keptBySale(v).map(t => token(t.tokenId, t.amount))
        val reserve = token(v.shareId, v.shares + 1L)
        val sold = market.tokenId match {
          case None => UTXO(v.script.contract, after, reserve +: kept, regs)
          case Some(id) => UTXO(v.script.contract, if (ZcRules.saleKeepsErg(v)) v.box.value else MinBox,
            Seq(reserve, token(id, after)) ++ kept, regs)
        }
        (zcInput.withCtxVar(0.toByte, int(0)), Seq(sold))

      case ZcSaleOrderTarget(ask, seller, units, price) =>
        val tag = Seq(bytes(ask.boxId), long(units))
        val payout = ask.market.tokenId match {
          case None => UTXO(seller, math.max(price, MinBox), Seq.empty, tag)
          case Some(id) => UTXO(seller, MinBox, Seq(token(id, price)), tag)
        }
        val left = ask.token.amount - units
        val rest = if (left <= 0L) None
          else Some(UTXO(ask.script.contract, ask.box.value, Seq(token(ask.token.tokenId, left)), zcInput.registers)
            .withRegNum(6, bytes(ask.boxId)))
        val in = rest.foldLeft(zcInput.withCtxVar(0.toByte, int(0)))((b, _) => b.withCtxVar(1.toByte, int(1)))
        (in, payout +: rest.toSeq)
    }

    val inputs = Seq(spentZc, carry)
    val height = math.max(blockHeight, TxBuilder.newestInput(inputs))
    val inErg = Math.addExact(zcInput.value, carry.value)
    val protoErg = protoOutputs.map(_.value).foldLeft(0L)(Math.addExact)

    // Compute exact token balance remaining after protocol outputs
    val inTokens = (zcInput.tokens ++ carry.tokens).groupBy(_.id.toString).map { case (id, ts) => id -> ts.map(_.amount).sum }
    val outTokens = protoOutputs.flatMap(_.tokens).groupBy(_.id.toString).map { case (id, ts) => id -> ts.map(_.amount).sum }
    val netTokens = (inTokens.keySet ++ outTokens.keySet).flatMap { id =>
      val diff = inTokens.getOrElse(id, 0L) - outTokens.getOrElse(id, 0L)
      require(diff >= 0L, s"ZC purchase leg lacks ${-diff} of token $id")
      if (diff > 0L) Some(id -> diff) else None
    }.toMap

    val liquidatedTokens = plan.valuation.liquidations.map(l => l.tokenId -> l.amount).toMap
    val sweepTokens = netTokens.flatMap { case (id, amt) =>
      val keep = liquidatedTokens.getOrElse(id, 0L)
      require(amt >= keep, s"ZC purchase leg expected $keep of $id to liquidate but holds $amt")
      val extra = amt - keep
      if (extra > 0L) Some(token(id, extra)) else None
    }.toVector
    val carryTokens = plan.valuation.liquidations.map(l => token(l.tokenId, l.amount))

    val sweepOut = if (sweepTokens.isEmpty) None
      else Some(UTXO(wallet.contract, MinBox, sweepTokens).setCreationHeight(height))
    val sweepErg = sweepOut.fold(0L)(_.value)
    val splitProfit = if (emitProfitNow) plan.netProfit else 0L
    val carryErg = inErg - protoErg - sweepErg - splitProfit
    require(carryErg >= ArbLeg.MinCarryValue,
      s"ZC purchase carry holds $carryErg nanoERG, under floor ${ArbLeg.MinCarryValue}")

    val carryIndex = protoOutputs.size + (if (sweepOut.isDefined) 1 else 0)
    val carryOut = UTXO(stake.contract, carryErg, carryTokens).setCreationHeight(height)
    val profitOut = if (!emitProfitNow) None else Some(UTXO(profitTo, splitProfit).setCreationHeight(height))
    val allOutputs = protoOutputs.map(_.setCreationHeight(height)) ++ sweepOut.toSeq ++ Seq(carryOut) ++ profitOut.toSeq

    val unsigned = TxBuilder(ctx)
      .setInputs(inputs: _*)
      .setOutputs(allOutputs: _*)
      .setPreHeader(ctx.createPreHeader().height(blockHeight).build())
      .buildTx(0L, wallet.p2pk)
    val signed = Batcher.withNodeMinimums(ctx, wallet.sign(unsigned))
    val signedCarry = InputUTXO(signed.getOutputsToSpend.get(carryIndex))
    val signedProfit = profitOut.map(_ => InputUTXO(signed.getOutputsToSpend.get(carryIndex + 1)))
    (signed, signedCarry, signedProfit)
  }

  /**
   * Builds up to `maxBundles` confirmed-profit ZC collateral arb bundles on `stake`, returning the bundles plus the
   * updated `carry`, `carryCreatedBy`, `states` and `places` so DEX cycles continue seamlessly on the same stake!
   */
  def buildAll(ctx: BlockchainContext, wallet: NodeWallet, book: ZcBook, resolver: ZcResolver, basis: ArbBasis,
               initialStates: Map[String, ArbPool], initialPlaces: Map[String, (Either[NodeBox, InputUTXO], Option[String])],
               initialCarry: InputUTXO, initialCarryParent: Option[String],
               stake: InputUTXO, blockHeight: Int, slots: Int, maxBundles: Int, maxErgIn: Long, minProfit: Long,
               minLegProceeds: Long, profitTo: Contract, selectUntil: Deadline, buildUntil: Deadline,
               reserve: String => Boolean, refused: Set[String], onRefuse: String => Unit,
               pricer: ZcAssetPricer = ZcAssetPricer.Default)
  : (Vector[CandidateBundle], InputUTXO, Option[String], Map[String, ArbPool], Map[String, (Either[NodeBox, InputUTXO], Option[String])]) = {
    val readBudget = new ZcBudget(MaxResolverReads)
    val resolveScript: ZcRecord => Option[Contract] =
      r => resolver.script(r, pending = false, readBudget).flatMap(contractOf)
    // Also inspect newly created ZC boxes in `basis.created` (e.g., a DutchAuctionVault just pulled by ZcBatcher!).
    val createdZc: Vector[(String, ZcRecord)] = basis.created.flatMap(c => ZcBoxes.read(c.box).map(c.txId -> _))

    var states = initialStates
    var places = initialPlaces
    var carry = initialCarry
    var carryParent = initialCarryParent
    var spentZc = basis.spent ++ refused
    var bundles = Vector.empty[CandidateBundle]
    var usedSlots = 0
    var stopped = false

    def poolInput(nft: String): InputUTXO = places(nft)._1.fold(box => {
      val in = SigmaFiBoxes.input(box, ctx).getOrElse(throw new IllegalStateException(s"pool box ${box.boxId} invalid"))
      require(in.id.toString == box.boxId, s"pool box ${box.boxId} reads back as ${in.id}")
      in
    }, identity)

    while (!stopped && bundles.size < maxBundles && usedSlots < slots &&
      !selectUntil.isOverdue() && !buildUntil.isOverdue()) {
      bestPlan(book, createdZc, states, resolveScript, blockHeight, maxErgIn, minProfit, slots - usedSlots,
        minLegProceeds, spentZc, pricer) match {
        case None => stopped = true
        case Some((plan, zcParent)) =>
          sign(ctx, wallet, plan, poolInput, carry, blockHeight, profitTo, minProfit) match {
            case Failure(ex) =>
              logger.warn(s"Could not build ZC collateral arb for ${plan.target.boxId} at $blockHeight, skipping: ${ex.getMessage}")
              spentZc += plan.target.boxId
              onRefuse(plan.target.boxId)
            case Success((legs, movedPools, nextCarry, profitOut)) =>
              if (!reserve(nextCarry.id.toString)) {
                logger.warn(s"${nextCarry.id} could not be noted as the next arb stake; stopping ZC arb for $blockHeight")
                stopped = true
              } else {
                val ids = legs.map(CandidateTx.txId)
                val poolParents = plan.allSwaps.map(_.pool.nft).distinct.flatMap(nft => places.get(nft).flatMap(_._2))
                val parents = (poolParents ++ zcParent ++ carryParent).distinct.filterNot(ids.contains)
                bundles :+= CandidateBundle(legs.map(CandidateTx.signed(_, ArbKind.ZcArb)),
                  parents.map(ChainFromPackage),
                  Seq(CapitalEntry(CapitalOrigin.Arbitrage, profitOut, ids.last)))
                movedPools.foreach { case (nft, box, txId) =>
                  places += nft -> (Right(box) -> Some(txId))
                }
                plan.allSwaps.zip(movedPools).foreach { case (s, (nft, box, _)) =>
                  states += nft -> s.after.withBox(box.id.toString)
                }
                spentZc += plan.target.boxId
                carry = nextCarry
                carryParent = Some(ids.last)
                usedSlots += legs.size
              }
          }
      }
    }
    if (bundles.nonEmpty)
      logger.info(s"Arb offers ${bundles.size} ZC collateral arb bundle(s) for block $blockHeight, " +
        s"${bundles.flatMap(_.capital.map(_.value)).sum} nanoERG profit")

    (bundles, carry, carryParent, states, places)
  }
}
