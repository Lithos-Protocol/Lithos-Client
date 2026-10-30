package api

import api.models._

/**
 * The `/dex/pools` endpoints: every LithosDex pool, ERG/token and token/token, rather than the canonical
 * ERG:LIT pool the rest of `/dex` serves. Reads and quotes are open, since every figure is public chain
 * state; placing an order spends this wallet's funds and needs the api key.
 */
trait LDPoolsApi {

  /** GET /dex/pools */
  def listPools: LDPoolList

  /** GET /dex/pools/:poolNft */
  def getPool(poolNft: String): LDPoolDetail

  /** POST /dex/pools/create/check */
  def checkCreatePool(request: LDPoolCreateRequest): LDPoolCreateQuote

  /** POST /dex/pools/create */
  def createPool(request: LDPoolCreateExecuteRequest): LDPoolCreated

  /** POST /dex/pools/:poolNft/orders/swap/check */
  def checkSwapOrder(poolNft: String, request: LDPoolSwapRequest): LDPoolSwapQuote

  /** POST /dex/pools/:poolNft/orders/swap */
  def placeSwapOrder(poolNft: String, request: LDPoolSwapExecuteRequest): LDPoolOrderPlaced

  /** POST /dex/pools/:poolNft/orders/deposit/check */
  def checkDepositOrder(poolNft: String, request: LDPoolDepositRequest): LDPoolDepositQuote

  /** POST /dex/pools/:poolNft/orders/deposit */
  def placeDepositOrder(poolNft: String, request: LDPoolDepositExecuteRequest): LDPoolOrderPlaced

  /** POST /dex/pools/:poolNft/orders/redeem/check */
  def checkRedeemOrder(poolNft: String, request: LDPoolRedeemRequest): LDPoolRedeemQuote

  /** POST /dex/pools/:poolNft/orders/redeem */
  def placeRedeemOrder(poolNft: String, request: LDPoolRedeemRequest): LDPoolOrderPlaced
}
