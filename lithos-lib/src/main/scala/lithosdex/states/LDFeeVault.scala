package lithosdex.states

import lfsm.states.UTXOState
import lithosdex.LDHelpers
import org.ergoplatform.appkit.NetworkType
import org.ergoplatform.sdk.ErgoId
import sigma.data.CBigInt
import work.lithos.mutations.InputUTXO

/**
 * The fee vault as tracked off-chain, and what it can pay.
 *
 * The vault holds fees flushed out of the pool along with the accumulators those fees paid for. A
 * provision is owed its size times however far the vault's accumulator has moved since the provision
 * last settled — which is why this is the box to ask about a claim, not the pool. The pool's own
 * accumulators run ahead of these between flushes, and that difference is money the vault does not yet
 * have and cannot pay out.
 *
 * @param accX       R4, the pool's ERG accumulator as of the last flush
 * @param accY       R5, the token side of the same
 * @param balanceX   value of the vault box, [[LDHelpers.VAULT_MIN]] included
 * @param balanceY   token-side fees held; zero until a flush carries any
 * @param tokenY     the pool's token, or None while balanceY is zero and the vault holds no token entry
 * @param syncHeight height this snapshot was taken at
 */
case class LDFeeValue(accX: BigInt,
                      accY: BigInt,
                      balanceX: Long,
                      balanceY: Long,
                      vaultNFT: ErgoId,
                      tokenY: Option[ErgoId],
                      startHeight: Int,
                      utxoId: String,
                      syncHeight: Int) extends UTXOState {

  /** ERG the vault can actually part with. The rest keeps it above storage rent and is never paid out. */
  def payableX: Long = math.max(0L, balanceX - LDHelpers.VAULT_MIN)

  /** Tokens the vault can part with. Nothing is held back on this side. */
  def payableY: Long = balanceY

  /**
   * What a provision is owed right now, computed exactly as LD_FeeVault computes it.
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

object LDFeeValue {

  /**
   * Read a vault box.
   *
   * R4 accX, R5 accY. Tokens: 0 vault NFT, 1 tokenY when any token fees have been flushed.
   */
  def fromBox(box: InputUTXO, startHeight: Int, syncHeight: Int): LDFeeValue = {
    val tokenEntry = box.tokens.lift(1)

    LDFeeValue(
      accX = BigInt(box.registers.head.getValue.asInstanceOf[CBigInt].wrappedValue),
      accY = BigInt(box.registers(1).getValue.asInstanceOf[CBigInt].wrappedValue),
      balanceX = box.value,
      balanceY = tokenEntry.map(_.amount).getOrElse(0L),
      vaultNFT = box.tokens.head.id,
      tokenY = tokenEntry.map(_.id),
      startHeight = startHeight,
      utxoId = box.id.toString,
      syncHeight = syncHeight
    )
  }

  /** The vault as minted: both accumulators at zero, funded only to the point it survives rent. */
  def genesis(networkType: NetworkType): LDFeeValue = {
    LDFeeValue(
      accX = BigInt(0),
      accY = BigInt(0),
      balanceX = LDHelpers.VAULT_MIN,
      balanceY = 0L,
      vaultNFT = LDHelpers.getVaultNFT(networkType),
      tokenY = None,
      startHeight = LDHelpers.GENESIS_HEIGHT,
      utxoId = LDHelpers.VAULT_GENESIS_ID,
      syncHeight = LDHelpers.GENESIS_HEIGHT
    )
  }

  /** Apply a flush: the pool hands over its pending fees and the accumulators they funded. */
  def afterFlush(vault: LDFeeValue, pool: LDLiquidityState, height: Int): LDFeeValue = {
    vault.copy(
      accX = pool.accX,
      accY = pool.accY,
      balanceX = vault.balanceX + pool.pendingX,
      balanceY = vault.balanceY + pool.pendingY,
      tokenY = vault.tokenY.orElse(if (pool.pendingY > 0) Some(pool.tokenY) else None),
      syncHeight = height
    )
  }
}
