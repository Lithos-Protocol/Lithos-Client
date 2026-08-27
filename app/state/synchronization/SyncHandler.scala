package state.synchronization

import akka.actor.Actor
import cache.{MDCache, RollupCache}
import configs.{NodeContext, SyncConfig}
import lfsm.states.MinerTree
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import play.api.cache.SyncCacheApi
import state.messages.RollupMessages._
import state.messages.MempoolMessages.{MempoolRollupState, MempoolSnapshot, MempoolUnavailable, ResetMempoolState}
import state.messages.StateFrameMessages.NewBlock
import state.messages.SyncMessages._
import state.messages.{BlockInfo, BlockMessage}
import state.persistence.StateSnapshotActor.SnapshotCandidate
import utils.Globals

import javax.inject.Inject
import scala.util.control.NonFatal

/**
 * Owns canonical synchronized state and its committed cursor.
 * Blocks are acknowledged only after reduction and mirror publication succeed.
 */
class SyncHandler @Inject()(config: Configuration,
                            protocol: SyncProtocolContext,
                            cacheApi: SyncCacheApi,
                            @javax.inject.Named("state-snapshot") snapshotActor: akka.actor.ActorRef) extends Actor {

  private val logger: Logger = LoggerFactory.getLogger("SyncHandler")
  private val syncConfig = new SyncConfig(config)
  private val repository = SyncStateRepository(cacheApi, Globals.mdDB)

  private var committed = Option.empty[CommittedSyncState]
  // Retained states support exact rollback; the longer cursor window locates ancestors after restart.
  private var retained = Vector.empty[CommittedSyncState]
  private var knownCursors = Vector.empty[SyncCursor]
  private var status: SyncStatus = Starting
  private var canonicalReadyRequested = false
  private var locallyDisabledRollups = Set.empty[String]
  private var mempool = MempoolSnapshot(0L, Map.empty, Map.empty, Set.empty)
  private var projections = Map.empty[String, (Long, Long, MempoolRollupState)]
  private var suppressedProjections = Map.empty[String, Long]
  private var mempoolFailure: Option[String] =
    Some("Mempool view has not completed its initial refresh")
  private var initialReadySnapshotRequested = false

  override def preStart(): Unit = publishStatus(Starting)

  override def receive: Receive = {
    case BeginCatchUp(targetHeight) =>
      canonicalReadyRequested = false
      status = committed.map(s => CatchingUp(s.cursor, targetHeight)).getOrElse(Starting)
      publishStatus(status)

    // Canonical blocks enter only through StateFrame's commit request.
    case ApplyBlock(block) => applyAndReply(block)

    // Mempool failure removes projections but does not make confirmed state unavailable.
    case MarkReady =>
      canonicalReadyRequested = true
      committed match {
        case Some(state) => publishReady(state)
        case None =>
          status = SyncFailed(None, "Cannot become ready before a block has committed")
          publishStatus(status)
          logger.error(status.asInstanceOf[SyncFailed].reason)
      }
      sender() ! status

    case MarkStale(reason) =>
      canonicalReadyRequested = false
      status = committed.map(s => Stale(s.cursor, reason)).getOrElse(SyncFailed(None, reason))
      publishStatus(status)

    case GetSyncStatus => sender() ! status
    case GetCommittedState => committed match {
      case Some(state) => sender() ! CommittedState(state)
      case None => sender() ! SyncUnavailable(status)
    }
    // Return the cursor window used for ancestor discovery, not only exactly retained states.
    case GetRetainedCursors => sender() ! RetainedCursors(knownCursors)

    case ResetSyncState =>
      reset() match {
        case Right(_) => sender() ! SyncStateReset
        case Left(reason) => sender() ! BlockRejected(committed.map(_.cursor), "", reason)
      }

    case RestoreCommittedState(state, cursors) =>
      restore(state, cursors) match {
        case Right(_) => sender() ! BlockCommitted(state.cursor, state.version, 0, 0)
        case Left(reason) => sender() ! BlockRejected(committed.map(_.cursor), state.cursor.blockId, reason)
      }

    case RollbackTo(blockId) => rollback(blockId)

    case snapshot: MempoolSnapshot =>
      safelyUpdateMempool {
        committed.toSeq.flatMap(_.rollups.keys).foreach(repository.removeMempoolState)
        mempool = snapshot
        projections = Map.empty
        suppressedProjections = Map.empty
        if (mempoolFailure.nonEmpty)
          logger.info(s"Mempool view recovered at revision ${snapshot.revision} with " +
            s"${snapshot.chains.size} chain(s)")
        mempoolFailure = None
      }

    // Drop projections so consumers fall back to confirmed state.
    case MempoolUnavailable(reason) =>
      safelyUpdateMempool {
        committed.toSeq.flatMap(_.rollups.keys).foreach(repository.removeMempoolState)
        projections = Map.empty
        mempool = MempoolSnapshot(mempool.revision, Map.empty, Map.empty, Set.empty)
        if (mempoolFailure.isEmpty)
          logger.warn(s"Mempool projections unavailable, chaining from confirmed state instead: $reason")
        mempoolFailure = Some(reason)
      }

    case ResetMempoolState(blockId) =>
      safelyUpdateMempool {
        repository.removeMempoolState(blockId)
        projections -= blockId
        suppressedProjections += blockId -> mempool.revision
      }

    case GetSynced =>
      val requester = sender()
      try {
        committed match {
          case Some(state) if status.isInstanceOf[Ready] => requester ! FullSync(activeRollups(state))
          case _ => requester ! SyncUnavailable(status)
        }
      } catch {
        case NonFatal(ex) => failRequest(requester, "Could not build synchronized rollup view", ex)
      }

    case GetCurrentRollup(blockId) =>
      val requester = sender()
      try {
        committed.flatMap(_.rollups.get(blockId)).filter(_ => !locallyDisabledRollups.contains(blockId)) match {
          case Some(tree) if status.isInstanceOf[Ready] =>
            if (mempool.blockedRoots.contains(tree.utxoId)) requester ! NoRollupFound()
            else requester ! CurrentRollup(tree.utxoId, tree, projectedRollup(committed.get, blockId, tree))
          case _ => requester ! NoRollupFound()
        }
      } catch {
        case NonFatal(ex) =>
          markRuntimeFailure("Could not build the current rollup view", ex)
          requester ! NoRollupFound()
      }

    case UpdateEvaluation(blockId) =>
      committed.flatMap(_.rollups.get(blockId)).foreach { tree =>
        try {
          val updated = tree.copy(evaluated = true)
          repository.setRollup(updated)
          committed = committed.map(s => s.copy(rollups = s.rollups + (blockId -> updated)))
        } catch {
          case NonFatal(ex) => markRuntimeFailure(s"Could not publish evaluation state for $blockId", ex)
        }
      }

    case RemoveRollup(blockId, reason) =>
      if (committed.exists(_.rollups.contains(blockId))) {
        try {
          repository.removeMempoolState(blockId)
          locallyDisabledRollups += blockId
          logger.warn(s"Disabled local use of rollup $blockId: $reason")
        } catch {
          case NonFatal(ex) => markRuntimeFailure(s"Could not disable local rollup $blockId", ex)
        }
      }
  }

  private def applyAndReply(block: BlockInfo): Unit = {
    val requester = sender()
    committed match {
      case Some(state) if state.cursor.blockId == block.id =>
        requester ! BlockCommitted(state.cursor, state.version, 0, 0)
      case _ =>
        val base = committed.getOrElse(seedFor(block))
        val reduced = try BlockReducer.applyBlock(base, block, protocol)
        catch {
          case NonFatal(ex) => Left(SyncApplyError.InternalFailure(block.id,
            Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName)))
        }
        reduced match {
          case Left(error) =>
            val reason = error.message
            status = error match {
              case _: SyncApplyError.ParentMismatch => Stale(base.cursor, reason)
              case _ => SyncFailed(committed.map(_.cursor), reason)
            }
            publishStatus(status)
            logger.error(s"Rejected block ${block.id} at height ${block.height}: $reason")
            requester ! BlockRejected(committed.map(_.cursor), block.id, reason)

          case Right(transition) =>
            publishTransition(committed, transition.state) match {
              case Left(reason) =>
                status = SyncFailed(committed.map(_.cursor), reason)
                publishStatus(status)
                logger.error(s"Failed to publish block ${block.id}: $reason")
                requester ! BlockRejected(committed.map(_.cursor), block.id, reason)
              case Right(_) =>
                committed = Some(transition.state)
                retained = (retained :+ transition.state).takeRight(syncConfig.reorgWindow + 1)
                knownCursors = (knownCursors :+ transition.state.cursor).takeRight(syncConfig.reorgWindow + 1)
                projections = Map.empty
                suppressedProjections = Map.empty
                locallyDisabledRollups = locallyDisabledRollups.intersect(transition.state.rollups.keySet)
                reportFaults(block, transition)
                reportProgress(block, transition)
                status = status match {
                  case CatchingUp(_, target) => CatchingUp(transition.state.cursor, target)
                  case Ready(_) => Ready(transition.state.cursor)
                  case Reorganizing(_, target) => Reorganizing(transition.state.cursor, target)
                  case _ => CatchingUp(transition.state.cursor, transition.state.cursor.height)
                }
                publishStatus(status)
                snapshotActor ! SnapshotCandidate(transition.state, knownCursors)
                requester ! BlockCommitted(transition.state.cursor, transition.state.version,
                  transition.relevantEvents, transition.stagedDictionaryCopies)
            }
        }
    }
  }

  /** Logs isolated rollup and dictionary faults that do not stop the canonical cursor. */
  private def reportFaults(block: BlockInfo, transition: BlockTransition): Unit = {
    transition.quarantined.foreach { case (rollupId, reason) =>
      logger.error(s"Quarantined rollup $rollupId at height ${block.height}: $reason. " +
        "The block committed and synchronization continues without it.")
    }
    transition.minerDictionaryFault.foreach { reason =>
      logger.error(s"Miner Dictionary stopped tracking at height ${block.height}: $reason. " +
        "State reverted to the last known-good entry; commitments and fraud proofs needing the " +
        "dictionary are unavailable until it is bootstrapped again.")
    }
    // Missing input evidence prevents rollup tracking, so report it as a synchronization error.
    transition.unauthenticatedGenesis.foreach { case (txId, reason) =>
      logger.error(s"Could not authenticate a genesis at height ${block.height}: transaction $txId " +
        s"creates a holding output but $reason. No rollup is tracked for it.")
    }
    transition.unrecognizedGenesis.foreach { txId =>
      logger.error(s"Transaction $txId at height ${block.height} spends a collateral box into a " +
        "genesis-shaped output under a holding script this build does not compile. Its rollup is " +
        "invisible to this client. The deployed rollup chain has moved ahead of this release.")
    }
  }

  /** Logs state-changing blocks and periodic progress for empty catch-up blocks. */
  private def reportProgress(block: BlockInfo, transition: BlockTransition): Unit = {
    val cursor = transition.state.cursor
    if (transition.relevantEvents > 0)
      logger.info(s"Applied ${transition.relevantEvents} transform(s) at height ${cursor.height} " +
        s"(${cursor.blockId}); ${transition.state.rollups.size} rollup(s) tracked, " +
        s"${transition.stagedDictionaryCopies} dictionary copy/copies staged")
    else if (cursor.height % SyncHandler.ProgressInterval == 0)
      logger.info(s"Synchronized to height ${cursor.height} (${transition.state.rollups.size} rollup(s) " +
        s"tracked, ${transition.state.minerTree.numMiners} miner(s) in the dictionary)")
  }

  private def rollback(blockId: String): Unit = {
    retained.indexWhere(_.cursor.blockId == blockId) match {
      case -1 => sender() ! RollbackRejected(
        s"Block $blockId is outside the retained ${syncConfig.reorgWindow}-block rollback window")
      case index =>
        val target = retained(index)
        status = committed.map(s => Reorganizing(s.cursor, target.cursor.height)).getOrElse(Starting)
        publishStatus(status)
        publishTransition(committed, target) match {
          case Left(reason) =>
            status = SyncFailed(committed.map(_.cursor), reason)
            publishStatus(status)
            sender() ! RollbackRejected(reason)
          case Right(_) =>
            committed = Some(target)
            retained = retained.take(index + 1)
            knownCursors = knownCursors.filterNot(_.height > target.cursor.height)
            projections = Map.empty
            suppressedProjections = Map.empty
            locallyDisabledRollups = locallyDisabledRollups.intersect(target.rollups.keySet)
            status = Reorganizing(target.cursor, target.cursor.height)
            publishStatus(status)
            sender() ! RollbackCompleted(target.cursor)
        }
    }
  }

  private def restore(state: CommittedSyncState,
                      cursors: Vector[SyncCursor]): Either[String, Unit] = {
    publishTransition(committed, state).map { _ =>
      committed = Some(state)
      // Only the restored state supports exact rollback; older cursors still locate forks.
      retained = Vector(state)
      knownCursors =
        (cursors.filter(_.height < state.cursor.height) :+ state.cursor).takeRight(syncConfig.reorgWindow + 1)
      projections = Map.empty
      suppressedProjections = Map.empty
      locallyDisabledRollups = Set.empty
      canonicalReadyRequested = false
      initialReadySnapshotRequested = false
      status = CatchingUp(state.cursor, state.cursor.height)
      publishStatus(status)
    }
  }

  private def reset(): Either[String, Unit] =
    repository.clear(committed).map { _ =>
      committed = None
      retained = Vector.empty
      knownCursors = Vector.empty
      projections = Map.empty
      suppressedProjections = Map.empty
      locallyDisabledRollups = Set.empty
      canonicalReadyRequested = false
      initialReadySnapshotRequested = false
      status = Starting
      publishStatus(status)
    }

  private def publishTransition(previous: Option[CommittedSyncState],
                                next: CommittedSyncState): Either[String, Unit] =
    repository.publish(previous, next)

  /** Returns actionable rollups and publishes their mempool projections to the shared cache. */
  private def activeRollups(state: CommittedSyncState): Seq[(String, lfsm.states.NISPTree)] = {
    val (unavailable, available) = state.routedRollups.partition { case (utxoId, tree) =>
      locallyDisabledRollups.contains(tree.blockId) || mempool.blockedRoots.contains(utxoId)
    }
    unavailable.foreach { case (_, tree) => repository.removeMempoolState(tree.blockId) }
    available.foreach { case (_, tree) => projectedRollup(state, tree.blockId, tree) }
    available
  }

  private def projectedRollup(state: CommittedSyncState,
                              blockId: String,
                              tree: lfsm.states.NISPTree): Option[MempoolRollupState] = {
    if (suppressedProjections.get(blockId).contains(mempool.revision)) None
    else mempool.chains.get(tree.utxoId) match {
      case None =>
        projections -= blockId
        repository.removeMempoolState(blockId)
        None
      case Some(chain) =>
        projections.get(blockId) match {
          case Some((version, revision, projection))
            if version == state.version && revision == mempool.revision => Some(projection)
          case _ =>
            mempool.endInputs.get(tree.utxoId).map { endInput =>
              val synthetic = BlockInfo(s"mempool-${mempool.revision}-$blockId", state.cursor.height + 1,
                chain.transforms.map(_.tx), state.cursor.blockId)
              val projection = BlockReducer.applyBlock(state, synthetic, protocol) match {
                case Right(result) => result.state.rollups.get(blockId) match {
                  case Some(next) => MempoolRollupState(endInput, next)
                  case None => MempoolRollupState(endInput, tree, toBeRemoved = true)
                }
                case Left(error) =>
                  logger.warn(s"Rejected mempool projection for rollup $blockId: ${error.message}")
                  MempoolRollupState(endInput, tree, toBeRemoved = true)
              }
              projections += blockId -> ((state.version, mempool.revision, projection))
              repository.setMempoolState(blockId, projection)
              projection
            }
        }
    }
  }

  private def seedFor(block: BlockInfo): CommittedSyncState = {
    val tree = MinerTree.initialState.copy(syncHeight = block.height - 1, savedHeight = block.height - 1)
    CommittedSyncState(SyncCursor(block.height - 1, block.parentId, ""), 0L,
      Map.empty, Map.empty, tree, None)
  }

  private def safelyUpdateMempool(update: => Unit): Unit =
    try update
    catch {
      case NonFatal(ex) => markRuntimeFailure("Could not publish the mempool revision", ex)
    }

  private def failRequest(requester: akka.actor.ActorRef, message: String, ex: Throwable): Unit = {
    markRuntimeFailure(message, ex)
    requester ! SyncUnavailable(status)
  }

  private def markRuntimeFailure(message: String, ex: Throwable): Unit = {
    val reason = s"$message: ${Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName)}"
    status = committed.map(state => Stale(state.cursor, reason)).getOrElse(SyncFailed(None, reason))
    publishStatus(status)
    logger.error(reason, ex)
  }

  private def publishReady(state: CommittedSyncState): Unit = {
    val newlyReady = status != Ready(state.cursor)
    status = Ready(state.cursor)
    publishStatus(status)
    if (newlyReady && !initialReadySnapshotRequested) {
      initialReadySnapshotRequested = true
      if (state.cursor.height % syncConfig.snapshotInterval != 0)
        snapshotActor ! SnapshotCandidate(state, knownCursors, force = true)
    }
  }

  private def publishStatus(next: SyncStatus): Unit = Globals.setSyncStatus(next)
}

object SyncHandler {
  /** Blocks between "still moving" lines during catch-up, when no block changed protocol state. */
  private final val ProgressInterval = 500
}
