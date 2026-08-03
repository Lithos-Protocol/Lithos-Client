package lfsm.states

import lfsm.LFSMHelpers
import org.ergoplatform.appkit.Address
import org.ergoplatform.sdk.ErgoId
import sigma.data.AvlTreeFlags
import work.lithos.plasma.PlasmaParameters
import work.lithos.plasma.collections.{LocalPlasmaMap, PlasmaMap}

case class MinerTree(dictionary: PlasmaMap[Array[Byte], Array[Byte]],
                     numMiners: Int,
                     startHeight: Int,
                     minerMap: Map[String, (Address, ErgoId)],
                     hasMiner: Boolean,
                     utxoId: String,
                     synced: Boolean,
                     syncHeight: Int,
                     savedHeight: Int,
                    ) extends UTXOState {

}

object MinerTree {
  final val ADD_MINER_OP = 0.toByte
  final val REMOVE_MINER_OP = 1.toByte

  def initialState: MinerTree = {
    val dictionary = new PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.AllOperationsAllowed, PlasmaParameters.default)
    MinerTree(dictionary, 0, LFSMHelpers.MD_GENESIS_HEIGHT, Map.empty[String, (Address, ErgoId)], hasMiner = false,
      LFSMHelpers.MD_GENESIS_ID, synced = false, 0, 0)
  }
}
