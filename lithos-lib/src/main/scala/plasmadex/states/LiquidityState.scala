package plasmadex.states

import lfsm.states.UTXOState
import plasmadex.PDHelpers
import sigma.data.AvlTreeFlags
import work.lithos.plasma.PlasmaParameters
import work.lithos.plasma.collections.PlasmaMap

case class LiquidityState(dictionary: PlasmaMap[Array[Byte], Array[Byte]],
                          liquidity: Long,
                          feeParams: Array[Long],
                          lsFeesX: Long,
                          lsFeesY: Long,
                          numProvisions: Int,
                          startHeight: Int,
                          utxoId: String,
                          syncHeight: Int) extends UTXOState {

}

object LiquidityState {
  final val SWAP_OP = 0.toByte
  final val DEP_OP  = 1.toByte
  final val RED_OP  = 2.toByte
  final val WITHDRAW_OP  = 3.toByte

  def initialState: LiquidityState = {
    val dictionary = new PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.AllOperationsAllowed, PlasmaParameters.default)
    LiquidityState(dictionary, PDHelpers.MAX_LIQ - 1000000, Array(15, 15, 15), 0L, 0L, 0, PDHelpers.LP_GENESIS_HEIGHT, PDHelpers.LP_GENESIS_ID,
      PDHelpers.LP_GENESIS_HEIGHT)
  }
}
