package transactions.batching.lithosdex

import lithosdex.contracts.LDContracts
import lithosdex.states.LDFeeValue
import lithosdex.{LDHelpers, LDLiquidityPool}
import mutations.NodeWallet
import node.MutationConversions._
import org.ergoplatform.appkit.{BlockchainContext, SignedTransaction}
import org.slf4j.{Logger, LoggerFactory}
import state.synchronization.CompleteMempool
import transactions.batching.{BatchRun, Batcher}
import transactions.candidate.BlockTxMessages.{CandidateTx, ChainFromMempool, IncludeExisting, Supersede}
import transactions.candidate.{CandidateBundle, CandidateCapital, CapitalEntry, CapitalOrigin}
import transactions.engine.execution.RollupExecution
import work.lithos.mutations.{Contract, InputUTXO, Token, TxBuilder, UTXO}

import scala.collection.JavaConverters._
import scala.concurrent.duration.Deadline
import scala.util.{Failure, Success, Try}

/**
 * One order priced against one pool state.
 *
 * @param revenue     the order's executor fee, miner fee included
 * @param rewardValue nanoERG in the owner's reward box
 * @param rewardY     pool tokens in the owner's reward box: a sell's output, a deposit's token change, or
 *                    a redemption's share
 * @param shares      shares a deposit issues; 0 for every other kind
 * @param provision   the provision a redemption closes
 */
final case class LithosDexFill(order: LithosDexOrder,
                               pool: LDLiquidityPool,
                               poolAfter: LDLiquidityPool,
                               revenue: Long,
                               rewardValue: Long,
                               rewardY: Long,
                               shares: Long = 0L,
                               provision: Option[LDBoxes.Provision] = None)

/**
 * What one pass over a pool's orders produced.
 *
 * @param chain       the executions that signed, or None when none did
 * @param placements  the unconfirmed placements those executions spend, in spend order
 * @param unbuildable box ids of orders that priced but could not be built, in the order they were tried
 * @param cutShort    whether the pass stopped at its deadline or its unbuildable limit with orders left
 */
final case class LithosDexRun(chain: Option[LithosDexChain],
                              placements: Vector[CompleteMempool.MempoolTx],
                              unbuildable: Vector[String],
                              cutShort: Boolean)

/**
 * Signed executions in spend order, an optional closing flush, and the unspent box holding their takings.
 *
 * @param poolIds the pool box each execution spends, in order
 */
final case class LithosDexChain(transactions: Vector[SignedTransaction],
                                fills: Vector[LithosDexFill],
                                poolIds: Vector[String],
                                takings: InputUTXO,
                                flush: Option[SignedTransaction] = None) extends BatchRun {

  override def orderIdAt(index: Int): String = fills(index).order.boxId

  override def poolIdAt(index: Int): String = poolIds(index)

  override def kind: String = LithosDexExecution.Kind

  /** The executions, then the flush when there is one. */
  def members: Vector[CandidateTx] =
    transactions.map(LithosDexExecution.member(_, LithosDexExecution.Kind)) ++
      flush.map(LithosDexExecution.member(_, LithosDexExecution.FlushKind))

  /** Carries placements first, supersedes competitors, and credits only the final takings box. */
  def bundle(competitors: Set[String], placements: Vector[CandidateTx] = Vector.empty): CandidateBundle =
    CandidateBundle(placements ++ members,
      placements.flatMap(placement => Seq(ChainFromMempool(placement.id), IncludeExisting(placement.id))) ++
        (if (competitors.isEmpty) Seq.empty else Seq(Supersede(competitors))),
      Seq(CapitalEntry(CapitalOrigin.ExecutorReward, takings, transactions.last.getId)))
}

/**
 * Prices and signs LithosDex order executions, one order per transaction, chained through the pool.
 *
 * {{{
 *   swap      IN  pool, order, [takings]              OUT pool, reward, takings, [fee]
 *   deposit   IN  pool, order, [takings]              OUT pool, provision, reward, takings, [fee]
 *   redeem    IN  pool, provision, order, [takings]   OUT pool, reward, takings, [fee]   (NFT burned)
 *   flush     IN  pool, vault                         OUT pool, vault
 * }}}
 */
object LithosDexExecution {

  private val logger: Logger = LoggerFactory.getLogger("LithosDexExecution")

  /** Named so a refused candidate says which builder produced the offending transaction. */
  final val Kind = "lithosdex-batch"
  final val FlushKind = "lithosdex-flush"

  /** A redemption with no provision to close. */
  type Provisions = String => Option[LDBoxes.Provision]

  final val NoProvisions: Provisions = _ => None

  /**
   * The fill `order` gets against `pool`, or None when it cannot be executed at a profit: the pool
   * refuses it, the owner's own terms refuse it, the fee is under `minRevenue` or does not cover the
   * miner fee, the reward box would hold under `MIN_CHANGE`, or the first fill's takings could not fund a
   * box. The node's minimum for the reward box's actual size is checked when it is built.
   */
  def price(order: LithosDexOrder, pool: LDLiquidityPool, minRevenue: Long, provisions: Provisions,
            fundsItsOwnBox: Boolean = true, minerFeeCeiling: Long = 0L): Option[LithosDexFill] =
    if (order.poolNft != pool.poolNFT.toString) None
    else Try(movement(order, pool, provisions)).toOption.flatten.filter { fill =>
      val net = fill.revenue - math.min(order.terms.maxMinerFee, math.max(0L, minerFeeCeiling))
      fill.revenue >= minRevenue && net > 0 && (!fundsItsOwnBox || net >= UTXO.MIN_CHANGE) &&
        fill.rewardValue >= UTXO.MIN_CHANGE &&
        fill.poolAfter.boxValue > LDHelpers.MIN_RENT && fill.poolAfter.reservesX > 0 && fill.poolAfter.reservesY > 0
    }

  /**
   * How an offer of `offeredX` nanoERG and `offeredY` tokens meets `pool`: shares from the scarcer side,
   * and whatever the other side cannot match returned, truncated as the deposit order truncates.
   *
   * @param unlocked the most shares the pool grants for what it takes; the order's rounding keeps a
   *                 valid fill at or under it
   */
  final case class DepositSplit(shares: BigInt, takenX: BigInt, takenY: BigInt,
                                excessX: BigInt, excessY: BigInt, unlocked: BigInt)

  def depositSplit(pool: LDLiquidityPool, offeredX: Long, offeredY: Long): DepositSplit = {
    val supply = BigInt(pool.supply)
    val byX = BigInt(offeredX) * supply / pool.reservesX
    val byY = BigInt(offeredY) * supply / pool.reservesY
    val excessX = if (byX > byY) (byX - byY) * pool.reservesX / supply else BigInt(0)
    val excessY = if (byY > byX) (byY - byX) * pool.reservesY / supply else BigInt(0)
    val takenX = BigInt(offeredX) - excessX
    val takenY = BigInt(offeredY) - excessY
    DepositSplit(byX min byY, takenX, takenY, excessX, excessY,
      (takenX * supply / pool.reservesX) min (takenY * supply / pool.reservesY))
  }

  /** The arithmetic each order contract checks, applied through the pool's own quoting functions. */
  private def movement(order: LithosDexOrder, pool: LDLiquidityPool, provisions: Provisions): Option[LithosDexFill] = {
    val fee = order.terms.executorFee
    order match {
      case sell: LithosDexOrder.SwapSell =>
        val q = pool.simSwap(sell.baseAmount, ergIn = true)
        val rewardValue = BigInt(sell.box.value) - sell.baseAmount - fee
        if (!q.isExecutable || q.amountOut < sell.minQuote || !rewardValue.isValidLong) None
        else Some(LithosDexFill(sell, pool, pool.afterSwap(q), fee, rewardValue.toLong, q.amountOut))

      case buy: LithosDexOrder.SwapBuy =>
        if (buy.tokenId != pool.tokenY.toString) None
        else {
          val q = pool.simSwap(buy.amount, ergIn = false)
          val gain = q.amountOut - fee
          if (!q.isExecutable || gain < buy.minQuote) None
          else Some(LithosDexFill(buy, pool, pool.afterSwap(q), fee, Math.addExact(buy.box.value, gain), 0L))
        }

      case deposit: LithosDexOrder.Deposit =>
        if (deposit.tokenId != pool.tokenY.toString || !pool.canDeposit) None
        else {
          val split = depositSplit(pool, deposit.depositX, deposit.amount)
          import split._
          val rewardValue = BigInt(deposit.box.value) - takenX - LDHelpers.PROVISION_MIN - fee
          if (shares <= 0 || shares < deposit.minShares || shares > unlocked || !shares.isValidLong ||
            !rewardValue.isValidLong) None
          else Some(LithosDexFill(deposit, pool, pool.afterDeposit(takenX.toLong, takenY.toLong, shares.toLong), fee,
            rewardValue.toLong, excessY.toLong, shares = shares.toLong))
        }

      case redeem: LithosDexOrder.Redeem =>
        provisions(redeem.ownerNft).filter(_.ownerNFT.toString == redeem.ownerNft).flatMap { provision =>
          val q = pool.simRedeem(provision.shares)
          val rewardValue = BigInt(redeem.box.value) + provision.value + q.amountX - fee
          if (!q.withinMinSupply || !rewardValue.isValidLong) None
          else Some(LithosDexFill(redeem, pool, pool.afterRedeem(q), fee, rewardValue.toLong, q.amountY,
            provision = Some(provision)))
        }
    }
  }

  /**
   * Signs the highest-fee orders against `poolBox` one at a time, each priced against the pool box the last
   * execution left. An order that prices but cannot be signed is named and passed over, and the next is
   * priced against the same pool box. Stops at `limit` transactions, placements included, at `deadline`,
   * or after `unbuildableLimits.perRun` unbuildable orders. Once `unbuildableLimits.perTx` orders one
   * transaction created have failed, that transaction's other orders are passed over untried.
   *
   * @param contracts      the deployment `poolBox` belongs to, which every output is built under
   * @param placementOf    the unconfirmed placements an order's box needs carried ahead of it
   * @param alreadyCarried placements another run in the same block carries, which take no slot here
   */
  def run(ctx: BlockchainContext, wallet: NodeWallet, contracts: LDContracts, poolBox: InputUTXO,
          orders: Seq[LithosDexOrder], limit: Int,
          minRevenue: Long, provisions: Provisions, blockHeight: Int, minerFeeCeiling: Long, useTrueProp: Boolean,
          deadline: Deadline,
          placementOf: LithosDexOrder => Vector[CompleteMempool.MempoolTx] = _ => Vector.empty,
          unbuildableLimits: Batcher.UnbuildableLimits = Batcher.UnbuildableLimits.Default,
          alreadyCarried: Set[String] = Set.empty): LithosDexRun = {
    val opening = LDLiquidityPool(poolBox)
    val ranked = orders
      .flatMap(order => price(order, opening, minRevenue, provisions, fundsItsOwnBox = false, minerFeeCeiling)
        .map(fill => order -> fill.revenue))
      .sortBy { case (order, revenue) => (-revenue, order.boxId) }
      .map(_._1)

    var pool = poolBox
    var carried = Option.empty[InputUTXO]
    var built = Vector.empty[SignedTransaction]
    var fills = Vector.empty[LithosDexFill]
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
      // Only the opening fill has to fund a takings box by itself; the rest add to the one it made. An order
      // whose transaction has already failed perTx times is passed over untried
      val priced =
        if (failedByTx.getOrElse(createdBy, 0) >= unbuildableLimits.perTx) None
        else price(order, LDLiquidityPool(pool), minRevenue, provisions, fundsItsOwnBox = built.isEmpty, minerFeeCeiling)
      priced.foreach { fill =>
        val added = placementOf(order).filterNot(tx => placements.exists(_.id == tx.id))
        if (slotsUsed(placements ++ added) < limit) Try {
          val signed = assembled(ctx, wallet, contracts, pool, fill, blockHeight, minerFeeCeiling, useTrueProp, carried)
          val outputs = signed.getOutputsToSpend
          (signed, InputUTXO(outputs.get(0)), InputUTXO(outputs.get(takingsIndex(order))))
        } match {
          case Success((signed, nextPool, takings)) =>
            built :+= signed
            fills :+= fill
            poolIds :+= pool.id.toString
            placements ++= added
            pool = nextPool
            carried = Some(takings)
          case Failure(ex) =>
            logger.warn(s"Could not build LithosDEX order ${order.boxId} against pool ${pool.id}, " +
              s"passing over it: ${ex.getMessage}")
            unbuildable :+= order.boxId
            failedByTx = failedByTx.updated(createdBy, failedByTx.getOrElse(createdBy, 0) + 1)
        }
      }
    }
    LithosDexRun(carried.map(LithosDexChain(built, fills, poolIds, _)), placements, unbuildable,
      cutShort = remaining.hasNext && !full)
  }

  /**
   * `chain` closed with a flush of the pending fees its last pool box holds, or `chain` unchanged when
   * nothing is pending or the flush cannot be built. A flush is optional, so failing one never costs the run.
   */
  def withFlush(ctx: BlockchainContext, wallet: NodeWallet, contracts: LDContracts, chain: LithosDexChain,
                vaultBox: InputUTXO, blockHeight: Int): LithosDexChain = {
    val poolBox = InputUTXO(chain.transactions.last.getOutputsToSpend.get(0))
    if (!LDLiquidityPool(poolBox).canFlush) chain
    else Try(flush(ctx, wallet, contracts, poolBox, vaultBox, blockHeight)) match {
      case Success(signed) => chain.copy(flush = Some(signed))
      case Failure(ex) =>
        logger.warn(s"Could not close a LithosDEX run with a flush, offering it without one: ${ex.getMessage}")
        chain
    }
  }

  /** Where each kind puts the takings: after the pool, a deposit's provision, and the owner's reward. */
  private def takingsIndex(order: LithosDexOrder): Int = order match {
    case _: LithosDexOrder.Deposit => 3
    case _ => 2
  }

  /** One execution in the positions its order contract reads, followed by an optional miner fee. */
  private[lithosdex] def assembled(ctx: BlockchainContext, wallet: NodeWallet, contracts: LDContracts,
                                   poolBox: InputUTXO, fill: LithosDexFill,
                                   blockHeight: Int, minerFeeCeiling: Long, useTrueProp: Boolean,
                                   carriedTakings: Option[InputUTXO] = None): SignedTransaction = {
    val order = fill.order
    val p = LDLiquidityPool(poolBox)
    // Candidate executions pay none; broadcasts pay the capped fee out of the executor fee
    val minerFee = math.min(order.terms.maxMinerFee, minerFeeCeiling)
    val takings = Math.addExact(carriedTakings.map(_.value).getOrElse(0L), fill.revenue - minerFee)
    require(takings >= UTXO.MIN_CHANGE, "the takings cannot fund an output of their own")

    val orderIn = order.box.toInputUTXO(ctx)
    require(orderIn.id.toString == order.boxId, "the order box's fields do not match its id")

    // The block this execution is built for, unless a box it chains onto was itself built for a later
    // one: consensus refuses an output created below the newest input.
    val height = math.max(blockHeight, TxBuilder.newestInput(
      Seq(poolBox, orderIn) ++ carriedTakings.toSeq ++ fill.provision.map(_.box)))

    val poolOut = LithosDexTransactions.poolUTXO(contracts, p, fill.poolAfter).setCreationHeight(height)
    // The reward's script is the order's own constant, never anything this client chooses
    val redeemer = Contract(sigma.ast.ErgoTree.fromHex(order.terms.redeemerTree))
    val takingsOut = UTXO(CandidateCapital.collectionContract(wallet, useTrueProp), takings)
      .setCreationHeight(height)
    val feeOut = if (minerFee > 0) Seq(UTXO.feeBox(minerFee).setCreationHeight(height)) else Seq.empty

    def tokensY(amount: Long): Seq[Token] = if (amount > 0) Seq(Token(p.tokenY, amount)) else Seq.empty

    val (inputs, outputs, burn) = order match {
      case _: LithosDexOrder.SwapSell | _: LithosDexOrder.SwapBuy =>
        (Seq(LithosDexTransactions.poolInput(poolBox, LDHelpers.POOL_SWAP), orderIn),
          Seq(poolOut, UTXO(redeemer, fill.rewardValue, tokensY(fill.rewardY)).setCreationHeight(height), takingsOut),
          Seq.empty[Token])

      case _: LithosDexOrder.Deposit =>
        // The ownership NFT can only take the id of the pool box, the transaction's first input
        val nft = poolBox.id
        val provisionOut = LithosDexTransactions.provisionUTXO(contracts, p.provToken, p.accX, p.accY, nft, fill.shares,
          LDHelpers.PROVISION_MIN).setCreationHeight(height)
        val takenX = fill.poolAfter.reservesX - p.reservesX
        val takenY = fill.poolAfter.reservesY - p.reservesY
        // EIP-4 registers so a wallet shows the NFT as the provision it owns
        val reward = UTXO(redeemer, fill.rewardValue, Token(nft, 1L) +: tokensY(fill.rewardY))
          .withReg(0, LithosDexTransactions.utf8(LithosDexTransactions.provisionNftName(nft)))
          .withReg(1, LithosDexTransactions.utf8(LithosDexTransactions.provisionNftDescription(ctx, p, nft,
            p.accX, p.accY, fill.shares, takenX, takenY)))
          .withReg(2, LithosDexTransactions.utf8("0"))
          .setCreationHeight(height)
        (Seq(LithosDexTransactions.poolInput(poolBox, LDHelpers.POOL_DEPOSIT), orderIn),
          Seq(poolOut, provisionOut, reward, takingsOut),
          Seq.empty[Token])

      case _: LithosDexOrder.Redeem =>
        val provision = fill.provision.getOrElse(throw new IllegalStateException("a redemption needs its provision"))
        (Seq(LithosDexTransactions.poolInput(poolBox, LDHelpers.POOL_REDEEM),
          LithosDexTransactions.provisionInput(contracts, provision.box, LDHelpers.PROV_REDEEM), orderIn),
          Seq(poolOut, UTXO(redeemer, fill.rewardValue, tokensY(fill.rewardY)).setCreationHeight(height), takingsOut),
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
  private[lithosdex] def flush(ctx: BlockchainContext, wallet: NodeWallet, contracts: LDContracts, poolBox: InputUTXO,
                               vaultBox: InputUTXO,
                               blockHeight: Int): SignedTransaction = {
    val p = LDLiquidityPool(poolBox)
    require(p.canFlush, "nothing is pending: the pool refuses a flush that moves nothing")
    val v = LDFeeValue.fromBox(vaultBox, 0, blockHeight)
    // A flush closes a chain, so the pool it spends is normally the last execution's own output
    val height = math.max(blockHeight, TxBuilder.newestInput(Seq(poolBox, vaultBox)))
    val poolOut = LithosDexTransactions.poolUTXO(contracts, p, p.reservesX, p.reservesY, 0L, 0L, p.supply,
      p.provTokensLeft, p.accX, p.accY).setCreationHeight(height)
    val vaultOut = LithosDexTransactions.vaultUTXO(contracts, v, Some(p.tokenY), v.balanceX + p.pendingX,
      v.balanceY + p.pendingY, p.accX, p.accY).setCreationHeight(height)
    val unsigned = TxBuilder(ctx)
      .setInputs(LithosDexTransactions.poolInput(poolBox, LDHelpers.POOL_FLUSH),
        LithosDexTransactions.vaultInput(vaultBox, LDHelpers.VAULT_FLUSH))
      .setOutputs(poolOut, vaultOut)
      .buildTx(0L, wallet.p2pk)
    Batcher.withNodeMinimums(ctx, wallet.sign(unsigned))
  }

  private[lithosdex] def member(signed: SignedTransaction, kind: String): CandidateTx =
    CandidateTx(signed.getId, signed.toJson(false, false), kind,
      RollupExecution.signedInputIds(signed), RollupExecution.signedSizeBytes(signed),
      signed.getCost.toLong, RollupExecution.signedLeaf(signed))
}
