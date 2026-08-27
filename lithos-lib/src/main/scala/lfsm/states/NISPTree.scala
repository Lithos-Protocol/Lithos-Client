package lfsm.states

import lfsm.LFSMPhase

case class NISPTree(dictionary: AuthenticatedDictionaryView,
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
