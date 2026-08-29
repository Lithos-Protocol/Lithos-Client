package transactions.rollups

import akka.actor.{Actor, ActorRef, Cancellable}
import akka.pattern.ask
import akka.util.Timeout
import configs.{NodeContext, StateConfig}
import evaluation.Evaluator
import lfsm.LFSMHelpers
import mutations.BoxLoader
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.SignedTransaction
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import play.api.libs.concurrent.InjectedActorSupport
import sigma.exceptions.InterpreterException
import state.messages.RollupMessages
import state.messages.RollupMessages.{GetCurrentRollup, RollupInfo, UpdateEvaluation}
import transactions.ProtocolContracts
import transactions.rollups.RollupEvaluator.{BatchEvaluated, EVAL_BATCH_SIZE, EvaluateNextBatch, EvaluationBatch}
import transactions.rollups.TransactionMessages.{CriticalEvalError, EvaluationSet, FailedEvaluation, FraudBatch, FraudFound, LatestRollup, MinerEvaluationResult, NoFraudulence, NormalEvalError, RollupRemovedException, RollupTxStub, StopEvaluating, SuccessfulEvaluation}
import work.lithos.mutations.{Contract, InputUTXO}

import javax.inject.{Inject, Named}
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Random, Success, Try}

/**
 * Actor responsible for running fraud-proof checks on EVAL-phase rollups.
 * Receives EvaluationSet messages from TransactionProcessor and evaluates
 * each NISPEvaluation stub in the set.
 */
class RollupEvaluator @Inject()(config: Configuration, nodeContext: NodeContext,
                                @Named("sync-handler") syncHandler: ActorRef,
                                @Named("transaction-processor") txProcessor: ActorRef)
  extends Actor with InjectedActorSupport {

  implicit val timeout: Timeout = Timeout(30.seconds)
  implicit val ec: ExecutionContext = context.dispatcher

  private val stateConfig: StateConfig = new StateConfig(config)
  private val nodeConfig: NodeContext = nodeContext
  private val client = nodeConfig.getClient
  private val wallet = nodeConfig.getNodeWallet
  private val logger: Logger = LoggerFactory.getLogger("RollupEvaluator")
  private var evalMap: Map[String, RollupTxStub] = Map.empty

  /**
   * Whether a batch is running. A batch is up to five rollups, each running seven proof contracts
   * against every miner in it, which is minutes of interpreter and AVL work — and entries only leave
   * `evalMap` when that work reports back. Without this the four-minute tick starts the same rollups
   * again, doubling the load on the dispatcher where WalletManager's five-second asks are waiting.
   */
  private var evaluating: Boolean = false

  private var ticker: Option[Cancellable] = None

  // ─── lifecycle ────────────────────────────────────────────────────────────

  override def preStart(): Unit = {
    if (!stateConfig.disableTransforms.getOrElse(false)) {
      logger.info("RollupEvaluator starting - evaluating rollups every 4 minutes")
      ticker = Some(
        context.system.scheduler.scheduleWithFixedDelay(4.minutes, 4.minutes, self, EvaluateNextBatch)(context.dispatcher)
      )
    } else {
      logger.info("RollupEvaluator disabled via disableTransforms config")
    }
  }

  override def postStop(): Unit = ticker.foreach(_.cancel())

  override def receive: Receive = {
    case EvaluationSet(stubs) =>
      logger.info(s"Got ${stubs.size} stubs to evaluate")
      val stubsToAdd = stubs
        .filterNot(s => evalMap.contains(s.rollupBlockId))
        .map(s => s.rollupBlockId -> s)
      logger.info(s"Added ${stubsToAdd.size} unique rollups to evaluate")
      evalMap = evalMap ++ stubsToAdd

    case EvaluateNextBatch =>
      // Oldest first, and tolerant of a missing period rather than throwing inside receive — an
      // exception here restarts the actor and drops every rollup queued for evaluation.
      if (evaluating)
        logger.info("Skipping evaluation tick, the previous batch is still running")
      else if (evalMap.nonEmpty) {
        // Claimed HERE, where the decision is made, not in the EvaluationBatch handler below.
        // That message is sent to self, so it goes to the back of the mailbox — and two ticks
        // arriving before it is processed would both see the flag clear and both dispatch.
        evaluating = true
        self ! EvaluationBatch(
          evalMap.values.toSeq.sortBy(_.currentPeriod.getOrElse(Long.MaxValue)).take(EVAL_BATCH_SIZE))
      }

    case EvaluationBatch(stubs) =>
      evaluating = true
      // Off the actor thread, and sequential within one Future. Each rollup runs up to seven proofs
      // against every miner in it, which is minutes of interpreter work — inline it would block this
      // mailbox, and running the batch in parallel would take several threads of the shared
      // tx-dispatcher where WalletManager's callers wait on 5-second asks. The contracts are fetched
      // in here too, since that opens a BlockchainContext.
      Future {
        val fpContracts = getFPContracts
        stubs.foreach { s =>
          // Errors reaching here are the ordinary kind — a box lookup or a state timeout, since
          // per-miner failures are handled inside. Stop evaluating and pick it up next batch.
          attemptEvaluation(s, fpContracts) match {
            case Failure(ex) =>
              logger.error(s"Got error outside of evaluation attempt for rollup ${s.rollupBlockId}", ex)
              self ! StopEvaluating(s.rollupBlockId)
            case Success(_) => ()
          }
        }
      }.onComplete {
        case Success(_) => self ! BatchEvaluated
        case Failure(ex) =>
          logger.error("Got error when running an evaluation batch; not changing evaluation state", ex)
          self ! BatchEvaluated
      }

    case BatchEvaluated =>
      evaluating = false


    case SuccessfulEvaluation(rollupBlockId) =>
      logger.info(s"Successfully evaluated rollup ${rollupBlockId}")
      syncHandler ! UpdateEvaluation(rollupBlockId)
      self ! StopEvaluating(rollupBlockId)

    case FailedEvaluation(rollupTxStub, fpMap) =>
      if (fpMap.isEmpty) {
        // Explanation in attemptEvaluation()
        logger.warn(s"Updating evaluation state for rollup ${rollupTxStub.rollupBlockId} with critical errors")
        syncHandler ! UpdateEvaluation(rollupTxStub.rollupBlockId)
      } else {
        val updatedStubs = fpMap.map(fpm => rollupTxStub.copy(fpInfo = Some(fpm._1 -> fpm._2))).toSeq

        logger.info(s"Sent FraudBatch of ${updatedStubs.size} stubs to TransactionProcessor" +
          s" for rollup ${rollupTxStub.rollupBlockId} ")
        // If we find fraud proofs, we send batch to tx processor. We can stop evaluating after,
        // but we should not update evaluation state until the txs make it onto the blockchain.
        // If for some reason an error occurs during tx submission, this ensures that next EvaluationSet from
        // tx processor will still contain this rollup (and that the batch will be sent again)
        txProcessor ! FraudBatch(updatedStubs)
      }

      self ! StopEvaluating(rollupTxStub.rollupBlockId)

    case StopEvaluating(rollupBlockId) =>
      logger.info(s"Stopping evaluation for rollup $rollupBlockId")
      evalMap = evalMap - rollupBlockId
  }

  private def attemptEvaluation(stub: RollupTxStub, fpContracts: Seq[Contract]) = {
    Try {
      client.execute {
        ctx =>
          val latestRollup = latestRollupState(stub).get
          val stillValid = stub.validate(ctx.getHeight, latestRollup.rollup)

          if (stillValid) {
            val currentMiners = ArrayBuffer.empty[Array[Byte]]
            latestRollup.rollup.dictionary.foreachKey { key =>
              require(key.length == latestRollup.rollup.dictionary.parameters.keySize,
                s"Rollup ${latestRollup.rollup.blockId} contains an invalid ${key.length}-byte miner key")
              currentMiners += key
            }
            require(currentMiners.size == latestRollup.rollup.numMiners,
              s"Rollup ${latestRollup.rollup.blockId} counts ${latestRollup.rollup.numMiners} " +
                s"miners but its authenticated dictionary contains ${currentMiners.size}")
            // Preserve randomized evaluation order without sortBy's O(n log n) comparisons and
            // temporary random-key tuples. This list exists only for the active evaluation.
            var index = currentMiners.size - 1
            while (index > 0) {
              val swap = Random.nextInt(index + 1)
              val miner = currentMiners(index)
              currentMiners(index) = currentMiners(swap)
              currentMiners(swap) = miner
              index -= 1
            }
            val fpControl = LFSMHelpers.getFPControlBox(ctx)
            val evaluator = Evaluator(ctx, wallet, latestRollup.inputUTXO, latestRollup.rollup, currentMiners,
              fpControl, new BoxLoader(ctx, nodeContext.getNodeApi), fpContracts)
            val evals = evaluator.evaluateSync
            val minerEvaluationResults = evals.map(processEvaluationResult(stub, _))
            manageEvaluationState(stub, minerEvaluationResults)
          }
          else {
            // If invalidated, rollup is either already invalidated or it has left its current phase
            logger.warn(s"Removing invalidated NISPEvaluation stub for rollup ${stub.rollupBlockId}")
            self ! StopEvaluating(stub.rollupBlockId)
          }
      }
    }

  }
  private def processEvaluationResult(stub: RollupTxStub,
                                      minerEval: (Array[Byte], Try[Option[(SignedTransaction, String)]])
                                     ): (Array[Byte], MinerEvaluationResult) = {
    val tryEval = minerEval._2

    tryEval match {
      case Failure(crit: InterpreterException) =>
        logger.error(s"Got critical error during evaluation of rollup ${stub.rollupBlockId} for" +
          s" miner hash ${Hex.toHexString(minerEval._1)}", crit)
        minerEval._1 -> CriticalEvalError
      case Failure(err) =>
        logger.error(s"Got normal error during evaluation of rollup ${stub.rollupBlockId} for" +
          s" miner hash ${Hex.toHexString(minerEval._1)}", err)
        minerEval._1 -> NormalEvalError
      case Success(evalResult) =>
        evalResult match {
          case Some(fpTx) => minerEval._1 -> FraudFound(fpTx._2)
          case None => minerEval._1 -> NoFraudulence
        }
    }
  }

  private def manageEvaluationState(stub: RollupTxStub, results:  Seq[(Array[Byte], MinerEvaluationResult)]): Unit = {
    val critErrors = results.filter(_._2 == CriticalEvalError)
    if (critErrors.nonEmpty) {
      logger.error(s"Stopping evaluation due to critical errors in rollup ${stub.rollupBlockId}")
      // Critical errors should never occur, as they indicate problems with
      // the fraud proof contract itself. However, if they are found
      // we should send a failed eval with empty fraud proofs. If such an error
      // can only happen under special conditions, this ensures that
      // un-affected rollups can still be evaluated properly without
      // critically failing rollups clogging up the evalMap and therefore
      // every evaluation batch until their phase changes.
      self ! FailedEvaluation(stub, Map.empty)
    } else {
      if (results.forall(_._2 == NoFraudulence)) {
        self ! SuccessfulEvaluation(stub.rollupBlockId)
      } else if (results.exists(_._2 == NormalEvalError)) {
        // For normal(non interpreter exception) eval errors, we will stop evaluating for right now.
        // As the evaluation state is not changing, they should get another chance to be evaluated
        // when the next evaluation set is sent from TransactionProcessor
        self ! StopEvaluating(stub.rollupBlockId)
      } else {
        val minerFPs = results
          .filter(_._2.isInstanceOf[FraudFound])
          .map(mfp => mfp._1 -> mfp._2.asInstanceOf[FraudFound].fp)
          .toMap
        self ! FailedEvaluation(stub, minerFPs)
      }
    }
  }

  private def getFPContracts = client.execute(ctx => ProtocolContracts.fraudProofs(ctx))

  private def latestRollupState(rollupTxStub: RollupTxStub) = {
    Try {
      val rollupInfo = Await.result[RollupInfo](
        (syncHandler ? GetCurrentRollup(rollupTxStub.rollupBlockId)).mapTo[RollupInfo], timeout.duration)
      rollupInfo match {
        case RollupMessages.CurrentRollup(utxoId, rollup, mempoolState) =>
          if (mempoolState.isDefined) {
            if (!mempoolState.get.toBeRemoved) {
              logger.info(s"Using mempool state for rollup ${rollupTxStub.rollupBlockId}" +
                s" with synced id $utxoId and mempool id ${mempoolState.get.asInput.id}")
              Success(LatestRollup(mempoolState.get.asInput, mempoolState.get.rollup))
            } else {
              Failure(RollupRemovedException(s"Cannot evaluate rollup" +
                s" ${rollupTxStub.rollupBlockId} with upcoming removal"))
            }
          } else {
            Success(LatestRollup(InputUTXO(client.execute(_.getBoxesById(utxoId).head)), rollup))
          }
        case RollupMessages.NoRollupFound() =>
          Failure(new IllegalStateException(s"No existing rollup for blockId ${rollupTxStub.rollupBlockId}"))
        case RollupMessages.RollupUnavailable(reason) =>
          Failure(new IllegalStateException(
            s"Rollup ${rollupTxStub.rollupBlockId} is temporarily unavailable: $reason"))
      }
    }.flatten
  }
}

object RollupEvaluator {
  /** Maximum number of rollups to evaluate. */
  private final val EVAL_BATCH_SIZE: Int = 5

  private case class EvaluationBatch(stubs: Seq[RollupTxStub])

  /** Widened from `private` so the package's tests can drive a tick instead of waiting 4 minutes. */
  private[transactions] case object EvaluateNextBatch

  /** Self-message: the batch Future has finished, however it went. */
  private case object BatchEvaluated
}
