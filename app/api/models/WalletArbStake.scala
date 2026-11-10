package api.models

import play.api.libs.json._

/**
 * The arb stake: ERG kept at this wallet's arb address for arbitrage in this miner's own blocks. The node
 * wallet does not list boxes there, so none of it is in the wallet's balances.
 *
 * @param address        the arb address: this wallet's first key under a script of its own
 * @param enabled        arb is on and can reach a block
 * @param paused         a reclaim stopped arb until this client restarts
 * @param targetNanoErgs `batching.arb.stakeNanoErg`, the size auto-reserve sends
 * @param stakeNanoErgs  ERG of the box cycles start from, 0 when none has been taken up
 * @param boxes          confirmed boxes at the address
 * @param nanoErgs       their ERG, which a reclaim returns less its fee
 */
case class WalletArbStake(address: String,
                          enabled: Boolean,
                          paused: Boolean,
                          targetNanoErgs: String,
                          stakeNanoErgs: String,
                          boxes: Int,
                          nanoErgs: String)

object WalletArbStake {
  implicit lazy val jsonFormat: Format[WalletArbStake] = Json.format[WalletArbStake]
}

/**
 * A reclaim of the arb stake to the primary address.
 *
 * @param nanoErgs       ERG of the boxes spent, before the fee
 * @param outcome        `accepted`, or `uncertain` when the node's answer was lost
 * @param remainingBoxes boxes left at the address because one transaction could not spend them all
 */
case class WalletArbReclaimResult(txId: String, boxes: Int, nanoErgs: String, outcome: String, remainingBoxes: Int)

object WalletArbReclaimResult {
  implicit lazy val jsonFormat: Format[WalletArbReclaimResult] = Json.format[WalletArbReclaimResult]
}
