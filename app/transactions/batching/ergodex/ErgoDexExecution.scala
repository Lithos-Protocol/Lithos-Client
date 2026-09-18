package transactions.batching.ergodex

import mutations.NodeWallet
import node.model.NodeAsset
import node.MutationConversions._
import org.ergoplatform.appkit.{BlockchainContext, SignedTransaction}
import org.ergoplatform.sdk.ErgoId
import org.slf4j.{Logger, LoggerFactory}
import state.synchronization.CompleteMempool
import transactions.batching.{BatchRun, Batcher}
import transactions.candidate.BlockTxMessages.{CandidateTx, ChainFromMempool, IncludeExisting, Supersede}
import transactions.candidate.{CandidateBundle, CandidateCapital, CapitalEntry, CapitalOrigin}
import transactions.engine.execution.RollupExecution
import work.lithos.mutations.{Contract, InputUTXO, Token, TxBuilder, UTXO}

import scala.concurrent.duration.Deadline
import scala.util.{Failure, Success, Try}

/**
 * Priced output balances for one order. Revenue includes the miner fee; quote is LP for deposits and X
 * for redemptions.
 */
final case class ErgoDexFill(order: ErgoDexOrder, pool: ErgoDexPool, quote: Long, revenue: Long,
                             poolAfter: ErgoDexPool, rewardValue: Long, rewardTokens: Seq[NodeAsset])

/**
 * Signed executions in spend order, with the final unspent box holding their combined takings.
 *
 * @param poolIds the pool box each execution spends, in order
 */
final case class ErgoDexChain(transactions: Vector[SignedTransaction], fills: Vector[ErgoDexFill],
                              poolIds: Vector[String], takings: InputUTXO) extends BatchRun {

  override def orderIdAt(index: Int): String = fills(index).order.boxId

  override def poolIdAt(index: Int): String = poolIds(index)

  override def kind: String = ErgoDexExecution.Kind

  def members: Vector[CandidateTx] = transactions.map { signed =>
    CandidateTx(signed.getId, signed.toJson(false, false), ErgoDexExecution.Kind,
      RollupExecution.signedInputIds(signed), RollupExecution.signedSizeBytes(signed),
      signed.getCost.toLong, RollupExecution.signedLeaf(signed))
  }

  /** Carries ancestors first, supersedes competitors, and credits only the final takings box. */
  def bundle(competitors: Set[String], placements: Vector[CandidateTx] = Vector.empty): CandidateBundle =
    CandidateBundle(placements ++ members,
      placements.flatMap(placement => Seq(ChainFromMempool(placement.id), IncludeExisting(placement.id))) ++
        (if (competitors.isEmpty) Seq.empty else Seq(Supersede(competitors))),
      Seq(CapitalEntry(CapitalOrigin.ExecutorReward, takings, transactions.last.getId)))
}

/**
 * What one pass over a pool's orders produced.
 *
 * @param chain       the executions that signed, or None when none did
 * @param placements  the unconfirmed placements those executions spend, in spend order
 * @param unbuildable box ids of orders that priced but could not be built, in the order they were tried
 * @param cutShort    whether the pass stopped at its deadline or its unbuildable limit with orders left
 */
final case class ErgoDexRun(chain: Option[ErgoDexChain],
                            placements: Vector[CompleteMempool.MempoolTx],
                            unbuildable: Vector[String],
                            cutShort: Boolean)

/** Signs one order per transaction, with the pool at input/output 0 and the owner's reward at output 1. */
object ErgoDexExecution {

  private val logger: Logger = LoggerFactory.getLogger("ErgoDexExecution")

  /** Named so a refused candidate says which builder produced the offending transaction. */
  final val Kind = "ergodex-batch"

  /** Requires gross revenue above the floor and positive net takings; the first fill must fund a box. */
  def price(order: ErgoDexOrder, pool: ErgoDexPool, minRevenue: Long,
            fundsItsOwnBox: Boolean = true, minerFeeCeiling: Long = 0L): Option[ErgoDexFill] = {
    if (order.poolNft != pool.nft || order.quoteId.exists(_ != pool.yId)) None
    else {
      Try {
        val assets = order.box.assets
        val movement = order.contract.kind match {
          case OrderKind.Deposit if assets.size == 1 && assets.head.tokenId == pool.yId =>
            pool.deposit(order.baseAmount, assets.head.amount)
          case OrderKind.Redeem if assets.size == 1 && assets.head.tokenId == pool.lpId =>
            pool.redeem(order.baseAmount)
          case OrderKind.SwapSell if assets.isEmpty =>
            val quote = pool.outputAmount(order.baseAmount, baseIsErg = true)
            Some(quote -> pool.afterSwap(order.baseAmount, quote, baseIsErg = true))
          case OrderKind.SwapBuy if assets.size == 1 && assets.head.tokenId == pool.yId =>
            val quote = pool.outputAmount(order.baseAmount, baseIsErg = false)
            Some(quote -> pool.afterSwap(order.baseAmount, quote, baseIsErg = false))
          case _ => None
        }
        movement.flatMap { case (quote, after) =>
          val revenue = order.dexFee(quote)
          val net = revenue - math.min(order.maxMinerFee, math.max(0L, minerFeeCeiling))
          val rewardValue = BigInt(order.box.value) + pool.reservesX - after.reservesX - revenue
          // Conservation fixes the reward amounts; LP tokens must precede deposit change.
          val tokens = Seq((pool.lpId, pool.lpSupply, after.lpSupply), (pool.yId, pool.reservesY, after.reservesY))
            .map { case (id, before, remaining) =>
              id -> (BigInt(before) + assets.filter(_.tokenId == id).map(a => BigInt(a.amount)).sum - remaining)
            }
          if (quote < order.minQuoteAmount || revenue < minRevenue || net <= 0L ||
            (fundsItsOwnBox && net < UTXO.MIN_CHANGE) ||
            !rewardValue.isValidLong || rewardValue < UTXO.MIN_CHANGE ||
            after.reservesX <= ErgoDexPool.MinStorageRent || after.reservesY <= 0 || after.lpSupply <= 0 ||
            tokens.exists { case (_, amount) => !amount.isValidLong || amount < 0 }) None
          else Some(ErgoDexFill(order, pool, quote, revenue, after, rewardValue.toLong,
            tokens.collect { case (id, amount) if amount > 0 => NodeAsset(id, amount.toLong) }))
        }
      }.toOption.flatten
    }
  }

  /** Ranks orders by revenue, then reprices each against the preceding fill's pool balances. Ranks pools; builds nothing. */
  def priceChain(orders: Seq[ErgoDexOrder], pool: ErgoDexPool, minRevenue: Long,
                 limit: Int, minerFeeCeiling: Long = 0L): Vector[ErgoDexFill] = {
    // Rank by revenue so the opening fill can fund the takings box.
    val ranked = orders.flatMap(order =>
      price(order, pool, minRevenue, fundsItsOwnBox = false, minerFeeCeiling)
        .map(fill => order -> fill.revenue)).sortBy { case (order, revenue) => (-revenue, order.boxId) }
      .map(_._1)

    var state = pool
    var fills = Vector.empty[ErgoDexFill]
    ranked.foreach { order =>
      if (fills.size < limit) {
        // Only the opening fill has to fund a box by itself; the rest add to the one it made.
        price(order, state, minRevenue, fundsItsOwnBox = fills.isEmpty, minerFeeCeiling).foreach { fill =>
          fills :+= fill
          state = fill.poolAfter
        }
      }
    }
    fills
  }

  /**
   * Signs the highest-revenue orders against `poolBox` one at a time, each priced against the pool the last
   * execution left. An order that prices but cannot be signed is named and passed over, and the next is
   * priced against the same pool. Stops at `limit` transactions, counting placements not in
   * `alreadyCarried`, at `deadline`, or after `unbuildableLimits.perRun` unbuildable orders. Once
   * `unbuildableLimits.perTx` orders one transaction created have failed, its other orders are passed over untried.
   *
   * @param placementOf    the unconfirmed placements an order's box needs carried ahead of it
   * @param alreadyCarried placement ids an earlier run in the same package already carries
   */
  def run(ctx: BlockchainContext, wallet: NodeWallet, poolBox: InputUTXO, pool: ErgoDexPool,
          orders: Seq[ErgoDexOrder], limit: Int, minRevenue: Long, blockHeight: Int, minerFeeCeiling: Long,
          useTrueProp: Boolean, deadline: Deadline,
          placementOf: ErgoDexOrder => Vector[CompleteMempool.MempoolTx] = _ => Vector.empty,
          alreadyCarried: Set[String] = Set.empty,
          unbuildableLimits: Batcher.UnbuildableLimits = Batcher.UnbuildableLimits.Default): ErgoDexRun = {
    require(poolBox.id.toString == pool.boxId, "a run must open on the pool it is priced against")
    val ranked = orders
      .flatMap(order => price(order, pool, minRevenue, fundsItsOwnBox = false, minerFeeCeiling)
        .map(fill => order -> fill.revenue))
      .sortBy { case (order, revenue) => (-revenue, order.boxId) }
      .map(_._1)

    var box = poolBox
    var state = pool
    var carried = Option.empty[InputUTXO]
    var built = Vector.empty[SignedTransaction]
    var fills = Vector.empty[ErgoDexFill]
    var poolIds = Vector.empty[String]
    var placements = Vector.empty[CompleteMempool.MempoolTx]
    var unbuildable = Vector.empty[String]
    var failedByTx = Map.empty[String, Int]
    val remaining = ranked.iterator
    def slotsUsed(txs: Vector[CompleteMempool.MempoolTx]) = built.size + txs.count(tx => !alreadyCarried.contains(tx.id))
    def full = slotsUsed(placements) >= limit
    def stopped = deadline.isOverdue() || unbuildable.size >= unbuildableLimits.perRun

    while (remaining.hasNext && !full && !stopped) {
      val order = remaining.next()
      val createdBy = order.box.transactionId
      // Only the opening fill has to fund a box by itself; the rest add to the one it made. An order whose
      // transaction has already failed perTx times is passed over untried
      val priced =
        if (failedByTx.getOrElse(createdBy, 0) >= unbuildableLimits.perTx) None
        else price(order, state, minRevenue, fundsItsOwnBox = built.isEmpty, minerFeeCeiling)
      priced.foreach { fill =>
        val added = placementOf(order).filterNot(tx => placements.exists(_.id == tx.id))
        if (slotsUsed(placements ++ added) < limit) Try {
          val signed = assembled(ctx, wallet, box, fill, blockHeight, minerFeeCeiling, useTrueProp, carried)
          val outputs = signed.getOutputsToSpend
          (signed, InputUTXO(outputs.get(0)), InputUTXO(outputs.get(2)))
        } match {
          case Success((signed, nextPool, takings)) =>
            built :+= signed
            fills :+= fill
            poolIds :+= box.id.toString
            placements ++= added
            box = nextPool
            state = fill.poolAfter
            carried = Some(takings)
          case Failure(ex) =>
            logger.warn(s"Could not build ErgoDEX order ${order.boxId} against pool ${pool.nft.take(12)}, " +
              s"passing over it: ${ex.getMessage}")
            unbuildable :+= order.boxId
            failedByTx = failedByTx.updated(createdBy, failedByTx.getOrElse(createdBy, 0) + 1)
        }
      }
    }
    ErgoDexRun(carried.map(ErgoDexChain(built, fills, poolIds, _)), placements, unbuildable,
      cutShort = remaining.hasNext && !full)
  }

  /** Builds pool, reward and takings outputs in contract order, followed by an optional miner fee. */
  private[transactions] def assembled(ctx: BlockchainContext, wallet: NodeWallet, poolBox: InputUTXO,
                       fill: ErgoDexFill, blockHeight: Int, minerFeeCeiling: Long,
                       useTrueProp: Boolean,
                       carriedTakings: Option[InputUTXO] = None): SignedTransaction = {
    val order = fill.order
    val pool = fill.pool
    // Candidate executions pay zero; broadcasts pay the capped fee from their revenue.
    val minerFee = math.min(order.maxMinerFee, minerFeeCeiling)
    require(minerFee <= order.maxMinerFee, "an execution cannot pay past the order's miner-fee cap")
    // Whatever the chain has collected so far, plus this fill's share of it.
    val takings = Math.addExact(carriedTakings.map(_.value).getOrElse(0L), fill.revenue - minerFee)
    require(takings >= UTXO.MIN_CHANGE, "the takings cannot fund an output of their own")

    val after = fill.poolAfter
    require(after.reservesX > 0 && after.reservesY > 0, "an execution cannot empty the pool")

    // Verify the reported order id before spending the reconstructed input.
    val orderInput = order.boxAsInput(ctx)
    require(orderInput.id.toString == order.boxId, "the order box's fields do not match its id")
    val spent = Seq(poolBox, orderInput) ++ carriedTakings.toSeq

    // The block this execution is built for, unless a box it chains onto was itself built for a later
    // one: consensus refuses an output created below the newest input.
    val height = math.max(blockHeight, TxBuilder.newestInput(spent))

    // Preserve pool identity, script and registers while updating reserves and LP balance.
    val poolOut = UTXO(poolBox.contract, after.reservesX,
      Seq(Token(ErgoId.create(pool.nft), 1L),
        Token(ErgoId.create(pool.lpId), after.lpSupply),
        Token(ErgoId.create(pool.yId), after.reservesY)),
      poolBox.registers).setCreationHeight(height)

    // The reward is the user's, and its proposition is the order's own constant rather than
    // anything this client chooses.
    val redeemer = Contract(sigma.ast.ErgoTree.fromBytes(order.redeemerPropBytes))
    val rewardOut = UTXO(redeemer, fill.rewardValue,
      fill.rewardTokens.map(asset => Token(ErgoId.create(asset.tokenId), asset.amount)))
      .setCreationHeight(height)

    val takingsOut = UTXO(CandidateCapital.collectionContract(wallet, useTrueProp), takings)
      .setCreationHeight(height)
    val feeOut =
      if (minerFee > 0) Seq(UTXO.feeBox(minerFee).setCreationHeight(height)) else Seq.empty

    val unsigned = TxBuilder(ctx)
      .setInputs(spent: _*)
      .setOutputs((Seq(poolOut, rewardOut, takingsOut) ++ feeOut): _*)
      .buildTx(0L, wallet.p2pk)
    // Signing validates the scripts and supplies the measured execution cost.
    Batcher.withNodeMinimums(ctx, wallet.sign(unsigned))
  }

}
