package transactions.dex

import lithosdex.states.LDFeeValue
import lithosdex.{LDHelpers, LDLiquidityPool}
import mutations.NodeWallet
import org.ergoplatform.appkit._
import org.ergoplatform.appkit.scalaapi._
import org.ergoplatform.sdk.ErgoId
import sigma.Colls
import transactions.wallet.{WalletReservation, WalletSelector}
import transactions.dex.LDBoxes.Provision
import work.lithos.mutations.{InputUTXO, Token, TxBuilder, UTXO}

import scala.util.control.NonFatal

/**
 * The seven LithosDex transactions.
 *
 * Same shape as [[transactions.rollups.RollupTransactions]]: every method takes the boxes it needs already in
 * hand, builds, signs, and hands back a [[SignedTransaction]] without broadcasting. What to do with it
 * is the caller's business.
 *
 * ── THE SHAPE OF EACH TRANSACTION ────────────────────────────────────────────
 *
 * Both the vault and a provision find their successor at `OUTPUTS(CONTEXT.selfBoxIndex)`, and the pool
 * requires `INPUTS(0).id == SELF.id`, so input and output positions have to line up exactly:
 *
 * {{{
 *   swap      IN  pool                      OUT pool
 *   deposit   IN  pool                      OUT pool, provision, ownerNFT
 *   redeem    IN  pool, provision, ownerNFT OUT pool, payout        (NFT burned)
 *   flush     IN  pool, vault               OUT pool, vault
 *   claim     IN  vault, provisions...      OUT vault, successors...
 *   resize    IN  pool, provision, vault    OUT pool, provision, vault
 *   refresh   IN  provision                 OUT provision
 * }}}
 *
 * Wallet funding is appended after those fixed positions, where nothing looks at it.
 */
object LithosDexTransactions {

  /** Value put into a freshly minted ownership NFT box. It carries EIP-4 registers and nothing else. */
  final val OWNER_BOX_VALUE: Long = Parameters.MinFee

  /** Network fee on every LithosDex transaction, and the headroom asked of the wallet on top of it. */
  final val TX_FEE: Long = Parameters.MinFee * 2
  private final val FUNDING_HEADROOM: Long = Parameters.MinFee * 3

  /** Release a reservation when local construction or signing fails. */
  private def funded[A](walletSelector: WalletSelector,
                        value: Long,
                        tokens: Seq[Token] = Seq.empty)
                       (build: WalletReservation => A): A = {
    val reservation = walletSelector.reserve(value, tokens)
    try build(reservation)
    catch {
      case NonFatal(ex) =>
        reservation.release()
        throw ex
    }
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  SWAP — pool op 0
  // ══════════════════════════════════════════════════════════════════════════

  /**
   * Swap against the pool.
   *
   * The fee taken out of the input is set aside in `pending`, and the accumulator it funds advances by
   * exactly that over the supply live to earn it. Only a swap ever moves an accumulator.
   *
   * @param minOutput floor the caller quoted at. Checked here against the pool as it stands right now,
   *                  which is the whole point — the contract has no notion of the caller's price, so if
   *                  the pool moved since the quote this is the only thing standing between the caller
   *                  and execution at any price at all.
   */
  def swap(ctx: BlockchainContext,
           wallet: NodeWallet,
           walletSelector: WalletSelector,
           poolBox: InputUTXO,
           amountIn: Long,
           ergIn: Boolean,
           minOutput: Long): LDSwapTx = {
    val p = LDLiquidityPool(poolBox)
    val q = p.simSwap(amountIn, ergIn)

    require(q.isExecutable,
      s"the pool cannot fill this swap: ${q.tradedIn} reaches the curve for ${q.amountOut} out " +
        s"of a ${q.reserveOut} reserve")
    require(q.amountOut >= minOutput,
      s"the pool has moved since the quote — it would return ${q.amountOut}, below the ${minOutput} floor")

    val advance = (BigInt(q.protocolFee) * LDHelpers.SCALE) / BigInt(p.supply)
    val out =
      if (ergIn)
        poolUTXO(ctx, p,
          reservesX = p.reservesX + q.tradedIn,
          reservesY = p.reservesY - q.amountOut,
          pendingX = p.pendingX + q.protocolFee,
          pendingY = p.pendingY,
          supply = p.supply,
          provTokens = p.provTokensLeft,
          accX = p.accX + advance,
          accY = p.accY)
      else
        poolUTXO(ctx, p,
          reservesX = p.reservesX - q.amountOut,
          reservesY = p.reservesY + q.tradedIn,
          pendingX = p.pendingX,
          pendingY = p.pendingY + q.protocolFee,
          supply = p.supply,
          provTokens = p.provTokensLeft,
          accX = p.accX,
          accY = p.accY + advance)

    val fundingValue = if (ergIn) amountIn + FUNDING_HEADROOM else FUNDING_HEADROOM
    val fundingTokens = if (ergIn) Seq.empty else Seq(Token(p.tokenY, amountIn))
    funded(walletSelector, fundingValue, fundingTokens) { reservation =>
      val funding = reservation.inputs
      val uTx = build(ctx, poolInput(poolBox, LDHelpers.POOL_SWAP) +: funding, Seq(out), wallet)
      LDSwapTx(wallet.sign(uTx), q, reservation)
    }
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  DEPOSIT — pool op 1
  // ══════════════════════════════════════════════════════════════════════════

  /**
   * Open a provision: liquidity in, a provision token out, and the ownership NFT minted from the pool
   * box's own id — which is why it can exist nowhere else.
   *
   * The provision enters at the pool's current accumulators, so it is owed nothing that accrued before
   * it arrived and no snapshot is needed anywhere.
   */
  def deposit(ctx: BlockchainContext,
              wallet: NodeWallet,
              walletSelector: WalletSelector,
              poolBox: InputUTXO,
              shares: Long): LDDepositTx = {
    val p = LDLiquidityPool(poolBox)
    require(p.canDeposit, "the pool has no provision tokens left to hand out")
    val q = p.simDepositForShares(shares)

    // The NFT's id can only be the pool box's id, since the pool is INPUTS(0). That is what makes it
    // unmintable anywhere else, and it is why this cannot be quoted ahead of the box it will spend.
    val ownerNFT = poolBox.id

    val poolOut = poolUTXO(ctx, p,
      reservesX = p.reservesX + q.amountX,
      reservesY = p.reservesY + q.amountY,
      pendingX = p.pendingX,
      pendingY = p.pendingY,
      supply = p.supply + q.shares,
      provTokens = p.provTokensLeft - 1L,
      accX = p.accX,
      accY = p.accY)

    val provisionOut = provisionUTXO(ctx, p.provToken, q.entryX, q.entryY, ownerNFT, q.shares,
      LDHelpers.PROVISION_MIN)
    val ownerOut = ownerNftUTXO(ctx, wallet, p, ownerNFT, q.entryX, q.entryY, q.shares, q.amountX, q.amountY)

    funded(walletSelector,
      q.amountX + LDHelpers.PROVISION_MIN + OWNER_BOX_VALUE + FUNDING_HEADROOM,
      Seq(Token(p.tokenY, q.amountY))) { reservation =>
      val funding = reservation.inputs
      val uTx = build(ctx, poolInput(poolBox, LDHelpers.POOL_DEPOSIT) +: funding,
        Seq(poolOut, provisionOut, ownerOut), wallet)
      val signed = wallet.sign(uTx)
      LDDepositTx(signed, q, signed.getOutputsToSpend.get(1).getId.toString, ownerNFT, reservation)
    }
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  REDEEM — pool op 2, provision op 1
  // ══════════════════════════════════════════════════════════════════════════

  /**
   * Close a provision. The whole provision leaves and its token comes home; the ownership NFT is burned
   * so nothing is left pointing at a provision that no longer exists.
   *
   * Fees settled but not yet claimed are forfeited — they simply stay in the vault, owed to nobody.
   * Refusing that here would be wrong (the contract permits it, and a holder may not care), so the
   * caller is the one that has to decide, from `unclaimedX` / `unclaimedY` on the quote.
   */
  def redeem(ctx: BlockchainContext,
             wallet: NodeWallet,
             walletSelector: WalletSelector,
             poolBox: InputUTXO,
             provision: Provision): LDRedeemTx = {
    val p = LDLiquidityPool(poolBox)
    val q = p.simRedeem(provision.shares)
    require(q.withinMinSupply,
      s"closing this provision would take supply under ${LDHelpers.MIN_SUPPLY}, which the pool refuses")

    val poolOut = poolUTXO(ctx, p,
      reservesX = p.reservesX - q.amountX,
      reservesY = p.reservesY - q.amountY,
      pendingX = p.pendingX,
      pendingY = p.pendingY,
      supply = p.supply - provision.shares,
      provTokens = p.provTokensLeft + 1L,
      accX = p.accX,
      accY = p.accY)

    // OUTPUTS(1) has to exist: the provision sits at INPUTS(1) and reads OUTPUTS(selfBoxIndex).
    val payout = UTXO(wallet.contract, q.amountX + provision.value,
      if (q.amountY > 0) Seq(Token(p.tokenY, q.amountY)) else Seq.empty[Token])

    // Ownership is selected and reserved atomically with fee funding. Looking the NFT box up through
    // the node separately lets another DEX/emission/rollup request reserve the same wallet input.
    funded(walletSelector, FUNDING_HEADROOM, Seq(Token(provision.ownerNFT, 1L))) { reservation =>
      val walletInputs = reservation.inputs
      val inputs = Seq(
        poolInput(poolBox, LDHelpers.POOL_REDEEM),
        provisionInput(ctx, provision.box, LDHelpers.PROV_REDEEM)) ++ walletInputs

      val uTx = build(ctx, inputs, Seq(poolOut, payout), wallet,
        burn = Seq(Token(provision.ownerNFT, 1L)))
      LDRedeemTx(wallet.sign(uTx), q, reservation)
    }
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  FLUSH — pool op 3, vault op 1
  // ══════════════════════════════════════════════════════════════════════════

  /**
   * Move the pool's pending fees into the vault, along with the accumulators they paid for.
   *
   * Permissionless, and it pays the caller nothing — it benefits every provider at once by turning
   * fees the vault cannot yet pay into fees it can.
   */
  def flush(ctx: BlockchainContext,
            wallet: NodeWallet,
            walletSelector: WalletSelector,
            poolBox: InputUTXO,
            vaultBox: InputUTXO): LDFlushTx = {
    val p = LDLiquidityPool(poolBox)
    val v = LDFeeValue.fromBox(vaultBox, 0, ctx.getHeight)
    require(p.canFlush, "nothing is pending — the pool refuses a flush that moves nothing")

    val poolOut = poolUTXO(ctx, p,
      reservesX = p.reservesX, reservesY = p.reservesY,
      pendingX = 0L, pendingY = 0L,
      supply = p.supply, provTokens = p.provTokensLeft,
      accX = p.accX, accY = p.accY)

    val vaultOut = vaultUTXO(ctx, v, Some(p.tokenY),
      balanceX = v.balanceX + p.pendingX,
      balanceY = v.balanceY + p.pendingY,
      accX = p.accX, accY = p.accY)

    funded(walletSelector, FUNDING_HEADROOM) { reservation =>
      val funding = reservation.inputs
      val inputs = Seq(
        poolInput(poolBox, LDHelpers.POOL_FLUSH),
        vaultInput(vaultBox, LDHelpers.VAULT_FLUSH)) ++ funding

      val uTx = build(ctx, inputs, Seq(poolOut, vaultOut), wallet)
      LDFlushTx(wallet.sign(uTx), p.pendingX, p.pendingY, reservation)
    }
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  CLAIM — vault op 0, provision op 0. The pool is not involved.
  // ══════════════════════════════════════════════════════════════════════════

  /**
   * Settle provisions against the vault.
   *
   * The vault sits at INPUTS(0) and the provisions at 1..count, each producing its successor at the
   * matching output index. What is paid is what was OWED, computed by the vault from each provision's
   * own entry rather than from what the outputs happen to move — which is what stops a claimant
   * overpaying one provision at the others' expense.
   */
  def claim(ctx: BlockchainContext,
            wallet: NodeWallet,
            walletSelector: WalletSelector,
            vaultBox: InputUTXO,
             provisions: Seq[Provision]): LDClaimTx = {
    require(provisions.nonEmpty, "a claim must settle at least one provision")
    val v = LDFeeValue.fromBox(vaultBox, 0, ctx.getHeight)

    provisions.foreach { p =>
      require(v.canClaim(p.entryX, p.entryY),
        s"provision ${p.boxId} has nothing to settle — the vault has not been flushed since it last claimed")
    }

    val owed = provisions.map(p => v.owed(p.shares, p.entryX, p.entryY))
    val owedX = owed.map(_._1).sum
    val owedY = owed.map(_._2).sum
    require(v.payableX >= owedX,
      s"the vault can only part with ${v.payableX} nanoERG of the $owedX owed — " +
        s"${LDHelpers.VAULT_MIN} is held back for storage rent and is never payable")
    require(v.payableY >= owedY, s"the vault holds only ${v.payableY} tokens of the $owedY owed")

    val vaultOut = vaultUTXO(ctx, v, None,
      balanceX = v.balanceX - owedX,
      balanceY = v.balanceY - owedY,
      accX = v.accX, accY = v.accY)

    // Each successor must sit at its own input index and carry the vault's accumulator forward exactly.
    // Leaving one short would let the same stretch be settled twice.
    val provOuts = provisions.map(p =>
      provisionUTXO(ctx, p.box.tokens.head.id, v.accX, v.accY, p.ownerNFT, p.shares, p.value))

    val ownerRequirements = provisions.map(p => Token(p.ownerNFT, 1L))
    funded(walletSelector, FUNDING_HEADROOM, ownerRequirements) { reservation =>
      val walletInputs = reservation.inputs
      val inputs =
        Seq(vaultInput(vaultBox, LDHelpers.VAULT_CLAIM, provisions.size)) ++
          provisions.map(p => provisionInput(ctx, p.box, LDHelpers.PROV_CLAIM)) ++
          walletInputs

      val uTx = build(ctx, inputs, vaultOut +: provOuts, wallet)
      LDClaimTx(wallet.sign(uTx), owedX, owedY, reservation)
    }
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  RESIZE — pool op 4, provision op 3, vault op 1
  // ══════════════════════════════════════════════════════════════════════════

  /**
   * Change a provision's size without closing it, and flush in the same transaction.
   *
   * The flush is not optional. Preserving what is owed against the pool's accumulator would leave it
   * wrong against the vault's by the size change times the distance between them — a shrinking
   * provision would carry a claim on fees the vault has never been given. Closing that distance to
   * nothing is the only way the identity holds on a pool that is still trading.
   */
  def resize(ctx: BlockchainContext,
             wallet: NodeWallet,
             walletSelector: WalletSelector,
             poolBox: InputUTXO,
             vaultBox: InputUTXO,
             provision: Provision,
             newShares: Long): LDResizeTx = {
    val p = LDLiquidityPool(poolBox)
    val v = LDFeeValue.fromBox(vaultBox, 0, ctx.getHeight)
    val q = p.simResize(provision.shares, newShares, provision.entryX, provision.entryY)
    require(q.withinMinSupply,
      s"this resize would take supply under ${LDHelpers.MIN_SUPPLY}, which the pool refuses")

    val (nextReservesX, nextReservesY) =
      if (q.increasing) (p.reservesX + q.amountX, p.reservesY + q.amountY)
      else (p.reservesX - q.amountX, p.reservesY - q.amountY)

    val poolOut = poolUTXO(ctx, p,
      reservesX = nextReservesX, reservesY = nextReservesY,
      pendingX = 0L, pendingY = 0L,
      supply = p.supply + q.delta,
      provTokens = p.provTokensLeft,
      accX = p.accX, accY = p.accY)

    val provOut = provisionUTXO(ctx, p.provToken, q.nextEntryX, q.nextEntryY, provision.ownerNFT,
      newShares, provision.value)

    val vaultOut = vaultUTXO(ctx, v, Some(p.tokenY),
      balanceX = v.balanceX + p.pendingX,
      balanceY = v.balanceY + p.pendingY,
      accX = p.accX, accY = p.accY)

    val fundingRequirements =
      Seq(Token(provision.ownerNFT, 1L)) ++
        (if (q.increasing) Seq(Token(p.tokenY, q.amountY)) else Seq.empty)
    funded(walletSelector,
      (if (q.increasing) q.amountX else 0L) + FUNDING_HEADROOM,
      fundingRequirements) { reservation =>
      val funding = reservation.inputs
      val inputs = Seq(
        poolInput(poolBox, LDHelpers.POOL_RESIZE),
        provisionInput(ctx, provision.box, LDHelpers.PROV_RESIZE),
        vaultInput(vaultBox, LDHelpers.VAULT_FLUSH)) ++ funding

      val uTx = build(ctx, inputs, Seq(poolOut, provOut, vaultOut), wallet)
      val signed = wallet.sign(uTx)
      LDResizeTx(signed, q, signed.getOutputsToSpend.get(1).getId.toString, reservation)
    }
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  REFRESH — provision op 2. Permissionless.
  // ══════════════════════════════════════════════════════════════════════════

  /**
   * Re-create a provision box unchanged so its age resets.
   *
   * Anyone may do this — renewing costs a fee and moves nothing, so the only reason to do it is to keep
   * a box alive, including one whose owner has lost their key. Not in the API spec, but the contract
   * supports it and boxes that sit for years will need it before storage rent reaches them.
   */
  def refresh(ctx: BlockchainContext,
              wallet: NodeWallet,
              walletSelector: WalletSelector,
              provision: Provision): LDRefreshTx = {
    val height = ctx.getHeight
    require(provision.createdHeight < height - LDHelpers.REFRESH_AGE,
      s"provision ${provision.boxId} was created at ${provision.createdHeight} and is not old enough — " +
        s"refresh needs a creation height below ${height - LDHelpers.REFRESH_AGE}")

    // The successor must be genuinely young, not merely younger: monotonicity alone would let a
    // refresher hand back something instantly refreshable and spend it on repeat.
    val out = provisionUTXO(ctx, provision.box.tokens.head.id, provision.entryX, provision.entryY,
      provision.ownerNFT, provision.shares, provision.value).setCreationHeight(height)

    funded(walletSelector, FUNDING_HEADROOM) { reservation =>
      val funding = reservation.inputs
      val inputs = provisionInput(ctx, provision.box, LDHelpers.PROV_REFRESH) +: funding

      val uTx = build(ctx, inputs, Seq(out), wallet)
      val signed = wallet.sign(uTx)
      LDRefreshTx(signed, signed.getOutputsToSpend.get(0).getId.toString, reservation)
    }
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  BOX BUILDERS
  // ══════════════════════════════════════════════════════════════════════════

  /**
   * R4 liquidity (the unissued remainder, not the supply), R5 feeParams, R6 pending, R7 accX, R8 accY.
   * Tokens: 0 pool NFT, 1 tokenY, 2 provision token.
   *
   * Ids and fee params come from the pool being spent rather than from `LDHelpers`, so a successor is
   * built against what the box actually holds.
   */
  private def poolUTXO(ctx: BlockchainContext,
                       p: LDLiquidityPool,
                       reservesX: Long,
                       reservesY: Long,
                       pendingX: Long,
                       pendingY: Long,
                       supply: Long,
                       provTokens: Long,
                       accX: BigInt,
                       accY: BigInt): UTXO =
    UTXO(DexContracts(ctx).liquidityPool, reservesX + pendingX,
      Seq(Token(p.poolNFT, 1L),
        Token(p.tokenY, reservesY + pendingY),
        Token(p.provToken, provTokens)),
      Seq(
        ErgoValue.of(LDHelpers.LOCKED_LP - supply),
        longsValue(p.feeParams),
        longsValue(Array(pendingX, pendingY)),
        bigIntValue(accX),
        bigIntValue(accY)))

  /**
   * R4 accX, R5 accY. Tokens: 0 vault NFT, 1 tokenY once a flush has carried any.
   *
   * @param arriving the token a flush is about to bring in. The vault has no token entry at all until
   *                 the first flush that carries one, so on that transaction the id can only come from
   *                 the pool; afterwards the vault's own entry is the authority. A claim brings nothing
   *                 in and passes None.
   */
  private def vaultUTXO(ctx: BlockchainContext,
                        v: LDFeeValue,
                        arriving: Option[ErgoId],
                        balanceX: Long,
                        balanceY: Long,
                        accX: BigInt,
                        accY: BigInt): UTXO = {
    val tokenEntry =
      if (balanceY <= 0) Seq.empty[Token]
      else Seq(Token(v.tokenY.orElse(arriving).getOrElse(throw new IllegalStateException(
        s"the vault would hold $balanceY tokens but neither it nor the flush names which token")), balanceY))

    UTXO(DexContracts(ctx).feeVault, balanceX,
      Seq(Token(v.vaultNFT, 1L)) ++ tokenEntry,
      Seq(bigIntValue(accX), bigIntValue(accY)))
  }

  /** R4 entryX, R5 entryY, R6 ownerNFT, R7 shares. One provision token, under the guard. */
  private def provisionUTXO(ctx: BlockchainContext,
                            provToken: ErgoId,
                            entryX: BigInt,
                            entryY: BigInt,
                            ownerNFT: ErgoId,
                            shares: Long,
                            value: Long): UTXO =
    UTXO(DexContracts(ctx).provisionGuard, value, Seq(Token(provToken, 1L)),
      Seq(bigIntValue(entryX), bigIntValue(entryY), bytesValue(ownerNFT.getBytes), ErgoValue.of(shares)))

  /**
   * The ownership NFT, carrying EIP-4 registers so a wallet or explorer shows it as something legible
   * rather than an unnamed singleton. R4 name, R5 description, R6 decimals.
   *
   * The description carries the provision's own registers as JSON, so the NFT is self-describing:
   * whoever holds it can see what it is a claim on without looking up the provision box. Those values
   * are a SNAPSHOT taken at deposit, the provision box is always the authority in the end.
   */
  private def ownerNftUTXO(ctx: BlockchainContext,
                           wallet: NodeWallet,
                           p: LDLiquidityPool,
                           tokenId: ErgoId,
                           entryX: BigInt,
                           entryY: BigInt,
                           shares: Long,
                           depositedX: Long,
                           depositedY: Long): UTXO =
    UTXO(wallet.contract, OWNER_BOX_VALUE, Seq(Token(tokenId, 1L)))
      .withReg(0, utf8(provisionNftName(tokenId)))
      .withReg(1, utf8(provisionNftDescription(ctx, p, tokenId, entryX, entryY, shares, depositedX, depositedY)))
      .withReg(2, utf8("0")) // an ownership NFT is indivisible

  /** `LD-NFT-` plus the first six characters of the token id, which is the pool box that minted it. */
  def provisionNftName(tokenId: ErgoId): String = s"LD-NFT-${tokenId.toString.take(6)}"

  /** The provision's registers as compact JSON, plus what it took to open. */
  def provisionNftDescription(ctx: BlockchainContext,
                              p: LDLiquidityPool,
                              tokenId: ErgoId,
                              entryX: BigInt,
                              entryY: BigInt,
                              shares: Long,
                              depositedX: Long,
                              depositedY: Long): String =
    // Every value is a hex id or a number, so nothing here needs escaping. The accumulators are scaled
    // by 1e27 and go well past a double, so they are strings rather than JSON numbers.
    Seq(
      s""""type":"LithosDex Provision"""",
      s""""pool":"${p.poolNFT}"""",
      s""""tokenY":"${p.tokenY}"""",
      s""""provisionToken":"${p.provToken}"""",
      s""""R4_entryX":"$entryX"""",
      s""""R5_entryY":"$entryY"""",
      s""""R6_ownerNFT":"$tokenId"""",
      s""""R7_shares":$shares""",
      s""""depositedX":$depositedX""",
      s""""depositedY":$depositedY""",
      s""""height":${ctx.getHeight}"""
    ).mkString("{", ",", "}")

  // ══════════════════════════════════════════════════════════════════════════
  //  CONTEXT VARS
  // ══════════════════════════════════════════════════════════════════════════
  //
  // Always through DexContracts.attachCtxVars until ContextVar fix is made.

  private def poolInput(box: InputUTXO, op: Byte): InputUTXO =
    DexContracts.attachCtxVars(box, Seq(0.toByte -> ErgoValue.of(op)))

  private def vaultInput(box: InputUTXO, op: Byte, count: Int = -1): InputUTXO =
    DexContracts.attachCtxVars(box,
      Seq[(Byte, ErgoValue[_])](0.toByte -> ErgoValue.of(op)) ++
        (if (count >= 0) Seq[(Byte, ErgoValue[_])](1.toByte -> ErgoValue.of(count)) else Seq.empty))

  /** Every provision input needs its op AND the provision logic the guard executes from var 64. */
  private def provisionInput(ctx: BlockchainContext, box: InputUTXO, op: Byte): InputUTXO =
    DexContracts.attachCtxVars(box, Seq[(Byte, ErgoValue[_])](
      0.toByte -> ErgoValue.of(op),
      LDHelpers.PROVISION_LOGIC_VAR -> bytesValue(DexContracts(ctx).provisionLogic.valueBytes)))

  // ══════════════════════════════════════════════════════════════════════════
  //  TRANSACTION SHAPE
  // ══════════════════════════════════════════════════════════════════════════

  /**
   * An explicit fee output rather than `buildTx(fee, ...)`. `TxBuilder.buildTx` folds a below-minimum
   * change amount into the fee output and finds it with `indexWhere`, which throws when there is none.
   *
   * Inputs are deduplicated by box id, first occurrence winning. The selector normally makes every
   * wallet input unique, while this remains a final structural guard against an invalid transaction.
   */
  private def build(ctx: BlockchainContext,
                    inputs: Seq[InputUTXO],
                    outputs: Seq[UTXO],
                    wallet: NodeWallet,
                    burn: Seq[Token] = Seq.empty[Token]): UnsignedTransaction = {
    val distinct = dedupe(inputs)
    TxBuilder(ctx)
      .setInputs(distinct: _*)
      .setOutputs(outputs :+ UTXO.feeBox(TX_FEE): _*)
      .buildTx(0, wallet.p2pk, burn)
  }

  private def dedupe(inputs: Seq[InputUTXO]): Seq[InputUTXO] = {
    val seen = scala.collection.mutable.LinkedHashMap.empty[String, InputUTXO]
    inputs.foreach(i => if (!seen.contains(i.id.toString)) seen.put(i.id.toString, i))
    seen.values.toSeq
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  REGISTER VALUES
  // ══════════════════════════════════════════════════════════════════════════

  private def bigIntValue(v: BigInt): ErgoValue[_] = ErgoValue.of(v.bigInteger)

  private def longsValue(v: Array[Long]): ErgoValue[_] = ErgoValue.of(Colls.fromArray(v), scalaLongType)

  private def bytesValue(bytes: Array[Byte]): ErgoValue[_] =
    ErgoValue.of(Colls.fromArray(bytes), scalaByteType)

  private def utf8(s: String): ErgoValue[_] =
    ErgoValue.of(s.getBytes(java.nio.charset.StandardCharsets.UTF_8))
}

sealed trait LDFundedTx {
  def tx: SignedTransaction
  def reservation: WalletReservation
}

/** @param quote the swap as the pool would execute it, quoted against the very box being spent */
case class LDSwapTx(tx: SignedTransaction,
                    quote: lithosdex.LDSwapQuote,
                    reservation: WalletReservation) extends LDFundedTx

/**
 * @param provisionBoxId id of the provision box this transaction creates, known only once signed
 * @param ownerNFT       the minted NFT that owns it — the pool box's id
 */
case class LDDepositTx(tx: SignedTransaction,
                       quote: lithosdex.LDDepositQuote,
                       provisionBoxId: String,
                       ownerNFT: ErgoId,
                       reservation: WalletReservation) extends LDFundedTx

case class LDRedeemTx(tx: SignedTransaction,
                      quote: lithosdex.LDRedeemQuote,
                      reservation: WalletReservation) extends LDFundedTx

case class LDFlushTx(tx: SignedTransaction,
                     flushedX: Long,
                     flushedY: Long,
                     reservation: WalletReservation) extends LDFundedTx

case class LDClaimTx(tx: SignedTransaction,
                     claimedX: Long,
                     claimedY: Long,
                     reservation: WalletReservation) extends LDFundedTx

/** @param boxId id of the resized provision's successor */
case class LDResizeTx(tx: SignedTransaction,
                      quote: lithosdex.LDResizeQuote,
                      boxId: String,
                      reservation: WalletReservation) extends LDFundedTx

case class LDRefreshTx(tx: SignedTransaction,
                       boxId: String,
                       reservation: WalletReservation) extends LDFundedTx
