package transactions.batching.ergodex

import mutations.NodeWallet
import node.model.NodeAsset
import node.MutationConversions._
import org.ergoplatform.appkit.{BlockchainContext, SignedTransaction}
import org.ergoplatform.sdk.ErgoId
import org.slf4j.{Logger, LoggerFactory}
import transactions.candidate.BlockTxMessages.{CandidateTx, ChainFromMempool, IncludeExisting, Supersede}
import transactions.candidate.{CandidateBundle, CandidateCapital, CapitalEntry, CapitalOrigin}
import transactions.engine.execution.RollupExecution
import work.lithos.mutations.{Contract, InputUTXO, Token, TxBuilder, UTXO}

import scala.util.{Failure, Success, Try}

/**
 * Priced output balances for one order. Revenue includes the miner fee; quote is LP for deposits and X
 * for redemptions.
 */
final case class ErgoDexFill(order: ErgoDexOrder, pool: ErgoDexPool, quote: Long, revenue: Long,
                             poolAfter: ErgoDexPool, rewardValue: Long, rewardTokens: Seq[NodeAsset])

/** Signed executions in spend order, with the final unspent box holding their combined takings. */
final case class ErgoDexChain(transactions: Vector[SignedTransaction], fills: Vector[ErgoDexFill],
                              takings: InputUTXO) {

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

  /** Ranks orders by revenue, then reprices each against the preceding fill's pool balances. */
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

  /** Builds the entire run or returns None; later executions depend on earlier pool and takings outputs. */
  def build(ctx: BlockchainContext, wallet: NodeWallet, poolBox: InputUTXO,
            fills: Seq[ErgoDexFill], blockHeight: Int, minerFeeCeiling: Long,
            useTrueProp: Boolean): Option[ErgoDexChain] =
    if (fills.isEmpty) None
    else Try(chain(ctx, wallet, poolBox, fills, blockHeight, minerFeeCeiling, useTrueProp)) match {
      case Success(built) => Some(built)
      case Failure(ex) =>
        logger.warn(s"Could not build ${fills.size} ErgoDEX execution(s) against pool " +
          s"${fills.head.pool.nft.take(12)}: ${ex.getMessage}")
        None
    }

  private def chain(ctx: BlockchainContext, wallet: NodeWallet, poolBox: InputUTXO,
                    fills: Seq[ErgoDexFill], blockHeight: Int, minerFeeCeiling: Long,
                    useTrueProp: Boolean): ErgoDexChain = {
    require(fills.forall(_.pool.nft == fills.head.pool.nft), "a chain executes against one pool")
    require(poolBox.id.toString == fills.head.pool.boxId, "the chain must open on the pool it was priced against")

    // Each execution spends the previous pool and accumulates its takings.
    var pool = poolBox
    var carried = Option.empty[InputUTXO]
    var built = Vector.empty[SignedTransaction]
    fills.foreach { fill =>
      val signed = assembled(ctx, wallet, pool, fill, blockHeight, minerFeeCeiling, useTrueProp, carried)
      built :+= signed
      // The next order spends the pool this one just produced, which does not exist on chain yet,
      // and adds its takings to the same box rather than opening another.
      pool = InputUTXO(signed.getOutputsToSpend.get(0))
      carried = Some(InputUTXO(signed.getOutputsToSpend.get(2)))
    }
    ErgoDexChain(built, fills.toVector, carried.get)
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

    // Preserve pool identity, script and registers while updating reserves and LP balance.
    val poolOut = UTXO(poolBox.contract, after.reservesX,
      Seq(Token(ErgoId.create(pool.nft), 1L),
        Token(ErgoId.create(pool.lpId), after.lpSupply),
        Token(ErgoId.create(pool.yId), after.reservesY)),
      poolBox.registers).setCreationHeight(blockHeight)

    // The reward is the user's, and its proposition is the order's own constant rather than
    // anything this client chooses.
    val redeemer = Contract(sigma.ast.ErgoTree.fromBytes(order.redeemerPropBytes))
    val rewardOut = UTXO(redeemer, fill.rewardValue,
      fill.rewardTokens.map(asset => Token(ErgoId.create(asset.tokenId), asset.amount)))
      .setCreationHeight(blockHeight)

    val takingsOut = UTXO(CandidateCapital.collectionContract(wallet, useTrueProp), takings)
      .setCreationHeight(blockHeight)
    val feeOut =
      if (minerFee > 0) Seq(UTXO.feeBox(minerFee).setCreationHeight(blockHeight)) else Seq.empty

    // Verify the reported order id before spending the reconstructed input.
    val orderInput = order.boxAsInput(ctx)
    require(orderInput.id.toString == order.boxId, "the order box's fields do not match its id")
    val spent = Seq(poolBox, orderInput) ++ carriedTakings.toSeq
    val unsigned = TxBuilder(ctx)
      .setInputs(spent: _*)
      .setOutputs((Seq(poolOut, rewardOut, takingsOut) ++ feeOut): _*)
      .buildTx(0L, wallet.p2pk)
    // Signing validates the scripts and supplies the measured execution cost.
    wallet.sign(unsigned)
  }

}
