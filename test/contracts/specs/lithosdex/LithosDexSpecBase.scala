package contracts.specs.lithosdex

import contracts.specs.harness.ContractSpecBase
import lfsm.ScriptGenerator
import lithosdex.contracts.LDContracts
import lithosdex.{LDDepositQuote, LDHelpers, LDLiquidityPool, LDRedeemQuote, LDResizeQuote, LDSwapQuote}
import org.ergoplatform.appkit._
import org.ergoplatform.appkit.scalaapi._
import org.ergoplatform.sdk.ErgoId
import sigma.Colls
import work.lithos.mutations.{Contract, InputUTXO, Mutator, Token, TxBuilder, UTXO}

import java.math.BigInteger

/** The four LithosDex contracts, compiled once — the sigma compiler mutates shared AST nodes. */
object LithosDexSpecBase {

  // Ids belonging to nothing in the protocol. Deliberately not `LDHelpers`' placeholders: reaching for
  // one of those in a "some other token turns up" case silently produces a second proposition token.
  val poolNFT: ErgoId =
    ErgoId.create("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
  val vaultNFT: ErgoId =
    ErgoId.create("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
  val provToken: ErgoId =
    ErgoId.create("cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc")
  val tokenY: ErgoId =
    ErgoId.create("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd")
  val unrelatedToken: ErgoId =
    ErgoId.create("eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee")

  /** Stands in for an ownership NFT a past deposit minted from a pool box that is no longer around. */
  val ownerNFT: ErgoId =
    ErgoId.create("f00df00df00df00df00df00df00df00df00df00df00df00df00df00df00df00d")
  val otherOwnerNFT: ErgoId =
    ErgoId.create("f11df11df11df11df11df11df11df11df11df11df11df11df11df11df11df11d")

  /** One deployment's four scripts, with the hashes already threaded between them. */
  case class LDSet(provisionLogic: Contract, provisionGuard: Contract,
                   feeVault: Contract, liquidityPool: Contract)

  private var cache: Option[LDSet] = None
  private var proposed: Option[LDSet] = None

  /** Built through the production wiring, so a spec fails if the client's own builder drifts. */
  def compiled(ctx: BlockchainContext): LDSet = synchronized {
    cache.getOrElse {
      val c = LDContracts(ctx, poolNFT, vaultNFT, provToken)
      val all = LDSet(c.provisionLogic, c.provisionGuard, c.feeVault, c.liquidityPool)
      cache = Some(all)
      all
    }
  }

  /**
   * The same wiring over the `CLAUDE_` sources, so a proposal runs through every existing scenario
   * before it is promoted. Mirrors `lithosdex.contracts.LithosDexContracts`: the logic is named by the
   * guard, the guard by the pool, and the vault and pool name each other by NFT.
   */
  def compiledProposed(ctx: BlockchainContext): LDSet = synchronized {
    proposed.getOrElse {
      def script(n: String): String = ScriptGenerator.mkLithosDexScript("CLAUDE_" + n)
      val logic = Contract.fromErgoScript(ctx.getNetworkType, ConstantsBuilder.create()
        .item("CONST_POOL_NFT", Colls.fromArray(poolNFT.getBytes))
        .item("CONST_VAULT_NFT", Colls.fromArray(vaultNFT.getBytes))
        .build(), script("LD_Provision"), Seq.empty[Mutator])
      val guard = Contract.fromErgoScript(ctx.getNetworkType, ConstantsBuilder.create()
        .item("CONST_PROVISION_LOGIC_HASH", Colls.fromArray(logic.hashedValueBytes))
        .build(), ScriptGenerator.mkLithosDexScript("LD_Provision_Guard"), Seq.empty[Mutator])
      val vault = Contract.fromErgoScript(ctx.getNetworkType, ConstantsBuilder.create()
        .item("CONST_POOL_NFT", Colls.fromArray(poolNFT.getBytes))
        .item("CONST_PROV_TOKEN", Colls.fromArray(provToken.getBytes))
        .build(), script("LD_FeeVault"), Seq.empty[Mutator])
      val pool = Contract.fromErgoScript(ctx.getNetworkType, ConstantsBuilder.create()
        .item("CONST_VAULT_NFT", Colls.fromArray(vaultNFT.getBytes))
        .item("CONST_PROVISION_GUARD_HASH", Colls.fromArray(guard.hashedPropBytes))
        .build(), script("LD_LiquidityPool"), Seq.empty[Mutator])
      val all = LDSet(logic, guard, vault, pool)
      proposed = Some(all)
      all
    }
  }
}

/**
 * Shared harness for LD_LiquidityPool, LD_FeeVault, LD_Provision and LD_Provision_Guard.
 *
 * Every scenario builder returns one transaction the whole stack accepts; negatives take it and perturb
 * exactly one field, so a rejection can only come from the condition under test. Input and output
 * positions are load bearing throughout — both the vault and a provision find their successor at
 * `OUTPUTS(CONTEXT.selfBoxIndex)`, and the pool reads its counterparties at fixed indices:
 *
 *   swap      IN  pool                        OUT pool
 *   deposit   IN  pool                        OUT pool, provision, ownerNFT
 *   redeem    IN  pool, provision, ownerNFT   OUT pool, payout            (NFT burned)
 *   flush     IN  pool, vault                 OUT pool, vault
 *   claim     IN  vault, provisions...        OUT vault, successors...
 *   resize    IN  pool, provision, vault      OUT pool, provision, vault
 *   refresh   IN  provision                   OUT provision
 */
trait LithosDexSpecBase extends ContractSpecBase {

  import LithosDexSpecBase._

  // ─── contracts ────────────────────────────────────────────────────────────

  protected def ldContracts(ctx: BlockchainContext): LithosDexSpecBase.LDSet =
    LithosDexSpecBase.compiled(ctx)

  protected def poolContract(ctx: BlockchainContext): Contract = ldContracts(ctx).liquidityPool

  protected def vaultContract(ctx: BlockchainContext): Contract = ldContracts(ctx).feeVault

  /** What a provision box is actually locked under. The logic itself is never a box script. */
  protected def guardContract(ctx: BlockchainContext): Contract = ldContracts(ctx).provisionGuard

  protected def provisionLogic(ctx: BlockchainContext): Contract = ldContracts(ctx).provisionLogic

  // ─── identities ───────────────────────────────────────────────────────────

  /** One prover funds and signs everything: ownership in LithosDex is bearer, never by key. */
  protected val userSecret: BigInteger = BigInteger.valueOf(7007L)

  protected def user(ctx: BlockchainContext): ErgoProver = proverWith(ctx, userSecret)

  protected def userContract(ctx: BlockchainContext): Contract = contractOf(user(ctx))

  // ─── constants, mirrored from the .ergo sources ───────────────────────────

  protected val LOCKED_LP: Long = LDHelpers.LOCKED_LP
  protected val FEE_DENOM: Long = LDHelpers.FEE_DENOM
  protected def MIN_RENT: Long = LDHelpers.MIN_RENT

  /** What Ergo itself allows, and therefore what an uncapped box could be padded to. */
  protected val ERGO_MAX_BOX_BYTES: Int = 4096

  /** A box's serialized length gains the transaction id and index once it is being spent. */
  protected val refBytes: Int = 34
  protected val MIN_SUPPLY: Long = LDHelpers.MIN_SUPPLY
  protected val SCALE: BigInt = LDHelpers.SCALE
  protected def PROVISION_MIN: Long = LDHelpers.PROVISION_MIN
  protected def VAULT_MIN: Long = LDHelpers.VAULT_MIN
  protected val REFRESH_AGE: Int = LDHelpers.REFRESH_AGE
  protected val HEIGHT_SLACK: Int = LDHelpers.HEIGHT_SLACK

  /** [dexFee, ergFee, tokenFee]: the AMM keeps 0.15%, another 0.15% is externalized to providers. */
  protected val FEE_PARAMS: Array[Long] = Array(9985L, 15L, 15L)

  protected val NFT_BOX_VALUE: Long = Parameters.MinFee
  protected val SLACK: Long = 10L * Parameters.OneErg

  // ─── register values ──────────────────────────────────────────────────────

  protected def bigIntValue(v: BigInt): ErgoValue[_] = ErgoValue.of(v.bigInteger)

  protected def longsValue(v: Array[Long]): ErgoValue[_] = ErgoValue.of(Colls.fromArray(v), scalaLongType)

  protected def bytesValue(bytes: Array[Byte]): ErgoValue[_] =
    ErgoValue.of(Colls.fromArray(bytes), scalaByteType)

  /** Filler for a register that exists only to make a box longer. */
  protected def padding(n: Int): ErgoValue[_] = bytesValue(Array.fill(n)(7.toByte))

  // ─── pool state ───────────────────────────────────────────────────────────

  /**
   * Everything a pool box holds, in the units the contract reasons in. Reserves are what the AMM trades
   * with; pending is fees already promised to the vault and held apart from them.
   */
  protected case class PoolState(reservesX: Long,
                                 reservesY: Long,
                                 pendingX: Long,
                                 pendingY: Long,
                                 supply: Long,
                                 provTokens: Long,
                                 accX: BigInt,
                                 accY: BigInt,
                                 feeParams: Array[Long] = FEE_PARAMS) {

    /** The client's own quoting math, which mirrors the contract's arithmetic and truncation exactly. */
    def view: LDLiquidityPool =
      LDLiquidityPool(reservesX, reservesY, pendingX, pendingY, supply, feeParams,
        accX, accY, poolNFT, tokenY, provToken, provTokens)

    def boxValue: Long = reservesX + pendingX

    def tokenBalance: Long = reservesY + pendingY
  }

  /**
   * 10 ERG against 10,000 tokens, a trillion shares issued, both accumulators untouched.
   *
   * `provTokens` and `supply` are the shipped launch values rather than round numbers, so a change to
   * either is exercised by every property here rather than only by the sandbox that mints it.
   */
  protected val genesis: PoolState = PoolState(
    reservesX = 10L * Parameters.OneErg,
    reservesY = 10000L * 1000000L,
    pendingX = 0L,
    pendingY = 0L,
    supply = LDHelpers.GENESIS_SUPPLY,
    provTokens = LDHelpers.PROV_TOKEN_SUPPLY,
    accX = BigInt(0),
    accY = BigInt(0))

  /** Applies a swap the way LD_LiquidityPool requires it: fee into pending, the rest onto the curve. */
  protected def afterSwap(s: PoolState, amountIn: Long, ergIn: Boolean): PoolState = {
    val q = s.view.simSwap(amountIn, ergIn)
    val advance = (BigInt(q.protocolFee) * SCALE) / BigInt(s.supply)
    if (ergIn)
      s.copy(reservesX = s.reservesX + q.tradedIn, reservesY = s.reservesY - q.amountOut,
        pendingX = s.pendingX + q.protocolFee, accX = s.accX + advance)
    else
      s.copy(reservesX = s.reservesX - q.amountOut, reservesY = s.reservesY + q.tradedIn,
        pendingY = s.pendingY + q.protocolFee, accY = s.accY + advance)
  }

  /** Both sides traded, so both accumulators and both pending balances are non-zero. */
  protected val traded: PoolState = afterSwap(afterSwap(genesis, Parameters.OneErg, ergIn = true),
    2000L * 1000000L, ergIn = false)

  /**
   * A pool carrying more pending than reserves on both sides. Reaching it needs roughly 700x the pool's
   * depth in volume with nobody flushing, so no live pool is here — but the swap condition is stated in
   * terms of reserves, and reserves are a balance less pending, so this is where that arithmetic has to
   * be checked. Sized so that [[bothSidesOut]] clears CONST_MIN_RENT under both the deployed floor and
   * the raised one, leaving the reserve condition as the only thing that can reject it.
   */
  protected val pendingHeavy: PoolState = PoolState(
    reservesX = 100000000000L, pendingX = 120000000000L,
    reservesY = 100000000000L, pendingY = 110000000000L,
    supply = 1000000000000L, provTokens = 1000000L,
    accX = BigInt(0), accY = BigInt(0))

  /**
   * The cheapest swap taking ERG and tokens out together that the else branch's inequality admits, or
   * None when no such swap exists — which is the case for any pool whose pending is a fraction of its
   * reserves, because the bracket on the right of that inequality only turns negative once more tokens
   * can leave than the reserves hold.
   */
  protected def bothSidesOut(s: PoolState): Option[PoolState] = {
    val tokensOut = s.tokenBalance - 1000L
    val deltaPendingY = (BigInt(15) * BigInt(-tokensOut)) / BigInt(FEE_DENOM)
    val pendingY1 = s.pendingY + deltaPendingY.toLong
    val reservesY1 = (s.tokenBalance - tokensOut) - pendingY1
    val dY = BigInt(reservesY1 - s.reservesY)
    val inner = BigInt(s.reservesY) * FEE_DENOM + dY * 9985
    if (inner >= 0) None
    else {
      val ergOut = ((BigInt(s.reservesX) * dY * 9985) / inner) + 1
      if (ergOut <= 0 || ergOut > s.boxValue - MIN_RENT - 1) None
      else Some(s.copy(
        reservesX = (s.boxValue - ergOut.toLong) - s.pendingX,
        reservesY = reservesY1,
        pendingY = pendingY1,
        accY = s.accY + (deltaPendingY * SCALE) / BigInt(s.supply)))
    }
  }

  // ─── box builders ─────────────────────────────────────────────────────────

  /** R4 liquidity (the unissued remainder, not the supply), R5 feeParams, R6 pending, R7 accX, R8 accY. */
  protected def poolUTXO(ctx: BlockchainContext,
                         s: PoolState,
                         contract: Contract = null,
                         poolNFTId: ErgoId = poolNFT,
                         tokenYId: ErgoId = tokenY,
                         provTokenId: ErgoId = provToken): UTXO =
    UTXO(
      if (contract == null) poolContract(ctx) else contract,
      s.boxValue,
      Seq(Token(poolNFTId, 1L), Token(tokenYId, s.tokenBalance), Token(provTokenId, s.provTokens)),
      Seq(
        ErgoValue.of(LOCKED_LP - s.supply),
        longsValue(s.feeParams),
        longsValue(Array(s.pendingX, s.pendingY)),
        bigIntValue(s.accX),
        bigIntValue(s.accY)))

  /** R4 accX, R5 accY. Token 1 is absent until a flush has carried some of the token side across. */
  protected def vaultUTXO(ctx: BlockchainContext,
                          balanceX: Long,
                          balanceY: Long,
                          accX: BigInt,
                          accY: BigInt,
                          contract: Contract = null,
                          vaultNFTId: ErgoId = vaultNFT,
                          tokenYId: ErgoId = tokenY): UTXO =
    UTXO(
      if (contract == null) vaultContract(ctx) else contract,
      balanceX,
      Seq(Token(vaultNFTId, 1L)) ++ (if (balanceY > 0) Seq(Token(tokenYId, balanceY)) else Seq.empty[Token]),
      Seq(bigIntValue(accX), bigIntValue(accY)))

  /** One liquidity provision: R4 entryX, R5 entryY, R6 ownerNFT, R7 shares, one provision token. */
  protected case class Provision(utxo: UTXO, entryX: BigInt, entryY: BigInt, ownerNFT: ErgoId, shares: Long)

  protected def provisionUTXO(ctx: BlockchainContext,
                              entryX: BigInt,
                              entryY: BigInt,
                              ownerId: ErgoId,
                              shares: Long,
                              value: Long = PROVISION_MIN,
                              contract: Contract = null,
                              provTokenId: ErgoId = provToken,
                              provTokenAmount: Long = 1L): UTXO =
    UTXO(
      if (contract == null) guardContract(ctx) else contract,
      value,
      Seq(Token(provTokenId, provTokenAmount)),
      Seq(bigIntValue(entryX), bigIntValue(entryY), bytesValue(ownerId.getBytes), ErgoValue.of(shares)))

  protected def provisionOf(ctx: BlockchainContext,
                            shares: Long,
                            entryX: BigInt = BigInt(0),
                            entryY: BigInt = BigInt(0),
                            ownerId: ErgoId = LithosDexSpecBase.ownerNFT,
                            value: Long = PROVISION_MIN): Provision =
    Provision(provisionUTXO(ctx, entryX, entryY, ownerId, shares, value), entryX, entryY, ownerId, shares)

  /** The box holding an ownership NFT. Every owner-only path needs it as an INPUT, not a data input. */
  protected def nftUTXO(ctx: BlockchainContext, tokenId: ErgoId, value: Long = NFT_BOX_VALUE): UTXO =
    UTXO(userContract(ctx), value, Seq(Token(tokenId, 1L)))

  protected def funding(ctx: BlockchainContext,
                        index: Int,
                        value: Long = SLACK,
                        tokens: Seq[Token] = Seq.empty[Token]): InputUTXO =
    inputAt(UTXO(userContract(ctx), value, tokens), ctx, index)

  // ─── context vars ─────────────────────────────────────────────────────────
  //
  // Attached in ascending id order on purpose. Offline signing does not care, but on a live node the
  // extension is part of `messageToSign` and appkit serializes it in map iteration order — a different
  // attachment order signs one byte string and asks the node to verify another. For the ids LithosDex
  // uses (0, 1, 64) ascending is what the java.util.HashMap actually iterates.

  protected def poolInput(ctx: BlockchainContext, box: UTXO, op: Byte, index: Int = 0): InputUTXO =
    inputAt(box, ctx, index).withCtxVar(0.toByte, ErgoValue.of(op))

  protected def vaultInput(ctx: BlockchainContext,
                           box: UTXO,
                           op: Byte,
                           index: Int,
                           count: Option[Int] = None): InputUTXO = {
    val base = inputAt(box, ctx, index).withCtxVar(0.toByte, ErgoValue.of(op))
    count.fold(base)(c => base.withCtxVar(1.toByte, ErgoValue.of(c)))
  }

  /** Every provision input needs its op AND the provision logic the guard hashes and then executes. */
  protected def provisionInput(ctx: BlockchainContext,
                               box: UTXO,
                               op: Byte,
                               index: Int,
                               logic: Contract = null): InputUTXO =
    inputAt(box, ctx, index)
      .withCtxVar(0.toByte, ErgoValue.of(op))
      .withCtxVar(LDHelpers.PROVISION_LOGIC_VAR,
        bytesValue((if (logic == null) provisionLogic(ctx) else logic).valueBytes))

  // ─── transactions ─────────────────────────────────────────────────────────

  /**
   * Only the refresh path needs this. `CONST_REFRESH_AGE` is two years of blocks and the mocked context
   * sits at 123413, so no real creation height can be old enough until a preHeader moves HEIGHT.
   */
  protected val refreshHeight: Int = 1100000

  protected def buildAt(ctx: BlockchainContext,
                        inputs: Seq[InputUTXO],
                        outputs: Seq[UTXO],
                        changeTo: Address,
                        height: Int): UnsignedTransaction =
    TxBuilder(ctx)
      .setInputs(inputs: _*)
      .setOutputs(outputs: _*)
      .setPreHeader(ctx.createPreHeader().height(height).build())
      .buildTx(Parameters.MinFee, changeTo, Seq.empty[Token])

  // ═══ SWAP — pool op 0 ═════════════════════════════════════════════════════

  protected case class Swap(ctx: BlockchainContext,
                            before: PoolState,
                            after: PoolState,
                            quote: LDSwapQuote,
                            poolIn: InputUTXO,
                            fundingIn: InputUTXO,
                            poolOut: UTXO,
                            prover: ErgoProver)

  protected def swapScenario(ctx: BlockchainContext,
                             amountIn: Long = Parameters.OneErg,
                             ergIn: Boolean = true,
                             state: PoolState = genesis): Swap = {
    val p = user(ctx)
    val next = afterSwap(state, amountIn, ergIn)
    val fund =
      if (ergIn) funding(ctx, 1, amountIn + SLACK)
      else funding(ctx, 1, SLACK, Seq(Token(tokenY, amountIn)))
    Swap(ctx, state, next, state.view.simSwap(amountIn, ergIn),
      poolInput(ctx, poolUTXO(ctx, state), LDHelpers.POOL_SWAP), fund, poolUTXO(ctx, next), p)
  }

  protected def swapTx(s: Swap)(poolOut: UTXO = s.poolOut,
                                inputs: Seq[InputUTXO] = null,
                                outputs: Seq[UTXO] = null,
                                burn: Seq[Token] = Seq.empty[Token]): UnsignedTransaction =
    build(s.ctx, if (inputs == null) Seq(s.poolIn, s.fundingIn) else inputs,
      if (outputs == null) Seq(poolOut) else outputs, s.prover.getAddress, Seq.empty[InputUTXO], burn)

  // ═══ DEPOSIT — pool op 1 ══════════════════════════════════════════════════

  protected case class Deposit(ctx: BlockchainContext,
                               before: PoolState,
                               after: PoolState,
                               quote: LDDepositQuote,
                               ownerNFT: ErgoId,
                               poolIn: InputUTXO,
                               fundingIn: InputUTXO,
                               poolOut: UTXO,
                               provOut: UTXO,
                               nftOut: UTXO,
                               prover: ErgoProver)

  protected def depositScenario(ctx: BlockchainContext,
                                shares: Long = 10000000000L,
                                state: PoolState = genesis): Deposit = {
    val p = user(ctx)
    val q = state.view.simDepositForShares(shares)
    val poolIn = poolInput(ctx, poolUTXO(ctx, state), LDHelpers.POOL_DEPOSIT)
    // The ownership NFT's id can only be this pool box's id, which is what makes it unmintable anywhere
    // else — appkit treats an output token keyed to INPUTS(0) as a mint.
    val ownerId = poolIn.id
    val next = state.copy(
      reservesX = state.reservesX + q.amountX,
      reservesY = state.reservesY + q.amountY,
      supply = state.supply + shares,
      provTokens = state.provTokens - 1L)
    val fund = funding(ctx, 1, q.amountX + PROVISION_MIN + NFT_BOX_VALUE + SLACK, Seq(Token(tokenY, q.amountY)))
    Deposit(ctx, state, next, q, ownerId, poolIn, fund,
      poolUTXO(ctx, next),
      provisionUTXO(ctx, state.accX, state.accY, ownerId, shares),
      nftUTXO(ctx, ownerId), p)
  }

  protected def depositTx(d: Deposit)(poolOut: UTXO = d.poolOut,
                                      provOut: UTXO = d.provOut,
                                      nftOut: UTXO = d.nftOut,
                                      inputs: Seq[InputUTXO] = null,
                                      outputs: Seq[UTXO] = null,
                                      burn: Seq[Token] = Seq.empty[Token]): UnsignedTransaction =
    build(d.ctx, if (inputs == null) Seq(d.poolIn, d.fundingIn) else inputs,
      if (outputs == null) Seq(poolOut, provOut, nftOut) else outputs,
      d.prover.getAddress, Seq.empty[InputUTXO], burn)

  // ═══ REDEEM — pool op 2, provision op 1 ═══════════════════════════════════

  protected case class RedeemS(ctx: BlockchainContext,
                               before: PoolState,
                               after: PoolState,
                               quote: LDRedeemQuote,
                               prov: Provision,
                               poolIn: InputUTXO,
                               provIn: InputUTXO,
                               nftIn: InputUTXO,
                               fundingIn: InputUTXO,
                               poolOut: UTXO,
                               payoutOut: UTXO,
                               prover: ErgoProver)

  protected def redeemScenario(ctx: BlockchainContext,
                               shares: Long = 10000000000L,
                               state: PoolState = genesis,
                               entryX: BigInt = BigInt(0),
                               entryY: BigInt = BigInt(0),
                               ownerId: ErgoId = LithosDexSpecBase.ownerNFT): RedeemS = {
    val p = user(ctx)
    val prov = provisionOf(ctx, shares, entryX, entryY, ownerId)
    val q = state.view.simRedeem(shares)
    val next = state.copy(
      reservesX = state.reservesX - q.amountX,
      reservesY = state.reservesY - q.amountY,
      supply = state.supply - shares,
      provTokens = state.provTokens + 1L)
    // OUTPUTS(1) has to exist whatever it holds: the provision sits at INPUTS(1) and every path of its
    // logic reads OUTPUTS(selfBoxIndex) before it branches.
    val payout = UTXO(userContract(ctx), q.amountX + prov.utxo.value,
      if (q.amountY > 0) Seq(Token(tokenY, q.amountY)) else Seq.empty[Token])
    RedeemS(ctx, state, next, q, prov,
      poolInput(ctx, poolUTXO(ctx, state), LDHelpers.POOL_REDEEM),
      provisionInput(ctx, prov.utxo, LDHelpers.PROV_REDEEM, 1),
      inputAt(nftUTXO(ctx, prov.ownerNFT), ctx, 2),
      funding(ctx, 3),
      poolUTXO(ctx, next), payout, p)
  }

  protected def redeemTx(r: RedeemS)(poolOut: UTXO = r.poolOut,
                                     payoutOut: UTXO = r.payoutOut,
                                     inputs: Seq[InputUTXO] = null,
                                     outputs: Seq[UTXO] = null,
                                     burn: Seq[Token] = null): UnsignedTransaction =
    build(r.ctx, if (inputs == null) Seq(r.poolIn, r.provIn, r.nftIn, r.fundingIn) else inputs,
      if (outputs == null) Seq(poolOut, payoutOut) else outputs,
      r.prover.getAddress, Seq.empty[InputUTXO],
      if (burn == null) Seq(Token(r.prov.ownerNFT, 1L)) else burn)

  // ═══ FLUSH — pool op 3, vault op 1 ════════════════════════════════════════

  protected case class Flush(ctx: BlockchainContext,
                             before: PoolState,
                             after: PoolState,
                             vaultX: Long,
                             vaultY: Long,
                             poolIn: InputUTXO,
                             vaultIn: InputUTXO,
                             fundingIn: InputUTXO,
                             poolOut: UTXO,
                             vaultOut: UTXO,
                             prover: ErgoProver)

  protected def flushScenario(ctx: BlockchainContext,
                              state: PoolState = traded,
                              vaultX: Long = VAULT_MIN,
                              vaultY: Long = 0L,
                              vaultAccX: BigInt = BigInt(0),
                              vaultAccY: BigInt = BigInt(0)): Flush = {
    val p = user(ctx)
    val next = state.copy(pendingX = 0L, pendingY = 0L)
    Flush(ctx, state, next, vaultX, vaultY,
      poolInput(ctx, poolUTXO(ctx, state), LDHelpers.POOL_FLUSH),
      vaultInput(ctx, vaultUTXO(ctx, vaultX, vaultY, vaultAccX, vaultAccY), LDHelpers.VAULT_FLUSH, 1),
      funding(ctx, 2),
      poolUTXO(ctx, next),
      vaultUTXO(ctx, vaultX + state.pendingX, vaultY + state.pendingY, state.accX, state.accY), p)
  }

  protected def flushTx(f: Flush)(poolOut: UTXO = f.poolOut,
                                  vaultOut: UTXO = f.vaultOut,
                                  inputs: Seq[InputUTXO] = null,
                                  outputs: Seq[UTXO] = null,
                                  burn: Seq[Token] = Seq.empty[Token]): UnsignedTransaction =
    build(f.ctx, if (inputs == null) Seq(f.poolIn, f.vaultIn, f.fundingIn) else inputs,
      if (outputs == null) Seq(poolOut, vaultOut) else outputs,
      f.prover.getAddress, Seq.empty[InputUTXO], burn)

  // ═══ RESIZE — pool op 4, provision op 3, vault op 1 ═══════════════════════

  protected case class Resize(ctx: BlockchainContext,
                              before: PoolState,
                              after: PoolState,
                              quote: LDResizeQuote,
                              prov: Provision,
                              vaultX: Long,
                              vaultY: Long,
                              poolIn: InputUTXO,
                              provIn: InputUTXO,
                              vaultIn: InputUTXO,
                              nftIn: InputUTXO,
                              fundingIn: InputUTXO,
                              poolOut: UTXO,
                              provOut: UTXO,
                              vaultOut: UTXO,
                              prover: ErgoProver)

  protected def resizeScenario(ctx: BlockchainContext,
                               oldShares: Long = 10000000000L,
                               newShares: Long = 15000000000L,
                               state: PoolState = traded,
                               entryX: BigInt = BigInt(0),
                               entryY: BigInt = BigInt(0),
                               vaultX: Long = VAULT_MIN,
                               vaultY: Long = 0L,
                               provValue: Long = PROVISION_MIN,
                               ownerId: ErgoId = LithosDexSpecBase.ownerNFT): Resize = {
    val p = user(ctx)
    val prov = provisionOf(ctx, oldShares, entryX, entryY, ownerId, provValue)
    val q = state.view.simResize(oldShares, newShares, entryX, entryY)
    val delta = newShares - oldShares
    val next = state.copy(
      reservesX = if (q.increasing) state.reservesX + q.amountX else state.reservesX - q.amountX,
      reservesY = if (q.increasing) state.reservesY + q.amountY else state.reservesY - q.amountY,
      pendingX = 0L, pendingY = 0L,
      supply = state.supply + delta)
    val fund =
      if (q.increasing) funding(ctx, 4, q.amountX + SLACK, Seq(Token(tokenY, q.amountY)))
      else funding(ctx, 4)
    Resize(ctx, state, next, q, prov, vaultX, vaultY,
      poolInput(ctx, poolUTXO(ctx, state), LDHelpers.POOL_RESIZE),
      provisionInput(ctx, prov.utxo, LDHelpers.PROV_RESIZE, 1),
      vaultInput(ctx, vaultUTXO(ctx, vaultX, vaultY, BigInt(0), BigInt(0)), LDHelpers.VAULT_FLUSH, 2),
      inputAt(nftUTXO(ctx, prov.ownerNFT), ctx, 3),
      fund,
      poolUTXO(ctx, next),
      provisionUTXO(ctx, q.nextEntryX, q.nextEntryY, prov.ownerNFT, newShares, prov.utxo.value),
      vaultUTXO(ctx, vaultX + state.pendingX, vaultY + state.pendingY, state.accX, state.accY), p)
  }

  protected def resizeTx(r: Resize)(poolOut: UTXO = r.poolOut,
                                    provOut: UTXO = r.provOut,
                                    vaultOut: UTXO = r.vaultOut,
                                    inputs: Seq[InputUTXO] = null,
                                    outputs: Seq[UTXO] = null,
                                    burn: Seq[Token] = Seq.empty[Token]): UnsignedTransaction =
    build(r.ctx, if (inputs == null) Seq(r.poolIn, r.provIn, r.vaultIn, r.nftIn, r.fundingIn) else inputs,
      if (outputs == null) Seq(poolOut, provOut, vaultOut) else outputs,
      r.prover.getAddress, Seq.empty[InputUTXO], burn)

  // ═══ CLAIM — vault op 0, provision op 0. The pool is not involved. ════════

  protected case class Claim(ctx: BlockchainContext,
                             vaultX: Long,
                             vaultY: Long,
                             accX: BigInt,
                             accY: BigInt,
                             provs: Seq[Provision],
                             owedX: Long,
                             owedY: Long,
                             vaultIn: InputUTXO,
                             provIns: Seq[InputUTXO],
                             nftIns: Seq[InputUTXO],
                             fundingIn: InputUTXO,
                             vaultOut: UTXO,
                             provOuts: Seq[UTXO],
                             prover: ErgoProver)

  /** Fees the locked share of supply earned and nobody can claim, left in the vault by default. */
  protected val vaultSurplus: Long = 1000000000L

  /** What the vault owes a provision: its size times how far the vault's accumulator has moved. */
  protected def owed(shares: Long, entry: BigInt, acc: BigInt): Long =
    ((BigInt(shares) * (acc - entry)) / SCALE).toLong

  protected def claimScenario(ctx: BlockchainContext,
                              provs: Seq[Provision] = null,
                              accX: BigInt = null,
                              accY: BigInt = null,
                              vaultX: Long = 0L,
                              vaultY: Long = 0L,
                              count: Option[Int] = None): Claim = {
    val p = user(ctx)
    val theAccX = if (accX == null) traded.accX else accX
    val theAccY = if (accY == null) traded.accY else accY
    val theProvs =
      if (provs == null) Seq(provisionOf(ctx, 10000000000L, ownerId = LithosDexSpecBase.ownerNFT))
      else provs

    val owedX = theProvs.map(v => owed(v.shares, v.entryX, theAccX)).sum
    val owedY = theProvs.map(v => owed(v.shares, v.entryY, theAccY)).sum
    // Over-funded by default, which is the real steady state: the permanently locked share of supply
    // earns fees no provision can ever claim, and they stay here.
    val balX = if (vaultX == 0L) VAULT_MIN + owedX + vaultSurplus else vaultX
    val balY = if (vaultY == 0L) owedY + vaultSurplus else vaultY

    val vaultIn = vaultInput(ctx, vaultUTXO(ctx, balX, balY, theAccX, theAccY),
      LDHelpers.VAULT_CLAIM, 0, Some(count.getOrElse(theProvs.size)))
    val provIns = theProvs.zipWithIndex.map { case (v, i) =>
      provisionInput(ctx, v.utxo, LDHelpers.PROV_CLAIM, i + 1) }
    val nftIns = theProvs.map(_.ownerNFT).distinct.zipWithIndex.map { case (id, i) =>
      inputAt(nftUTXO(ctx, id), ctx, 20 + i) }
    val provOuts = theProvs.map(v =>
      provisionUTXO(ctx, theAccX, theAccY, v.ownerNFT, v.shares, v.utxo.value))

    Claim(ctx, balX, balY, theAccX, theAccY, theProvs, owedX, owedY,
      vaultIn, provIns, nftIns, funding(ctx, 30),
      vaultUTXO(ctx, balX - owedX, balY - owedY, theAccX, theAccY), provOuts, p)
  }

  protected def claimTx(c: Claim)(vaultOut: UTXO = c.vaultOut,
                                  provOuts: Seq[UTXO] = null,
                                  inputs: Seq[InputUTXO] = null,
                                  outputs: Seq[UTXO] = null,
                                  burn: Seq[Token] = Seq.empty[Token]): UnsignedTransaction =
    build(c.ctx,
      if (inputs == null) Seq(c.vaultIn) ++ c.provIns ++ c.nftIns ++ Seq(c.fundingIn) else inputs,
      if (outputs == null) Seq(vaultOut) ++ (if (provOuts == null) c.provOuts else provOuts) else outputs,
      c.prover.getAddress, Seq.empty[InputUTXO], burn)

  // ═══ REFRESH — provision op 2. Permissionless. ════════════════════════════

  protected case class Refresh(ctx: BlockchainContext,
                               prov: Provision,
                               height: Int,
                               provIn: InputUTXO,
                               fundingIn: InputUTXO,
                               provOut: UTXO,
                               prover: ErgoProver)

  protected def refreshScenario(ctx: BlockchainContext,
                                createdAt: Int = 500000,
                                height: Int = 0,
                                shares: Long = 10000000000L): Refresh = {
    val p = user(ctx)
    val h = if (height == 0) refreshHeight else height
    val prov = provisionOf(ctx, shares, traded.accX, traded.accY)
    val aged = prov.utxo.setCreationHeight(createdAt)
    Refresh(ctx, prov.copy(utxo = aged), h,
      provisionInput(ctx, aged, LDHelpers.PROV_REFRESH, 0), funding(ctx, 1),
      // Boxes built through toOutBox default to the mocked context height, which is nowhere near the
      // preHeader's — the successor has to declare its own or `unchanged` fails on staleness.
      prov.utxo.setCreationHeight(h), p)
  }

  protected def refreshTx(r: Refresh)(provOut: UTXO = r.provOut,
                                      inputs: Seq[InputUTXO] = null,
                                      height: Int = 0): UnsignedTransaction =
    buildAt(r.ctx, if (inputs == null) Seq(r.provIn, r.fundingIn) else inputs,
      Seq(provOut), r.prover.getAddress, if (height == 0) r.height else height)
}


