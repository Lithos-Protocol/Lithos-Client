package state.messages

import lfsm.states.NISPTree
import state.messages.DictionaryMessages.DictionaryTransform
import state.messages.RollupMessages.{Genesis, RollupTransform}
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
  case class RollbackTo(blockId: String)
  case class RollbackCompleted(cursor: SyncCursor)
  case class RollbackRejected(reason: String)

  case class SyncUnavailable(status: SyncStatus) extends SyncMessage



  case class RelevantTransactions(genTxs: Seq[Genesis], transforms: Seq[RollupTransform], dictionaryMap: Map[String, Seq[DictionaryTransform]])

  object RelevantTransactions {
    def empty = RelevantTransactions(Seq.empty[Genesis], Seq.empty[RollupTransform], Map.empty[String, Seq[DictionaryTransform]])
  }
  trait Transform {
    val tx: BlockTx
    def input: TxInput
    def output: TxOutput
  }

  case object GetSynced
  case object CompletedInitSync
  case class HandledBlock(blockInfo: BlockInfo)

  sealed trait SyncMessage

  case class FullSync(rollups: Seq[(String, NISPTree)]) extends SyncMessage

  case class PartialSync(syncedRollups: Seq[(String, NISPTree)], unsyncedIds: Seq[String]) extends SyncMessage

  case class NoRollups() extends SyncMessage
}
