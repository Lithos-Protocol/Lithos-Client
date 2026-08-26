package api

import api.models._

/**
 * The `wallet` tag — what this client's own wallet holds.
 *
 * Its own surface rather than a corner of LithosDex or the collateral market because both grew a
 * wallet read for their own panel, the two disagreed whenever a join landed between them, and
 * neither tag was the right home for a balance.
 */
trait WalletApi {

  /**
   * GET /wallet/balances
   * Balances and the collateral position in one read, so the two describe one instant.
   */
  def getWalletBalances: WalletBalances
}
