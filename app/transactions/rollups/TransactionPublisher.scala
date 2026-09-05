package transactions.rollups

import akka.actor.{Actor, ActorRef, Cancellable}
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import configs.{NodeContext, StateConfig, StratumConfig}
import lfsm.LFSMHelpers
import lfsm.LFSMPhase.{EVAL, HOLDING, PAYOUT}
import lfsm.states.RollupMetadata
import mutations.NodeWallet
import org.ergoplatform.appkit.ErgoClient
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import play.api.cache.SyncCacheApi
import play.api.libs.concurrent.InjectedActorSupport
import state.messages.MempoolMessages.MempoolRollupMetadata
import state.messages.SyncMessages._
import transactions.rollups.TransactionMessages.RollupTxType._
import transactions.rollups.TransactionMessages.{PublishRollupTxs, PublishedRollupMap, RollupTxStub, RollupTxType}
import transactions.rollups.TransactionPublisher._

import javax.inject.{Inject, Named}
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

/**
 * Actor that periodically evaluates all synced rollups and publishes a map
 * of pending on-chain transactions as a [[TransactionMessages.PublishRollupTxs]] message.
 *
 * Every 2 minutes this actor asks SyncHandler for the current rollup sync
 * state.  For each synced Rollup it applies the same phase + criteria checks
 * used by LFSMTransformer.onSync to decide which of the five transaction types
 * is due.  The resulting map is built fresh on every tick via buildRollupMap
 * so it always reflects the current chain state.
 *
 * Disabled entirely when `state.disableTransforms = true`.
 */
class TransactionPublisher @Inject()(config: Configuration, nodeContext: NodeContext,
                             cacheApi: SyncCacheApi,
                             @Named("sync-handler") syncHandler: ActorRef,
                             @Named("transaction-processor") transactionProcessor: ActorRef)
  extends Actor with InjectedActorSupport {

  implicit val timeout: Timeout          = Timeout(30.seconds)
  implicit val ec: ExecutionContext      = context.dispatcher

  private val logger: Logger             = LoggerFactory.getLogger("TransactionPublisher")

  val nodeConfig: NodeContext             = nodeContext
  val client: ErgoClient                 = nodeConfig.getClient
  val prover: NodeWallet                 = nodeConfig.getNodeWallet
  val stateConfig: StateConfig           = new StateConfig(config)
  val stratumConfig: StratumConfig       = new StratumConfig(config)

  private var ticker: Option[Cancellable] = None

  // ─── lifecycle ────────────────────────────────────────────────────────────

  override def preStart(): Unit = {
    if (!stateConfig.disableTransforms.getOrElse(false)) {
      logger.info("TransactionPublisher starting - evaluating rollup transactions every 2 minutes")
      ticker = Some(
        context.system.scheduler.scheduleWithFixedDelay(25.seconds, 25.seconds, self, Tick)(context.dispatcher)
      )
    } else {
      logger.info("TransactionPublisher disabled via disableTransforms config")
    }
  }

  override def postStop(): Unit = ticker.foreach(_.cancel())

  // ─── receive ──────────────────────────────────────────────────────────────

  override def receive: Receive = {

    case Tick =>
      (syncHandler ? GetSynced).mapTo[SyncMessage].onComplete {
        case Failure(ex) =>
          logger.error(s"TransactionPublisher failed to query sync state: ${ex.getMessage}", ex)

        case Success(FullSync(rollups, projections)) =>
          logger.info(s"Publishing rollup transactions for FullSync with ${rollups.size} rollup(s)")
          Future(buildRollupMap(rollups, projections)).map(PublishRollupTxs.apply).pipeTo(self)

        case Success(SyncUnavailable(status)) =>
          logger.warn(s"Skipping transaction publication while synchronization is $status")
          self ! PublishRollupTxs(Success(Map.empty))
      }

    // Result of buildRollupMap piped back from the Future
    case PublishRollupTxs(result) =>
      result match {
        case Failure(ex) =>
          logger.error(s"Failed to build rollup tx map: ${ex.getMessage}", ex)

        case Success(entries) =>
          if (entries.nonEmpty) {
            logger.debug(s"Publishing ${entries.size} transaction(s) to TransactionProcessor")
            val txGroups = entries.groupBy(_._2.txType)
            txGroups.foreach{
              g =>
                logger.info(s"Got ${g._2.size} ${g._1} transactions")
            }
          } else {
            logger.debug("Publishing empty map to TransactionProcessor")
          }
          transactionProcessor ! PublishedRollupMap(entries)
      }
  }

  // ─── map building ───────────────────────────────────────────────────────

  /**
   * Evaluates every synced Rollup against the five transaction-type criteria
   * and returns a map of rollupBlockId → RollupTxStub for all that are ready.
   *
   * Replicates the mempool-state merging and the phase + height-based
   * eligibility checks from LFSMTransformer.onSync and its five helper
   * methods.  Must be called off the actor thread (e.g. inside a Future)
   * because it calls client.execute.
   */
  private def buildRollupMap(rollups: Seq[(String, RollupMetadata)],
                             projections: Map[String, MempoolRollupMetadata]): Try[Map[String, RollupTxStub]] =
    Try(client.execute { ctx =>
      // Projections arrive with the trees they belong to, so a tree can never be paired with a
      // projection built against a different committed version.
      val memStates: Map[String, MempoolRollupMetadata] = rollups.flatMap { n =>
        projections.get(n._2.blockId).map(n._1 -> _)
      }.toMap

      val statesToRemove = memStates.filter(_._2.toBeRemoved)

      // Trees with mempool states substituted in where available
      val updatedTrees: Seq[(String, RollupMetadata)] = rollups.map { t =>
        if (memStates.contains(t._1)) t._1 -> memStates(t._1).metadata else t
      }

      // Trees not flagged for removal — used for all types except NISPEvaluation
      val transformableTrees: Seq[(String, RollupMetadata)] =
        updatedTrees.filterNot(u => statesToRemove.contains(u._1))

      val currentHeight = ctx.getHeight
      val entries       = mutable.Map.empty[String, RollupTxStub]

      // ── HOLDING phase ─────────────────────────────────────────────────────

      val holdingTrees = transformableTrees.filter(_._2.phase == HOLDING)

      holdingTrees.foreach { case (_, tree) =>
        val age = currentHeight - tree.currentPeriod.get

        if (age >= LFSMHelpers.HOLDING_PERIOD) {
          // Holding period elapsed → ready to transform to EVAL
          entries(tree.blockId) = RollupTxStub(tree.blockId, tree.currentPeriod, HoldingTransform)

        } else if (!tree.hasMiner) {
          // Still within holding period and no miner yet → submit a NISP
          entries(tree.blockId) = RollupTxStub(tree.blockId, tree.currentPeriod, NISPSubmission)
        }
      }

      // ── EVAL phase ────────────────────────────────────────────────────────

      // Eval transforms use mempool-adjusted trees
      val updatedEvalTrees = transformableTrees.filter(_._2.phase == EVAL)

      updatedEvalTrees.foreach { case (_, tree) =>
        val age = currentHeight - tree.currentPeriod.get

        if (age >= LFSMHelpers.EVAL_PERIOD && tree.evaluated) {
          // Eval period elapsed → ready to transform to PAYOUT
          entries(tree.blockId) = RollupTxStub(tree.blockId, tree.currentPeriod, EvalTransform)
        }
      }

      // Evaluation (fraud-proof checks) uses the raw trees — onSync comment:
      // "We do not use mempool states for evaluation"
      val rawEvalTrees = rollups.filter(_._2.phase == EVAL)

      rawEvalTrees.foreach { case (_, tree) =>
        val age = currentHeight - tree.currentPeriod.get

        if (age < LFSMHelpers.EVAL_PERIOD && !tree.evaluated) {
          // Still within eval period and not yet evaluated → run fraud-proof checks
          // Only add if an EvalTransform hasn't already claimed this blockId
          entries.getOrElseUpdate(tree.blockId, RollupTxStub(tree.blockId, tree.currentPeriod, NISPEvaluation))
        }
      }

      // ── PAYOUT phase ──────────────────────────────────────────────────────

      transformableTrees.filter(_._2.phase == PAYOUT).foreach { case (_, tree) =>
        entries(tree.blockId) = RollupTxStub(tree.blockId, tree.currentPeriod, Payout)
      }

      entries.toMap
    })
}

object TransactionPublisher {
  private case object Tick
}
