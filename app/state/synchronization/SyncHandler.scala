package state.synchronization

import akka.actor.Actor
import configs.SyncConfig
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import state.messages.RollupMessages._
import state.messages.MempoolMessages.{MempoolRollupState, MempoolSnapshot, MempoolUnavailable, ResetMempoolState}
import state.messages.SyncMessages._
import state.messages.{BlockInfo, Capability, SyncView}
import state.persistence.StateSnapshotActor.SnapshotCandidate
import state.persistence.{PersistedSyncState, StateSnapshotCodec, StateSnapshotIdentity}
import utils.Globals

import javax.inject.Inject
import scala.util.control.NonFatal

/**
 * Owns canonical synchronized state and its committed cursor.
 */
class SyncHandler @Inject()(config: Configuration,
                            protocol: SyncProtocolContext,
                            @javax.inject.Named("state-snapshot") snapshotActor: akka.actor.ActorRef) extends Actor {

  private val logger: Logger = LoggerFactory.getLogger("SyncHandler")
  private val syncConfig = new SyncConfig(config)
  private val repository = SyncStateRepository(Globals.mdDB)
  private val snapshotIdentity = StateSnapshotIdentity(protocol)
  private val repairAttempts = syncConfig.quarantineRepairAttempts

  private var committed = Option.empty[CommittedSyncState]
  // Retained states support exact rollback; the longer cursor window locates ancestors after restart.
  private var retained = Vector.empty[CommittedSyncState]
  private var knownCursors = Vector.empty[SyncCursor]
  private var status: SyncStatus = Starting
  private var canonicalReadyRequested = false
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
        mempool = snapshot
        projections = Map.empty
        suppressedProjections = Map.empty
        if (mempoolFailure.nonEmpty)
          logger.info(s"Mempool view recovered at revision ${snapshot.revision} with " +
            s"${snapshot.chains.size} chain(s)")
        mempoolFailure = None
      }
      publishStatus(status)

    // Drop projections so consumers fall back to confirmed state.
    case MempoolUnavailable(reason) =>
      safelyUpdateMempool {
        projections = Map.empty
        mempool = MempoolSnapshot(mempool.revision, Map.empty, Map.empty, Set.empty)
        if (mempoolFailure.isEmpty)
          logger.warn(s"Mempool projections unavailable, chaining from confirmed state instead: $reason")
        mempoolFailure = Some(reason)
      }
      publishStatus(status)

    case ResetMempoolState(blockId) =>
      safelyUpdateMempool {
        projections -= blockId
        suppressedProjections += blockId -> mempool.revision
      }

    case GetSynced =>
      val requester = sender()
      try {
        committed match {
          case Some(state) if status.isInstanceOf[Ready] =>
            val usable = usableRollups(state)
            // Projections travel with the rollups they belong to, so a builder cannot pair a tree
            // with a projection from a different committed version.
            val projected = usable.flatMap { case (_, tree) =>
              projectedRollup(state, tree.blockId, tree).map(tree.blockId -> _)
            }.toMap
            requester ! FullSync(usable, projected)
          case _ => requester ! SyncUnavailable(status)
        }
      } catch {
        case NonFatal(ex) => failRequest(requester, "Could not build synchronized rollup view", ex)
      }

    case GetCurrentRollup(blockId) =>
      val requester = sender()
      try {
        committed.flatMap(_.rollups.get(blockId)) match {
          // A blocked root yields no projection, so the builder chains from confirmed state and
          // races rather than conceding the rollup to whoever spent it first.
          case Some(tree) if status.isInstanceOf[Ready] =>
            requester ! CurrentRollup(tree.utxoId, tree, projectedRollup(committed.get, blockId, tree))
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
          committed = committed.map(s => s.copy(rollups = s.rollups + (blockId -> updated)))
          publishStatus(status)
        } catch {
          case NonFatal(ex) => markRuntimeFailure(s"Could not publish evaluation state for $blockId", ex)
        }
      }

    // Stops tracking a rollup this client can never submit to. The NISP window closes at the rollup's
    // own creation height, so the miss is permanent and its tree is dead weight until payout.
    case RemoveRollup(blockId, reason) => try {
      committed.filter(_.rollups.contains(blockId)).foreach { state =>
        val tree = state.rollups(blockId)
        val evicted = state.copy(rollups = state.rollups - blockId, routes = state.routes - tree.utxoId)
        publishTransition(committed, evicted) match {
          case Right(_) =>
            committed = Some(evicted)
            projections -= blockId
            suppressedProjections -= blockId
            logger.warn(s"Stopped tracking rollup $blockId: $reason")
            publishStatus(status)
          case Left(failed) => markRuntimeFailure(s"Could not stop tracking rollup $blockId", failed)
        }
      }
    } catch {
      case NonFatal(ex) => markRuntimeFailure(s"Could not stop tracking rollup $blockId", ex)
    }

    case GetRepairableQuarantines =>
      sender() ! RepairableQuarantines(
        committed.map(_.repairableQuarantines(repairAttempts)).getOrElse(Seq.empty),
        committed.map(_.cursor.height).getOrElse(0))

    // A rebuild is only installable at the height it was built for
    case RepairRollup(rollupId, atHeight, outcome) =>
      committed.filter(_.cursor.height == atHeight).flatMap(_.quarantined.get(rollupId)) match {
        case None => ()
        case Some(fault) => applyRepair(fault, outcome)
      }

    // Replaces dictionary state rebuilt at the committed height and clears its fault. A commit that
    // landed while the rebuild ran makes the result one block stale, so the height must still match.
    case RepairMinerDictionary(atHeight, minerTree, dataBoxToken) =>
      val requester = sender()
      committed match {
        case Some(state) if state.cursor.height == atHeight =>
          val repaired = state.copy(minerTree = minerTree, dataBoxToken = dataBoxToken,
            minerDictionaryFault = None)
          publishTransition(committed, repaired) match {
            case Right(_) =>
              committed = Some(repaired)
              logger.info(s"Miner Dictionary restored at height $atHeight with " +
                s"${minerTree.numMiners} miner(s); registration and commitment fraud proofs are " +
                "available again")
              publishStatus(status)
              // Durable now rather than at the next interval, or a restart repeats the whole repair.
              offerSnapshot(repaired, force = true)
              requester ! BlockCommitted(repaired.cursor, repaired.version, 0, 0)
            case Left(failed) =>
              markRuntimeFailure("Could not publish the rebuilt Miner Dictionary",
                new IllegalStateException(failed))
              requester ! BlockRejected(committed.map(_.cursor), "", failed)
          }
        case Some(state) =>
          requester ! BlockRejected(Some(state.cursor), "",
            s"dictionary was rebuilt at height $atHeight but the cursor is now ${state.cursor.height}")
        case None =>
          requester ! BlockRejected(None, "", "no committed state to repair")
      }
  }

  private def markRuntimeFailure(message: String, reason: String): Unit =
    markRuntimeFailure(message, new IllegalStateException(reason))

  /** Installs a rebuilt rollup, or records the attempt so the fault stops being retried eventually. */
  private def applyRepair(fault: QuarantineFault, outcome: RollupRepairResult): Unit =
    committed.foreach { state =>
      val next = outcome match {
        case RollupRepairResult.Rebuilt(tree) =>
          logger.warn(s"Rollup ${fault.rollupId} is tracked again after ${fault.attempts + 1} " +
            s"rebuild attempt(s); this miner can act on it once more")
          state.copy(rollups = state.rollups + (fault.rollupId -> tree),
            routes = state.routes + (tree.utxoId -> fault.rollupId),
            quarantined = state.quarantined - fault.rollupId)
        case RollupRepairResult.Terminated =>
          logger.info(s"Quarantined rollup ${fault.rollupId} had already ended on chain; " +
            "dropping its fault")
          state.copy(quarantined = state.quarantined - fault.rollupId)
        case RollupRepairResult.Failed(reason, retryable) =>
          val attempted = fault.attempted.copy(reason = reason, retryable = retryable)
          if (!retryable || attempted.attempts >= repairAttempts)
            logger.error(s"Rollup ${fault.rollupId} stays quarantined after " +
              s"${attempted.attempts} attempt(s): $reason. It will not be rebuilt again, and this " +
              "miner earns nothing from it.")
          else
            logger.warn(s"Rebuild ${attempted.attempts} of ${fault.rollupId} failed: $reason")
          state.copy(quarantined = state.quarantined + (fault.rollupId -> attempted))
      }
      publishTransition(committed, next) match {
        case Right(_) =>
          committed = Some(next)
          publishStatus(status)
          // A rebuilt rollup that a restart would have to rebuild again is not worth the walk.
          if (outcome.isInstanceOf[RollupRepairResult.Rebuilt]) offerSnapshot(next, force = true)
        case Left(failed) => markRuntimeFailure(s"Could not publish the rebuilt rollup " +
          s"${fault.rollupId}", failed)
      }
    }

  private def applyAndReply(block: BlockInfo): Unit = {
    val requester = sender()
    committed match {
      case Some(state) if state.cursor.blockId == block.id =>
        requester ! BlockCommitted(state.cursor, state.version, 0, 0)

      // The producer seeds this owner before any block, so no committed state means the owner lost
      // its own.
      case None =>
        logger.error(s"Refused block ${block.id} at height ${block.height}: synchronization holds no " +
          "committed state. It must be seeded from a snapshot or a dictionary bootstrap first.")
        status = SyncFailed(None, "synchronization has no committed state")
        publishStatus(status)
        requester ! SyncUnseeded

      case Some(_) =>
        val base = committed.get
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
                knownCursors = (knownCursors :+ transition.state.cursor).takeRight(syncConfig.cursorWindow + 1)
                projections = Map.empty
                suppressedProjections = Map.empty
                reportFaults(block, transition)
                reportProgress(block, transition)
                status = status match {
                  case CatchingUp(_, target) => CatchingUp(transition.state.cursor, target)
                  case Ready(_) => Ready(transition.state.cursor)
                  case Reorganizing(_, target) => Reorganizing(transition.state.cursor, target)
                  case _ => CatchingUp(transition.state.cursor, transition.state.cursor.height)
                }
                publishStatus(status)
                offerSnapshot(transition.state, force = false)
                requester ! BlockCommitted(transition.state.cursor, transition.state.version,
                  transition.relevantEvents, transition.stagedDictionaryCopies)
            }
        }
    }
  }

  /** Logs isolated rollup and dictionary faults that do not stop the canonical cursor. */
  private def reportFaults(block: BlockInfo, transition: BlockTransition): Unit = {
    transition.quarantined.foreach { fault =>
      val ending =
        if (fault.retryable) s"Rebuilding it from the indexed chain, up to $repairAttempts time(s)."
        else "Re-reading it cannot give a different answer, so it will not be rebuilt."
      logger.error(s"Quarantined rollup ${fault.rollupId} at height ${block.height}: " +
        s"${fault.reason}. The block committed and synchronization continues without it. $ending")
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
    if (cursor.height % SyncHandler.ProgressInterval == 0) reportQuarantines(transition.state)
  }

  /** Periodic, not per block: a standing quarantine is a lasting loss worth restating. */
  private def reportQuarantines(state: CommittedSyncState): Unit =
    if (state.quarantined.nonEmpty) {
      val (retryable, terminal) = state.quarantined.values.partition(_.retryable)
      val pending = retryable.count(_.attempts < repairAttempts)
      logger.warn(s"${state.quarantined.size} rollup(s) are quarantined at height " +
        s"${state.cursor.height}: $pending awaiting a rebuild, ${retryable.size - pending} out of " +
        s"attempts, ${terminal.size} not rebuildable. This miner earns nothing from them.")
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
        (cursors.filter(_.height < state.cursor.height) :+ state.cursor).takeRight(syncConfig.cursorWindow + 1)
      projections = Map.empty
      suppressedProjections = Map.empty
      canonicalReadyRequested = false
      initialReadySnapshotRequested = false
      status = CatchingUp(state.cursor, state.cursor.height)
      publishStatus(status)
    }
  }

  private def reset(): Either[String, Unit] =
    repository.clear().map { _ =>
      committed = None
      retained = Vector.empty
      knownCursors = Vector.empty
      projections = Map.empty
      suppressedProjections = Map.empty
      canonicalReadyRequested = false
      initialReadySnapshotRequested = false
      status = Starting
      publishStatus(status)
    }

  private def publishTransition(previous: Option[CommittedSyncState],
                                next: CommittedSyncState): Either[String, Unit] =
    repository.publish(previous, next)

  /** Rollups this client may act on. Cheap: no projection is built, so a commit can publish it. */
  private def usableRollups(state: CommittedSyncState): Seq[(String, lfsm.states.NISPTree)] =
    state.routedRollups

  private def projectedRollup(state: CommittedSyncState,
                              blockId: String,
                              tree: lfsm.states.NISPTree): Option[MempoolRollupState] = {
    if (suppressedProjections.get(blockId).contains(mempool.revision)) None
    else mempool.chains.get(tree.utxoId) match {
      case None =>
        projections -= blockId
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
              projection
            }
        }
    }
  }

  /** Serializes here, on the thread that owns the dictionaries, and hands the writer bytes. */
  private def offerSnapshot(state: CommittedSyncState, force: Boolean): Unit = {
    val due = force || state.cursor.height % syncConfig.snapshotInterval == 0
    if (syncConfig.snapshotsEnabled && due)
      StateSnapshotCodec.encode(PersistedSyncState(state, knownCursors), snapshotIdentity,
        syncConfig.snapshotMaxEntryBytes) match {
        case Right(encoded) =>
          snapshotActor ! SnapshotCandidate(state.cursor, state.version, encoded)
        case Left(reason) =>
          logger.error(s"Could not encode a snapshot at height ${state.cursor.height}: $reason. " +
            "Synchronization continues; restart recovery will use an older generation.")
      }
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
      if (state.cursor.height % syncConfig.snapshotInterval != 0) offerSnapshot(state, force = true)
    }
  }

  /**
   * Publishes status and sync capabilities together.
   *
   * The three capabilities fail independently.
   */
  private def publishStatus(next: SyncStatus): Unit = {
    val canonical =
      if (next.isInstanceOf[Ready]) Capability.Available
      else Capability.Unavailable(SyncHandler.describe(next))
    val dictionary = committed.flatMap(_.minerDictionaryFault) match {
      case Some(fault) => Capability.Unavailable(fault)
      case None if canonical.available => Capability.Available
      case None => Capability.Unavailable(SyncHandler.describe(next))
    }
    val mempoolCapability =
      mempoolFailure.map(Capability.Unavailable).getOrElse(Capability.Available)

    Globals.setSyncView(SyncView(
      status = next,
      version = committed.map(_.version).getOrElse(0L),
      rollups = if (canonical.available) committed.map(usableRollups).getOrElse(Seq.empty) else Seq.empty,
      minerTree = committed.map(_.minerTree),
      dataBoxToken = committed.flatMap(_.dataBoxToken),
      canonical = canonical,
      minerDictionary = dictionary,
      mempool = mempoolCapability))
  }
}

object SyncHandler {
  /** Blocks between "still moving" lines during catch-up, when no block changed protocol state. */
  private final val ProgressInterval = 500

  /** Why a capability is unavailable, in words an operator can act on. */
  private def describe(status: SyncStatus): String = status match {
    case Starting => "synchronization has not committed a block yet"
    case CatchingUp(at, target) => s"catching up at height ${at.height} of $target"
    case Reorganizing(from, target) => s"reorganizing from height ${from.height} toward $target"
    case Stale(at, reason) => s"stale at height ${at.height}: $reason"
    case SyncFailed(_, reason) => s"synchronization failed: $reason"
    case Ready(_) => "ready"
  }
}
