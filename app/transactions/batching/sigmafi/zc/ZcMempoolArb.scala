package transactions.batching.sigmafi.zc

import configs.ZcCollateralArbConfig
import mutations.NodeWallet
import node.model.NodeBox
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.scalaapi.scalaByteType
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue, Parameters, SignedTransaction}
import org.ergoplatform.sdk.ErgoId
import sigma.Colls
import transactions.batching.Batcher
import transactions.batching.arb.{ArbPool, ArbPoolKind, ArbStage, ArbSwap}
import transactions.candidate.BlockTxMessages.CandidateTx
import work.lithos.mutations.{Contract, InputUTXO, Token, TxBuilder, UTXO}

import scala.collection.JavaConverters._

/**
 * A ZC collateral arb to broadcast: the plan, its one swap, the confirmed pool box that swap is priced against, and
 * the height its price was taken at. `paid` is the ERG the transaction pays out (purchase and miner fee), `net` what
 * it leaves after both, both as the plan estimates them.
 */
final case class ZcMempoolPlan(plan: ZcArbPlan, swap: ArbSwap, poolBox: NodeBox, height: Int) {
  def targetId: String = plan.target.boxId
  def paid: Long = Math.addExact(Math.addExact(plan.ergCost, plan.valuation.sweepErgCost), ZcMempoolArb.TxFee)
  def net: Long = plan.netProfit - ZcMempoolArb.TxFee
}

/**
 * ZC collateral arb in one broadcast transaction: `[pool, ZC box, wallet...] -> [pool successor, ZC outputs, wallet,
 * miner fee]`. The pool sits at input 0 with its successor at output 0, as every pool kind requires; the ZC box
 * follows with its payout index in context variable 0 (and a sale order's continuation index in variable 1), and the
 * wallet funds the purchase last. The wallet output takes everything left, unpriced tokens included, so the pool's
 * ERG pays the wallet back inside the same transaction.
 *
 * Only plans that fit one transaction qualify: an ERG market, no currency to acquire, and exactly one swap selling
 * one token for ERG through an ErgoDEX or LithosDex ERG pool. Everything else stays with the in-block path.
 */
object ZcMempoolArb {

  final val TxFee: Long = Parameters.MinFee

  /** Most transactions one background tick sends. */
  final val MaxPerPass = 4

  /** The pool kinds whose swap reads only input 0 and output 0 and pays ERG. */
  def sellsForErg(pool: ArbPool): Boolean =
    pool.x.isErg && (pool.kind == ArbPoolKind.ErgoDexNative || pool.kind == ArbPoolKind.LithosDexNative)

  /** The pools of `parsed` a broadcast may swap through: those whose confirmed box no mempool transaction spends. */
  def freePools(parsed: Map[String, transactions.batching.arb.ArbPoolEntry], spent: Set[String]): Map[String, ArbPool] =
    parsed.collect { case (nft, e) if !spent.contains(e.box.boxId) => nft -> e.pool }

  /** Whether `plan` is built as one transaction: ERG market, nothing acquired, one token sold through one ERG pool. */
  def fits(plan: ZcArbPlan): Boolean =
    plan.target.market.tokenId.isEmpty && plan.acquireSwap.isEmpty && plan.allSwaps.size == 1 &&
      plan.valuation.ergInBasket == 0L && plan.allSwaps.forall(s => s.assetOut.isErg && sellsForErg(s.pool))

  /** `net` is at least `percent` of `paid`, exactly; a transaction paying nothing out passes on `net` alone. */
  def returns(net: Long, paid: Long, percent: Int): Boolean =
    paid <= 0L || BigInt(net) * 100 >= BigInt(paid) * percent

  /**
   * The best one-transaction plan on `book` at `height` (the next block's), over `pools` less the boxes in
   * `excluded`, whose estimated net clears `settings`. Pools that do not sell for ERG are left out first, so no
   * other kind is ever priced here.
   */
  def best(book: ZcBook, pools: Map[String, ArbPool], poolBoxes: Map[String, NodeBox],
           resolveScript: ZcRecord => Option[Contract], height: Int, settings: ZcCollateralArbConfig,
           minLegProceeds: Long, excluded: Set[String]): Option[ZcMempoolPlan] = {
    val usable = pools.filter { case (nft, p) => sellsForErg(p) && poolBoxes.get(nft).exists(_.boxId == p.boxId) }
    val minProfit = math.max(Math.addExact(settings.minProfitNanoErg, TxFee), ArbStage.MinProfitOutput)
    def admit(plan: ZcArbPlan): Boolean = fits(plan) && {
      val net = plan.netProfit - TxFee
      val paid = Math.addExact(Math.addExact(plan.ergCost, plan.valuation.sweepErgCost), TxFee)
      net >= settings.minProfitNanoErg && paid <= settings.maxErgIn && returns(net, paid, settings.minReturnPercent)
    }
    if (settings.maxErgIn <= TxFee || usable.isEmpty) None
    else ZcCollateralArb.bestPlan(book, Seq.empty, usable, resolveScript, height, settings.maxErgIn - TxFee, minProfit,
      slots = 2, minLegProceeds, excluded, admit = admit).flatMap { case (plan, _) =>
      val swap = plan.allSwaps.head
      poolBoxes.get(swap.pool.nft).map(box => ZcMempoolPlan(plan, swap, box, height))
    }
  }

  private def bytes(hex: String): ErgoValue[_] = ErgoValue.of(Colls.fromArray(Hex.decode(hex)), scalaByteType)
  private def int(i: Int): ErgoValue[_] = ErgoValue.of(i)
  private def long(l: Long): ErgoValue[_] = ErgoValue.of(l)
  private def token(id: String, n: Long): Token = Token(ErgoId.create(id), n)

  /** The ZC box with the context variables its purchase reads, and the outputs it requires, from output 1 on. */
  private def purchase(ctx: BlockchainContext, target: ZcArbTarget, zc: InputUTXO): (InputUTXO, Seq[UTXO]) =
    target match {
      case ZcLotTarget(lot, seller, price) =>
        (zc.withCtxVar(0.toByte, int(1)), Seq(UTXO(seller, Math.addExact(lot.box.value, price), Seq.empty,
          Seq(bytes(lot.boxId)))))
      case ZcVaultTarget(v, _, _, after) =>
        (zc.withCtxVar(0.toByte, int(1)), Seq(ZcUserTransactions.soldVault(ctx, v, zc, after)))
      case ZcSaleOrderTarget(ask, seller, units, price) =>
        val payout = UTXO(seller, math.max(price, ZcBoxes.MinBox), Seq.empty, Seq(bytes(ask.boxId), long(units)))
        val left = ask.token.amount - units
        if (left <= 0L) (zc.withCtxVar(0.toByte, int(1)), Seq(payout))
        else (zc.withCtxVar(0.toByte, int(1)).withCtxVar(1.toByte, int(2)), Seq(payout,
          UTXO(ask.script.contract, ask.box.value, Seq(token(ask.token.tokenId, left)), zc.registers)
            .withRegNum(6, bytes(ask.boxId))))
    }

  private def tally(ts: Seq[Token]): Map[String, Long] =
    ts.groupBy(_.id.toString).map { case (id, all) => id -> all.map(_.amount).foldLeft(0L)(Math.addExact) }

  /**
   * Signs `planned` with `pool` and `zc` as read now and `funding` from this wallet, for `height` and later. The
   * swap must price exactly as planned against `pool`, and the signed transaction must pass [[checked]].
   */
  def sign(ctx: BlockchainContext, wallet: NodeWallet, planned: ZcMempoolPlan, pool: InputUTXO, zc: InputUTXO,
           funding: Seq[InputUTXO], settings: ZcCollateralArbConfig): SignedTransaction = {
    require(funding.nonEmpty, "a collateral arb needs a wallet box to fund it")
    require(zc.id.toString == planned.targetId, s"ZC box ${planned.targetId} reads back as ${zc.id}")
    val planSwap = planned.swap
    val swap = planSwap.pool.withBox(pool.id.toString).swap(planSwap.amountIn, planSwap.xIn)
      .filter(s => s.amountOut == planSwap.amountOut && pool.id.toString == planned.poolBox.boxId)
      .getOrElse(throw new IllegalStateException(s"pool ${planSwap.pool.nft} no longer pays as priced"))
    val (zcIn, protocol) = purchase(ctx, planned.plan.target, zc)
    val inputs = Seq(swap.pool.input(pool), zcIn) ++ funding
    val height = math.max(planned.height, TxBuilder.newestInput(inputs))
    val successor = swap.pool.successor(pool, swap.after)
    val outsSoFar = successor +: protocol
    val ergIn = inputs.map(_.value).foldLeft(0L)(Math.addExact)
    val ergOut = Math.addExact(outsSoFar.map(_.value).foldLeft(0L)(Math.addExact), TxFee)
    val left = tally(inputs.flatMap(_.tokens))
    val used = tally(outsSoFar.flatMap(_.tokens))
    val toWallet = (left.keySet ++ used.keySet).toVector.sorted.flatMap { id =>
      val n = left.getOrElse(id, 0L) - used.getOrElse(id, 0L)
      require(n >= 0L, s"the collateral arb lacks ${-n} of $id")
      if (n > 0L) Some(token(id, n)) else None
    }
    val walletOut = UTXO(wallet.contract, ergIn - ergOut, toWallet)
    val unsigned = TxBuilder(ctx)
      .setInputs(inputs: _*)
      .setOutputs((outsSoFar :+ walletOut :+ UTXO.feeBox(TxFee)).map(_.setCreationHeight(height)): _*)
      .setPreHeader(ctx.createPreHeader().height(height).build())
      .buildTx(0L, wallet.p2pk)
    val signed = Batcher.withNodeMinimums(ctx, wallet.sign(unsigned))
    checked(signed, planned, zc.value, funding, wallet.contract, settings).fold(r => throw new IllegalStateException(r),
      _ => signed)
  }

  /**
   * What the signed transaction does to this wallet, refused unless it is what the plan meant: one wallet output
   * second to last, at the wallet's script, a 0.001 ERG fee last, and a net gain of at least `minProfitNanoErg` and
   * `minReturnPercent` of the ERG paid out. Paid is everything the ZC outputs hold beyond the ZC box, plus the fee.
   */
  def checked(signed: SignedTransaction, planned: ZcMempoolPlan, zcValue: Long, funding: Seq[InputUTXO],
              wallet: Contract, settings: ZcCollateralArbConfig): Either[String, (Long, Long)] = {
    val outs = signed.getOutputsToSpend.asScala.toVector
    val fundingIn = funding.map(_.value).foldLeft(0L)(Math.addExact)
    if (outs.size < 4) Left(s"${outs.size} outputs where a collateral arb has at least four")
    else {
      val walletOut = outs(outs.size - 2)
      val fee = outs.last
      val zcOuts = outs.slice(1, outs.size - 2)
      val paid = Math.addExact(zcOuts.map(_.getValue.longValue).foldLeft(0L)(Math.addExact) - zcValue, fee.getValue)
      val net = walletOut.getValue - fundingIn
      if (InputUTXO(walletOut).contract.ergoTreeHex != wallet.ergoTreeHex)
        Left("the second-to-last output is not at this wallet")
      else if (InputUTXO(fee).contract.ergoTreeHex != Contract.FEE.ergoTreeHex || fee.getValue != TxFee)
        Left("the last output is not the 0.001 ERG miner fee")
      else if (zcOuts.exists(o => InputUTXO(o).contract.ergoTreeHex == wallet.ergoTreeHex))
        Left("a ZC output pays this wallet")
      else if (net < settings.minProfitNanoErg) Left(s"the signed transaction nets $net nanoERG, under ${settings.minProfitNanoErg}")
      else if (!returns(net, paid, settings.minReturnPercent))
        Left(s"the signed transaction nets $net on $paid paid out, under ${settings.minReturnPercent}%")
      else if (paid > settings.maxErgIn) Left(s"the signed transaction pays out $paid, over maxErgIn ${settings.maxErgIn}")
      else Right(net -> paid)
    }
  }

  /** The kind a broadcast collateral arb is sent and logged under. */
  final val Kind = "arb-zc-broadcast"

  def txId(signed: SignedTransaction): String = CandidateTx.txId(signed)
}

/**
 * The boxes this client's own broadcast collateral arbs spend while they are believed pending: ZC box or pool box id,
 * to the transaction and when it was sent (`System.nanoTime`). The in-block path leaves these boxes alone, so a block
 * of this miner's carries the broadcast instead of displacing it. Bounded to [[ZcOwnArbs.MaxHeld]] boxes; an entry
 * leaves when an observation taken after it no longer holds its transaction, or after [[ZcOwnArbs.TtlNanos]].
 */
final class ZcOwnArbs(now: () => Long = () => System.nanoTime()) {

  import ZcOwnArbs._

  private val held = new java.util.concurrent.atomic.AtomicReference(Map.empty[String, (String, Long)])

  def note(boxIds: Seq[String], txId: String): Unit = {
    val at = now()
    held.updateAndGet { m =>
      val live = m.filter { case (_, (_, sent)) => at - sent < TtlNanos } ++ boxIds.map(_ -> (txId -> at))
      if (live.size <= MaxHeld) live else live.toVector.sortBy(-_._2._2).take(MaxHeld).toMap
    }
  }

  /** Every box a pending broadcast of this client's spends. */
  def boxes: Set[String] = {
    val at = now()
    held.get.collect { case (id, (_, sent)) if at - sent < TtlNanos => id }.toSet
  }

  /** Drops what an observation finished at `observedAt` (nanoTime) no longer holds, sent well before it began. */
  def prune(ids: Set[String], observedAt: Long): Unit =
    held.updateAndGet(_.filter { case (_, (tx, sent)) =>
      ids.contains(tx) || sent > observedAt - state.synchronization.CompleteMempool.MaxWalkNanos
    })
}

object ZcOwnArbs {
  final val MaxHeld = 256
  /** About fifteen blocks: past it a transaction still pending is no longer counted on. */
  final val TtlNanos: Long = 30L * 60L * 1000000000L
}
