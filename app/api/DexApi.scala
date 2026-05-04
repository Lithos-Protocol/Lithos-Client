package api

import cache.PDCache
import model._

trait DexApi {
  /**
    * POST /dex/syncFee/:feeNFTId
    * Requests synchronization for a fee reward box.
    */
  def syncFee(feeNFTId: String, PDCache: PDCache): FeeSyncResult

  /**
    * POST /dex/claimFee/:feeNFTId
    * Sends a transaction to claim a fee reward box.
    */
  def claimFee(feeNFTId: String, PDCache: PDCache): FeeClaimResult

  /**
    * POST /dex/:lpId/withdraw/check
    * Check the result of a fee withdrawal without sending a transaction.
    */
  def checkWithdrawDex(lpId: String, PDCache: PDCache): WithdrawResult

  /**
    * POST /dex/:lpId/withdraw
    * Withdraws liquidity state fees from the PlasmaDex LP.
    */
  def withdrawDex(lpId: String, PDCache: PDCache): WithdrawResult

  /**
    * GET /dex
    * Provides information about the PlasmaDex Liquidity Pool.
    */
  def getDexInfo(PDCache: PDCache): DexInfo

  /**
    * GET /dex/:lpId/sync
    * Check synchronization status of PlasmaDex LP.
    */
  def getDexSyncStatus(lpId: String, PDCache: PDCache): LPSyncStatus

  /**
    * POST /dex/:lpId/swap
    * Perform a swap in the PlasmaDex LP.
    */
  def swapDex(lpId: String, swapInfo: SwapInfo, PDCache: PDCache): SwapResult

  /**
    * POST /dex/:lpId/swap/check
    * Check the result of a swap without sending a transaction.
    */
  def checkSwapDex(lpId: String, swapInfo: SwapInfo, PDCache: PDCache): SwapResult

  /**
    * POST /dex/:lpId/deposit
    * Deposit liquidity into the PlasmaDex LP.
    */
  def depositDex(lpId: String, depositInfo: DepositInfo, PDCache: PDCache): DepositResult

  /**
    * POST /dex/:lpId/deposit/check
    * Check the result of a deposit without sending a transaction.
    */
  def checkDepositDex(lpId: String, depositInfo: DepositInfo, PDCache: PDCache): DepositResult

  /**
    * POST /dex/:lpId/redemption
    * Redeem liquidity from the PlasmaDex LP.
    */
  def redemptionDex(lpId: String, redemptionInfo: RedemptionInfo, PDCache: PDCache): RedemptionResult

  /**
    * POST /dex/:lpId/redemption/check
    * Check the result of a redemption without sending a transaction.
    */
  def checkRedemptionDex(lpId: String, redemptionInfo: RedemptionInfo, PDCache: PDCache): RedemptionResult
}
