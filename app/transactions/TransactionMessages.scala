package transactions

import lfsm.LFSMHelpers
import lfsm.LFSMPhase.{EVAL, HOLDING, PAYOUT}
import lfsm.states.NISPTree
import transactions.TransactionMessages.RollupTxType.{EvalTransform, HoldingTransform, NISPEvaluation, NISPSubmission, Payout}
import work.lithos.mutations.InputUTXO

import scala.util.Try

object TransactionMessages {

  // ─── base trait ───────────────────────────────────────────────────────────

  /**
   * TxStub represents the minimum amount of information needed to post a transaction
   * onto the blockchain
   */
  sealed trait TxStub

  // ─── rollup transaction types ─────────────────────────────────────────────

  /**
   * The five distinct on-chain operations that can be performed on a rollup.
   *
   *  HoldingTransform  — HOLDING phase rollup whose holding period has elapsed;
   *                      advances the rollup to the EVAL contract.
   *  EvalTransform     — EVAL phase rollup whose eval period has elapsed;
   *                      advances the rollup to the PAYOUT contract.
   *  Payout            — PAYOUT phase rollup; pays out ERG (and tokens) to the
   *                      local miner.
   *  NISPSubmission    — HOLDING phase rollup still within its holding period
   *                      that has no miner yet; submits the best valid NISP.
   *  NISPEvaluation    — EVAL phase rollup still within its eval period that
   *                      has not yet been evaluated; runs fraud-proof checks.
   */
  sealed trait RollupTxType
  object RollupTxType {
    case object HoldingTransform extends RollupTxType
    case object EvalTransform    extends RollupTxType
    case object Payout           extends RollupTxType
    case object NISPSubmission   extends RollupTxType
    case object NISPEvaluation   extends RollupTxType
  }

  // ─── rollup tx stub ───────────────────────────────────────────────────────

  /**
   * Identifies a single rollup transaction which will be sent to the blockchain.
   *
   * @param rollupBlockId  NISPTree.blockId — the block that originated this rollup
   * @param currentPeriod  NISPTree.currentPeriod — the block height at which the
   *                       rollup entered its current phase; None for PAYOUT-phase
   *                       rollups where the field is not set
   * @param txType         Which of the five transaction types is due
   * @param fee            Transaction fee in nanoERG; defaults to ROLLUP_FEE
   */
  case class RollupTxStub(rollupBlockId: String,
                          currentPeriod: Option[Long],
                          txType: RollupTxType,
                          fee: Long = RollupTxStub.ROLLUP_FEE,
                          fpInfo: Option[(Array[Byte], String)] = None) extends TxStub{
    override def toString: String = {
      s"RollupTxStub($rollupBlockId, $txType, $currentPeriod, $fee, $fpInfo)"
    }

    /**
     * Check if this stub is valid against a given height and NISPTree
     * @param height Height to validate against
     * @param NISPTree NISPTree to validate against
     */
    def validate(height: Int, NISPTree: NISPTree): Boolean = {
      txType match {
        case HoldingTransform =>
          NISPTree.phase == HOLDING && (height - NISPTree.currentPeriod.get) >= LFSMHelpers.HOLDING_PERIOD
        case NISPSubmission =>
          NISPTree.phase == HOLDING && (height - NISPTree.currentPeriod.get) < LFSMHelpers.HOLDING_PERIOD && !NISPTree.hasMiner
        case EvalTransform =>
          NISPTree.phase == EVAL && (height - NISPTree.currentPeriod.get) >= LFSMHelpers.EVAL_PERIOD
        case NISPEvaluation =>
          NISPTree.phase == EVAL && (height - NISPTree.currentPeriod.get) < LFSMHelpers.EVAL_PERIOD && !NISPTree.evaluated
        case Payout =>
          NISPTree.phase == PAYOUT
      }
    }
  }

  object RollupTxStub {
    /** Standard fee applied to all rollup transactions. */
    final val ROLLUP_FEE: Long = work.lithos.mutations.UTXO.MIN_FEE
  }

  // ─── actor messages ───────────────────────────────────────────────────────

  /**
   * Piped to TransactionPublisher from itself once buildRollupMap completes.
   * Carries either the rebuilt map or a captured failure.
   */
  case class PublishRollupTxs(entries: Try[Map[String, RollupTxStub]])

  /**
   * Sent from TransactionPublisher to TransactionProcessor on every successful
   * map build, carrying the ready-to-process rollup stubs.
   */
  case class PublishedRollupMap(entries: Map[String, RollupTxStub])

  /**
   * A priority-ordered batch of up to TX_BATCH_SIZE non-evaluation stubs
   * (NISPSubmissions, then Transforms, then Payouts) sent from
   * TransactionProcessor to SubmissionHandler for on-chain submission.
   */
  case class RollupBatch(stubs: Seq[RollupTxStub])

  /**
   * A batch of up to EVAL_SET_SIZE NISPEvaluation stubs sent from
   * TransactionProcessor to RollupEvaluator for fraud-proof checking.
   */
  case class EvaluationSet(stubs: Seq[RollupTxStub])

  // Trait representing entire rollup evaluation state
  sealed trait RollupEvaluationResult
  case class SuccessfulEvaluation(rollupBlockId: String) extends RollupEvaluationResult
  // Failed evaluation, holding map of hashed miner prop bytes to the hex representation of the fraud proof hash
  case class FailedEvaluation(stub: RollupTxStub, minerFPMap: Map[Array[Byte], String]) extends RollupEvaluationResult

  sealed trait MinerEvaluationResult
  case object NoFraudulence extends MinerEvaluationResult
  case class FraudFound(fp: String) extends MinerEvaluationResult
  case object CriticalEvalError extends MinerEvaluationResult
  case object NormalEvalError extends MinerEvaluationResult

  case class StopEvaluating(rollupBlockId: String)

  // In a fraud batch, fpInfo for all stubs is defined
  // To be sent from RollupEvaluator to TransactionProcessor
  // and then stored in fraud map for use during rollup batch creation
  // NOTE: all stubs in a fraud batch belong to the same rollup
  case class FraudBatch(fpStubs: Seq[RollupTxStub])

  // Latest States
  sealed trait LatestState
  case class LatestRollup(inputUTXO: InputUTXO, NISPTree: NISPTree) extends LatestState
  // Exceptions
  case class RollupRemovedException(msg: String) extends Exception(msg)
  case class StubInvalidException(msg: String) extends Exception(msg)
}
