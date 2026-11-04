package transactions.batching.lithosdex

import lithosdex.contracts.LDT2TContracts
import lithosdex.states.LDT2TFeeValue
import lithosdex.{LDHelpers, LDT2TLiquidityPool}
import mutations.NodeWallet
import node.MutationConversions._
import org.ergoplatform.appkit.scalaapi.{scalaByteType, scalaLongType}
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue, SignedTransaction}
import org.ergoplatform.sdk.ErgoId
import org.slf4j.{Logger, LoggerFactory}
import sigma.Colls
import state.synchronization.CompleteMempool
import transactions.batching.{BatchRun, Batcher, Planned, Priced, RunProblem, RunStrategy}
import transactions.candidate.BlockTxMessages.{CandidateTx, ChainFromMempool, IncludeExisting, Supersede}
import transactions.candidate.{CandidateBundle, CandidateCapital, CapitalEntry, CapitalOrigin}
import work.lithos.mutations.{Contract, InputUTXO, Token, TxBuilder, UTXO}

import scala.concurrent.duration.{Deadline, FiniteDuration}
import scala.util.{Failure, Success, Try}

/**
 * One token/token order priced against one pool state.
 *
 * @param revenue      the order's executor fee, miner fee included
 * @param rewardValue  nanoERG in the owner's reward box
 * @param rewardTokens pool tokens in the owner's reward box, none at zero: a swap's output, a deposit's
 *                     excess, or a redemption's share of both reserves
 * @param shares       shares a deposit issues; 0 for every other kind
 * @param provision    the provision a redemption closes
 */
final case class LDT2TFill(order: LDT2TOrder,
                           pool: LDT2TLiquidityPool,
                           poolAfter: LDT2TLiquidityPool,
                           revenue: Long,
                           rewardValue: Long,
                           rewardTokens: Seq[Token],
                           shares: Long = 0L,
                           provision: Option[LDBoxes.Provision] = None)

/** What one pass over a token/token pool's orders produced; the same fields as [[LithosDexRun]]. */
final case class LDT2TRun(chain: Option[LDT2TChain],
                          placements: Vector[CompleteMempool.MempoolTx],
                          unbuildable: Vector[String],
                          cutShort: Boolean)

/** Signed token/token executions in spend order, an optional closing flush, and the box holding their takings. */
final case class LDT2TChain(transactions: Vector[SignedTransaction],
                            fills: Vector[LDT2TFill],
                            poolIds: Vector[String],
                            takings: InputUTXO,
                            flush: Option[SignedTransaction] = None) extends BatchRun {

  override def orderIdAt(index: Int): String = fills(index).order.boxId

  override def poolIdAt(index: Int): String = poolIds(index)

  override def kind: String = LDT2TExecution.Kind

  def members: Vector[CandidateTx] =
    transactions.map(CandidateTx.signed(_, LDT2TExecution.Kind)) ++
      flush.map(CandidateTx.signed(_, LDT2TExecution.FlushKind))

  /** Carries placements first, supersedes competitors, and credits only the final takings box. */
  def bundle(competitors: Set[String], placements: Vector[CandidateTx] = Vector.empty): CandidateBundle =
    CandidateBundle(placements ++ members,
      placements.flatMap(placement => Seq(ChainFromMempool(placement.id), IncludeExisting(placement.id))) ++
        (if (competitors.isEmpty) Seq.empty else Seq(Supersede(competitors))),
      Seq(CapitalEntry(CapitalOrigin.ExecutorReward, takings, CandidateTx.txId(transactions.last))))
}

/**
 * Prices and signs token/token LithosDex order executions, one order per transaction, chained through the
 * pool, as [[LithosDexExecution]] does for ERG/token pools. The pool's ERG is storage only and never moves.
 *
 * {{{
 *   swap      IN  pool, order, [takings]              OUT pool, reward, takings, [fee]
 *   deposit   IN  pool, order, [takings]              OUT pool, provision, reward, takings, [fee]
 *   redeem    IN  pool, provision, order, [takings]   OUT pool, reward, takings, [fee]   (NFT burned)
 *   flush     IN  pool, vault                         OUT pool, vault
 * }}}
 */
object LDT2TExecution {

  private val logger: Logger = LoggerFactory.getLogger("LDT2TExecution")

  final val Kind = "lithosdex-t2t-batch"
  final val FlushKind = "lithosdex-t2t-flush"

  /**
   * The fill `order` gets against `pool`, or None when it cannot be executed at a profit: the pool refuses
   * it, the owner's terms refuse it, the fee is under `minRevenue` or does not cover the miner fee, the reward
   * box would hold under `MIN_CHANGE`, or the first fill's takings could not fund a box.
   */
  def price(order: LDT2TOrder, pool: LDT2TLiquidityPool, minRevenue: Long, provisions: LithosDexExecution.Provisions,
            fundsItsOwnBox: Boolean = true, minerFeeCeiling: Long = 0L): Option[LDT2TFill] =
    if (order.poolNft != pool.poolNFT.toString) None
    else Try(movement(order, pool, provisions)).toOption.flatten.filter { fill =>
      val net = fill.revenue - math.min(order.terms.maxMinerFee, math.max(0L, minerFeeCeiling))
      fill.revenue >= minRevenue && net > 0 && (!fundsItsOwnBox || net >= UTXO.MIN_CHANGE) &&
        fill.rewardValue >= UTXO.MIN_CHANGE && fill.poolAfter.reservesX > 0 && fill.poolAfter.reservesY > 0
    }

  /** Shares from the scarcer side, and whatever the other side cannot match returned, as the deposit order truncates. */
  final case class Split(shares: BigInt, takenX: BigInt, takenY: BigInt,
                         excessX: BigInt, excessY: BigInt, unlocked: BigInt)

  def depositSplit(pool: LDT2TLiquidityPool, offeredX: Long, offeredY: Long): Split = {
    val supply = BigInt(pool.supply)
    val byX = BigInt(offeredX) * supply / pool.reservesX
    val byY = BigInt(offeredY) * supply / pool.reservesY
    val excessX = if (byX > byY) (byX - byY) * pool.reservesX / supply else BigInt(0)
    val excessY = if (byY > byX) (byY - byX) * pool.reservesY / supply else BigInt(0)
    val takenX = BigInt(offeredX) - excessX
    val takenY = BigInt(offeredY) - excessY
    Split(byX min byY, takenX, takenY, excessX, excessY,
      (takenX * supply / pool.reservesX) min (takenY * supply / pool.reservesY))
  }

  /** The arithmetic each order contract checks, applied through the pool's own quoting functions. */
  private def movement(order: LDT2TOrder, pool: LDT2TLiquidityPool,
                       provisions: LithosDexExecution.Provisions): Option[LDT2TFill] = {
    val fee = order.terms.executorFee
    def nonZero(id: ErgoId, amount: BigInt): Seq[Token] = if (amount > 0) Seq(Token(id, amount.toLong)) else Seq.empty
    order match {
      case swap: LDT2TOrder.Swap =>
        val xIn = swap.tokenId == pool.tokenX.toString
        if (!xIn && swap.tokenId != pool.tokenY.toString) None
        else {
          val q = pool.simSwap(swap.amount, xIn)
          val rewardValue = swap.box.value - fee
          if (!q.isExecutable || q.amountOut < swap.minQuote) None
          else Some(LDT2TFill(swap, pool, pool.afterSwap(q), fee, rewardValue,
            Seq(Token(if (xIn) pool.tokenY else pool.tokenX, q.amountOut))))
        }

      case deposit: LDT2TOrder.Deposit =>
        if (deposit.tokenX != pool.tokenX.toString || deposit.tokenY != pool.tokenY.toString || !pool.canDeposit) None
        else {
          val split = depositSplit(pool, deposit.amountX, deposit.amountY)
          import split._
          val rewardValue = BigInt(deposit.box.value) - LDHelpers.PROVISION_MIN - fee
          if (shares <= 0 || shares < deposit.minShares || shares > unlocked || !shares.isValidLong ||
            !rewardValue.isValidLong) None
          else Some(LDT2TFill(deposit, pool, pool.afterDeposit(takenX.toLong, takenY.toLong, shares.toLong), fee,
            rewardValue.toLong, nonZero(pool.tokenX, excessX) ++ nonZero(pool.tokenY, excessY), shares = shares.toLong))
        }

      case redeem: LDT2TOrder.Redeem =>
        provisions(redeem.ownerNft).filter(_.ownerNFT.toString == redeem.ownerNft).flatMap { provision =>
          val q = pool.simRedeem(provision.shares)
          val rewardValue = BigInt(redeem.box.value) + provision.value - fee
          if (!q.withinMinSupply || !rewardValue.isValidLong) None
          else Some(LDT2TFill(redeem, pool, pool.afterRedeem(q), fee, rewardValue.toLong,
            nonZero(pool.tokenX, q.amountX) ++ nonZero(pool.tokenY, q.amountY), provision = Some(provision)))
        }
    }
  }

  /** What `strategy` would execute against `pool`, ignoring placements. Ranks pools; builds nothing. */
  def priceChain(orders: Seq[LDT2TOrder], pool: LDT2TLiquidityPool, minRevenue: Long,
                 provisions: LithosDexExecution.Provisions, limit: Int, minerFeeCeiling: Long = 0L,
                 strategy: RunStrategy = RunStrategy.Default, deadline: Deadline = Deadline.now): Vector[LDT2TFill] =
    strategy.plan(problem(orders, pool, limit, minRevenue, provisions, minerFeeCeiling, fundsFirst = true,
      _ => Seq.empty, Set.empty, deadline)).map(_.priced.fill)

  private def problem(orders: Seq[LDT2TOrder], pool: LDT2TLiquidityPool, limit: Int, minRevenue: Long,
                      provisions: LithosDexExecution.Provisions, minerFeeCeiling: Long, fundsFirst: Boolean,
                      placements: LDT2TOrder => Seq[String], alreadyCarried: Set[String], deadline: Deadline,
                      searchBudget: FiniteDuration = RunStrategy.SearchBudget)
  : RunProblem[LDT2TOrder, LDT2TFill, LDT2TLiquidityPool] =
    RunProblem[LDT2TOrder, LDT2TFill, LDT2TLiquidityPool](orders, pool, limit, _.boxId,
      (order, state, first) => price(order, state, minRevenue, provisions, fundsItsOwnBox = first && fundsFirst,
        minerFeeCeiling).map(fill => Priced(fill, kept(fill, minerFeeCeiling), fill.poolAfter)),
      placements, alreadyCarried, spot, RunStrategy.searchUntil(deadline, searchBudget))

  /**
   * Signs the orders `strategy` plans against `poolBox`, one at a time, each against the pool box the last
   * execution left. The same loop as [[LithosDexExecution.run]]: an order that plans but cannot be signed is
   * named and passed over, and the run stops at `limit` transactions, at `deadline`, or at its unbuildable limits.
   */
  def run(ctx: BlockchainContext, wallet: NodeWallet, contracts: LDT2TContracts, poolBox: InputUTXO,
          orders: Seq[LDT2TOrder], limit: Int, minRevenue: Long, provisions: LithosDexExecution.Provisions,
          blockHeight: Int, minerFeeCeiling: Long, useTrueProp: Boolean, deadline: Deadline,
          placementOf: LDT2TOrder => Vector[CompleteMempool.MempoolTx] = _ => Vector.empty,
          unbuildableLimits: Batcher.UnbuildableLimits = Batcher.UnbuildableLimits.Default,
          alreadyCarried: Set[String] = Set.empty,
          strategy: RunStrategy = RunStrategy.Default,
          searchBudget: FiniteDuration = RunStrategy.SearchBudget): LDT2TRun = {
    var pool = poolBox
    var carried = Option.empty[InputUTXO]
    var built = Vector.empty[SignedTransaction]
    var fills = Vector.empty[LDT2TFill]
    var poolIds = Vector.empty[String]
    var placements = Vector.empty[CompleteMempool.MempoolTx]
    var unbuildable = Vector.empty[String]
    var failedByTx = Map.empty[String, Int]
    var passedOver = Set.empty[String]
    var plan = Vector.empty[Planned[LDT2TOrder, LDT2TFill, LDT2TLiquidityPool]]
    var replan = true
    def slotsUsed(txs: Vector[CompleteMempool.MempoolTx]) = built.size + txs.count(tx => !alreadyCarried.contains(tx.id))
    def full = slotsUsed(placements) >= limit
    def stopped = deadline.isOverdue() || unbuildable.size >= unbuildableLimits.perRun

    def open: Seq[LDT2TOrder] = orders.filterNot(order => fills.exists(_.order.boxId == order.boxId) ||
      unbuildable.contains(order.boxId) || passedOver.contains(order.boxId) ||
      failedByTx.getOrElse(order.box.transactionId, 0) >= unbuildableLimits.perTx)

    while (!full && !stopped && (replan || plan.nonEmpty)) {
      if (replan) {
        plan = strategy.plan(problem(open, LDT2TLiquidityPool(pool), limit - slotsUsed(placements), minRevenue,
          provisions, minerFeeCeiling, fundsFirst = built.isEmpty, order => placementOf(order).map(_.id),
          alreadyCarried ++ placements.map(_.id), deadline, searchBudget))
        replan = false
      } else {
        val step = plan.head
        plan = plan.tail
        val order = step.order
        val createdBy = order.box.transactionId
        val added = placementOf(order).filterNot(tx => placements.exists(_.id == tx.id))
        val repriced =
          if (slotsUsed(placements ++ added) >= limit) None
          else price(order, LDT2TLiquidityPool(pool), minRevenue, provisions, fundsItsOwnBox = built.isEmpty,
            minerFeeCeiling)
        repriced.map(fill => fill -> Try {
          val signed = assembled(ctx, wallet, contracts, pool, fill, blockHeight, minerFeeCeiling, useTrueProp, carried)
          val outputs = signed.getOutputsToSpend
          (signed, InputUTXO(outputs.get(0)), InputUTXO(outputs.get(takingsIndex(order))))
        }) match {
          case None =>
            passedOver += order.boxId
            replan = true
          case Some((fill, Success((signed, nextPool, takings)))) =>
            built :+= signed
            fills :+= fill
            poolIds :+= pool.id.toString
            placements ++= added
            pool = nextPool
            carried = Some(takings)
          case Some((_, Failure(ex))) =>
            logger.warn(s"Could not build LithosDEX token/token order ${order.boxId} against pool ${pool.id}, " +
              s"passing over it: ${ex.getMessage}")
            unbuildable :+= order.boxId
            failedByTx = failedByTx.updated(createdBy, failedByTx.getOrElse(createdBy, 0) + 1)
            replan = true
        }
      }
    }
    LDT2TRun(carried.map(LDT2TChain(built, fills, poolIds, _)), placements, unbuildable,
      cutShort = (replan || plan.nonEmpty) && !full)
  }

  private def kept(fill: LDT2TFill, minerFeeCeiling: Long): Long =
    fill.revenue - math.min(fill.order.terms.maxMinerFee, math.max(0L, minerFeeCeiling))

  /** Units of Y per unit of X, which the strategy measures a fill's price impact by. */
  private def spot(pool: LDT2TLiquidityPool): Double = pool.reservesY.toDouble / pool.reservesX.toDouble

  /** `chain` closed with a flush of its last pool box's pending fees, or unchanged when there is nothing to flush. */
  def withFlush(ctx: BlockchainContext, wallet: NodeWallet, contracts: LDT2TContracts, chain: LDT2TChain,
                vaultBox: InputUTXO, blockHeight: Int): LDT2TChain = {
    val poolBox = InputUTXO(chain.transactions.last.getOutputsToSpend.get(0))
    if (!LDT2TLiquidityPool(poolBox).canFlush) chain
    else Try(flush(ctx, wallet, contracts, poolBox, vaultBox, blockHeight)) match {
      case Success(signed) => chain.copy(flush = Some(signed))
      case Failure(ex) =>
        logger.warn(s"Could not close a LithosDEX token/token run with a flush, offering it without one: ${ex.getMessage}")
        chain
    }
  }

  private def takingsIndex(order: LDT2TOrder): Int = order match {
    case _: LDT2TOrder.Deposit => 3
    case _ => 2
  }

  /** One execution in the positions its order contract reads, followed by an optional miner fee. */
  private[lithosdex] def assembled(ctx: BlockchainContext, wallet: NodeWallet, contracts: LDT2TContracts,
                                   poolBox: InputUTXO, fill: LDT2TFill, blockHeight: Int, minerFeeCeiling: Long,
                                   useTrueProp: Boolean, carriedTakings: Option[InputUTXO] = None): SignedTransaction = {
    val order = fill.order
    val p = LDT2TLiquidityPool(poolBox)
    // Candidate executions pay none; broadcasts pay the capped fee out of the executor fee
    val minerFee = math.min(order.terms.maxMinerFee, minerFeeCeiling)
    val takings = Math.addExact(carriedTakings.map(_.value).getOrElse(0L), fill.revenue - minerFee)
    require(takings >= UTXO.MIN_CHANGE, "the takings cannot fund an output of their own")

    val orderIn = order.box.toInputUTXO(ctx)
    require(orderIn.id.toString == order.boxId, "the order box's fields do not match its id")
    val height = math.max(blockHeight, TxBuilder.newestInput(
      Seq(poolBox, orderIn) ++ carriedTakings.toSeq ++ fill.provision.map(_.box)))

    val poolOut = poolUTXO(contracts, poolBox, fill.poolAfter).setCreationHeight(height)
    // The reward's script is the order's own constant, never anything this client chooses
    val redeemer = Contract(sigma.ast.ErgoTree.fromHex(order.terms.redeemerTree))
    val takingsOut = UTXO(CandidateCapital.collectionContract(wallet, useTrueProp), takings).setCreationHeight(height)
    val feeOut = if (minerFee > 0) Seq(UTXO.feeBox(minerFee).setCreationHeight(height)) else Seq.empty

    val (inputs, outputs, burn) = order match {
      case _: LDT2TOrder.Swap =>
        (Seq(LithosDexTransactions.poolInput(poolBox, LDHelpers.POOL_SWAP), orderIn),
          Seq(poolOut, UTXO(redeemer, fill.rewardValue, fill.rewardTokens).setCreationHeight(height), takingsOut),
          Seq.empty[Token])

      case _: LDT2TOrder.Deposit =>
        // The ownership NFT can only take the id of the pool box, the transaction's first input
        val nft = poolBox.id
        val provisionOut = UTXO(contracts.provisionGuard, LDHelpers.PROVISION_MIN, Seq(Token(contracts.provToken, 1L)),
          Seq(bigInt(p.accX), bigInt(p.accY), bytes(nft.getBytes), ErgoValue.of(fill.shares))).setCreationHeight(height)
        // EIP-4 registers so a wallet shows the NFT as the provision it owns
        val reward = UTXO(redeemer, fill.rewardValue, Token(nft, 1L) +: fill.rewardTokens)
          .withReg(0, LithosDexTransactions.utf8(LithosDexTransactions.provisionNftName(nft)))
          .withReg(1, LithosDexTransactions.utf8(s"LithosDex provision of ${fill.shares} shares in pool ${p.poolNFT}"))
          .withReg(2, LithosDexTransactions.utf8("0"))
          .setCreationHeight(height)
        (Seq(LithosDexTransactions.poolInput(poolBox, LDHelpers.POOL_DEPOSIT), orderIn),
          Seq(poolOut, provisionOut, reward, takingsOut),
          Seq.empty[Token])

      case _: LDT2TOrder.Redeem =>
        val provision = fill.provision.getOrElse(throw new IllegalStateException("a redemption needs its provision"))
        (Seq(LithosDexTransactions.poolInput(poolBox, LDHelpers.POOL_REDEEM),
          provisionInput(contracts, provision.box, LDHelpers.PROV_REDEEM), orderIn),
          Seq(poolOut, UTXO(redeemer, fill.rewardValue, fill.rewardTokens).setCreationHeight(height), takingsOut),
          Seq(Token(provision.ownerNFT, 1L)))
    }

    val unsigned = TxBuilder(ctx)
      .setInputs((inputs ++ carriedTakings.toSeq): _*)
      .setOutputs((outputs ++ feeOut): _*)
      .buildTx(0L, wallet.p2pk, burn)
    // Signing runs every script, so a transaction the contracts would refuse never leaves here
    Batcher.withNodeMinimums(ctx, wallet.sign(unsigned))
  }

  /** Moves the pool's pending fees and the accumulators they paid for into the vault. Fee-less. */
  private[lithosdex] def flush(ctx: BlockchainContext, wallet: NodeWallet, contracts: LDT2TContracts,
                               poolBox: InputUTXO, vaultBox: InputUTXO, blockHeight: Int): SignedTransaction = {
    val p = LDT2TLiquidityPool(poolBox)
    require(p.canFlush, "nothing is pending: the pool refuses a flush that moves nothing")
    val v = LDT2TFeeValue.fromBox(vaultBox, 0, blockHeight)
    require(v.vaultNFT.toString == contracts.vaultNFT.toString, s"vault ${v.vaultNFT} is not pool ${contracts.poolNFT}'s")
    val height = math.max(blockHeight, TxBuilder.newestInput(Seq(poolBox, vaultBox)))
    val poolOut = poolUTXO(contracts, poolBox, p.copy(pendingX = 0L, pendingY = 0L)).setCreationHeight(height)
    val vaultOut = UTXO(contracts.feeVault, vaultBox.value,
      Seq(Token(v.vaultNFT, 1L), Token(v.tokenX, v.balanceX + p.pendingX), Token(v.tokenY, v.balanceY + p.pendingY)),
      Seq(bigInt(p.accX), bigInt(p.accY))).setCreationHeight(height)
    val unsigned = TxBuilder(ctx)
      .setInputs(LithosDexTransactions.poolInput(poolBox, LDHelpers.POOL_FLUSH),
        LithosDexTransactions.vaultInput(vaultBox, LDHelpers.VAULT_FLUSH))
      .setOutputs(poolOut, vaultOut)
      .buildTx(0L, wallet.p2pk)
    Batcher.withNodeMinimums(ctx, wallet.sign(unsigned))
  }

  /**
   * The pool box holding `next`, with ids and fee params taken from the box being spent. Its ERG is storage
   * and never changes; each token entry is reserves plus pending fees.
   */
  private[batching] def poolUTXO(contracts: LDT2TContracts, spent: InputUTXO, next: LDT2TLiquidityPool): UTXO = {
    val p = LDT2TLiquidityPool(spent)
    require(p.poolNFT.toString == contracts.poolNFT.toString && p.provToken.toString == contracts.provToken.toString,
      s"pool ${p.poolNFT} is not the deployment of pool ${contracts.poolNFT}")
    UTXO(contracts.liquidityPool, spent.value,
      Seq(Token(p.poolNFT, 1L), Token(p.tokenX, next.reservesX + next.pendingX),
        Token(p.tokenY, next.reservesY + next.pendingY), Token(p.provToken, next.provTokensLeft)),
      Seq(ErgoValue.of(LDHelpers.LOCKED_LP - next.supply), longs(p.feeParams), longs(Array(next.pendingX, next.pendingY)),
        bigInt(next.accX), bigInt(next.accY)))
  }

  /** A provision input with its op and the token/token logic its guard executes from var 64. */
  private[lithosdex] def provisionInput(contracts: LDT2TContracts, box: InputUTXO, op: Byte): InputUTXO = {
    require(box.tokens.headOption.exists(_.id.toString == contracts.provToken.toString),
      s"provision ${box.id} is not the deployment of pool ${contracts.poolNFT}")
    DexContracts.attachCtxVars(box, Seq[(Byte, ErgoValue[_])](
      0.toByte -> ErgoValue.of(op),
      LDHelpers.PROVISION_LOGIC_VAR -> bytes(contracts.provisionLogic.valueBytes)))
  }

  private def bigInt(v: BigInt): ErgoValue[_] = ErgoValue.of(v.bigInteger)
  private def longs(v: Array[Long]): ErgoValue[_] = ErgoValue.of(Colls.fromArray(v), scalaLongType)
  private def bytes(v: Array[Byte]): ErgoValue[_] = ErgoValue.of(Colls.fromArray(v), scalaByteType)
}
