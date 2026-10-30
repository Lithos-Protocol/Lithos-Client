package api

import api.models._

/**
 * The `/dex/pools` endpoints: every LithosDex pool, ERG/token and token/token, rather than the canonical
 * ERG:LIT pool the rest of `/dex` serves. Reads are open, since every figure is public chain state.
 */
trait LDPoolsApi {

  /** GET /dex/pools */
  def listPools: LDPoolList

  /** GET /dex/pools/:poolNft */
  def getPool(poolNft: String): LDPoolDetail
}
