package lithosdex.states

import lfsm.states.UTXOState
import lithosdex.LDHelpers
import org.ergoplatform.sdk.ErgoId
import sigma.data.CBigInt
import work.lithos.mutations.InputUTXO

/**
 * A token/token fee vault as tracked off-chain, and what it can pay.
 *
 * It works as the ERG/token vault does, except that both sides are tokens: it holds the fees flushed
 * out of the pool in tokens(1) and tokens(2), along with the accumulators those fees paid for. Both
 * entries exist from genesis, so neither is ever absent.
 *
 * @param accX       R4, the pool's token X accumulator as of the last flush
 * @param accY       R5, the token Y side of the same
 * @param balanceX   token X held, the genesis seed included
 * @param balanceY   token Y held, the genesis seed included
 * @param value      ERG in the box, storage only
 * @param syncHeight height this snapshot was taken at
 */
case class LDT2TFeeValue(accX: BigInt,
                         accY: BigInt,
                         balanceX: Long,
                         balanceY: Long,
                         value: Long,
                         vaultNFT: ErgoId,
                         tokenX: ErgoId,
                         tokenY: ErgoId,
                         startHeight: Int,
                         utxoId: String,
                         syncHeight: Int) extends UTXOState {

  /** Token X the vault can part with. A payout cannot take the entry to zero, so the last unit stays. */
  def payableX: Long = math.max(0L, balanceX - LDHelpers.T2T_VAULT_SEED)

  /** Token Y the vault can part with, on the same basis. */
  def payableY: Long = math.max(0L, balanceY - LDHelpers.T2T_VAULT_SEED)

  /**
   * What a provision is owed right now, computed exactly as LD_T2T_FeeVault computes it.
   *
   * @param shares the provision's R7
   * @param entryX the provision's R4, the accumulator it last settled at
   * @param entryY the provision's R5
   */
  def owed(shares: Long, entryX: BigInt, entryY: BigInt): (Long, Long) = {
    val owedX = (BigInt(shares) * (accX - entryX) / LDHelpers.SCALE).toLong
    val owedY = (BigInt(shares) * (accY - entryY) / LDHelpers.SCALE).toLong
    (owedX, owedY)
  }

  /**
   * Whether a claim would be accepted. The vault refuses a settlement of nothing, and refuses an entry
   * that has run ahead of its own accumulator — which means fees the pool has not flushed yet.
   */
  def canClaim(entryX: BigInt, entryY: BigInt): Boolean =
    entryX <= accX && entryY <= accY && (entryX < accX || entryY < accY)
}

object LDT2TFeeValue {

  /**
   * Read a vault box.
   *
   * R4 accX, R5 accY. Tokens: 0 vault NFT, 1 tokenX, 2 tokenY.
   */
  def fromBox(box: InputUTXO, startHeight: Int, syncHeight: Int): LDT2TFeeValue = {
    LDT2TFeeValue(
      accX = BigInt(box.registers.head.getValue.asInstanceOf[CBigInt].wrappedValue),
      accY = BigInt(box.registers(1).getValue.asInstanceOf[CBigInt].wrappedValue),
      balanceX = box.tokens(1).amount,
      balanceY = box.tokens(2).amount,
      value = box.value,
      vaultNFT = box.tokens.head.id,
      tokenX = box.tokens(1).id,
      tokenY = box.tokens(2).id,
      startHeight = startHeight,
      utxoId = box.id.toString,
      syncHeight = syncHeight
    )
  }

  /** Apply a flush: the pool hands over its pending fees and the accumulators they funded. */
  def afterFlush(vault: LDT2TFeeValue, pool: LDT2TLiquidityState, height: Int): LDT2TFeeValue = {
    vault.copy(
      accX = pool.accX,
      accY = pool.accY,
      balanceX = vault.balanceX + pool.pendingX,
      balanceY = vault.balanceY + pool.pendingY,
      syncHeight = height
    )
  }
}
