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

import scala.concurrent.duration.{Deadline, FiniteDuration}
import scala.util.{Failure, Success, Try}

/**
 * Priced output balances for one order. Revenue includes the miner fee; quote is LP for deposits, X for
 * redemptions, and the tokens moved for a Machina fill. The reward is output 1: the owner's reward for an
 * ErgoDEX order, and the order's child for a Machina one, which `machina` describes.
 */
final case class ErgoDexFill(order: PoolOrder, pool: ErgoDexPool, quote: Long, revenue: Long,
                             poolAfter: ErgoDexPool, rewardValue: Long, rewardTokens: Seq[NodeAsset],
                             machina: Option[MachinaTrade] = None)

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

  def members: Vector[CandidateTx] = transactions.map(CandidateTx.signed(_, ErgoDexExecution.Kind))

  /** Carries ancestors first, supersedes competitors, and credits only the final takings box. */
  def bundle(competitors: Set[String], placements: Vector[CandidateTx] = Vector.empty): CandidateBundle =
    CandidateBundle(placements ++ members,
      placements.flatMap(placement => Seq(ChainFromMempool(placement.id), IncludeExisting(placement.id))) ++
        (if (competitors.isEmpty) Seq.empty else Seq(Supersede(competitors))),
      Seq(CapitalEntry(CapitalOrigin.ExecutorReward, takings, CandidateTx.txId(transactions.last))))
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

  /**
   * What `order` does to `pool`: the quote (swap output, LP issued, or X redeemed) and the pool after it. None
   * when the order's assets do not fit the pool, or a token pool swap would be refused at its own price.
   */
  def movement(order: ErgoDexOrder, pool: ErgoDexPool): Option[(Long, ErgoDexPool)] = {
    val assets = order.box.assets
    def held(index: Int, id: String) = assets.lift(index).exists(_.tokenId == id)
    (order.contract.kind, pool.xId) match {
      case (OrderKind.Deposit, None) if assets.size == 1 && held(0, pool.yId) =>
        pool.deposit(order.baseAmount, assets.head.amount)
      case (OrderKind.Deposit, Some(x)) if assets.size == 2 && held(0, x) && held(1, pool.yId) =>
        pool.deposit(order.baseAmount, assets(1).amount)
      case (OrderKind.Redeem, _) if assets.size == 1 && held(0, pool.lpId) =>
        pool.redeem(order.baseAmount)
      case (OrderKind.SwapSell, None) if assets.isEmpty =>
        val quote = pool.outputAmount(order.baseAmount, baseIsX = true)
        Some(quote -> pool.afterSwap(order.baseAmount, quote, baseIsX = true))
      case (OrderKind.SwapBuy, None) if assets.size == 1 && held(0, pool.yId) =>
        val quote = pool.outputAmount(order.baseAmount, baseIsX = false)
        Some(quote -> pool.afterSwap(order.baseAmount, quote, baseIsX = false))
      case (OrderKind.Swap, Some(x)) if assets.size == 1 =>
        val xIn = held(0, x) && order.quoteId.contains(pool.yId)
        val yIn = held(0, pool.yId) && order.quoteId.contains(x)
        if (!xIn && !yIn) None
        else {
          val quote = pool.outputAmount(order.baseAmount, baseIsX = xIn)
          if (!fairPrice(order, pool, xIn, quote)) None
          else Some(quote -> pool.afterSwap(order.baseAmount, quote, baseIsX = xIn))
        }
      case _ => None
    }
  }

  /**
   * A token pool swap's own check: the output plus one unit must reach what the order's fee numerator prices,
   * against the pool before the swap. The pool's fee decides the output, so an order pricing itself at a lower
   * fee than the pool charges is never fillable.
   */
  private def fairPrice(order: ErgoDexOrder, pool: ErgoDexPool, xIn: Boolean, quote: Long): Boolean =
    order.orderFeeNum.forall { fee =>
      val (reservesIn, reservesOut) = if (xIn) (pool.reservesX, pool.reservesY) else (pool.reservesY, pool.reservesX)
      BigInt(reservesOut) * order.baseAmount * fee <=
        (BigInt(quote) + 1) * (BigInt(reservesIn) * ErgoDexContracts.FeeDenominator + BigInt(order.baseAmount) * fee)
    }

  /** Requires gross revenue above the floor and positive net takings; the first fill must fund a box. */
  def price(order: PoolOrder, pool: ErgoDexPool, minRevenue: Long,
            fundsItsOwnBox: Boolean = true, minerFeeCeiling: Long = 0L): Option[ErgoDexFill] = order match {
    case m: MachinaOrder => priceMachina(m, pool, minRevenue, fundsItsOwnBox, minerFeeCeiling)
    case o: ErgoDexOrder => priceOrder(o, pool, minRevenue, fundsItsOwnBox, minerFeeCeiling)
    case _ => None
  }

  /** A Machina fill at the size that leaves the most ERG against this pool state, held to the same floors. */
  private def priceMachina(order: MachinaOrder, pool: ErgoDexPool, minRevenue: Long, fundsItsOwnBox: Boolean,
                           minerFeeCeiling: Long): Option[ErgoDexFill] =
    if (order.poolNft != pool.nft) None
    else MachinaPricing.best(order, pool).flatMap { trade =>
      val net = trade.gap - math.min(order.maxMinerFee, math.max(0L, minerFeeCeiling))
      if (trade.gap < minRevenue || net <= 0L || (fundsItsOwnBox && net < UTXO.MIN_CHANGE)) None
      else Some(ErgoDexFill(order, pool, trade.tokens, trade.gap, trade.poolAfter, trade.childValue,
        if (trade.childTokens > 0) Seq(NodeAsset(order.tokenId, trade.childTokens)) else Seq.empty, Some(trade)))
    }

  private def priceOrder(order: ErgoDexOrder, pool: ErgoDexPool, minRevenue: Long,
                         fundsItsOwnBox: Boolean, minerFeeCeiling: Long): Option[ErgoDexFill] = {
    if (order.poolNft != pool.nft || order.contract.shape != pool.shape ||
      order.quoteId.exists(q => q != pool.yId && !pool.xId.contains(q))) None
    else {
      Try {
        val assets = order.box.assets
        movement(order, pool).flatMap { case (quote, after) =>
          val revenue = order.dexFee(quote)
          val net = revenue - math.min(order.maxMinerFee, math.max(0L, minerFeeCeiling))
          // A token pool's ERG never moves, so the reward's ERG is the order's less the fee
          val ergOut = if (pool.xId.isDefined) BigInt(0) else BigInt(pool.reservesX) - after.reservesX
          val rewardValue = BigInt(order.box.value) + ergOut - revenue
          // Conservation fixes the reward amounts; LP tokens must precede deposit change, and X precede Y.
          val sides = (pool.lpId, pool.lpSupply, after.lpSupply) +:
            pool.xId.map(x => (x, pool.reservesX, after.reservesX)).toSeq :+
            ((pool.yId, pool.reservesY, after.reservesY))
          val tokens = sides.map { case (id, before, remaining) =>
            id -> (BigInt(before) + assets.filter(_.tokenId == id).map(a => BigInt(a.amount)).sum - remaining)
          }
          // A token pool's reward holds only what the order left beyond the fee, usually a box's bare minimum,
          // which assembly checks byte for byte; a native reward also carries ERG out of the pool
          val rewardFloor = if (pool.xId.isDefined) 1L else UTXO.MIN_CHANGE
          if (quote < order.minQuoteAmount || revenue < minRevenue || net <= 0L ||
            (fundsItsOwnBox && net < UTXO.MIN_CHANGE) ||
            !rewardValue.isValidLong || rewardValue < rewardFloor ||
            after.reservesX <= after.minReservesX || after.reservesY <= 0 || after.lpSupply <= 0 ||
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
  def priceChain(orders: Seq[PoolOrder], pool: ErgoDexPool, minRevenue: Long,
                 limit: Int, minerFeeCeiling: Long = 0L, strategy: RunStrategy = RunStrategy.Default,
                 deadline: Deadline = Deadline.now): Vector[ErgoDexFill] =
    strategy.plan(problem(orders, pool, limit, minRevenue, minerFeeCeiling, fundsFirst = true, _ => Seq.empty,
      Set.empty, deadline)).map(_.priced.fill)

  /** `orders` against `pool` as a strategy sees them, the first fill funding its own box when `fundsFirst`. */
  private def problem(orders: Seq[PoolOrder], pool: ErgoDexPool, limit: Int, minRevenue: Long,
                      minerFeeCeiling: Long, fundsFirst: Boolean, placements: PoolOrder => Seq[String],
                      alreadyCarried: Set[String], deadline: Deadline,
                      searchBudget: FiniteDuration = RunStrategy.SearchBudget): RunProblem[PoolOrder, ErgoDexFill, ErgoDexPool] =
    RunProblem[PoolOrder, ErgoDexFill, ErgoDexPool](orders, pool, limit, _.boxId,
      (order, state, first) => price(order, state, minRevenue, fundsItsOwnBox = first && fundsFirst, minerFeeCeiling)
        .map(fill => Priced(fill, fill.revenue - math.min(order.maxMinerFee, math.max(0L, minerFeeCeiling)),
          fill.poolAfter)),
      placements, alreadyCarried, state => state.reservesY.toDouble / state.reservesX.toDouble,
      RunStrategy.searchUntil(deadline, searchBudget))

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
   * @param searchBudget   most time `strategy` may spend on each plan
   */
  def run(ctx: BlockchainContext, wallet: NodeWallet, poolBox: InputUTXO, pool: ErgoDexPool,
          orders: Seq[PoolOrder], limit: Int, minRevenue: Long, blockHeight: Int, minerFeeCeiling: Long,
          useTrueProp: Boolean, deadline: Deadline,
          placementOf: PoolOrder => Vector[CompleteMempool.MempoolTx] = _ => Vector.empty,
          alreadyCarried: Set[String] = Set.empty,
          unbuildableLimits: Batcher.UnbuildableLimits = Batcher.UnbuildableLimits.Default,
          strategy: RunStrategy = RunStrategy.Default,
          searchBudget: FiniteDuration = RunStrategy.SearchBudget): ErgoDexRun = {
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
    var plan = Vector.empty[Planned[PoolOrder, ErgoDexFill, ErgoDexPool]]
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
          deadline, searchBudget))
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
    val boxInput = order.boxAsInput(ctx)
    require(boxInput.id.toString == order.boxId, "the order box's fields do not match its id")
    val orderInput = (order, fill.machina) match {
      case (machina: MachinaOrder, Some(trade)) => MachinaFill.input(boxInput, machina, trade)
      case _ => boxInput
    }
    val spent = Seq(poolBox, orderInput) ++ carriedTakings.toSeq

    // The block this execution is built for, unless a box it chains onto was itself built for a later
    // one: consensus refuses an output created below the newest input.
    val height = math.max(blockHeight, TxBuilder.newestInput(spent))

    // Preserve pool identity, script and registers while updating reserves and LP balance. A token pool keeps
    // its ERG and carries X as its third token; a native pool's ERG is its X.
    val poolOut = UTXO(poolBox.contract, if (pool.xId.isDefined) poolBox.value else after.reservesX,
      Seq(Token(ErgoId.create(pool.nft), 1L), Token(ErgoId.create(pool.lpId), after.lpSupply)) ++
        pool.xId.map(x => Token(ErgoId.create(x), after.reservesX)).toSeq :+
        Token(ErgoId.create(pool.yId), after.reservesY),
      poolBox.registers).setCreationHeight(height)

    // The reward is the user's, and its proposition is the order's own constant rather than anything this
    // client chooses. A Machina order's is its child: the order recreated, or its owner paid out.
    val rewardOut = (order, fill.machina) match {
      case (machina: MachinaOrder, Some(trade)) => MachinaFill.child(boxInput, machina, trade).setCreationHeight(height)
      case (ergoDex: ErgoDexOrder, _) =>
        UTXO(Contract(sigma.ast.ErgoTree.fromBytes(ergoDex.redeemerPropBytes)), fill.rewardValue,
          fill.rewardTokens.map(asset => Token(ErgoId.create(asset.tokenId), asset.amount)))
          .setCreationHeight(height)
      case _ => throw new IllegalArgumentException(s"no output rule for order ${order.boxId}")
    }

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
