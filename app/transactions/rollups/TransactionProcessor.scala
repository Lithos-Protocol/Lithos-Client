package transactions.rollups

import akka.actor.{Actor, ActorRef, Cancellable}
import akka.pattern.ask
import akka.util.Timeout
import configs.{NodeContext, StateConfig}
import lfsm.states.NISPTree
import org.ergoplatform.appkit.ErgoClient
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import play.api.cache.SyncCacheApi
import play.api.libs.concurrent.InjectedActorSupport
import state.messages.MempoolMessages.MempoolRollupState
import state.messages.SyncMessages._
import transactions.BlockTxMessages.{BlockTxsReady, CandidateTx, RequestBlockTxs}
import transactions.rollups.TransactionMessages.RollupTxType._
import transactions.rollups.TransactionMessages.{BatchAccepted, BuildBlockTxs, EvaluationSet, FraudBatch, PublishedRollupMap, RollupBatch, RollupTxStub, RollupTxType}
import transactions.rollups.TransactionProcessor._

import javax.inject.{Inject, Named}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success}

/** Owns pending rollup work, removes stale entries, and dispatches ready transaction stubs. */
class TransactionProcessor @Inject()(config: Configuration, nodeContext: NodeContext,
                                     cacheApi: SyncCacheApi,
                                     @Named("sync-handler")        syncHandler:       ActorRef,
                                     @Named("submission-handler")  submissionHandler: ActorRef,
                                     @Named("rollup-evaluator")    rollupEvaluator:   ActorRef)
  extends Actor with InjectedActorSupport {

  implicit val timeout: Timeout     = Timeout(30.seconds)
  implicit val ec: ExecutionContext = context.dispatcher

  private val logger: Logger        = LoggerFactory.getLogger("TransactionProcessor")

  private val nodeConfig: NodeContext        = nodeContext
  private val client: ErgoClient            = nodeConfig.getClient
  private val stateConfig: StateConfig      = new StateConfig(config)

  // ─── mutable state ────────────────────────────────────────────────────────

  /**
   * Map of rollupBlockId -> Seq[RollupTxStub] for all pending on-chain
   * transactions. Cleaned and extended on every PublishedRollupMap received.
   */
  private var pendingRollupTxs: Map[String, Seq[RollupTxStub]] = Map.empty

  private var ticker: Option[Cancellable] = None

  // ─── lifecycle ────────────────────────────────────────────────────────────

  override def preStart(): Unit = {
    if (!stateConfig.disableTransforms.getOrElse(false)) {
      logger.info("TransactionProcessor starting - processing pending transactions every 3 minutes")
      ticker = Some(
        context.system.scheduler.scheduleWithFixedDelay(3.minutes, 3.minutes, self, ProcessTransactions)(context.dispatcher)
      )
    } else {
      logger.info("TransactionProcessor disabled via disableTransforms config")
    }
  }

  override def postStop(): Unit = ticker.foreach(_.cancel())

  // ─── receive ──────────────────────────────────────────────────────────────

  override def receive: Receive = {

    case ProcessTransactions =>
      // Collect only the first (highest-priority) stub from each rollup's Seq
      val headStubs: Seq[RollupTxStub] = pendingRollupTxs.values.flatMap(_.headOption).toSeq
      val fpHeads = pendingRollupTxs.values
        .filter(_.headOption.exists(_.txType == RollupTxType.NISPEvaluation))
        .filter(_.exists(_.fpInfo.isDefined))
        .map(_.filter(_.fpInfo.isDefined))
        .flatMap(_.headOption).toSeq
      // Separate by type; timed types sorted oldest-first by currentPeriod
      val nispSubmissions = headStubs.filter(_.txType == NISPSubmission).sortBy(_.currentPeriod)
      val transforms      = headStubs.filter(s => s.txType == HoldingTransform || s.txType == EvalTransform).sortBy(_.currentPeriod)
      val payouts         = headStubs.filter(_.txType == Payout)
      val evaluations     = headStubs.filter(e => e.txType == NISPEvaluation && e.fpInfo.isEmpty).sortBy(_.currentPeriod)
      val fraudProofs     = fpHeads.sortBy(_.currentPeriod)
      // Priority order for the batch: NISPSubmissions -> Transforms -> Payouts

      val batch   = RollupBatch((nispSubmissions ++ fraudProofs ++ transforms ++ payouts).take(TX_BATCH_SIZE))

      // Evaluation tx stubs are sent to RollupEvaluator
      val evalSet = EvaluationSet(evaluations.take(EVAL_SET_SIZE))

      if (batch.stubs.nonEmpty)   submissionHandler ! batch
      if (evalSet.stubs.nonEmpty) rollupEvaluator   ! evalSet

      // Evaluation stubs are consumed on the send, because RollupEvaluator takes every set it is
      // given. The batch is not: SubmissionHandler refuses one while a batch is already running, so
      // its stubs are dropped only against a BatchAccepted. Dropping them here lost fraud proof
      // stubs outright, since nothing but a fresh evaluation cycle re-derives those.
      dropStubs(evalSet.stubs)

    case BatchAccepted(stubs) =>
      dropStubs(stubs)
    // One stub per fraudulent miner, all for the SAME rollup. Keying them by rollupBlockId collapsed
    // every miner but one, so a rollup with three fraudulent miners only ever slashed one of them.
    // Merged directly rather than through PublishedRollupMap, which would also spend a full sync
    // query re-validating entries the evaluator just derived from current state.
    case FraudBatch(fpStubs) =>
      logger.info(s"Merging ${fpStubs.size} fraud proof stub(s) for rollup " +
        s"${fpStubs.headOption.map(_.rollupBlockId).getOrElse("?")}")
      fpStubs.foreach(s => addRollupStubs(Map(s.rollupBlockId -> s)))
    // A block is being assembled. SubmissionHandler builds the highest-priority queued
    // work fee-less and replies straight back to the requester. Nothing is dispatched or removed
    // here, so the funded copies still reach the mempool if no block is found.
    case RequestBlockTxs(blockHeight, limit) =>
      val requester = sender()
      val chosen =
        if (limit <= 0) Seq.empty[RollupTxStub]
        else prioritise(pendingRollupTxs.values.flatMap(_.headOption).toSeq).take(limit)

      if (chosen.isEmpty) requester ! BlockTxsReady(blockHeight, Seq.empty[CandidateTx])
      else submissionHandler.tell(BuildBlockTxs(blockHeight, chosen), requester)

    // The height comes back with the sync state rather than being read in the handler. This actor
    // answers RequestBlockTxs, so a BlockchainContext opened on its own thread is a node round trip
    // in front of a miner assembling a block.
    case PublishedRollupMap(newEntries) =>
      (syncHandler ? GetSynced).mapTo[SyncMessage]
        .map(syncMsg => ProcessPublishedMap(syncMsg, newEntries, client.execute(_.getHeight)))
        .onComplete {
          case Failure(ex) =>
            logger.error(s"TransactionProcessor failed to query sync state: ${ex.getMessage}", ex)
          case Success(msg) =>
            self ! msg
        }

    // Preserve queued work when synchronization cannot provide a current rollup set.
    case ProcessPublishedMap(unavailable: SyncUnavailable, _, _) =>
      logger.warn(s"Keeping ${pendingRollupTxs.values.map(_.size).sum} pending stub(s) while " +
        s"synchronization is ${unavailable.status}")

    case ProcessPublishedMap(FullSync(rollups, projections), newEntries, height) =>
      cleanupRollupTxs(rollups, projections, height)
      addRollupStubs(newEntries)
  }

  /**
   * Priority order for this miner's own block. Submissions and fraud proofs first, since both are
   * time-boxed — a missed submission is a miner's whole claim on a rollup. Transforms and payouts
   * are on much longer clocks and lose nothing by riding the mempool instead.
   */
  /** Removes dispatched stubs by both transaction type and fraud target. */
  private def dropStubs(dispatched: Seq[RollupTxStub]): Unit =
    dispatched.foreach { stub =>
      pendingRollupTxs.get(stub.rollupBlockId).foreach { stubs =>
        val remaining = stubs.filterNot(s => s.txType == stub.txType && sameFraudTarget(s, stub))
        if (remaining.isEmpty)
          pendingRollupTxs = pendingRollupTxs - stub.rollupBlockId
        else
          pendingRollupTxs = pendingRollupTxs + (stub.rollupBlockId -> remaining)
      }
    }

  /**
   * Whether two stubs of the same type name the same fraud target. Always true for the four types
   * that carry no `fpInfo`, so they are still removed by type alone.
   */
  private def sameFraudTarget(a: RollupTxStub, b: RollupTxStub): Boolean =
    (a.fpInfo, b.fpInfo) match {
      case (Some(x), Some(y)) => x._1.sameElements(y._1)
      case (None, None) => true
      case _ => false
    }

  private def prioritise(stubs: Seq[RollupTxStub]): Seq[RollupTxStub] = {
    val submissions = stubs.filter(_.txType == NISPSubmission).sortBy(_.currentPeriod)
    val fraudProofs = stubs.filter(s => s.txType == NISPEvaluation && s.fpInfo.isDefined).sortBy(_.currentPeriod)
    val transforms  = stubs.filter(s => s.txType == HoldingTransform || s.txType == EvalTransform).sortBy(_.currentPeriod)
    val payouts     = stubs.filter(_.txType == Payout)
    submissions ++ fraudProofs ++ transforms ++ payouts
  }

  // ─── pending tx management ────────────────────────────────────────────────

  /**
   * Removes inactive rollups and stubs whose submission criteria no longer hold.
   */
  private def cleanupRollupTxs(rollups: Seq[(String, NISPTree)],
                               projections: Map[String, MempoolRollupState],
                               currentHeight: Int): Unit = {
    val rawTrees = rollups

    // Projections arrive with the trees they belong to, so no second read can disagree with them.
    val memStates: Map[String, MempoolRollupState] = rawTrees.flatMap { n =>
      projections.get(n._2.blockId).map(n._1 -> _)
    }.toMap

    val statesToRemove = memStates.filter(_._2.toBeRemoved)

    val updatedTrees: Seq[(String, NISPTree)] = rawTrees.map { t =>
      if (memStates.contains(t._1)) t._1 -> memStates(t._1).nispTree else t
    }

    // Build the lookup map from trees not flagged for removal, keyed by blockId
    val syncedTrees: Map[String, NISPTree] =
      updatedTrees.filterNot(u => statesToRemove.contains(u._1))
                  .map(t => t._2.blockId -> t._2)
                  .toMap

    // Remove rollups that are no longer present in the synced trees
    pendingRollupTxs.keys.filterNot(syncedTrees.contains).toSeq.foreach { blockId =>
      logger.warn(s"Removing all pending stubs for rollup $blockId")
      pendingRollupTxs = pendingRollupTxs - blockId
    }

    // Validate remaining stubs against current on-chain criteria.
    // Both lookups below are unguarded `Map.apply`, which is safe only because the loop above has
    // just removed every key absent from `syncedTrees`. This runs on the mailbox, where a throw
    // restarts the actor and drops every queued stub including fraud proofs — so anything that
    // changes when or how keys leave `pendingRollupTxs` has to re-establish that guarantee.
    pendingRollupTxs.keys.toSeq.foreach { blockId =>
      val tree  = syncedTrees(blockId)
      val stubs = pendingRollupTxs(blockId)

      val validStubs = stubs.filter { stub =>
        val isValid = stub.validate(currentHeight, tree)
        if (!isValid)
          logger.warn(s"Removing stale [${stub.txType}] stub for rollup $blockId - criteria no longer met")
        isValid
      }

      if (validStubs.isEmpty) {
        logger.warn(s"All stubs removed for rollup $blockId - removing entry from pendingRollupTxs")
        pendingRollupTxs = pendingRollupTxs - blockId
      } else {
        pendingRollupTxs = pendingRollupTxs + (blockId -> validStubs)
      }
    }
  }

  /** Merges new stubs by rollup while suppressing duplicate transaction types. */
  private def addRollupStubs(newEntries: Map[String, RollupTxStub]): Unit = {
    newEntries.foreach { case (blockId, stub) =>
      pendingRollupTxs.get(blockId) match {
        case Some(existing) =>
          if (!existing.exists(_.txType == stub.txType)){
            pendingRollupTxs = pendingRollupTxs + (blockId -> (existing :+ stub))
          }else if(stub.txType == RollupTxType.NISPEvaluation){
            if(stub.fpInfo.isDefined) {
              if (!existing.exists(_.fpInfo.isDefined)) {
                pendingRollupTxs = pendingRollupTxs + (blockId -> (existing :+ stub))
              } else if (!existing.exists(_.fpInfo.exists(_._1 sameElements stub.fpInfo.get._1))){
                pendingRollupTxs = pendingRollupTxs + (blockId -> (existing :+ stub))
              }
            }
          }
        case None =>
          pendingRollupTxs = pendingRollupTxs + (blockId -> Seq(stub))
      }
    }
  }
}

object TransactionProcessor {
  /** Maximum number of non-evaluation stubs dispatched to SubmissionHandler per tick. */
  private final val TX_BATCH_SIZE: Int  = 100

  /** Maximum number of NISPEvaluation stubs dispatched to RollupEvaluator per tick. */
  private final val EVAL_SET_SIZE: Int  = 20

  /** Widened from `private` so the package's tests can drive a tick instead of waiting 3 minutes. */
  private[transactions] case object ProcessTransactions
  private case class  ProcessPublishedMap(syncMsg: SyncMessage, entries: Map[String, RollupTxStub],
                                          currentHeight: Int)
}
