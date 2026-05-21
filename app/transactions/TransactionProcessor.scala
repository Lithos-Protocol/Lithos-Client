package transactions

import akka.actor.{Actor, ActorRef, Cancellable}
import akka.pattern.ask
import akka.util.Timeout
import cache.RollupCache
import configs.{NodeConfig, StateConfig}
import lfsm.states.NISPTree
import org.ergoplatform.appkit.ErgoClient
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import play.api.cache.SyncCacheApi
import play.api.libs.concurrent.InjectedActorSupport
import state.messages.MempoolMessages.MempoolRollupState
import state.messages.SyncMessages._
import transactions.TransactionMessages.RollupTxType._
import transactions.TransactionMessages.{EvaluationSet, PublishedRollupMap, RollupBatch, RollupTxStub}
import transactions.TransactionProcessor._
import utils.Globals

import javax.inject.{Inject, Named}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success}

/**
 * Actor responsible for maintaining pendingRollupTxs — a durable map of
 * rollupBlockId -> Seq[RollupTxStub] representing all on-chain transactions
 * that are still waiting to be submitted.
 *
 * On every PublishedRollupMap received from TransactionPublisher it:
 *   1. Asks SyncHandler for the current sync state.
 *   2. Calls cleanupRollupTxs to evict stale entries.
 *   3. Calls addRollupStubs to merge new stubs into the map.
 *
 * Every 3 minutes a ProcessTransactions tick drives the actual submission
 * logic (to be implemented).
 *
 * Disabled entirely when `state.disableTransforms = true`.
 */
class TransactionProcessor @Inject()(config: Configuration,
                                     cacheApi: SyncCacheApi,
                                     @Named("sync-handler")        syncHandler:       ActorRef,
                                     @Named("submission-handler")  submissionHandler: ActorRef,
                                     @Named("rollup-evaluator")    rollupEvaluator:   ActorRef)
  extends Actor with InjectedActorSupport {

  implicit val timeout: Timeout     = Timeout(30.seconds)
  implicit val ec: ExecutionContext = context.dispatcher

  private val logger: Logger        = LoggerFactory.getLogger("TransactionProcessor")

  private val nodeConfig: NodeConfig        = Globals.getNodeConfig
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

      // Separate by type; timed types sorted oldest-first by currentPeriod
      val nispSubmissions = headStubs.filter(_.txType == NISPSubmission).sortBy(_.currentPeriod)
      val transforms      = headStubs.filter(s => s.txType == HoldingTransform || s.txType == EvalTransform).sortBy(_.currentPeriod)
      val payouts         = headStubs.filter(_.txType == Payout)
      val evaluations     = headStubs.filter(_.txType == NISPEvaluation).sortBy(_.currentPeriod)

      // Priority order for the batch: NISPSubmissions -> Transforms -> Payouts
      val batch   = RollupBatch((nispSubmissions ++ transforms ++ payouts).take(TX_BATCH_SIZE))

      // Evaluation tx stubs are sent to RollupEvaluator
      val evalSet = EvaluationSet(evaluations.take(EVAL_SET_SIZE))

      if (batch.stubs.nonEmpty)   submissionHandler ! batch
      if (evalSet.stubs.nonEmpty) rollupEvaluator   ! evalSet

      // Remove dispatched stubs from pendingRollupTxs
      (batch.stubs ++ evalSet.stubs).foreach { stub =>
        pendingRollupTxs.get(stub.rollupBlockId).foreach { stubs =>
          val remaining = stubs.filterNot(_.txType == stub.txType)
          if (remaining.isEmpty)
            pendingRollupTxs = pendingRollupTxs - stub.rollupBlockId
          else
            pendingRollupTxs = pendingRollupTxs + (stub.rollupBlockId -> remaining)
        }
      }

    case PublishedRollupMap(newEntries) =>
      (syncHandler ? GetSynced).mapTo[SyncMessage].onComplete {
        case Failure(ex) =>
          logger.error(s"TransactionProcessor failed to query sync state: ${ex.getMessage}", ex)
        case Success(syncMsg) =>
          self ! ProcessPublishedMap(syncMsg, newEntries)
      }

    case ProcessPublishedMap(syncMsg, newEntries) =>
      cleanupRollupTxs(syncMsg)
      addRollupStubs(newEntries)
  }

  // ─── pending tx management ────────────────────────────────────────────────

  /**
   * Removes stale entries from pendingRollupTxs using the latest sync state.
   *
   * First, any rollup whose blockId is no longer present in the synced trees
   * is removed entirely. Then, for each remaining rollup, every RollupTxStub
   * whose criteria are no longer met is removed from its Seq. If the Seq
   * becomes empty after removal the key is dropped from the map.
   */
  private def cleanupRollupTxs(syncMsg: SyncMessage): Unit = {
    val rawTrees: Seq[(String, NISPTree)] = syncMsg match {
      case FullSync(trees)       => trees
      case PartialSync(trees, _) => trees
      case NoRollups()           => Seq.empty
    }

    // ── mempool-state merging (mirrors TransactionPublisher.buildRollupMap) ──
    val rollupCache = new RollupCache(cacheApi)

    val memStates: Map[String, MempoolRollupState] = rawTrees.flatMap { n =>
      rollupCache.getMempoolState(n._2.blockId).map(n._1 -> _)
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

    // Validate remaining stubs against current on-chain criteria
    val currentHeight: Int = client.execute(ctx => ctx.getHeight)

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

  /**
   * Merges new RollupTxStubs from a PublishedRollupMap into pendingRollupTxs.
   *
   * For each incoming stub:
   *   - If the blockId already exists and the Seq already contains a stub with
   *     the same RollupTxType, the new stub is skipped (no duplicates).
   *   - If the blockId exists but the type is new, the stub is appended.
   *   - If the blockId does not exist, a new Seq is created for it.
   */
  private def addRollupStubs(newEntries: Map[String, RollupTxStub]): Unit = {
    newEntries.foreach { case (blockId, stub) =>
      pendingRollupTxs.get(blockId) match {
        case Some(existing) =>
          if (!existing.exists(_.txType == stub.txType)) {
            pendingRollupTxs = pendingRollupTxs + (blockId -> (existing :+ stub))
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

  private case object ProcessTransactions
  private case class  ProcessPublishedMap(syncMsg: SyncMessage, entries: Map[String, RollupTxStub])
}
