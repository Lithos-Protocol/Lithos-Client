package lfsm

import org.ergoplatform.appkit.{Address, ErgoId}
import work.lithos.plasma.collections.{LocalPlasmaMap, PlasmaMap}

case class MinerTree(dictionary: LocalPlasmaMap[Array[Byte], Array[Byte]],
                     numMiners: Int,
                     startHeight: Int,
                     minerMap: Map[String, (Address, ErgoId)],
                     hasMiner: Boolean,
                     utxoId: String,
                     synced: Boolean,
                     syncHeight: Int,
                     savedHeight: Int,
                    ) {

  def tempDictionary: PlasmaMap[Array[Byte], Array[Byte]] = {
    val copy = dictionary.toPlasmaMap
    copy.prover.generateProof()
    copy
  }
}
