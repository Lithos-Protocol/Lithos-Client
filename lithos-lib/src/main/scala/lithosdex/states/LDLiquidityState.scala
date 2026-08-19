package lithosdex.states

import lfsm.states.UTXOState
import lithosdex.{LDHelpers, LDLiquidityPool}
import org.ergoplatform.appkit.NetworkType
import org.ergoplatform.sdk.ErgoId
import work.lithos.mutations.InputUTXO
import sigma.data.CBigInt

/**
 * The pool box as tracked off-chain.
 *
 * There is no tree and nothing to synchronize any more: the pool's whole state is five registers and
 * three token balances, and a provision's entitlement is derived from the accumulators rather than
 * recorded anywhere. Following the pool means following one box.
 *
 * @param reservesX      ERG the AMM trades with: box value less pendingX
 * @param reservesY      tokens the AMM trades with: token balance less pendingY
 * @param pendingX       R6(0), ERG fees accrued but not yet flushed to the vault
 * @param pendingY       R6(1), the token side of the same
 * @param supply         LP shares outstanding, CONST_LOCKED_LP less R4
 * @param feeParams      R5, [dexFee, ergFee, tokenFee] over [[LDHelpers.FEE_DENOM]]
 * @param accX           R7, cumulative ERG fee per share, scaled by [[LDHelpers.SCALE]]
 * @param accY           R8, the token side of the same
 * @param provTokensLeft provision tokens still held by the pool; one leaves per deposit
 * @param syncHeight     height this snapshot was taken at
 */
case class LDLiquidityState(reservesX: Long,
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
                            startHeight: Int,
                            utxoId: String,
                            syncHeight: Int) extends UTXOState {

  /** Value the pool box holds: reserves plus the fees it is carrying for the vault. */
  def boxValue: Long = reservesX + pendingX

  /** Token balance of the pool box, on the same basis. */
  def tokenBalance: Long = reservesY + pendingY

  /** R4, which is the unissued remainder rather than the supply itself. */
  def liquidityReg: Long = LDHelpers.LOCKED_LP - supply

  /** Quote against this snapshot. */
  def pool: LDLiquidityPool = LDLiquidityPool(this)
}

object LDLiquidityState {

  /** Read a pool box, recording the height it was seen at. */
  def fromBox(box: InputUTXO, startHeight: Int, syncHeight: Int): LDLiquidityState = {
    val tokenY    = box.tokens(1)
    val provToken = box.tokens(2)
    val pending   = box.parseCollReg[Long](2)

    LDLiquidityState(
      reservesX = box.value - pending(0),
      reservesY = tokenY.amount - pending(1),
      pendingX = pending(0),
      pendingY = pending(1),
      supply = LDHelpers.LOCKED_LP - box.parseReg[Long](0),
      feeParams = box.parseCollReg[Long](1),
      accX = BigInt(box.registers(3).getValue.asInstanceOf[CBigInt].wrappedValue),
      accY = BigInt(box.registers(4).getValue.asInstanceOf[CBigInt].wrappedValue),
      poolNFT = box.tokens.head.id,
      tokenY = tokenY.id,
      provToken = provToken.id,
      provTokensLeft = provToken.amount,
      startHeight = startHeight,
      utxoId = box.id.toString,
      syncHeight = syncHeight
    )
  }

  /**
   * The pool as minted. Both accumulators start at zero, nothing is pending, and the supply issued is
   * less than [[LDHelpers.LOCKED_LP]] so a locked remainder stays behind that no redemption can reach.
   */
  def genesis(reservesX: Long, reservesY: Long, networkType: NetworkType): LDLiquidityState = {
    LDLiquidityState(
      reservesX = reservesX,
      reservesY = reservesY,
      pendingX = 0L,
      pendingY = 0L,
      supply = LDHelpers.GENESIS_SUPPLY,
      feeParams = LDHelpers.GENESIS_FEE_PARAMS,
      accX = BigInt(0),
      accY = BigInt(0),
      poolNFT = LDHelpers.getPoolNFT(networkType),
      tokenY = LDHelpers.getTokenY(networkType),
      provToken = LDHelpers.getProvToken(networkType),
      provTokensLeft = LDHelpers.PROV_TOKEN_SUPPLY,
      startHeight = LDHelpers.GENESIS_HEIGHT,
      utxoId = LDHelpers.POOL_GENESIS_ID,
      syncHeight = LDHelpers.GENESIS_HEIGHT
    )
  }
}
