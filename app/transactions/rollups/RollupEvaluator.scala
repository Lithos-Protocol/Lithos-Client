package transactions.rollups

import akka.actor.{Actor, ActorRef, Cancellable}
import akka.pattern.ask
import akka.util.Timeout
import configs.{NodeContext, StateConfig}
import evaluation.Evaluator
import lfsm.LFSMHelpers
import lfsm.LFSMPhase
import lfsm.states.RollupMetadata
import nisp.{NispCommitment, NispPayloadUnavailable, ResolvedNisps}
import lfsm.contracts.FraudProofContracts.FraudProofSet
import mutations.BoxLoader
import org.bouncycastle.util.encoders.Hex
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import play.api.libs.concurrent.InjectedActorSupport
import state.messages.RollupMessages
import state.messages.RollupMessages.{GetCurrentRollupCritical, RollupInfo, UpdateEvaluation}
import state.messages.RollupMessages.RollupHistoryAnchor
import state.synchronization.{RollupNispResolver, SyncProtocolContext}
import transactions.ProtocolContracts
import transactions.rollups.RollupEvaluator.{BatchEvaluated, EVAL_BATCH_SIZE, EvaluateNextBatch, EvaluationBatch}
import transactions.rollups.TransactionMessages.{EvalError, EvaluationSet, FailedEvaluation, FraudBatch, FraudFound, LatestRollup, MinerEvaluationResult, NoFraudulence, RollupRemovedException, RollupTxStub, StopEvaluating, SuccessfulEvaluation}
import work.lithos.mutations.InputUTXO

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
   * Whether a batch is running. A batch is up to five rollups, each running nine proof contracts
   * against every miner in it, which is minutes of interpreter and AVL work — and entries only leave
   * `evalMap` when that work reports back. Without this the four-minute tick starts the same rollups
   * again, doubling the load on the dispatcher where WalletManager's five-second asks are waiting.
   */
  private var evaluating: Boolean = false

  /**
   * Rollup id to evaluations that left a miner without a verdict. Dropped on a successful
   * evaluation; emptied whole above
   * [[RollupEvaluator.MaxTrackedRollups]].
   *
   * Never pruned by membership, because neither `evalMap` nor an `EvaluationSet` holds the rollups
   * still being retried.
   */
  private var failedAttempts: Map[String, Int] = Map.empty
  private val recoveryMaxTransforms = config.getOptional[Int]("evaluation.nispRecovery.maxTransforms")
    .getOrElse(100000)
  require(recoveryMaxTransforms > 0, "evaluation.nispRecovery.maxTransforms must be positive")

  private case class EvaluationSnapshot(latest: LatestRollup, confirmed: RollupMetadata,
                                         history: Option[RollupHistoryAnchor])

  private var ticker: Option[Cancellable] = None

  // ─── lifecycle ────────────────────────────────────────────────────────────

  override def preStart(): Unit = {
    if (!stateConfig.disableTransforms.getOrElse(false)) {
      logger.info("RollupEvaluator starting - evaluating rollups every 4 minutes")
      ticker = Some(
        context.system.scheduler.scheduleWithFixedDelay(30.seconds, 30.seconds, self, EvaluateNextBatch)(context.dispatcher)
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
      // Off the actor thread, and sequential within one Future. Each rollup runs up to nine proofs
      // against every miner in it, which is minutes of interpreter work — inline it would block this
      // mailbox, and running the batch in parallel would take several threads of the shared
      // tx-dispatcher where WalletManager's callers wait on 5-second asks. The contracts are fetched
      // in here too, since that opens a BlockchainContext.
      Future {
        val fpSet = getFPSet
        stubs.foreach { s =>
          // Errors reaching here are the ordinary kind — a box lookup or a state timeout, since
          // per-miner failures are handled inside. Stop evaluating and pick it up next batch.
          attemptEvaluation(s, fpSet) match {
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


    case SuccessfulEvaluation(rollupBlockId, expectedUtxoId) =>
      logger.info(s"Successfully evaluated rollup ${rollupBlockId}")
      failedAttempts -= rollupBlockId
      syncHandler ! UpdateEvaluation(rollupBlockId, expectedUtxoId)
      self ! StopEvaluating(rollupBlockId)

    case FailedEvaluation(rollupTxStub, fpMap, payloadUnavailable) =>
      if (fpMap.isEmpty) {
        val rollupId = rollupTxStub.rollupBlockId
        // Emptied whole rather than evicted one key at a time.
        if (failedAttempts.size >= RollupEvaluator.MaxTrackedRollups) {
          logger.warn(s"Counting failed evaluations for ${failedAttempts.size} rollups; clearing " +
            "the counts, so any rollup mid-retry starts its attempts again")
          failedAttempts = Map.empty
        }
        val attempts = math.min(failedAttempts.getOrElse(rollupId, 0) + 1, RollupEvaluator.MaxEvaluationAttempts)
        failedAttempts += rollupId -> attempts
        if (attempts < RollupEvaluator.MaxEvaluationAttempts)
          // Left unevaluated, so the next EvaluationSet offers it again.
          logger.warn(s"Rollup $rollupId left a miner without a verdict, attempt $attempts of " +
            s"${RollupEvaluator.MaxEvaluationAttempts}; leaving it for a later batch")
        else {
          logger.error("=" * 100)
          logger.error(s"Rollup $rollupId remains incompletely evaluated after $attempts attempts; " +
            s"payload unavailable: $payloadUnavailable. Retaining it for retry.")
          logger.error("=" * 100)
        }
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

  private def attemptEvaluation(stub: RollupTxStub, fpSet: FraudProofSet) = {
    Try {
      client.execute {
        ctx =>
          val snapshot = latestRollupState(stub).get
          val latestRollup = snapshot.latest
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
            // Only FP_NonMatchingCommitment reads this, and only for miners it finds registered.
            // None leaves that one proof unbuildable and the other eight unaffected.
            val commitment = CommitmentSources.load(ctx, nodeContext.getNodeApi, syncHandler,
              currentMiners.toSeq)
            val loader = new BoxLoader(ctx, nodeContext.getNodeApi)
            val first = Evaluator(ctx, wallet, latestRollup.inputUTXO, latestRollup.rollup, currentMiners,
              fpControl, loader, fpSet, Seq(fpSet.nonMatchingCommitment), commitment)
              .evaluateProofTypes.map { case (key, result) => Hex.toHexString(key) -> result }.toMap
            val entries = if (currentMiners.isEmpty) Seq.empty
              else latestRollup.rollup.dictionary.copy().lookUp(currentMiners: _*).response
            val commitments = currentMiners.zip(entries).map { case (key, entry) =>
              Hex.toHexString(key) -> NispCommitment.decode(entry.get)
            }.toMap
            val pending = commitments.filterNot { case (key, _) => first(key).toOption.flatten.isDefined }
            val recovered = if (pending.isEmpty) None else snapshot.history match {
              case Some(anchor) if snapshot.confirmed.phase == LFSMPhase.EVAL =>
                val protocol = SyncProtocolContext(ctx.getNetworkType, snapshot.confirmed.startHeight,
                  wallet.contract.hashedPropBytes)
                val resolution = new RollupNispResolver(nodeContext.getNodeApi, protocol, recoveryMaxTransforms)
                  .resolve(snapshot.confirmed, anchor.collateralBoxId, anchor.confirmedHeight, pending)
                logger.info(s"NISP recovery for ${stub.rollupBlockId}: ${resolution.indexRequests} indexed reads, " +
                  s"${resolution.transforms} transforms, " +
                  s"${resolution.elapsedMillis}ms, ${resolution.resolved.retainedBytes} payload bytes, " +
                  s"${resolution.unavailable.size} unavailable")
                resolution.historyError.foreach(logger.warn)
                Some(resolution.resolved)
              case _ =>
                logger.info(s"Deferring payload recovery for ${stub.rollupBlockId} until its Evaluation transition is indexed")
                None
            }

            // Slashes may have changed the root during recovery. Rebuild every trial witness from
            // the current state and ignore entries that have already been removed.
            val refreshed = latestRollupState(stub).get.latest
            if (stub.validate(ctx.getHeight, refreshed.rollup)) {
              val remaining = refreshed.rollup.dictionary.foldKeys(Vector.empty[Array[Byte]])(_ :+ _)
              require(remaining.size == refreshed.rollup.numMiners, "Refreshed rollup miner count differs from its dictionary")
              val currentEntries = if (remaining.isEmpty) Seq.empty
                else refreshed.rollup.dictionary.copy().lookUp(remaining: _*).response
              val unchanged = remaining.zip(currentEntries).map { case (key, entry) =>
                Hex.toHexString(key) -> commitments.get(Hex.toHexString(key))
                  .contains(NispCommitment.decode(entry.get))
              }.toMap
              val toEvaluate = remaining.filter { key =>
                val hex = Hex.toHexString(key)
                unchanged(hex) && !first.get(hex).flatMap(_.toOption.flatten).isDefined &&
                  recovered.flatMap(_.get(key)).isDefined
              }
              val later = Evaluator(ctx, wallet, refreshed.inputUTXO, refreshed.rollup, toEvaluate,
                fpControl, loader, fpSet, fpSet.ordered.tail, commitment, recovered)
                .evaluateProofTypes.map { case (key, verdict) => Hex.toHexString(key) -> verdict }.toMap
              val results = remaining.map { key =>
                val hex = Hex.toHexString(key)
                val verdict = if (!unchanged(hex)) Failure(new NispPayloadUnavailable(
                  s"Entry changed during evaluation for miner $hex"))
                else first(hex) match {
                  case fraud @ Success(Some(_)) => fraud
                  case firstVerdict => later.get(hex) match {
                    case Some(fraud @ Success(Some(_))) => fraud
                    case Some(Success(None)) => firstVerdict
                    case Some(failure @ Failure(_)) => failure
                    case None => Failure(new NispPayloadUnavailable(s"Payload unavailable for miner $hex"))
                  }
                }
                processEvaluationResult(stub, key -> verdict)
              }
              manageEvaluationState(stub.copy(resolvedNisps = recovered), results,
                Some(refreshed.rollup.utxoId), pending.nonEmpty &&
                  pending.keys.exists(key => !recovered.exists(_.payloads.contains(key))))
            } else self ! StopEvaluating(stub.rollupBlockId)
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
                                      minerEval: (Array[Byte], Try[Option[String]])
                                     ): (Array[Byte], MinerEvaluationResult) = {
    val tryEval = minerEval._2

    tryEval match {
      case Failure(err) =>
        logger.error(s"Got error during evaluation of rollup ${stub.rollupBlockId} for" +
          s" miner hash ${Hex.toHexString(minerEval._1)}", err)
        minerEval._1 -> EvalError
      case Success(evalResult) =>
        evalResult match {
          case Some(fpHash) => minerEval._1 -> FraudFound(fpHash)
          case None => minerEval._1 -> NoFraudulence
        }
    }
  }

  private def manageEvaluationState(stub: RollupTxStub, results: Seq[(Array[Byte], MinerEvaluationResult)],
                                     expectedUtxoId: Option[String], payloadUnavailable: Boolean): Unit = {
    val minerFPs = results.collect { case (miner, FraudFound(proof)) => miner -> proof }.toMap
    if (minerFPs.nonEmpty) {
      val queuedKeys = minerFPs.keysIterator.map(Hex.toHexString).toSet
      val retained = stub.resolvedNisps.map(r => r.copy(payloads = r.payloads.filter {
        case (key, _) => queuedKeys(key)
      })).filter(_.payloads.nonEmpty)
      self ! FailedEvaluation(stub.copy(resolvedNisps = retained), minerFPs, payloadUnavailable)
    } else if (results.exists(_._2 == EvalError)) {
      logger.error(s"Rollup ${stub.rollupBlockId} has ${results.count(_._2 == EvalError)} of " +
        s"${results.size} miner(s) without a verdict; retrying the rollup")
      self ! FailedEvaluation(stub, Map.empty, payloadUnavailable)
    } else if (results.forall(_._2 == NoFraudulence)) {
      self ! SuccessfulEvaluation(stub.rollupBlockId, expectedUtxoId)
    }
  }

  private def getFPSet = client.execute(ctx => ProtocolContracts(ctx).fraudProofs)

  private def latestRollupState(rollupTxStub: RollupTxStub) = {
    Try {
      val rollupInfo = Await.result[RollupInfo](
        (syncHandler ? GetCurrentRollupCritical(rollupTxStub.rollupBlockId)).mapTo[RollupInfo],
        timeout.duration)
      rollupInfo match {
        case RollupMessages.CurrentRollup(utxoId, rollup, mempoolState, history) =>
          if (mempoolState.isDefined) {
            if (!mempoolState.get.toBeRemoved) {
              logger.info(s"Using mempool state for rollup ${rollupTxStub.rollupBlockId}" +
                s" with synced id $utxoId and mempool id ${mempoolState.get.asInput.id}")
              Success(EvaluationSnapshot(LatestRollup(mempoolState.get.asInput, mempoolState.get.rollup),
                rollup.metadata, history))
            } else {
              Failure(RollupRemovedException(s"Cannot evaluate rollup" +
                s" ${rollupTxStub.rollupBlockId} with upcoming removal"))
            }
          } else {
            Success(EvaluationSnapshot(LatestRollup(InputUTXO(client.execute(_.getBoxesById(utxoId).head)), rollup),
              rollup.metadata, history))
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

  /** Repeated incomplete evaluations trigger an alarm, never a successful verdict. */
  private[rollups] final val MaxEvaluationAttempts: Int = 10

  /** Rollups counted at once, above which the counts are dropped. Over the twenty a set can offer. */
  private[rollups] final val MaxTrackedRollups: Int = 64

  /** Maximum number of rollups to evaluate. */
  private final val EVAL_BATCH_SIZE: Int = 5

  private case class EvaluationBatch(stubs: Seq[RollupTxStub])

  /** Widened from `private` so the package's tests can drive a tick instead of waiting 4 minutes. */
  private[transactions] case object EvaluateNextBatch

  /** Self-message: the batch Future has finished, however it went. */
  private case object BatchEvaluated
}
