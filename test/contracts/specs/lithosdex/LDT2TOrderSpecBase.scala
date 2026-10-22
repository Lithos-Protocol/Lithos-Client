package contracts.specs.lithosdex

import lithosdex.LDHelpers
import lithosdex.contracts.{LDOrderContracts, LDOrderTerms}
import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ErgoId
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

import java.math.BigInteger

/**
 * Shared harness for the three token/token order contracts, executed against the real pool stack.
 *
 *   swap      IN  pool, order, funding              OUT pool, reward, takings
 *   deposit   IN  pool, order, funding              OUT pool, provision, reward, takings
 *   redeem    IN  pool, provision, order, funding   OUT pool, reward, takings   (NFT burned)
 *
 * The executor signs every execution, so only the owner's refunds need the owner's key. Orders are built
 * through `LDOrderContracts`, so every property also exercises the pinned templates.
 */
trait LDT2TOrderSpecBase extends LDT2TSpecBase {

  import LDT2TSpecBase._

  // ─── identities and terms ─────────────────────────────────────────────────

  protected val executorSecret: BigInteger = BigInteger.valueOf(9009L)

  protected def executor(ctx: BlockchainContext): ErgoProver = proverWith(ctx, executorSecret)

  protected def executorContract(ctx: BlockchainContext): Contract = contractOf(executor(ctx))

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

  protected def takingsUTXO(ctx: BlockchainContext, value: Long, tokens: Seq[Token] = Seq.empty[Token]): UTXO =
    UTXO(executorContract(ctx), value, tokens)

  protected def executorFunding(ctx: BlockchainContext, index: Int, tokens: Seq[Token] = Seq.empty[Token]): InputUTXO =
    inputAt(UTXO(executorContract(ctx), SLACK, tokens), ctx, index)

  private def nonZero(id: ErgoId, amount: BigInt): Seq[Token] =
    if (amount > 0) Seq(Token(id, amount.toLong)) else Seq.empty[Token]

  // ─── executions ───────────────────────────────────────────────────────────

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

  /** Whether the pool is the real contract or a stand-in under `sigmaProp(true)`, and its operation. */
  protected case class PoolSetup(standIn: Boolean = false, op: Option[Byte] = null)

  private def poolSide(ctx: BlockchainContext, s: PoolState, next: PoolState, ownOp: Byte,
                       setup: PoolSetup): (InputUTXO, UTXO) = {
    val op = if (setup.op == null) Some(ownOp) else setup.op
    val contract = if (setup.standIn) Contract.SIGMA_TRUE else poolContract
    val in = inputAt(poolUTXO(s, contract = contract), ctx, 0)
    (op.fold(in)(o => in.withCtxVar(0.toByte, ErgoValue.of(o))), poolUTXO(next, contract = contract))
  }

  /** The smallest output the relaxed price check accepts: `ceil(N / D) - 1`. */
  protected def fairFloor(reserveIn: Long, reserveOut: Long, amountIn: Long, sideFee: Long): Long = {
    val traded = BigInt(amountIn) - BigInt(amountIn) * sideFee / FEE_DENOM
    val n = BigInt(reserveOut) * traded * FEE_PARAMS(0)
    val d = BigInt(reserveIn) * FEE_DENOM + traded * FEE_PARAMS(0)
    ((n + d - 1) / d - 1).toLong
  }

  // ═══ SWAP ═════════════════════════════════════════════════════════════════

  /** Outputs: 0 pool, 1 reward, 2 takings. The owner receives exactly the pool's output. */
  protected def swapOrderScenario(ctx: BlockchainContext,
                                  base: Long = 1000L * 1000000L,
                                  xIn: Boolean = true,
                                  fee: Long = FEE,
                                  minQuote: Long = -1L,
                                  maxMinerFee: Long = MAX_MINER_FEE,
                                  state: PoolState = traded,
                                  orderToken: ErgoId = null,
                                  setup: PoolSetup = PoolSetup()): Execution = {
    val q = state.view.simSwap(base, xIn)
    val next = afterSwap(state, base, xIn)
    val sold = if (xIn) tokenX else tokenY
    val quote = if (xIn) tokenY else tokenX
    val held = if (orderToken == null) sold else orderToken
    val order = LDOrderContracts.t2tSwap(terms(ctx, fee, maxMinerFee), if (minQuote < 0) q.amountOut else minQuote)
    val (poolIn, poolOut) = poolSide(ctx, state, next, LDHelpers.POOL_SWAP, setup)
    // An order holding some other token needs the executor to bring the pool its tokens, and takes the
    // stray token as takings, so that nothing but the order's own check can refuse it
    val stray = held != sold
    Execution(ctx, state, next,
      Seq(poolIn,
        inputAt(UTXO(order, REWARD_ERG + fee, Seq(Token(held, base))), ctx, 1),
        executorFunding(ctx, 2, if (stray) Seq(Token(sold, base)) else Seq.empty[Token])),
      Seq(poolOut, rewardUTXO(ctx, REWARD_ERG, Seq(Token(quote, q.amountOut))),
        takingsUTXO(ctx, fee, if (stray) Seq(Token(held, base)) else Seq.empty[Token])))
  }

  // ═══ DEPOSIT ══════════════════════════════════════════════════════════════

  /** What the order's own arithmetic says a deposit is worth, and what must come back. */
  protected case class DepositTerms(shares: BigInt, excessX: BigInt, excessY: BigInt)

  protected def depositTerms(s: PoolState, offeredX: Long, offeredY: Long): DepositTerms = {
    val minByX = BigInt(offeredX) * s.supply / s.reservesX
    val minByY = BigInt(offeredY) * s.supply / s.reservesY
    DepositTerms(minByX min minByY,
      if (minByX > minByY) (minByX - minByY) * s.reservesX / s.supply else BigInt(0),
      if (minByY > minByX) (minByY - minByX) * s.reservesY / s.supply else BigInt(0))
  }

  /** Y matching `offeredX` at the pool's ratio, scaled by `ratio` to put either side in excess. */
  protected def offeredYFor(s: PoolState, offeredX: Long, ratio: Double): Long =
    (BigDecimal(BigInt(offeredX) * s.reservesY / s.reservesX) * ratio).toLong

  /**
   * Outputs: 0 pool, 1 provision, 2 reward, 3 takings. The executor hands back exactly the excess the
   * order computes, and the pool issues exactly the shares the order is owed. `swapped` places the
   * order's tokens Y first.
   */
  protected def depositOrderScenario(ctx: BlockchainContext,
                                     offeredX: Long = 500L * 1000000L,
                                     ratio: Double = 1.2,
                                     fee: Long = FEE,
                                     maxMinerFee: Long = MAX_MINER_FEE,
                                     state: PoolState = traded,
                                     minShares: Long = -1L,
                                     swapped: Boolean = false,
                                     setup: PoolSetup = PoolSetup()): Execution = {
    val offeredY = offeredYFor(state, offeredX, ratio)
    val t = depositTerms(state, offeredX, offeredY)
    val shares = t.shares.toLong
    val next = stateOf(state.view.afterDeposit(offeredX - t.excessX.toLong, offeredY - t.excessY.toLong, shares))
    val order = LDOrderContracts.t2tDeposit(terms(ctx, fee, maxMinerFee), if (minShares < 0) shares else minShares)
    val (poolIn, poolOut) = poolSide(ctx, state, next, LDHelpers.POOL_DEPOSIT, setup)
    val nft = poolIn.id
    val held = Seq(Token(tokenX, offeredX), Token(tokenY, offeredY))
    Execution(ctx, state, next,
      Seq(poolIn,
        inputAt(UTXO(order, PROVISION_MIN + REWARD_ERG + fee, if (swapped) held.reverse else held), ctx, 1),
        executorFunding(ctx, 2)),
      Seq(poolOut,
        provisionUTXO(state.accX, state.accY, nft, shares),
        rewardUTXO(ctx, REWARD_ERG, Seq(Token(nft, 1L)) ++ nonZero(tokenX, t.excessX) ++ nonZero(tokenY, t.excessY)),
        takingsUTXO(ctx, fee)))
  }

  // ═══ REDEEM ═══════════════════════════════════════════════════════════════

  /** Outputs: 0 pool, 1 reward, 2 takings. The owner receives exactly the pool's release, less the fee. */
  protected def redeemOrderScenario(ctx: BlockchainContext,
                                    shares: Long = 10000000000L,
                                    fee: Long = FEE,
                                    maxMinerFee: Long = MAX_MINER_FEE,
                                    state: PoolState = traded,
                                    setup: PoolSetup = PoolSetup()): Execution = {
    val prov = provisionOf(shares)
    val q = state.view.simRedeem(shares)
    val next = stateOf(state.view.afterRedeem(q))
    val order = LDOrderContracts.t2tRedeem(terms(ctx, fee, maxMinerFee))
    val (poolIn, poolOut) = poolSide(ctx, state, next, LDHelpers.POOL_REDEEM, setup)
    Execution(ctx, state, next,
      Seq(poolIn,
        provisionInput(ctx, prov.utxo, LDHelpers.PROV_REDEEM, 1),
        inputAt(UTXO(order, REWARD_ERG + fee, Seq(Token(ownerNFT, 1L))), ctx, 2),
        executorFunding(ctx, 3)),
      Seq(poolOut,
        rewardUTXO(ctx, REWARD_ERG + prov.utxo.value, Seq(Token(tokenX, q.amountX), Token(tokenY, q.amountY))),
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
