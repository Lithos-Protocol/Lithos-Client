package lfsm

import work.lithos.plasma.collections.PlasmaMap

case class NISPTree(tree: PlasmaMap[Array[Byte], Array[Byte]],
                    numMiners: Int,
                    totalScore: BigInt,
                    currentPeriod: Option[Long],
                    totalReward: Long,
                    startHeight: Int,
                    hasMiner: Boolean,
                    phase: LFSMPhase,
                    minerSet: Set[String] = Set.empty[String],
                    evaluated: Boolean = false,
                    blockId: String
                   )
