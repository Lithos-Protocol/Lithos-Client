package state.synchronization

import lfsm.states.{MinerTree, NISPTree}
import org.ergoplatform.sdk.ErgoId
import state.messages.SyncMessages.SyncCursor

/**
 * Complete state published by one block commit.
 *
 * `minerDictionaryFault` is versioned with the state so snapshots and reorgs preserve its lifetime.
 */
case class CommittedSyncState(cursor: SyncCursor,
                              version: Long,
                              rollups: Map[String, NISPTree],
                              routes: Map[String, String],
                              minerTree: MinerTree,
                              dataBoxToken: Option[ErgoId],
                              minerDictionaryFault: Option[String] = None) {

  require(routes.values.forall(rollups.contains), "Every rollup route must name committed state")
  require(routes.forall { case (utxoId, rollupId) => rollups(rollupId).utxoId == utxoId },
    "Every route must match its rollup's current UTXO")

  def routedRollups: Seq[(String, NISPTree)] =
    routes.toSeq.sortBy(_._1).map { case (utxoId, rollupId) => utxoId -> rollups(rollupId) }
}

