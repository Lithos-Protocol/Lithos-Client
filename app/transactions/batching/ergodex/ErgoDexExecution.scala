package transactions.batching.ergodex

import mutations.NodeWallet
import node.model.NodeAsset
import node.MutationConversions._
import org.ergoplatform.appkit.{BlockchainContext, SignedTransaction}
import org.ergoplatform.sdk.ErgoId
import org.slf4j.{Logger, LoggerFactory}
import state.synchronization.CompleteMempool
import transactions.batching.{BatchRun, Batcher, Planned, Priced, RunProblem, RunStrategy}
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

  /**
   * What `strategy` would execute against `pool`, ignoring placements. Ranks pools; builds nothing. With no
   * time to search by default it plans greedily, so ranking many pools costs little.
   */
  def priceChain(orders: Seq[ErgoDexOrder], pool: ErgoDexPool, minRevenue: Long,
                 limit: Int, minerFeeCeiling: Long = 0L, strategy: RunStrategy = RunStrategy.Default,
                 deadline: Deadline = Deadline.now): Vector[ErgoDexFill] =
    strategy.plan(problem(orders, pool, limit, minRevenue, minerFeeCeiling, fundsFirst = true, _ => Seq.empty,
      Set.empty, deadline)).map(_.priced.fill)

  /** `orders` against `pool` as a strategy sees them, the first fill funding its own box when `fundsFirst`. */
  private def problem(orders: Seq[ErgoDexOrder], pool: ErgoDexPool, limit: Int, minRevenue: Long,
                      minerFeeCeiling: Long, fundsFirst: Boolean, placements: ErgoDexOrder => Seq[String],
                      alreadyCarried: Set[String], deadline: Deadline): RunProblem[ErgoDexOrder, ErgoDexFill, ErgoDexPool] =
    RunProblem[ErgoDexOrder, ErgoDexFill, ErgoDexPool](orders, pool, limit, _.boxId,
      (order, state, first) => price(order, state, minRevenue, fundsItsOwnBox = first && fundsFirst, minerFeeCeiling)
        .map(fill => Priced(fill, fill.revenue - math.min(order.maxMinerFee, math.max(0L, minerFeeCeiling)),
          fill.poolAfter)),
      placements, alreadyCarried, state => state.reservesY.toDouble / state.reservesX.toDouble,
      RunStrategy.searchUntil(deadline))

  /**
   * Signs the orders `strategy` plans against `poolBox`, one at a time, each against the pool the last
   * execution left. An order that plans but cannot be signed is named and passed over, and the rest are planned
   * again from the same pool. Stops at `limit` transactions, counting placements not in `alreadyCarried`, at
   * `deadline`, or after `unbuildableLimits.perRun` unbuildable orders. Once `unbuildableLimits.perTx` orders
   * one transaction created have failed, its other orders are left out of every later plan.
   *
   * @param placementOf    the unconfirmed placements an order's box needs carried ahead of it
   * @param alreadyCarried placement ids an earlier run in the same package already carries
   * @param strategy       chooses which orders to execute and in what order
   */
  def run(ctx: BlockchainContext, wallet: NodeWallet, poolBox: InputUTXO, pool: ErgoDexPool,
          orders: Seq[ErgoDexOrder], limit: Int, minRevenue: Long, blockHeight: Int, minerFeeCeiling: Long,
          useTrueProp: Boolean, deadline: Deadline,
          placementOf: ErgoDexOrder => Vector[CompleteMempool.MempoolTx] = _ => Vector.empty,
          alreadyCarried: Set[String] = Set.empty,
          unbuildableLimits: Batcher.UnbuildableLimits = Batcher.UnbuildableLimits.Default,
          strategy: RunStrategy = RunStrategy.Default): ErgoDexRun = {
    require(poolBox.id.toString == pool.boxId, "a run must open on the pool it is priced against")

    var box = poolBox
    var state = pool
    var carried = Option.empty[InputUTXO]
    var built = Vector.empty[SignedTransaction]
    var fills = Vector.empty[ErgoDexFill]
    var poolIds = Vector.empty[String]
    var placements = Vector.empty[CompleteMempool.MempoolTx]
    var unbuildable = Vector.empty[String]
    var failedByTx = Map.empty[String, Int]
    var passedOver = Set.empty[String]
    var plan = Vector.empty[Planned[ErgoDexOrder, ErgoDexFill, ErgoDexPool]]
    var replan = true
    def slotsUsed(txs: Vector[CompleteMempool.MempoolTx]) = built.size + txs.count(tx => !alreadyCarried.contains(tx.id))
    def full = slotsUsed(placements) >= limit
    def stopped = deadline.isOverdue() || unbuildable.size >= unbuildableLimits.perRun

    while (!full && !stopped && (replan || plan.nonEmpty)) {
      if (replan) {
        // Everything not yet signed or refused, planned from the pool the last execution left. Only the run's
        // first fill has to fund a box by itself; the rest add to the one it made
        val open = orders.filterNot(order => fills.exists(_.order.boxId == order.boxId) ||
          unbuildable.contains(order.boxId) || passedOver.contains(order.boxId) ||
          failedByTx.getOrElse(order.box.transactionId, 0) >= unbuildableLimits.perTx)
        plan = strategy.plan(problem(open, state, limit - slotsUsed(placements), minRevenue, minerFeeCeiling,
          fundsFirst = built.isEmpty, order => placementOf(order).map(_.id), alreadyCarried ++ placements.map(_.id),
          deadline))
        replan = false
      } else {
        val step = plan.head
        plan = plan.tail
        val order = step.order
        val createdBy = order.box.transactionId
        val added = placementOf(order).filterNot(tx => placements.exists(_.id == tx.id))
        // Priced again against the pool the chain actually left, which the plan's own pool should equal. A step
        // that no longer fits or fills is left out of this run and the rest planned again, since every later
        // step was priced against the pool this one would have left
        val repriced =
          if (slotsUsed(placements ++ added) >= limit) None
          else price(order, state, minRevenue, fundsItsOwnBox = built.isEmpty, minerFeeCeiling)
        repriced.map(fill => fill -> Try {
          val signed = assembled(ctx, wallet, box, fill, blockHeight, minerFeeCeiling, useTrueProp, carried)
          val outputs = signed.getOutputsToSpend
          (signed, InputUTXO(outputs.get(0)), InputUTXO(outputs.get(2)))
        }) match {
          case None =>
            passedOver += order.boxId
            replan = true
          case Some((fill, Success((signed, nextPool, takings)))) =>
            built :+= signed
            fills :+= fill
            poolIds :+= box.id.toString
            placements ++= added
            box = nextPool
            state = fill.poolAfter
            carried = Some(takings)
          case Some((_, Failure(ex))) =>
            logger.warn(s"Could not build ErgoDEX order ${order.boxId} against pool ${pool.nft.take(12)}, " +
              s"passing over it: ${ex.getMessage}")
            unbuildable :+= order.boxId
            failedByTx = failedByTx.updated(createdBy, failedByTx.getOrElse(createdBy, 0) + 1)
            replan = true
        }
      }
    }
    ErgoDexRun(carried.map(ErgoDexChain(built, fills, poolIds, _)), placements, unbuildable,
      cutShort = (replan || plan.nonEmpty) && !full)
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
