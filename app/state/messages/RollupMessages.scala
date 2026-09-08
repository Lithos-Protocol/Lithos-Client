package state.messages

import lfsm.states.{Rollup, RollupMetadata}
import state.messages.MempoolMessages.{MempoolChain, MempoolRollupMetadata, MempoolRollupState}
import state.messages.SyncMessages.Transform

object RollupMessages {

  case class Genesis(tree: Rollup, blockInfo: BlockInfo)

  case class RollupTransform(blockInfo: BlockInfo, tx: BlockTx) extends Transform {
    val input: TxInput = tx.inputs.head
    val output: TxOutput = tx.outputs.head

    override def toString: String = s"RollupTransform(${blockInfo.height}: ${input.id} => ${output.id})"
  }

  case class RemoveRollup(blockId: String, reason: String)
  // Safely updates evaluation status for a rollup synchronizer
  case class UpdateEvaluation(blockId: String, expectedUtxoId: Option[String] = None)
  // Gets the current rollup state and returns RollupInfo
  case class GetCurrentRollup(blockId: String)
  /** Internal time-sensitive submission/fraud-proof lookup; never exposed by the Blocks API. */
  case class GetCurrentRollupCritical(blockId: String)

  /**
   * The rollup's box and metadata, for operations that do not read or change authenticated state.
   *
   * A holding transform and a pre-send freshness check need the box to spend and the phase it is
   * in, not the dictionary inside it. Answering those without loading one is what keeps projection
   * cost proportional to the operations that actually need authenticated state.
   */
  case class GetRollupMetadata(blockId: String)

  sealed trait RollupInfo
  case class CurrentRollup(utxoId: String,
                           rollup: Rollup,
                           mempoolState: Option[MempoolRollupState],
                           history: Option[RollupHistoryAnchor] = None) extends RollupInfo

  /** Reply to [[GetRollupMetadata]]. Carries no dictionary, so none had to be materialized. */
  case class CurrentRollupMetadata(utxoId: String,
                                   metadata: RollupMetadata,
                                   mempoolState: Option[MempoolRollupMetadata]) extends RollupInfo
  case class RollupHistoryAnchor(collateralBoxId: String, confirmedHeight: Int)
  case class NoRollupFound() extends RollupInfo
  /** The rollup exists, but its authenticated dictionary could not be made current for this request. */
  case class RollupUnavailable(reason: String) extends RollupInfo
}
