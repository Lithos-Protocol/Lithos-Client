package state.synchronization

import lfsm.states.{MinerTree, NISPTree}
import org.ergoplatform.sdk.ErgoId
import state.messages.SyncMessages.SyncCursor

/**
 * A rollup this client stopped tracking, and whether rebuilding it could ever succeed.
 * `retryable` is false where re-reading the same transaction gives the same answer.
 */
final case class QuarantineFault(rollupId: String,
                                  genesisHeight: Int,
                                  collateralBoxId: String,
                                  reason: String,
                                  retryable: Boolean,
                                  attempts: Int = 0,
                                  removalHeight: Option[Int] = None,
                                  removalWarningLogged: Boolean = false) {
  require(collateralBoxId.nonEmpty, "A quarantined rollup must retain its collateral box id")
  require(removalHeight.forall(_ >= 0), "A quarantine removal height cannot be negative")
  require(!removalWarningLogged || removalHeight.isDefined,
    "A quarantine removal warning requires a removal height")

  def attempted: QuarantineFault = copy(attempts = attempts + 1)

  def repairable(maxAttempts: Int): Boolean = retryable && attempts < maxAttempts

  /** Starts diagnostic retention only after this episode can no longer be repaired. */
  def scheduleRemoval(currentHeight: Int, retentionBlocks: Int): QuarantineFault =
    if (removalHeight.isDefined) this
    else copy(removalHeight = Some(
      math.min(Int.MaxValue.toLong, currentHeight.toLong + retentionBlocks.toLong).toInt))
}

/**
 * Complete state published by one block commit.
 * Both fault maps are versioned with the state, so snapshots and reorgs preserve their lifetimes.
 */
case class CommittedSyncState(cursor: SyncCursor,
                               version: Long,
                               rollups: Map[String, NISPTree],
                               routes: Map[String, String],
                               rollupOrigins: Map[String, String],
                               minerTree: MinerTree,
                               dataBoxToken: Option[ErgoId],
                              minerDictionaryFault: Option[String] = None,
                              quarantined: Map[String, QuarantineFault] = Map.empty) {

  require(routes.values.forall(rollups.contains), "Every rollup route must name committed state")
  require(routes.forall { case (utxoId, rollupId) => rollups(rollupId).utxoId == utxoId },
    "Every route must match its rollup's current UTXO")
  require(rollupOrigins.keySet == rollups.keySet,
    "Every committed rollup must retain exactly one collateral origin")
  require(quarantined.keySet.forall(!rollups.contains(_)),
    "A quarantined rollup must not also be tracked")

  def routedRollups: Seq[(String, NISPTree)] =
    routes.toSeq.sortBy(_._1).map { case (utxoId, rollupId) => utxoId -> rollups(rollupId) }

  /** Faults worth node calls, newest first so a recent break is rebuilt before an old one. */
  def repairableQuarantines(maxAttempts: Int): Seq[QuarantineFault] =
    quarantined.values.filter(_.repairable(maxAttempts))
      .toSeq.sortBy(-_.genesisHeight)
}
