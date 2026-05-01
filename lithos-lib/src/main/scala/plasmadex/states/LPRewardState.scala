package plasmadex.states

import lfsm.states.UTXOState
import work.lithos.plasma.collections.PlasmaMap

case class LPRewardState(feeDictionary: PlasmaMap[Array[Byte], Array[Byte]],
                         liquidity: Long,
                         lsFeesX: Long,
                         lsFeesY: Long,
                         numProvisions: Int,
                         startHeight: Int,
                         utxoId: String) extends UTXOState {

}
