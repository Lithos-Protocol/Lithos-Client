package lithosdex.states

import lfsm.states.UTXOState
import lithosdex.{LDHelpers, LDT2TLiquidityPool}
import org.ergoplatform.sdk.ErgoId
import sigma.data.CBigInt
import work.lithos.mutations.InputUTXO

/**
 * A token/token pool box as tracked off-chain. Following the pool means following one box, exactly as
 * for an ERG/token pool; only the token layout differs.
 *
 * @param reservesX      token X the AMM trades with: tokens(1) less pendingX
 * @param reservesY      token Y the AMM trades with: tokens(2) less pendingY
 * @param pendingX       R6(0), token X fees accrued but not yet flushed to the vault
 * @param pendingY       R6(1), the token Y side of the same
 * @param supply         LP shares outstanding, CONST_LOCKED_LP less R4
 * @param feeParams      R5, [dexFee, xFee, yFee] over [[LDHelpers.FEE_DENOM]]
 * @param accX           R7, cumulative token X fee per share, scaled by [[LDHelpers.SCALE]]
 * @param accY           R8, the token Y side of the same
 * @param provTokensLeft provision tokens still held by the pool; one leaves per deposit
 * @param value          ERG in the box, storage only
 * @param syncHeight     height this snapshot was taken at
 */
case class LDT2TLiquidityState(reservesX: Long,
                               reservesY: Long,
                               pendingX: Long,
                               pendingY: Long,
                               supply: Long,
                               feeParams: Array[Long],
                               accX: BigInt,
                               accY: BigInt,
                               poolNFT: ErgoId,
                               tokenX: ErgoId,
                               tokenY: ErgoId,
                               provToken: ErgoId,
                               provTokensLeft: Long,
                               value: Long,
                               startHeight: Int,
                               utxoId: String,
                               syncHeight: Int) extends UTXOState {

  /** Token balances of the pool box: reserves plus the fees it is carrying for the vault. */
  def balanceX: Long = reservesX + pendingX
  def balanceY: Long = reservesY + pendingY

  /** R4, which is the unissued remainder rather than the supply itself. */
  def liquidityReg: Long = LDHelpers.LOCKED_LP - supply

  /** Quote against this snapshot. */
  def pool: LDT2TLiquidityPool = LDT2TLiquidityPool(this)
}

object LDT2TLiquidityState {

  /** Read a pool box, recording the height it was seen at. */
  def fromBox(box: InputUTXO, startHeight: Int, syncHeight: Int): LDT2TLiquidityState = {
    val tokenX    = box.tokens(1)
    val tokenY    = box.tokens(2)
    val provToken = box.tokens(3)
    val pending   = box.parseCollReg[Long](2)

    LDT2TLiquidityState(
      reservesX = tokenX.amount - pending(0),
      reservesY = tokenY.amount - pending(1),
      pendingX = pending(0),
      pendingY = pending(1),
      supply = LDHelpers.LOCKED_LP - box.parseReg[Long](0),
      feeParams = box.parseCollReg[Long](1),
      accX = BigInt(box.registers(3).getValue.asInstanceOf[CBigInt].wrappedValue),
      accY = BigInt(box.registers(4).getValue.asInstanceOf[CBigInt].wrappedValue),
      poolNFT = box.tokens.head.id,
      tokenX = tokenX.id,
      tokenY = tokenY.id,
      provToken = provToken.id,
      provTokensLeft = provToken.amount,
      value = box.value,
      startHeight = startHeight,
      utxoId = box.id.toString,
      syncHeight = syncHeight
    )
  }
}
