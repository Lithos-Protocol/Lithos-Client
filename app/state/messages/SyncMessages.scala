package state.messages

import lfsm.NISPTree
import state.messages.DictionaryMessages.DictionaryTransform
import state.messages.RollupMessages.{Genesis, Transform}
object SyncMessages {



  case class RelevantTransactions(genTxs: Seq[Genesis], transforms: Seq[Transform], dictionaryTransforms: Seq[DictionaryTransform])

  object RelevantTransactions {
    def empty = RelevantTransactions(Seq.empty[Genesis], Seq.empty[Transform], Seq.empty[DictionaryTransform])
  }

  case object GetSynced

  case class HandledBlock(blockInfo: BlockInfo)

  sealed trait SyncMessage

  case class FullSync(rollups: Seq[(String, NISPTree)]) extends SyncMessage

  case class PartialSync(syncedRollups: Seq[(String, NISPTree)], unsyncedIds: Seq[String]) extends SyncMessage
}