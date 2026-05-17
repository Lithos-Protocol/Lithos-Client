package state.messages

import lfsm.states.NISPTree
import state.messages.DictionaryMessages.DictionaryTransform
import state.messages.RollupMessages.{Genesis, RollupTransform}
object SyncMessages {



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