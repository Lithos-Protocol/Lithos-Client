package lithosdex

import org.ergoplatform.sdk.ErgoId
import sigma.data.CBigInt
import work.lithos.mutations.InputUTXO
import lithosdex.states.LDLiquidityState

/**
 * A read-only view of the pool, and every number an API needs to quote against it.
 *
 * Nothing here builds or signs a transaction. Each `sim` method answers "if this were done right now,
 * what would come out" using exactly the arithmetic LD_LiquidityPool enforces — same truncation, same
 * ordering — so a quote that says a swap yields N is a swap the contract will accept at N.
 *
 * X is ERG, Y is the pool's token, matching the contract's own naming.
 *
 * @param reservesX  ERG the AMM trades with: box value less pending fees
 * @param reservesY  tokens the AMM trades with: token balance less pending fees
 * @param pendingX   ERG fees accrued but not yet flushed to the vault
 * @param pendingY   token fees accrued but not yet flushed
 * @param supply     LP shares outstanding, CONST_LOCKED_LP less R4
 * @param feeParams  [dexFee, ergFee, tokenFee] numerators over [[LDHelpers.FEE_DENOM]]
 * @param accX       cumulative ERG fee per share, scaled by [[LDHelpers.SCALE]]
 * @param accY       cumulative token fee per share, same scale
 * @param provTokensLeft provision tokens still in the pool; one leaves per deposit
 */
case class LDLiquidityPool(reservesX: Long,
                           reservesY: Long,
                           pendingX: Long,
                           pendingY: Long,
                           supply: Long,
                           feeParams: Array[Long],
                           accX: BigInt,
                           accY: BigInt,
                           poolNFT: ErgoId,
                           tokenY: ErgoId,
                           provToken: ErgoId,
                           provTokensLeft: Long,
                           box: Option[InputUTXO] = None) {

  private val D = LDHelpers.FEE_DENOM

  /** Numerator of the fee the AMM keeps for itself. Accrues to everyone holding shares. */
  val dexFeeNum: Long = feeParams(0)

  /** Numerator of the fee taken out of an incoming ERG swap and set aside for providers. */
  val ergFeeNum: Long = feeParams(1)

  /** Numerator of the fee taken out of an incoming token swap and set aside for providers. */
  val tokenFeeNum: Long = feeParams(2)

  /** What the pool charges a swap, as a fraction. dex fee plus whichever side fee applies. */
  val dexFeeRate: Double   = (D - dexFeeNum).toDouble / D.toDouble
  val ergFeeRate: Double   = ergFeeNum.toDouble / D.toDouble
  val tokenFeeRate: Double = tokenFeeNum.toDouble / D.toDouble

  /** Value of the pool box: reserves plus the fees it is holding for the vault. */
  val boxValue: Long   = reservesX + pendingX
  val tokenBalance: Long = reservesY + pendingY

  /** R4. The pool stores the unissued remainder rather than the supply itself. */
  val liquidityReg: Long = LDHelpers.LOCKED_LP - supply

  /** Tokens per ERG, ignoring fees and depth. */
  val spotPriceY: BigDecimal = if (reservesX == 0) BigDecimal(0) else BigDecimal(reservesY) / BigDecimal(reservesX)

  /** ERG per token, ignoring fees and depth. */
  val spotPriceX: BigDecimal = if (reservesY == 0) BigDecimal(0) else BigDecimal(reservesX) / BigDecimal(reservesY)

  /** Fees waiting on a flush. Until one happens, the vault cannot pay them out. */
  val pendingFees: (Long, Long) = (pendingX, pendingY)

  /** A flush needs something to move, or the pool rejects it. */
  val canFlush: Boolean = pendingX > 0 || pendingY > 0

  /** Shares that could still be redeemed before hitting CONST_MIN_SUPPLY. */
  val redeemableSupply: Long = math.max(0L, supply - LDHelpers.MIN_SUPPLY)

  /** A deposit needs a provision token to hand out. */
  val canDeposit: Boolean = provTokensLeft > 0

  // -----------------------------------------------------------------------------------------------
  // Swaps
  // -----------------------------------------------------------------------------------------------

  /**
   * Quote a swap of `amountIn` into the pool.
   *
   * @param amountIn total handed to the pool, fees included — this is what leaves the user's wallet
   * @param ergIn    true to sell ERG for the token, false to sell the token for ERG
   */
  def simSwap(amountIn: Long, ergIn: Boolean): LDSwapQuote = {
    require(amountIn > 0, "Swap input must be positive")

    val sideFeeNum = if (ergIn) ergFeeNum else tokenFeeNum
    val protocolFee = (BigInt(amountIn) * sideFeeNum / D).toLong
    val traded = amountIn - protocolFee

    val (reserveIn, reserveOut) = if (ergIn) (reservesX, reservesY) else (reservesY, reservesX)
    val amountOut =
      if (traded <= 0) 0L
      else ((BigInt(reserveOut) * traded * dexFeeNum) /
            (BigInt(reserveIn) * D + BigInt(traded) * dexFeeNum)).toLong

    // What the constant-product curve would have returned with no dex fee at all
    val ammFee = if (traded <= 0) 0L else (BigInt(traded) * (D - dexFeeNum) / D).toLong

    LDSwapQuote(ergIn, amountIn, protocolFee, ammFee, traded, amountOut,
      spotBefore = if (ergIn) spotPriceY else spotPriceX,
      reserveIn = reserveIn, reserveOut = reserveOut)
  }

  /**
   * The other direction: what must go in for `amountOut` to come out.
   *
   * Returns None when the pool cannot produce that much — an output must stay strictly under the
   * reserve it is drawn from, since the curve only approaches it.
   */
  def simSwapForOutput(amountOut: Long, ergIn: Boolean): Option[LDSwapQuote] = {
    require(amountOut > 0, "Swap output must be positive")

    val (reserveIn, reserveOut) = if (ergIn) (reservesX, reservesY) else (reservesY, reservesX)
    if (amountOut >= reserveOut) return None

    // Invert the AMM inequality for the traded amount, rounding up so the result still satisfies it
    val traded = ceilDiv(BigInt(amountOut) * reserveIn * D, BigInt(dexFeeNum) * (reserveOut - amountOut)).toLong

    // Then invert the side fee. The contract's fee is a truncating division, so step up until the
    // amount left after it actually covers `traded` rather than trusting the closed form.
    val sideFeeNum = if (ergIn) ergFeeNum else tokenFeeNum
    var amountIn = ceilDiv(BigInt(traded) * D, BigInt(D - sideFeeNum)).toLong
    while (amountIn - (BigInt(amountIn) * sideFeeNum / D).toLong < traded) amountIn += 1

    Some(simSwap(amountIn, ergIn))
  }

  // -----------------------------------------------------------------------------------------------
  // Deposits
  // -----------------------------------------------------------------------------------------------

  /**
   * Best deposit for the funds on hand: the most shares `availableX` and `availableY` can buy, and the
   * amounts actually consumed. Whatever is left over is the depositor's change.
   */
  def simDeposit(availableX: Long, availableY: Long): LDDepositQuote = {
    require(availableX > 0 && availableY > 0, "Deposit must supply both sides")

    val sharesX = BigInt(availableX) * supply / reservesX
    val sharesY = BigInt(availableY) * supply / reservesY
    val shares = (if (sharesX <= sharesY) sharesX else sharesY).toLong
    simDepositForShares(shares)
  }

  /** The cheapest deposit that buys exactly `shares`, rounded the way the pool rounds. */
  def simDepositForShares(shares: Long): LDDepositQuote = {
    require(shares > 0, "Deposit must unlock at least one share")

    val amountX = ceilDiv(BigInt(shares) * reservesX, BigInt(supply)).toLong
    val amountY = ceilDiv(BigInt(shares) * reservesY, BigInt(supply)).toLong

    LDDepositQuote(shares, amountX, amountY,
      shareOfSupply = shares.toDouble / (supply + shares).toDouble,
      provisionBoxValue = LDHelpers.PROVISION_MIN,
      entryX = accX, entryY = accY)
  }

  // -----------------------------------------------------------------------------------------------
  // Redemptions
  // -----------------------------------------------------------------------------------------------

  /**
   * What a provision of `shares` is worth if it is closed now.
   *
   * This is the liquidity only. Fees already earned are settled against the vault separately and are
   * not returned here — see [[feesAccrued]].
   */
  def simRedeem(shares: Long): LDRedeemQuote = {
    require(shares > 0, "Redemption must remove at least one share")

    val amountX = (BigInt(shares) * reservesX / supply).toLong
    val amountY = (BigInt(shares) * reservesY / supply).toLong

    LDRedeemQuote(shares, amountX, amountY, withinMinSupply = supply - shares >= LDHelpers.MIN_SUPPLY)
  }

  // -----------------------------------------------------------------------------------------------
  // Resizes
  // -----------------------------------------------------------------------------------------------

  /**
   * Change a provision's size without closing it, in either direction.
   *
   * A resize always flushes in the same transaction, so the entries below are computed against this
   * pool's accumulators — which is what the vault will be holding by the time it matters.
   *
   * @param oldShares the provision's current R7
   * @param newShares what it should become; must be positive, since a size of nothing is a redemption
   * @param entryX    the provision's current R4
   * @param entryY    the provision's current R5
   */
  def simResize(oldShares: Long, newShares: Long, entryX: BigInt, entryY: BigInt): LDResizeQuote = {
    require(newShares > 0, "A resize to zero is a redemption")
    require(newShares != oldShares, "A resize must change the size")

    val delta = newShares - oldShares
    val increasing = delta > 0

    val (amountX, amountY) =
      if (increasing)
        (ceilDiv(BigInt(delta) * reservesX, BigInt(supply)).toLong,
         ceilDiv(BigInt(delta) * reservesY, BigInt(supply)).toLong)
      else
        ((BigInt(-delta) * reservesX / supply).toLong,
         (BigInt(-delta) * reservesY / supply).toLong)

    // Move the entry so that size times the distance to the accumulator comes out unchanged. This is
    // what settles the old size's earnings without paying anything out.
    val nextEntryX = accX - (BigInt(oldShares) * (accX - entryX)) / BigInt(newShares)
    val nextEntryY = accY - (BigInt(oldShares) * (accY - entryY)) / BigInt(newShares)

    LDResizeQuote(oldShares, newShares, delta, increasing, amountX, amountY, nextEntryX, nextEntryY,
      withinMinSupply = supply + delta >= LDHelpers.MIN_SUPPLY)
  }

  // -----------------------------------------------------------------------------------------------
  // Fees
  // -----------------------------------------------------------------------------------------------

  /**
   * Fees a provision has earned as of this pool box, including anything not yet flushed.
   *
   * This is the number to show a provider. What they can actually claim right now is smaller whenever
   * the pool has pending fees, because the vault can only pay against what it has been handed — see
   * [[lithosdex.states.LDFeeValue.owed]] for that figure.
   */
  def feesAccrued(shares: Long, entryX: BigInt, entryY: BigInt): (Long, Long) = {
    val owedX = (BigInt(shares) * (accX - entryX) / LDHelpers.SCALE).toLong
    val owedY = (BigInt(shares) * (accY - entryY) / LDHelpers.SCALE).toLong
    (owedX, owedY)
  }

  /** Share of the pool a provision represents, as a fraction. */
  def shareOfSupply(shares: Long): Double = shares.toDouble / supply.toDouble

  private def ceilDiv(a: BigInt, b: BigInt): BigInt = (a + b - 1) / b
}

object LDLiquidityPool {

  /**
   * Read a pool box.
   *
   * R4 liquidity, R5 feeParams, R6 pending, R7 accX, R8 accY.
   * Tokens: 0 pool NFT, 1 tokenY, 2 provision token.
   */
  def apply(box: InputUTXO): LDLiquidityPool = {
    val poolNFT   = box.tokens.head
    val tokenY    = box.tokens(1)
    val provToken = box.tokens(2)

    val liquidity = box.parseReg[Long](0)
    val feeParams = box.parseCollReg[Long](1)
    val pending   = box.parseCollReg[Long](2)
    val accX      = bigIntReg(box, 3)
    val accY      = bigIntReg(box, 4)

    LDLiquidityPool(
      reservesX = box.value - pending(0),
      reservesY = tokenY.amount - pending(1),
      pendingX = pending(0),
      pendingY = pending(1),
      supply = LDHelpers.LOCKED_LP - liquidity,
      feeParams = feeParams,
      accX = accX,
      accY = accY,
      poolNFT = poolNFT.id,
      tokenY = tokenY.id,
      provToken = provToken.id,
      provTokensLeft = provToken.amount,
      box = Some(box)
    )
  }

  /** Quote against tracked state rather than a box, for simulation and tests. */
  def apply(state: LDLiquidityState): LDLiquidityPool = {
    LDLiquidityPool(
      reservesX = state.reservesX,
      reservesY = state.reservesY,
      pendingX = state.pendingX,
      pendingY = state.pendingY,
      supply = state.supply,
      feeParams = state.feeParams,
      accX = state.accX,
      accY = state.accY,
      poolNFT = state.poolNFT,
      tokenY = state.tokenY,
      provToken = state.provToken,
      provTokensLeft = state.provTokensLeft
    )
  }

  private def bigIntReg(box: InputUTXO, idx: Int): BigInt =
    BigInt(box.registers(idx).getValue.asInstanceOf[CBigInt].wrappedValue)
}

/**
 * @param ergIn       true when ERG was sold for the token
 * @param amountIn    total handed to the pool, fees included
 * @param protocolFee taken out of the input and set aside for liquidity providers
 * @param ammFee      the curve's own fee on the remainder; stays in the pool
 * @param tradedIn    what actually reaches the curve: `amountIn - protocolFee`
 * @param amountOut   what comes back
 */
case class LDSwapQuote(ergIn: Boolean,
                       amountIn: Long,
                       protocolFee: Long,
                       ammFee: Long,
                       tradedIn: Long,
                       amountOut: Long,
                       spotBefore: BigDecimal,
                       reserveIn: Long,
                       reserveOut: Long) {

  /** Every fee the swap pays, on the input side. */
  val totalFee: Long = protocolFee + ammFee

  /** Output per unit of input, fees and depth included. */
  val executionPrice: BigDecimal =
    if (amountIn == 0) BigDecimal(0) else BigDecimal(amountOut) / BigDecimal(amountIn)

  /** Price after the swap lands, ignoring fees. */
  val spotAfter: BigDecimal = {
    val nextIn = reserveIn + tradedIn
    val nextOut = reserveOut - amountOut
    if (nextIn == 0) BigDecimal(0) else BigDecimal(nextOut) / BigDecimal(nextIn)
  }

  /** How far execution fell short of the pre-trade price, as a fraction. Includes fees. */
  val priceImpact: Double =
    if (spotBefore == 0) 0.0 else (1 - (executionPrice / spotBefore)).toDouble

  /** The pool rejects a swap that moves nothing, and cannot fill one that reaches the curve empty. */
  val isExecutable: Boolean = tradedIn > 0 && amountOut > 0 && amountOut < reserveOut

  /** Floor to put in a transaction so the quote stays valid if the pool moves first. */
  def minOutput(slippageTolerance: Double): Long = {
    require(slippageTolerance >= 0 && slippageTolerance < 1, "Slippage tolerance must be in [0, 1)")
    (BigDecimal(amountOut) * (1 - slippageTolerance)).toLong
  }
}

/**
 * @param shares            LP shares the deposit unlocks; this becomes the provision's R7
 * @param amountX           ERG the pool takes
 * @param amountY           tokens the pool takes
 * @param shareOfSupply     fraction of the pool the provision will represent once it lands
 * @param provisionBoxValue ERG the depositor must also put into the new provision box, on top of amountX
 * @param entryX            accumulator the provision enters at; it is owed nothing accrued before this
 * @param entryY            same, token side
 */
case class LDDepositQuote(shares: Long,
                          amountX: Long,
                          amountY: Long,
                          shareOfSupply: Double,
                          provisionBoxValue: Long,
                          entryX: BigInt,
                          entryY: BigInt) {

  /** Total ERG leaving the depositor's wallet, network fee aside. */
  val totalErgRequired: Long = amountX + provisionBoxValue
}

/**
 * @param withinMinSupply false when closing this provision would take supply under CONST_MIN_SUPPLY,
 *                        which the pool refuses
 */
case class LDRedeemQuote(shares: Long,
                         amountX: Long,
                         amountY: Long,
                         withinMinSupply: Boolean)

/**
 * @param delta      change in shares; positive adds liquidity, negative removes it
 * @param amountX    ERG the pool takes when increasing, or returns when decreasing
 * @param amountY    the same on the token side
 * @param nextEntryX entry the resized provision must carry, chosen so nothing already earned is lost
 */
case class LDResizeQuote(oldShares: Long,
                         newShares: Long,
                         delta: Long,
                         increasing: Boolean,
                         amountX: Long,
                         amountY: Long,
                         nextEntryX: BigInt,
                         nextEntryY: BigInt,
                         withinMinSupply: Boolean)
