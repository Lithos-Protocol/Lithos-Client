package transactions.engine.execution

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import api.LithosApiErrors.LithosUnavailable
import api.models.{LDAmounts, LDOrder}
import lithosdex.{LDLiquidityPool, LDT2TLiquidityPool}
import node.NodeApi
import node.model.{MempoolOptions, NodeBox}
import org.ergoplatform.appkit.{Address, BlockchainContext}
import sigma.ast.ErgoTree
import state.synchronization.CompleteMempool
import transactions.batching.lithosdex._

import scala.concurrent.Await
import scala.util.Try

/** How the `/dex` reads describe this wallet's orders, on the canonical pool or any other, of either kind. */
private[execution] object LDOrderViews {

  /**
   * A complete, fresh mempool observation from the engine. Order statuses are read from it, so a stale or
   * partial one would report a filling order as open.
   */
  def mempoolSnapshot(walletRef: ActorRef): CompleteMempool.Snapshot = {
    val wait = transactions.batching.Batcher.ObservationTimeout
    val observed = Try(Await.result((walletRef ? CompleteMempool.Refresh)(Timeout(wait))
      .mapTo[CompleteMempool.Observation], wait)).getOrElse(throw LithosUnavailable(
      "no mempool observation arrived from the transaction engine"))
    if (!observed.fresh) throw LithosUnavailable(
      s"no fresh mempool observation: ${observed.failure.getOrElse("the last one is too old")}")
    observed.snapshot.get
  }

  /** The current provision each of `ownerNfts` owns, among `guard`'s provisions of `provToken`. */
  def redeemProvisions(ctx: BlockchainContext, nodeApi: NodeApi, guard: String, provToken: String,
                       ownerNfts: Set[String]): LithosDexExecution.Provisions =
    if (ownerNfts.isEmpty) LithosDexExecution.NoProvisions
    else {
      val (found, _) = LDBoxes.provisionsOwnedBy(ctx, nodeApi, guard, provToken, ownerNfts, MempoolOptions.WithMempool)
      found.map(p => p.ownerNFT.toString -> p).toMap.get
    }

  def ergToken(ctx: BlockchainContext, owned: LDOrderBook.Owned[LithosDexOrder], pool: LDLiquidityPool,
               provisions: LithosDexExecution.Provisions): LDOrder = {
    val base = common(ctx, owned, owned.order.box, owned.order.terms,
      LithosDexExecution.price(owned.order, pool, 0L, provisions, fundsItsOwnBox = false).nonEmpty)
    owned.order match {
      case sell: LithosDexOrder.SwapSell =>
        base.copy(`type` = "SWAP", ergIn = Some(true), amountIn = Some(LDAmounts(sell.baseAmount)),
          minOutput = Some(LDAmounts(sell.minQuote)))
      case buy: LithosDexOrder.SwapBuy =>
        base.copy(`type` = "SWAP", ergIn = Some(false), amountIn = Some(LDAmounts(buy.amount)),
          minOutput = Some(LDAmounts(buy.minQuote)))
      case deposit: LithosDexOrder.Deposit =>
        base.copy(`type` = "DEPOSIT", amountX = Some(LDAmounts(deposit.depositX)),
          amountY = Some(LDAmounts(deposit.amount)), minShares = Some(LDAmounts(deposit.minShares)))
      case redeem: LithosDexOrder.Redeem =>
        base.copy(`type` = "REDEEM", ownerNFT = Some(redeem.ownerNft),
          provisionBoxId = provisions(redeem.ownerNft).map(_.boxId))
    }
  }

  def tokenToken(ctx: BlockchainContext, owned: LDOrderBook.Owned[LDT2TOrder], pool: LDT2TLiquidityPool,
                 provisions: LithosDexExecution.Provisions): LDOrder = {
    val base = common(ctx, owned, owned.order.box, owned.order.terms,
      LDT2TExecution.price(owned.order, pool, 0L, provisions, fundsItsOwnBox = false).nonEmpty)
    owned.order match {
      case swap: LDT2TOrder.Swap =>
        base.copy(`type` = "SWAP", tokenIn = Some(swap.tokenId), amountIn = Some(LDAmounts(swap.amount)),
          minOutput = Some(LDAmounts(swap.minQuote)))
      case deposit: LDT2TOrder.Deposit =>
        base.copy(`type` = "DEPOSIT", amountX = Some(LDAmounts(deposit.amountX)),
          amountY = Some(LDAmounts(deposit.amountY)), minShares = Some(LDAmounts(deposit.minShares)))
      case redeem: LDT2TOrder.Redeem =>
        base.copy(`type` = "REDEEM", ownerNFT = Some(redeem.ownerNft),
          provisionBoxId = provisions(redeem.ownerNft).map(_.boxId))
    }
  }

  /** The fields every order shares; the caller fills in its type and terms. */
  private def common(ctx: BlockchainContext, owned: LDOrderBook.Owned[_], box: NodeBox, terms: LithosDexTerms,
                     fillsAtPool: => Boolean): LDOrder =
    LDOrder(
      boxId = box.boxId,
      `type` = "",
      status = owned.status.name,
      placementTxId = owned.placementTxId,
      placedHeight = owned.placedHeight,
      spendingTxId = owned.spendingTxId,
      owner = Try(Address.fromErgoTree(ErgoTree.fromHex(terms.redeemerTree), ctx.getNetworkType).toString)
        .getOrElse(terms.redeemerTree),
      value = LDAmounts(box.value),
      executorFee = LDAmounts(terms.executorFee),
      maxMinerFee = LDAmounts(terms.maxMinerFee),
      fillableNow = owned.status match {
        // The pool is read at its mempool tip, which is what a filling order's own fill leaves, so pricing it
        // there would call it unfillable. The node accepted that fill, so the order met its terms.
        case LDOrderBook.Status.Filling => true
        case LDOrderBook.Status.Cancelling => false
        case _ => fillsAtPool
      })
}
