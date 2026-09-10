package transactions.rollups

import lfsm.LFSMHelpers
import lfsm.LFSMPhase.{EVAL, HOLDING, PAYOUT}
import lfsm.states.{Rollup, RollupMetadata}
import nisp.ResolvedNisps
import org.bouncycastle.util.encoders.Hex
import transactions.rollups.TransactionMessages.RollupTxType.{EvalTransform, HoldingTransform, NISPEvaluation, NISPSubmission, Payout}
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
   * @param rollupBlockId  Rollup.blockId — the block that originated this rollup
   * @param currentPeriod  Rollup.currentPeriod — the block height at which the
   *                       rollup entered its current phase; None for PAYOUT-phase
   *                       rollups where the field is not set
   * @param txType         Which of the five transaction types is due
   * @param fee            Transaction fee in nanoERG; defaults to ROLLUP_FEE
   */
  case class RollupTxStub(rollupBlockId: String,
                          currentPeriod: Option[Long],
                          txType: RollupTxType,
                          fee: Long = RollupTxStub.ROLLUP_FEE,
                          fpInfo: Option[(Array[Byte], String)] = None,
                          resolvedNisps: Option[ResolvedNisps] = None) extends TxStub{
    override def toString: String = {
      s"RollupTxStub($rollupBlockId, $txType, $currentPeriod, $fee," +
        s" ${fpInfo.map(i => Hex.toHexString(i._1) -> i._2)})"
    }

    /**
     * Check if this stub is valid against a given height and Rollup
     * @param height Height to validate against
     * @param rollup Rollup to validate against
     */
    def validate(height: Int, rollup: RollupMetadata): Boolean = {
      txType match {
        case HoldingTransform =>
          rollup.phase == HOLDING && (height - rollup.currentPeriod.get) >= LFSMHelpers.HOLDING_PERIOD
        case NISPSubmission =>
          rollup.phase == HOLDING && (height - rollup.currentPeriod.get) < LFSMHelpers.HOLDING_PERIOD && !rollup.hasMiner
        case EvalTransform =>
          rollup.phase == EVAL && rollup.evaluated &&
            (height - rollup.currentPeriod.get) >= LFSMHelpers.EVAL_PERIOD
        case NISPEvaluation =>
          rollup.phase == EVAL && (height - rollup.currentPeriod.get) < LFSMHelpers.EVAL_PERIOD && !rollup.evaluated
        case Payout =>
          rollup.phase == PAYOUT
      }
    }

    def validate(height: Int, rollup: Rollup): Boolean = validate(height, rollup.metadata)
  }

  object RollupTxStub {
    /** Standard fee applied to all rollup transactions. */
    final val ROLLUP_FEE: Long = work.lithos.mutations.UTXO.MIN_FEE
  }

  // ─── actor messages ───────────────────────────────────────────────────────

  /**
   * Piped to RollupPublisher from itself once buildRollupMap completes.
   * Carries either the rebuilt map or a captured failure.
   */
  case class PublishRollupTxs(entries: Try[Map[String, RollupTxStub]])

  /**
   * Sent from RollupPublisher to RollupProcessor on every successful
   * map build, carrying the ready-to-process rollup stubs.
   */
  case class PublishedRollupMap(entries: Map[String, RollupTxStub])

  /**
   * A priority-ordered batch of up to TX_BATCH_SIZE non-evaluation stubs
   * (NISPSubmissions, then Transforms, then Payouts) sent from
   * RollupProcessor to RollupExecution for on-chain submission.
   */
  case class RollupBatch(stubs: Seq[RollupTxStub])

  /**
   * RollupExecution → RollupProcessor: this batch was taken, so its stubs may be dropped.
   *
   * A batch refused by the submission lock is NOT acknowledged, and its stubs stay queued for the
   * next tick. Dropping them on the send instead lost them whenever the lock was held — and while
   * `RollupPublisher` rebuilds most stub types from chain state every two minutes, fraud proof
   * stubs only ever arrive through `FraudBatch` and nothing else re-derives them.
   */
  case class BatchAccepted(stubs: Seq[RollupTxStub])

  /**
   * A batch of up to EVAL_SET_SIZE NISPEvaluation stubs sent from
   * RollupProcessor to RollupEvaluator for fraud-proof checking.
   */
  case class EvaluationSet(stubs: Seq[RollupTxStub])

  /**
   * RollupProcessor → RollupExecution: build these stubs fee-less and reply to the original
   * requester WITHOUT sending them. The stubs stay queued, so the funded copies still reach the
   * mempool on the normal tick; whichever lands first wins and the other is a double spend.
   */
  /**
   * @param answer whether a requester is waiting on this build. False when it is preparation, which
   *               caches its result for the request that follows.
   */
  case class BuildBlockTxs(blockHeight: Int, stubs: Seq[RollupTxStub], answer: Boolean = true)

  // Trait representing entire rollup evaluation state
  sealed trait RollupEvaluationResult
  case class SuccessfulEvaluation(rollupBlockId: String, expectedUtxoId: Option[String] = None) extends RollupEvaluationResult
  // Failed evaluation, holding map of hashed miner prop bytes to the hex representation of the fraud proof hash
  case class FailedEvaluation(stub: RollupTxStub, minerFPMap: Map[Array[Byte], String],
                              payloadUnavailable: Boolean = false) extends RollupEvaluationResult

  sealed trait MinerEvaluationResult
  case object NoFraudulence extends MinerEvaluationResult
  case class FraudFound(fp: String) extends MinerEvaluationResult
  /** A proof neither found fraud nor declined: it threw, so this miner has no verdict. */
  case object EvalError extends MinerEvaluationResult

  case class StopEvaluating(rollupBlockId: String)

  // In a fraud batch, fpInfo for all stubs is defined
  // To be sent from RollupEvaluator to RollupProcessor
  // and then stored in fraud map for use during rollup batch creation
  // NOTE: all stubs in a fraud batch belong to the same rollup
  case class FraudBatch(fpStubs: Seq[RollupTxStub])

  // Latest States
  sealed trait LatestState
  /**
   * @param ancestorIds unconfirmed transactions behind `inputUTXO`, parent first. Empty when it is
   *                    a confirmed box; anything built on it into a block must carry these too.
   */
  case class LatestRollup(inputUTXO: InputUTXO, rollup: Rollup,
                          ancestorIds: Seq[String] = Seq.empty) extends LatestState
  /**
   * Forget every queued stub for a rollup that has been dropped deliberately.
   *
   * Synchronisation stops tracking the rollup at once, but stubs already queued here keep being
   * offered until a later published map reaps them — each one a node round trip for work that cannot
   * succeed. This closes that window.
   */
  case class DropRollupStubs(blockId: String, reason: String)

  // Exceptions
  case class RollupRemovedException(msg: String) extends Exception(msg)
  case class NewlyGeneratedRollupException(msg: String) extends Exception(msg)
  case class StubInvalidException(msg: String) extends Exception(msg)
  case class ProjectionChangedException(msg: String) extends Exception(msg)
}
