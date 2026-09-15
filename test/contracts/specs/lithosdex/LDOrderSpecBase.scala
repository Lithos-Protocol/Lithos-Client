package contracts.specs.lithosdex

import lithosdex.LDHelpers
import lithosdex.contracts.{LDOrderTerms, LDOrderContracts}
import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ErgoId
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

import java.math.BigInteger

/**
 * Shared harness for the four LithosDex order contracts, executed against the real pool stack.
 *
 *   sell / buy   IN  pool, order, funding              OUT pool, reward, takings
 *   deposit      IN  pool, order, funding              OUT pool, provision, reward, takings
 *   redeem       IN  pool, provision, order, funding   OUT pool, reward, takings   (NFT burned)
 *
 * The executor signs every execution: the pool, the order and a provision all reduce to true, so only
 * the executor's funding needs a key. The owner signs only refunds, which is how a spec shows the order
 * refusing an execution rather than someone lacking a key.
 *
 * Orders are built through `LDOrderContracts`, so every property also exercises the pinned templates.
 */
trait LDOrderSpecBase extends LithosDexSpecBase {

  import LithosDexSpecBase._

  // ─── identities ───────────────────────────────────────────────────────────

  protected val executorSecret: BigInteger = BigInteger.valueOf(9009L)

  protected def executor(ctx: BlockchainContext): ErgoProver = proverWith(ctx, executorSecret)

  protected def executorContract(ctx: BlockchainContext): Contract = contractOf(executor(ctx))

  // ─── terms ────────────────────────────────────────────────────────────────

  /** ERG an order sets aside for its own reward box. */
  protected val REWARD_ERG: Long = Parameters.MinFee
  protected val FEE: Long = 6000000L
  /** Above the MinFee every spec transaction pays, so the cap is not what decides a property. */
  protected val MAX_MINER_FEE: Long = 2000000L

  protected def terms(ctx: BlockchainContext,
                      fee: Long = FEE,
                      maxMinerFee: Long = MAX_MINER_FEE,
                      poolNFTId: ErgoId = poolNFT): LDOrderTerms =
    LDOrderTerms(user(ctx).getAddress.getPublicKey, poolNFTId, fee, maxMinerFee)

  // ─── boxes ────────────────────────────────────────────────────────────────

  protected def rewardUTXO(ctx: BlockchainContext, value: Long, tokens: Seq[Token] = Seq.empty[Token]): UTXO =
    UTXO(userContract(ctx), value, tokens)

  /** The executor's cut, plus any tokens a property routes to it. */
  protected def takingsUTXO(ctx: BlockchainContext, value: Long, tokens: Seq[Token] = Seq.empty[Token]): UTXO =
    UTXO(executorContract(ctx), value, tokens)

  protected def executorFunding(ctx: BlockchainContext, index: Int, tokens: Seq[Token] = Seq.empty[Token]): InputUTXO =
    inputAt(UTXO(executorContract(ctx), SLACK, tokens), ctx, index)

  /** A box at input 0 carrying the pool NFT under a script that accepts anything, with any operation. */
  protected def standInPoolInput(ctx: BlockchainContext, s: PoolState, op: Option[Byte]): InputUTXO = {
    val in = inputAt(poolUTXO(ctx, s, contract = Contract.SIGMA_TRUE), ctx, 0)
    op.fold(in)(o => in.withCtxVar(0.toByte, ErgoValue.of(o)))
  }

  // ─── executions ───────────────────────────────────────────────────────────

  /** One execution. Negatives replace a single input or output and rebuild. */
  protected case class Execution(ctx: BlockchainContext,
                                 before: PoolState,
                                 after: PoolState,
                                 inputs: Seq[InputUTXO],
                                 outputs: Seq[UTXO],
                                 burn: Seq[Token] = Seq.empty[Token]) {
    def input(i: Int, in: InputUTXO): Execution = copy(inputs = inputs.updated(i, in))
    def output(i: Int, out: UTXO): Execution = copy(outputs = outputs.updated(i, out))
    def tx: UnsignedTransaction =
      build(ctx, inputs, outputs, executor(ctx).getAddress, Seq.empty[InputUTXO], burn)
  }

  /** Whether the pool is the real contract or a stand-in, and which operation it carries. */
  protected case class PoolSetup(standIn: Boolean = false, op: Option[Byte] = null)

  private def poolSide(ctx: BlockchainContext, s: PoolState, next: PoolState, ownOp: Byte,
                       setup: PoolSetup): (InputUTXO, UTXO) = {
    val op = if (setup.op == null) Some(ownOp) else setup.op
    if (setup.standIn)
      (standInPoolInput(ctx, s, op), poolUTXO(ctx, next, contract = Contract.SIGMA_TRUE))
    else {
      val in = inputAt(poolUTXO(ctx, s), ctx, 0)
      (op.fold(in)(o => in.withCtxVar(0.toByte, ErgoValue.of(o))), poolUTXO(ctx, next))
    }
  }

  /**
   * The smallest output the relaxed price check accepts: `ceil(N / D) - 1`, one unit under the most the
   * pool can release whenever that most is not exact.
   */
  protected def fairFloor(reserveIn: Long, reserveOut: Long, amountIn: Long, sideFee: Long): Long = {
    val traded = BigInt(amountIn) - BigInt(amountIn) * sideFee / FEE_DENOM
    val n = BigInt(reserveOut) * traded * FEE_PARAMS(0)
    val d = BigInt(reserveIn) * FEE_DENOM + traded * FEE_PARAMS(0)
    ((n + d - 1) / d - 1).toLong
  }

  // ═══ SWAP SELL ════════════════════════════════════════════════════════════

  /** Outputs: 0 pool, 1 reward, 2 takings. The owner receives exactly the pool's output. */
  protected def sellScenario(ctx: BlockchainContext,
                             base: Long = Parameters.OneErg,
                             fee: Long = FEE,
                             minQuote: Long = -1L,
                             maxMinerFee: Long = MAX_MINER_FEE,
                             state: PoolState = traded,
                             setup: PoolSetup = PoolSetup()): Execution = {
    val q = state.view.simSwap(base, ergIn = true)
    val next = afterSwap(state, base, ergIn = true)
    val order = LDOrderContracts.swapSell(terms(ctx, fee, maxMinerFee), base,
      if (minQuote < 0) q.amountOut else minQuote)
    val (poolIn, poolOut) = poolSide(ctx, state, next, LDHelpers.POOL_SWAP, setup)
    Execution(ctx, state, next,
      Seq(poolIn, inputAt(UTXO(order, base + fee + REWARD_ERG), ctx, 1), executorFunding(ctx, 2)),
      Seq(poolOut, rewardUTXO(ctx, REWARD_ERG, Seq(Token(tokenY, q.amountOut))), takingsUTXO(ctx, fee)))
  }

  // ═══ SWAP BUY ═════════════════════════════════════════════════════════════

  /** Outputs: 0 pool, 1 reward, 2 takings. The owner receives exactly the pool's output less the fee. */
  protected def buyScenario(ctx: BlockchainContext,
                            base: Long = 2000L * 1000000L,
                            fee: Long = FEE,
                            minQuote: Long = -1L,
                            maxMinerFee: Long = MAX_MINER_FEE,
                            state: PoolState = traded,
                            orderToken: ErgoId = tokenY,
                            setup: PoolSetup = PoolSetup()): Execution = {
    val q = state.view.simSwap(base, ergIn = false)
    val next = afterSwap(state, base, ergIn = false)
    val net = q.amountOut - fee
    val order = LDOrderContracts.swapBuy(terms(ctx, fee, maxMinerFee), if (minQuote < 0) net else minQuote)
    val (poolIn, poolOut) = poolSide(ctx, state, next, LDHelpers.POOL_SWAP, setup)
    // An order holding some other token needs the executor to bring the pool its tokens, and takes the
    // stray token as takings, so that nothing but the order's own check can refuse it.
    val stray = orderToken != tokenY
    Execution(ctx, state, next,
      Seq(poolIn,
        inputAt(UTXO(order, REWARD_ERG, Seq(Token(orderToken, base))), ctx, 1),
        executorFunding(ctx, 2, if (stray) Seq(Token(tokenY, base)) else Seq.empty[Token])),
      Seq(poolOut, rewardUTXO(ctx, REWARD_ERG + net),
        takingsUTXO(ctx, fee, if (stray) Seq(Token(orderToken, base)) else Seq.empty[Token])))
  }

  // ═══ DEPOSIT ══════════════════════════════════════════════════════════════

  /** What the order's own arithmetic says a deposit is worth, and what must come back. */
  protected case class DepositTerms(shares: BigInt, excessX: BigInt, excessY: BigInt)

  protected def depositTerms(s: PoolState, depositX: Long, offeredY: Long): DepositTerms = {
    val minByX = BigInt(depositX) * s.supply / s.reservesX
    val minByY = BigInt(offeredY) * s.supply / s.reservesY
    DepositTerms(minByX min minByY,
      if (minByX > minByY) (minByX - minByY) * s.reservesX / s.supply else BigInt(0),
      if (minByY > minByX) (minByY - minByX) * s.reservesY / s.supply else BigInt(0))
  }

  /** Tokens matching `depositX` at the pool's ratio, scaled by `ratio` to put either side in excess. */
  protected def offeredYFor(s: PoolState, depositX: Long, ratio: Double): Long =
    (BigDecimal(BigInt(depositX) * s.reservesY / s.reservesX) * ratio).toLong

  /**
   * Outputs: 0 pool, 1 provision, 2 reward, 3 takings. The executor hands back exactly the excess the order
   * computes, which is the least it may, and the pool issues exactly the shares the order is owed.
   * `minShares` defaults to exactly those shares; `offeredY` defaults to `ratio` times the pool's own.
   */
  protected def depositOrderScenario(ctx: BlockchainContext,
                                     depositX: Long = Parameters.OneErg,
                                     ratio: Double = 1.2,
                                     fee: Long = FEE,
                                     maxMinerFee: Long = MAX_MINER_FEE,
                                     state: PoolState = traded,
                                     orderToken: ErgoId = tokenY,
                                     minShares: Long = -1L,
                                     offered: Long = -1L,
                                     setup: PoolSetup = PoolSetup()): Execution = {
    val offeredY = if (offered < 0) offeredYFor(state, depositX, ratio) else offered
    val t = depositTerms(state, depositX, offeredY)
    val shares = t.shares.toLong
    val next = stateOf(state.view.afterDeposit(depositX - t.excessX.toLong, offeredY - t.excessY.toLong, shares))
    val order = LDOrderContracts.deposit(terms(ctx, fee, maxMinerFee), depositX,
      if (minShares < 0) shares else minShares)
    val (poolIn, poolOut) = poolSide(ctx, state, next, LDHelpers.POOL_DEPOSIT, setup)
    val nft = poolIn.id
    val stray = orderToken != tokenY
    Execution(ctx, state, next,
      Seq(poolIn,
        inputAt(UTXO(order, depositX + PROVISION_MIN + REWARD_ERG + fee, Seq(Token(orderToken, offeredY))), ctx, 1),
        executorFunding(ctx, 2, if (stray) Seq(Token(tokenY, offeredY)) else Seq.empty[Token])),
      Seq(poolOut,
        provisionUTXO(ctx, state.accX, state.accY, nft, shares),
        rewardUTXO(ctx, REWARD_ERG + t.excessX.toLong,
          Seq(Token(nft, 1L)) ++ (if (t.excessY > 0) Seq(Token(tokenY, t.excessY.toLong)) else Seq.empty[Token])),
        takingsUTXO(ctx, fee, if (stray) Seq(Token(orderToken, offeredY)) else Seq.empty[Token])))
  }

  // ═══ REDEEM ═══════════════════════════════════════════════════════════════

  /** Outputs: 0 pool, 1 reward, 2 takings. The owner receives exactly the pool's release less the fee. */
  protected def redeemOrderScenario(ctx: BlockchainContext,
                                    shares: Long = 10000000000L,
                                    fee: Long = FEE,
                                    maxMinerFee: Long = MAX_MINER_FEE,
                                    state: PoolState = traded,
                                    setup: PoolSetup = PoolSetup()): Execution = {
    val prov = provisionOf(ctx, shares, ownerId = ownerNFT)
    val q = state.view.simRedeem(shares)
    val next = stateOf(state.view.afterRedeem(q))
    val order = LDOrderContracts.redeem(terms(ctx, fee, maxMinerFee))
    val (poolIn, poolOut) = poolSide(ctx, state, next, LDHelpers.POOL_REDEEM, setup)
    Execution(ctx, state, next,
      Seq(poolIn,
        provisionInput(ctx, prov.utxo, LDHelpers.PROV_REDEEM, 1),
        inputAt(UTXO(order, REWARD_ERG + fee, Seq(Token(ownerNFT, 1L))), ctx, 2),
        executorFunding(ctx, 3)),
      Seq(poolOut,
        rewardUTXO(ctx, REWARD_ERG + prov.utxo.value + q.amountX, Seq(Token(tokenY, q.amountY))),
        takingsUTXO(ctx, fee)),
      burn = Seq(Token(ownerNFT, 1L)))
  }

  // ═══ REFUND ═══════════════════════════════════════════════════════════════

  /** The owner spends an order alone, returning its contents to themselves. */
  protected def refundTx(ctx: BlockchainContext, order: UTXO): UnsignedTransaction = {
    val orderIn = inputAt(order, ctx, 0)
    val fund = inputAt(UTXO(userContract(ctx), SLACK), ctx, 5)
    build(ctx, Seq(orderIn, fund), Seq(UTXO(userContract(ctx), order.value, order.tokens)), user(ctx).getAddress)
  }
}
