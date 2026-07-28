package plasmadex.states

import lfsm.states.UTXOState
import work.lithos.plasma.collections.PlasmaMap

case class LPRewardState(feeDictionary: PlasmaMap[Array[Byte], Array[Byte]],
                         liquidity: Long,
                         lsFeesX: Long,
                         lsFeesY: Long,
                         numProvisions: Int,
                         startHeight: Int,
                         feeNFT: String,
                         utxoId: String,
                         claimed: Boolean) extends UTXOState {

}

object LPRewardState {

  def fromLiquidityState(ls: LiquidityState, height: Int, utxoId: String): LPRewardState = {

    val copiedDict = ls.dictionary.copy()
    copiedDict.prover.generateProof()
    LPRewardState(copiedDict, ls.liquidity, ls.lsFeesX, ls.lsFeesY, ls.numProvisions, height,
      feeNFT = ls.utxoId, utxoId, claimed = false)

  }
}
