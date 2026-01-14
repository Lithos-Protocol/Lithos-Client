package lfsm.states

import lfsm.LFSMPhase
import work.lithos.plasma.collections.PlasmaMap

case class NISPTree(dictionary: PlasmaMap[Array[Byte], Array[Byte]],
                    numMiners: Int,
                    totalScore: BigInt,
                    currentPeriod: Option[Long],
                    totalReward: Long,
                    startHeight: Int,
                    hasMiner: Boolean,
                    phase: LFSMPhase,
                    minerSet: Set[String] = Set.empty[String],
                    evaluated: Boolean = false,
                    blockId: String,
                    utxoId: String
                   ) extends UTXOState
