package api

import cache.LDCache
import api.models._
import transactions.wallet.WalletSelector

/**
 * The LithosDex API — the `lithosdex` tag of the spec, one method per endpoint.
 *
 * Read-only endpoints and every `check` are pure functions over the current boxes and need no key. The
 * six that sign and broadcast — swap, deposit, redeem, claim, resize, flush — are behind `api_key`.
 *
 * There is no `sync` endpoint here and no `LPSyncStatus`: LithosDex has no plasma tree, so following
 * the pool is following one box and there is nothing to be out of sync with.
 */
trait LithosDexApi {

  /**
   * GET /dex/pool
   * State of the LithosDex liquidity pool box.
   */
  def getPool(ldCache: LDCache): LDPoolInfo

  /**
   * GET /dex/vault
   * State of the fee vault. Its accumulators trail the pool's by whatever is not yet flushed.
   */
  def getVault(ldCache: LDCache): LDVaultInfo

  /**
   * POST /dex/swap/check
   * Quote a swap without sending a transaction.
   */
  def checkSwap(request: LDSwapRequest, ldCache: LDCache): LDSwapQuote

  /**
   * POST /dex/swap/checkOutput
   * The reverse quote: what must go in for `amountOut` to come out.
   */
  def checkSwapOutput(request: LDSwapOutputRequest, ldCache: LDCache): LDSwapQuote

  /**
   * POST /dex/swap
   * Sign and submit a swap. `minOutput` is required and is checked against the pool as it stands.
   */
  def swap(request: LDSwapExecuteRequest, ldCache: LDCache, walletSelector: WalletSelector): LDSwapResult

  /**
   * POST /dex/deposit/check
   * Quote a deposit, either from the funds on hand or for an exact number of shares.
   */
  def checkDeposit(request: LDDepositRequest, ldCache: LDCache): LDDepositQuote

  /**
   * POST /dex/deposit
   * Deposit a balanced pair and mint a provision box.
   */
  def deposit(request: LDDepositExecuteRequest, ldCache: LDCache, walletSelector: WalletSelector): LDDepositResult

  /**
   * POST /dex/redeem/check
   * Quote closing a provision, including the settled fees spending it would forfeit.
   */
  def checkRedeem(request: LDRedeemRequest, ldCache: LDCache): LDRedeemQuote

  /**
   * POST /dex/redeem
   * Close a provision and return its share of the reserves.
   */
  def redeem(request: LDRedeemRequest, ldCache: LDCache, walletSelector: WalletSelector): LDRedeemResult

  /**
   * GET /dex/provisions
   * Every provision box held by this wallet, with both fee figures.
   */
  def listProvisions(ldCache: LDCache): LDProvisionList

  /**
   * POST /dex/provisions/:boxId/claim
   * Settle one provision against the vault.
   */
  def claimProvision(boxId: String,
                     request: LDClaimRequest,
                     ldCache: LDCache,
                     walletSelector: WalletSelector): LDClaimResult

  /**
   * POST /dex/provisions/:boxId/resize/check
   * Quote changing a provision's size without closing it.
   */
  def checkResize(boxId: String, request: LDResizeRequest, ldCache: LDCache): LDResizeQuote

  /**
   * POST /dex/provisions/:boxId/resize
   * Change a provision's size. Always flushes in the same transaction.
   */
  def resize(boxId: String, request: LDResizeRequest, ldCache: LDCache, walletSelector: WalletSelector): LDResizeResult

  /**
   * POST /dex/flush/check
   * Preview moving the pool's pending fees into the vault.
   */
  def checkFlush(ldCache: LDCache): LDFlushCheck

  /**
   * POST /dex/flush
   * Hand the pool's pending fees to the vault. Permissionless; pays the caller nothing.
   */
  def flush(request: LDFlushRequest, ldCache: LDCache, walletSelector: WalletSelector): LDFlushResult

  /**
   * GET /dex/fees/history
   * Cumulative fees taken by the pool, bucketed by height.
   */
  def getFeeHistory(from: Option[Int], to: Option[Int], bucket: Option[Int], ldCache: LDCache): LDFeeHistory

  /**
   * GET /dex/provisions/:boxId/fees/history
   * Earnings over time for one provision, from the same index as the pool-wide series.
   */
  def getProvisionFeeHistory(boxId: String,
                             from: Option[Int],
                             to: Option[Int],
                             bucket: Option[Int],
                             ldCache: LDCache): LDProvisionFeeHistory

  /**
   * GET /dex/price/history
   * Spot price over a range, for the swap page's chart.
   */
  def getPriceHistory(range: Option[String], bucket: Option[Int], ldCache: LDCache): LDPriceHistory

  /**
   * GET /dex/swaps/recent
   * Swaps against the pool, newest first, including unconfirmed ones.
   */
  def getRecentSwaps(limit: Option[Int]): LDRecentSwaps

  /**
   * GET /wallet/balance
   * ERG and token balances for the client's wallet.
   */
  def getWalletBalance: WalletBalance
}
