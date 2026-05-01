package plasmadex.states

import lfsm.states.UTXOState
import work.lithos.plasma.collections.PlasmaMap

case class LiquidityState(dictionary: PlasmaMap[Array[Byte], Array[Byte]],
                          liquidity: Long,
                          feeParams: Array[Long],
                          lsFeesX: Long,
                          lsFeesY: Long,
                          numProvisions: Int,
                          startHeight: Int,
                          utxoId: String) extends UTXOState {

}
