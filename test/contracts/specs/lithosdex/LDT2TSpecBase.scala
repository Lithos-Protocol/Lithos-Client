package contracts.specs.lithosdex

import contracts.specs.harness.ContractSpecBase
import lithosdex.contracts.LDT2TContracts
import lithosdex.{LDHelpers, LDRedeemQuote, LDResizeQuote, LDT2TDepositQuote, LDT2TLiquidityPool, LDT2TSwapQuote}
import org.ergoplatform.appkit._
import org.ergoplatform.appkit.scalaapi._
import org.ergoplatform.sdk.ErgoId
import sigma.Colls
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

import java.math.BigInteger

/** One token/token deployment, built from pinned templates: nothing here compiles. */
object LDT2TSpecBase {

  // Ids belonging to nothing else, and distinct from the ERG/token harness's so the two deployments'
  // provision guards differ as they would on chain
  val poolNFT: ErgoId =
    ErgoId.create("a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1")
  val vaultNFT: ErgoId =
    ErgoId.create("b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2")
  val provToken: ErgoId =
    ErgoId.create("c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3")
  val tokenX: ErgoId =
    ErgoId.create("d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4d4")
  val tokenY: ErgoId =
    ErgoId.create("d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5d5")

  val unrelatedToken: ErgoId = LithosDexSpecBase.unrelatedToken
  val ownerNFT: ErgoId = LithosDexSpecBase.ownerNFT
  val otherOwnerNFT: ErgoId = LithosDexSpecBase.otherOwnerNFT

  lazy val deployment: LDT2TContracts = LDT2TContracts(poolNFT, vaultNFT, provToken)
}

/**
 * Shared harness for LD_T2T_LiquidityPool and LD_T2T_FeeVault, with the provision logic and guard they
 * share with the ERG/token pool.
 *
 * Positions are the same as the ERG/token harness's, and just as load bearing:
 *
 *   swap      IN  pool                        OUT pool
 *   deposit   IN  pool                        OUT pool, provision, ownerNFT
 *   redeem    IN  pool, provision, ownerNFT   OUT pool, payout            (NFT burned)
 *   flush     IN  pool, vault                 OUT pool, vault
 *   claim     IN  vault, provisions...        OUT vault, successors...
 *   resize    IN  pool, provision, vault      OUT pool, provision, vault
 */
trait LDT2TSpecBase extends ContractSpecBase {

  import LDT2TSpecBase._

  // ─── contracts ────────────────────────────────────────────────────────────

  protected def poolContract: Contract = deployment.liquidityPool
  protected def vaultContract: Contract = deployment.feeVault
  protected def guardContract: Contract = deployment.provisionGuard
  protected def provisionLogic: Contract = deployment.provisionLogic

  // ─── identities ───────────────────────────────────────────────────────────

  protected val userSecret: BigInteger = BigInteger.valueOf(7007L)

  protected def user(ctx: BlockchainContext): ErgoProver = proverWith(ctx, userSecret)

  protected def userContract(ctx: BlockchainContext): Contract = contractOf(user(ctx))

  // ─── constants ────────────────────────────────────────────────────────────

  protected val LOCKED_LP: Long = LDHelpers.LOCKED_LP
  protected val FEE_DENOM: Long = LDHelpers.FEE_DENOM
  protected val MIN_SUPPLY: Long = LDHelpers.MIN_SUPPLY
  protected val SCALE: BigInt = LDHelpers.SCALE
  protected val PROVISION_MIN: Long = LDHelpers.PROVISION_MIN
  protected val SEED: Long = LDHelpers.T2T_VAULT_SEED
  protected val POOL_VALUE: Long = LDHelpers.T2T_POOL_MIN_VALUE
  protected val VAULT_VALUE: Long = LDHelpers.T2T_VAULT_MIN_VALUE

  /** [dexFee, xFee, yFee]. The side fees differ so a direction mix-up changes the numbers. */
  protected val FEE_PARAMS: Array[Long] = Array(9970L, 15L, 25L)

  protected val NFT_BOX_VALUE: Long = Parameters.MinFee
  protected val SLACK: Long = 10L * Parameters.OneErg

  // ─── register values ──────────────────────────────────────────────────────

  protected def bigIntValue(v: BigInt): ErgoValue[_] = ErgoValue.of(v.bigInteger)

  protected def longsValue(v: Array[Long]): ErgoValue[_] = ErgoValue.of(Colls.fromArray(v), scalaLongType)

  protected def bytesValue(bytes: Array[Byte]): ErgoValue[_] = ErgoValue.of(Colls.fromArray(bytes), scalaByteType)

  // ─── pool state ───────────────────────────────────────────────────────────

  /** Everything a pool box holds. Reserves are what the AMM trades with; pending is owed to the vault. */
  protected case class PoolState(reservesX: Long,
                                 reservesY: Long,
                                 pendingX: Long,
                                 pendingY: Long,
                                 supply: Long,
                                 provTokens: Long,
                                 accX: BigInt,
                                 accY: BigInt,
                                 feeParams: Array[Long] = FEE_PARAMS,
                                 value: Long = POOL_VALUE) {

    /** The client's own quoting math, which mirrors the contract's arithmetic and truncation exactly. */
    def view: LDT2TLiquidityPool =
      LDT2TLiquidityPool(reservesX, reservesY, pendingX, pendingY, supply, feeParams,
        accX, accY, poolNFT, tokenX, tokenY, provToken, provTokens, value)

    def balanceX: Long = reservesX + pendingX

    def balanceY: Long = reservesY + pendingY
  }

  /** 10,000 X against 20,000 Y (six decimals), launch supply values, accumulators untouched. */
  protected val genesis: PoolState = PoolState(
    reservesX = 10000L * 1000000L,
    reservesY = 20000L * 1000000L,
    pendingX = 0L,
    pendingY = 0L,
    supply = LDHelpers.GENESIS_SUPPLY,
    provTokens = LDHelpers.PROV_TOKEN_SUPPLY,
    accX = BigInt(0),
    accY = BigInt(0))

  protected def stateOf(p: LDT2TLiquidityPool): PoolState =
    PoolState(p.reservesX, p.reservesY, p.pendingX, p.pendingY, p.supply, p.provTokensLeft, p.accX, p.accY,
      p.feeParams, p.value)

  /** Applies a swap through the client's own `afterSwap`, so every signed successor checks it too. */
  protected def afterSwap(s: PoolState, amountIn: Long, xIn: Boolean): PoolState = {
    val view = s.view
    stateOf(view.afterSwap(view.simSwap(amountIn, xIn)))
  }

  /** Both sides traded, so both accumulators and both pending balances are non-zero. */
  protected val traded: PoolState =
    afterSwap(afterSwap(genesis, 1000L * 1000000L, xIn = true), 3000L * 1000000L, xIn = false)

  // ─── box builders ─────────────────────────────────────────────────────────

  /** R4 liquidity, R5 feeParams, R6 pending, R7 accX, R8 accY. Tokens: NFT, X, Y, provision token. */
  protected def poolUTXO(s: PoolState,
                         contract: Contract = null,
                         poolNFTId: ErgoId = poolNFT,
                         tokenXId: ErgoId = tokenX,
                         tokenYId: ErgoId = tokenY): UTXO =
    UTXO(
      if (contract == null) poolContract else contract,
      s.value,
      Seq(Token(poolNFTId, 1L), Token(tokenXId, s.balanceX), Token(tokenYId, s.balanceY),
        Token(provToken, s.provTokens)),
      Seq(
        ErgoValue.of(LOCKED_LP - s.supply),
        longsValue(s.feeParams),
        longsValue(Array(s.pendingX, s.pendingY)),
        bigIntValue(s.accX),
        bigIntValue(s.accY)))

  /** R4 accX, R5 accY. Tokens: vault NFT, X, Y — both present from genesis. */
  protected def vaultUTXO(balanceX: Long,
                          balanceY: Long,
                          accX: BigInt,
                          accY: BigInt,
                          value: Long = VAULT_VALUE,
                          contract: Contract = null,
                          tokenXId: ErgoId = tokenX,
                          tokenYId: ErgoId = tokenY): UTXO =
    UTXO(
      if (contract == null) vaultContract else contract,
      value,
      Seq(Token(vaultNFT, 1L), Token(tokenXId, balanceX), Token(tokenYId, balanceY)),
      Seq(bigIntValue(accX), bigIntValue(accY)))

  /** One liquidity provision: R4 entryX, R5 entryY, R6 ownerNFT, R7 shares, one provision token. */
  protected case class Provision(utxo: UTXO, entryX: BigInt, entryY: BigInt, ownerNFT: ErgoId, shares: Long)

  protected def provisionUTXO(entryX: BigInt,
                              entryY: BigInt,
                              ownerId: ErgoId,
                              shares: Long,
                              value: Long = PROVISION_MIN,
                              contract: Contract = null): UTXO =
    UTXO(
      if (contract == null) guardContract else contract,
      value,
      Seq(Token(provToken, 1L)),
      Seq(bigIntValue(entryX), bigIntValue(entryY), bytesValue(ownerId.getBytes), ErgoValue.of(shares)))

  protected def provisionOf(shares: Long,
                            entryX: BigInt = BigInt(0),
                            entryY: BigInt = BigInt(0),
                            ownerId: ErgoId = ownerNFT): Provision =
    Provision(provisionUTXO(entryX, entryY, ownerId, shares), entryX, entryY, ownerId, shares)

  protected def nftUTXO(ctx: BlockchainContext, tokenId: ErgoId): UTXO =
    UTXO(userContract(ctx), NFT_BOX_VALUE, Seq(Token(tokenId, 1L)))

  protected def funding(ctx: BlockchainContext, index: Int, tokens: Seq[Token] = Seq.empty[Token]): InputUTXO =
    inputAt(UTXO(userContract(ctx), SLACK, tokens), ctx, index)

  // ─── context vars, in ascending id order ──────────────────────────────────

  protected def poolInput(ctx: BlockchainContext, box: UTXO, op: Byte, index: Int = 0): InputUTXO =
    inputAt(box, ctx, index).withCtxVar(0.toByte, ErgoValue.of(op))

  protected def vaultInput(ctx: BlockchainContext, box: UTXO, op: Byte, index: Int,
                           count: Option[Int] = None): InputUTXO = {
    val base = inputAt(box, ctx, index).withCtxVar(0.toByte, ErgoValue.of(op))
    count.fold(base)(c => base.withCtxVar(1.toByte, ErgoValue.of(c)))
  }

  protected def provisionInput(ctx: BlockchainContext, box: UTXO, op: Byte, index: Int): InputUTXO =
    inputAt(box, ctx, index)
      .withCtxVar(0.toByte, ErgoValue.of(op))
      .withCtxVar(LDHelpers.PROVISION_LOGIC_VAR, bytesValue(provisionLogic.valueBytes))

  /** One transaction. Negatives replace a single input or output and rebuild. */
  protected case class Scenario(ctx: BlockchainContext,
                                inputs: Seq[InputUTXO],
                                outputs: Seq[UTXO],
                                burn: Seq[Token] = Seq.empty[Token]) {
    def input(i: Int, in: InputUTXO): Scenario = copy(inputs = inputs.updated(i, in))
    def output(i: Int, out: UTXO): Scenario = copy(outputs = outputs.updated(i, out))
    def tx: UnsignedTransaction = build(ctx, inputs, outputs, user(ctx).getAddress, Seq.empty[InputUTXO], burn)
  }

  // ═══ SWAP — pool op 0 ═════════════════════════════════════════════════════

  protected def swapScenario(ctx: BlockchainContext,
                             amountIn: Long = 1000L * 1000000L,
                             xIn: Boolean = true,
                             state: PoolState = traded): (Scenario, PoolState, LDT2TSwapQuote) = {
    val next = afterSwap(state, amountIn, xIn)
    val sold = Token(if (xIn) tokenX else tokenY, amountIn)
    (Scenario(ctx,
      Seq(poolInput(ctx, poolUTXO(state), LDHelpers.POOL_SWAP), funding(ctx, 1, Seq(sold))),
      Seq(poolUTXO(next))), next, state.view.simSwap(amountIn, xIn))
  }

  // ═══ DEPOSIT — pool op 1 ══════════════════════════════════════════════════

  protected def depositScenario(ctx: BlockchainContext,
                                shares: Long = 10000000000L,
                                state: PoolState = traded): (Scenario, PoolState, LDT2TDepositQuote) = {
    val q = state.view.simDepositForShares(shares)
    val poolIn = poolInput(ctx, poolUTXO(state), LDHelpers.POOL_DEPOSIT)
    val nft = poolIn.id
    val next = stateOf(state.view.afterDeposit(q.amountX, q.amountY, shares))
    (Scenario(ctx,
      Seq(poolIn, funding(ctx, 1, Seq(Token(tokenX, q.amountX), Token(tokenY, q.amountY)))),
      Seq(poolUTXO(next), provisionUTXO(state.accX, state.accY, nft, shares), nftUTXO(ctx, nft))), next, q)
  }

  // ═══ REDEEM — pool op 2, provision op 1 ═══════════════════════════════════

  protected def redeemScenario(ctx: BlockchainContext,
                               shares: Long = 10000000000L,
                               state: PoolState = traded): (Scenario, PoolState, LDRedeemQuote) = {
    val prov = provisionOf(shares)
    val q = state.view.simRedeem(shares)
    val next = stateOf(state.view.afterRedeem(q))
    val payout = UTXO(userContract(ctx), prov.utxo.value,
      Seq(Token(tokenX, q.amountX), Token(tokenY, q.amountY)))
    (Scenario(ctx,
      Seq(poolInput(ctx, poolUTXO(state), LDHelpers.POOL_REDEEM),
        provisionInput(ctx, prov.utxo, LDHelpers.PROV_REDEEM, 1),
        inputAt(nftUTXO(ctx, prov.ownerNFT), ctx, 2),
        funding(ctx, 3)),
      Seq(poolUTXO(next), payout),
      Seq(Token(prov.ownerNFT, 1L))), next, q)
  }

  // ═══ FLUSH — pool op 3, vault op 1 ════════════════════════════════════════

  protected def flushScenario(ctx: BlockchainContext,
                              state: PoolState = traded,
                              vaultX: Long = SEED,
                              vaultY: Long = SEED): Scenario =
    Scenario(ctx,
      Seq(poolInput(ctx, poolUTXO(state), LDHelpers.POOL_FLUSH),
        vaultInput(ctx, vaultUTXO(vaultX, vaultY, BigInt(0), BigInt(0)), LDHelpers.VAULT_FLUSH, 1),
        funding(ctx, 2)),
      Seq(poolUTXO(state.copy(pendingX = 0L, pendingY = 0L)),
        vaultUTXO(vaultX + state.pendingX, vaultY + state.pendingY, state.accX, state.accY)))

  // ═══ RESIZE — pool op 4, provision op 3, vault op 1 ═══════════════════════

  protected def resizeScenario(ctx: BlockchainContext,
                               oldShares: Long = 10000000000L,
                               newShares: Long = 15000000000L,
                               state: PoolState = traded): (Scenario, LDResizeQuote) = {
    val prov = provisionOf(oldShares)
    val q = state.view.simResize(oldShares, newShares, prov.entryX, prov.entryY)
    val next = state.copy(
      reservesX = if (q.increasing) state.reservesX + q.amountX else state.reservesX - q.amountX,
      reservesY = if (q.increasing) state.reservesY + q.amountY else state.reservesY - q.amountY,
      pendingX = 0L, pendingY = 0L,
      supply = state.supply + q.delta)
    val fund =
      if (q.increasing) funding(ctx, 4, Seq(Token(tokenX, q.amountX), Token(tokenY, q.amountY)))
      else funding(ctx, 4)
    (Scenario(ctx,
      Seq(poolInput(ctx, poolUTXO(state), LDHelpers.POOL_RESIZE),
        provisionInput(ctx, prov.utxo, LDHelpers.PROV_RESIZE, 1),
        vaultInput(ctx, vaultUTXO(SEED, SEED, BigInt(0), BigInt(0)), LDHelpers.VAULT_FLUSH, 2),
        inputAt(nftUTXO(ctx, prov.ownerNFT), ctx, 3),
        fund),
      Seq(poolUTXO(next),
        provisionUTXO(q.nextEntryX, q.nextEntryY, prov.ownerNFT, newShares),
        vaultUTXO(SEED + state.pendingX, SEED + state.pendingY, state.accX, state.accY))), q)
  }

  // ═══ CLAIM — vault op 0, provision op 0 ═══════════════════════════════════

  /** What the vault owes a provision: its size times how far the vault's accumulator has moved. */
  protected def owed(shares: Long, entry: BigInt, acc: BigInt): Long =
    ((BigInt(shares) * (acc - entry)) / SCALE).toLong

  /** Fees the locked share of supply earned and nobody can claim, left in the vault by default. */
  protected val vaultSurplus: Long = 1000000L

  /**
   * Provisions settle against the traded pool's accumulators. The vault holds what they are owed plus
   * the seed and a surplus, which is the real steady state. Returns the transaction and the total owed.
   */
  protected def claimScenario(ctx: BlockchainContext,
                              provs: Seq[Provision] = Seq(provisionOf(10000000000L)),
                              accX: BigInt = traded.accX,
                              accY: BigInt = traded.accY): (Scenario, Long, Long) = {
    val owedX = provs.map(p => owed(p.shares, p.entryX, accX)).sum
    val owedY = provs.map(p => owed(p.shares, p.entryY, accY)).sum
    val balX = SEED + owedX + vaultSurplus
    val balY = SEED + owedY + vaultSurplus
    val provIns = provs.zipWithIndex.map { case (p, i) =>
      provisionInput(ctx, p.utxo, LDHelpers.PROV_CLAIM, i + 1) }
    val nftIns = provs.map(_.ownerNFT).distinct.zipWithIndex.map { case (id, i) =>
      inputAt(nftUTXO(ctx, id), ctx, 20 + i) }
    (Scenario(ctx,
      Seq(vaultInput(ctx, vaultUTXO(balX, balY, accX, accY), LDHelpers.VAULT_CLAIM, 0, Some(provs.size))) ++
        provIns ++ nftIns :+ funding(ctx, 30),
      Seq(vaultUTXO(balX - owedX, balY - owedY, accX, accY)) ++
        provs.map(p => provisionUTXO(accX, accY, p.ownerNFT, p.shares, p.utxo.value))), owedX, owedY)
  }
}
