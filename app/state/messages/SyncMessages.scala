package state.messages

import lfsm.states.NISPTree
import state.messages.MempoolMessages.MempoolRollupState

object SyncMessages {

  case class SyncCursor(height: Int, blockId: String, parentId: String)

  sealed trait SyncStatus {
    def cursor: Option[SyncCursor]
  }

  case object Starting extends SyncStatus {
    override val cursor: Option[SyncCursor] = None
  }

  case class CatchingUp(committed: SyncCursor, targetHeight: Int) extends SyncStatus {
    override val cursor: Option[SyncCursor] = Some(committed)
  }

  case class Ready(committed: SyncCursor) extends SyncStatus {
    override val cursor: Option[SyncCursor] = Some(committed)
  }

  case class Stale(committed: SyncCursor, reason: String) extends SyncStatus {
    override val cursor: Option[SyncCursor] = Some(committed)
  }

  case class Reorganizing(from: SyncCursor, towardHeight: Int) extends SyncStatus {
    override val cursor: Option[SyncCursor] = Some(from)
  }

  case class SyncFailed(lastCommitted: Option[SyncCursor], reason: String) extends SyncStatus {
    override val cursor: Option[SyncCursor] = lastCommitted
  }

  case object GetSyncStatus

  case class BeginCatchUp(targetHeight: Int)
  case object MarkReady
  case class MarkStale(reason: String)
  case class RestoreCommittedState(state: _root_.state.synchronization.CommittedSyncState,
                                   recentCursors: Vector[SyncCursor] = Vector.empty)
  case object GetCommittedState
  case class CommittedState(state: _root_.state.synchronization.CommittedSyncState)
  case object GetRetainedCursors
  case class RetainedCursors(cursors: Seq[SyncCursor])
  case object ResetSyncState
  case object SyncStateReset

  case class ApplyBlock(blockInfo: BlockInfo)
  case class BlockCommitted(cursor: SyncCursor, stateVersion: Long, relevantEvents: Int,
                            stagedDictionaryCopies: Int)
  case class BlockRejected(cursor: Option[SyncCursor], blockId: String, reason: String)
  /**
   * Installs a rebuilt Miner Dictionary and clears its fault.
   *
   * Carries the exact cursor it was rebuilt at so a commit or same-height reorg that landed during the
   * rebuild rejects it rather than installing state from another chain.
   */
  case class RepairMinerDictionary(atCursor: SyncCursor,
                                    minerTree: lfsm.states.MinerTree,
                                    dataBoxToken: Option[org.ergoplatform.sdk.ErgoId])

  /** Asks which quarantined rollups are worth rebuilding, newest first. */
  case object GetRepairableQuarantines
  case class RepairableQuarantines(faults: Seq[_root_.state.synchronization.QuarantineFault],
                                    committedCursor: SyncCursor)

  /**
   * Installs a rebuilt rollup and clears its fault, or records the attempt when the rebuild failed.
   * Carries the exact cursor it was rebuilt at, so a commit or same-height reorg rejects it.
   */
  case class RepairRollup(rollupId: String,
                           atCursor: SyncCursor,
                           outcome: _root_.state.synchronization.RollupRepairResult)

  case class RollbackTo(blockId: String)
  case class RollbackCompleted(cursor: SyncCursor)
  case class RollbackRejected(reason: String)

  case class SyncUnavailable(status: SyncStatus) extends SyncMessage



  trait Transform {
    val tx: BlockTx
    def input: TxInput
    def output: TxOutput
  }

  case object GetSynced

  /**
   * Answered when a block arrives before synchronization has been seeded.
   * Generally only happens if state owner lost its own state due to a restart.
   */
  case object SyncUnseeded

  sealed trait SyncMessage

  /** Committed rollups with their mempool projections, so a consumer reads one consistent pair. */
  case class FullSync(rollups: Seq[(String, NISPTree)],
                      projections: Map[String, MempoolRollupState]) extends SyncMessage
}
