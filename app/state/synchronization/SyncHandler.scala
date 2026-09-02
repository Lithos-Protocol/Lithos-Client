package state.synchronization

import akka.actor.{Actor, ActorRef, Cancellable}
import configs.SyncConfig
import lfsm.states.{AuthenticatedDictionaryView, DeferredDictionary, PlasmaDictionary, Rollup}
import org.bouncycastle.util.encoders.Hex
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import state.messages.RollupMessages._
import state.messages.MempoolMessages.{MempoolChain, MempoolRollupMetadata, MempoolRollupState, MempoolSnapshot, MempoolUnavailable, ResetMempoolState}
import state.messages.SyncMessages._
import state.messages.{BlockInfo, Capability, SyncView}
import state.persistence.StateSnapshotActor._
import state.persistence.{SnapshotCheckpoint, SnapshotDictionaryBase, SnapshotDictionarySource}
import utils.Globals

import javax.inject.Inject
import java.util.UUID
import scala.concurrent.duration._
import scala.util.control.NonFatal

/**
 * Owns canonical synchronized state and its committed cursor. SyncHandler
 * is the overall manager of all synchronizable entities and determines what
 * the correct publishable state is at a given moment. It also manages
 * rollbacks, mempool projections, quarantines of sync entities.
 */
class SyncHandler @Inject()(config: Configuration,
                            protocol: SyncProtocolContext,
                            @javax.inject.Named("state-snapshot") snapshotActor: akka.actor.ActorRef) extends Actor {

  private val logger: Logger = LoggerFactory.getLogger("SyncHandler")
  private val syncConfig = new SyncConfig(config)
  private val repository = SyncStateRepository(Globals.mdDB)
  private val repairAttempts = syncConfig.quarantineRepairAttempts
  private val quarantineRetentionBlocks = syncConfig.quarantineRetentionBlocks
  private val quarantineWarningBlocks = math.max(1, quarantineRetentionBlocks / 10)
  private val incarnation = UUID.randomUUID().toString

  private var committed = Option.empty[CommittedSyncState]
  // One full checkpoint plus small metadata/operation entries replaces a retained vector of full states.
  private var journalBase = Option.empty[CommittedSyncState]
  private var journal = Option.empty[TransformJournal]
  private var knownCursors = Vector.empty[SyncCursor]
  private var status: SyncStatus = Starting
  private var mempool = MempoolSnapshot(0L, Map.empty, Map.empty, Set.empty)
  private final case class ProjectionStamp(cursor: SyncCursor,
                                           dictionaryDigest: String,
                                           confirmedUtxo: String,
                                           mempoolRevision: Long,
                                           chainTransactions: Vector[String])
  private final case class CachedProjection(stamp: ProjectionStamp, state: MempoolRollupState)
  private var projections = Map.empty[String, CachedProjection]
  private var suppressedProjections = Map.empty[String, Long]
  private var mempoolFailure: Option[String] =
    Some("Mempool view has not completed its initial refresh")
  private var initialReadySnapshotRequested = false
  private var stateEpoch = 0L
  private var minerDictionaryEpoch = 0L
  private var quarantineEpoch = 0L
  private var sequence = 0L
  private var checkpointSequence = 0L
  private var checkpointInFlight = Option.empty[PendingCheckpoint]
  private var checkpointRequested = false
  private var checkpointRequestedForce = false
  private var checkpointRetry = Option.empty[Cancellable]
  private case object RetryCheckpoint

  private final case class MaterializationKey(dictionaryId: DictionaryId, digest: String)
  private final case class MaterializationFailure(key: Option[MaterializationKey], reason: String)
  private final case class PendingOperation(epoch: Long,
                                            remaining: Set[MaterializationKey],
                                            dictionaries: Map[MaterializationKey, AuthenticatedDictionaryView],
                                            complete: Map[MaterializationKey, AuthenticatedDictionaryView] => Unit,
                                            fail: MaterializationFailure => Unit,
                                            deadline: Cancellable)
  /**
   * Ends an operation the persistence actor never answered.
   *
   * Every accepted request there carries its own deadline, so this only fires when no reply can
   * come at all — a stopped actor, or a message that went to dead letters. Without it `operations`
   * and the two load maps grow by one entry per request for the life of the process, and the caller
   * waits forever on a barrier that can never complete.
   */
  private final case class MaterializationExpired(incarnation: String, operationId: Long)
  private final case class ActiveLoad(key: MaterializationKey,
                                      token: String,
                                      operations: Set[Long])
  private final case class PendingCheckpoint(token: String,
                                             boundary: CommittedSyncMetadata,
                                             cutEntries: Int,
                                             sources: Map[DictionaryId, SnapshotDictionarySource],
                                             forced: Boolean)
  private final case class DictionaryOverride(at: SyncCursor,
                                              dictionary: AuthenticatedDictionaryView)
  private final case class RepairPermit(cursor: SyncCursor,
                                        epoch: Long,
                                        minerEpoch: Long,
                                        quarantineEpoch: Long,
                                        rollups: Set[String],
                                        minerDictionary: Boolean)

  private var operations = Map.empty[Long, PendingOperation]
  private var loadsByKey = Map.empty[MaterializationKey, ActiveLoad]
  private var loadsByToken = Map.empty[String, ActiveLoad]
  private var dictionaryCache = Map.empty[MaterializationKey, AuthenticatedDictionaryView]
  private var cacheOrder = Vector.empty[MaterializationKey]
  private var dictionaryCacheBytes = 0L
  private var dictionaryOverrides = Map.empty[DictionaryId, DictionaryOverride]
  private var repairPermits = Map.empty[String, RepairPermit]
  private var repairPermitOrder = Vector.empty[String]

  override def preStart(): Unit = publishStatus(Starting)

  override def receive: Receive = {
    case BeginCatchUp(targetHeight) =>
      status = committed.map(s => CatchingUp(s.cursor, targetHeight)).getOrElse(Starting)
      publishStatus(status)

    // Canonical blocks enter only through StateFrame's commit request.
    case ApplyBlock(block) => prepareBlock(block, sender())

    // Mempool failure removes projections but does not make confirmed state unavailable.
    case MarkReady =>
      committed match {
        case Some(state) => publishReady(state)
        case None =>
          status = SyncFailed(None, "Cannot become ready before a block has committed")
          publishStatus(status)
          logger.error(status.asInstanceOf[SyncFailed].reason)
      }
      sender() ! status

    case MarkStale(reason) =>
      status = committed.map(s => Stale(s.cursor, reason)).getOrElse(SyncFailed(None, reason))
      publishStatus(status)

    case GetSyncStatus => sender() ! status
    case GetCommittedState => committed match {
      case Some(state) => sender() ! CommittedState(state)
      case None => sender() ! SyncUnavailable(status)
    }
    case GetMinerDictionary => respondMinerDictionary(sender())
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
        projections = projections.flatMap { case (blockId, cached) =>
          committed.flatMap(_.rollups.get(blockId)).flatMap { rollup =>
            snapshot.chains.get(rollup.utxoId).filter { chain =>
              cached.stamp.cursor == committed.get.cursor &&
                cached.stamp.dictionaryDigest == Hex.toHexString(rollup.dictionary.digest) &&
                cached.stamp.confirmedUtxo == rollup.utxoId &&
                cached.stamp.chainTransactions == chain.transforms.iterator.map(_.tx.id).toVector
            }.map(_ => blockId -> cached.copy(stamp = cached.stamp.copy(
              mempoolRevision = snapshot.revision)))
          }
        }
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

    case GetSynced => respondSynced(sender())

    case GetCurrentRollup(blockId) =>
      respondCurrentRollup(blockId, sender(), MaterializationPriority.Normal)
    case GetCurrentRollupCritical(blockId) =>
      respondCurrentRollup(blockId, sender(), MaterializationPriority.Critical)

    case DictionaryLoaded(digest, requestToken, dictionary) =>
      finishDictionaryLoad(digest, requestToken, Right(dictionary))
    case DictionaryLoadFailed(digest, requestToken, reason) =>
      finishDictionaryLoad(digest, requestToken, Left(reason))

    case SnapshotSaved(cursor, token) => checkpointSaved(cursor, token)
    case SnapshotSaveFailed(cursor, token, reason) => checkpointFailed(cursor, token, reason)
    case RetryCheckpoint =>
      checkpointRetry = None
      if (checkpointRequested) offerSnapshot(force = checkpointRequestedForce)

    // `sequence` restarts at zero with the actor, so operation ids are reused across incarnations.
    // A deadline scheduled by a dead instance would otherwise expire a healthy operation that
    // happened to be given the same id.
    case MaterializationExpired(token, operationId) if token == incarnation =>
      expireOperation(operationId)
    case _: MaterializationExpired => ()

    case UpdateEvaluation(blockId) =>
      committed.flatMap(_.rollups.get(blockId)).foreach { tree =>
        try {
          val updated = tree.copy(evaluated = true)
          committed = committed.map(s => s.copy(rollups = s.rollups + (blockId -> updated)))
          committed.foreach(updateJournalTipMetadata)
          stateEpoch += 1L
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
        val evicted = state.copy(rollups = state.rollups - blockId,
          routes = state.routes - tree.utxoId, rollupOrigins = state.rollupOrigins - blockId)
        publishTransition(committed, evicted) match {
          case Right(_) =>
            committed = Some(evicted)
            updateJournalTipMetadata(evicted)
            stateEpoch += 1L
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

    case GetRepairableQuarantines => committed match {
      case Some(state) =>
        val faults = state.repairableQuarantines(repairAttempts)
        val permit = issueRepairPermit(RepairPermit(state.cursor, stateEpoch, minerDictionaryEpoch,
          quarantineEpoch, faults.map(_.rollupId).toSet, minerDictionary = false))
        sender() ! RepairableQuarantines(faults, state.cursor, permit)
      case None => sender() ! BlockRejected(None, "", "no committed state to repair")
    }

    case GetMinerDictionaryRepairPermit => committed match {
      case Some(state) if state.minerDictionaryFault.isDefined =>
        val permit = issueRepairPermit(RepairPermit(state.cursor, stateEpoch, minerDictionaryEpoch,
          quarantineEpoch, Set.empty, minerDictionary = true))
        sender() ! MinerDictionaryRepairPermit(state.cursor, permit)
      case Some(state) => sender() ! BlockRejected(Some(state.cursor), "",
        "Miner Dictionary is not faulted")
      case None => sender() ! BlockRejected(None, "", "no committed state to repair")
    }

    // A rebuild is only installable at the exact chain cursor it was built for.
    case RepairRollup(rollupId, atCursor, outcome, repairPermit) =>
      val requester = sender()
      consumeRepairPermit(repairPermit, atCursor, Some(rollupId), minerDictionary = false) match {
        case Left(reason) => requester ! BlockRejected(committed.map(_.cursor), "", reason)
        case Right(_) =>
          committed match {
            // Installed at the current cursor. The permit proved the quarantine did not move in
            // between, and a quarantined rollup has no route, so no block could have changed it.
            case Some(state) if state.cursor.height < atCursor.height =>
              requester ! BlockRejected(Some(state.cursor), "",
                s"rollup $rollupId was rebuilt at ${atCursor.blockId}@${atCursor.height} but the cursor " +
                  s"has gone back to ${state.cursor.blockId}@${state.cursor.height}")
            case Some(state) => state.quarantined.get(rollupId) match {
              case None => requester ! BlockRejected(Some(state.cursor), "",
                s"rollup $rollupId is no longer quarantined")
              case Some(fault) => applyRepair(fault, outcome) match {
                case Right(next) => requester ! BlockCommitted(next.cursor, next.version, 0, 0)
                case Left(reason) => requester ! BlockRejected(committed.map(_.cursor), "", reason)
              }
            }
            case None => requester ! BlockRejected(None, "", "no committed state to repair")
          }
      }

    // Replaces dictionary state rebuilt at the committed cursor and clears its fault. A commit or
    // same-height reorg that landed while it ran makes the result stale, so the cursor must match.
    case RepairMinerDictionary(atCursor, minerTree, dataBoxToken, repairPermit) =>
      val requester = sender()
      consumeRepairPermit(repairPermit, atCursor, None, minerDictionary = true) match {
        case Left(reason) => requester ! BlockRejected(committed.map(_.cursor), "", reason)
        case Right(_) =>
          committed match {
            // Installed at the current cursor, not the one it was built for. The permit already
            // proved no committed dictionary change happened in between, so the rebuilt tree is
            // still exact here; requiring the cursor itself to be unmoved rejected every walk that
            // took longer than one block.
            case Some(state) if state.cursor.height >= atCursor.height =>
              val repaired = state.copy(minerTree = minerTree, dataBoxToken = dataBoxToken,
                minerDictionaryFault = None)
              // Stamped with the cursor it is installed at, not the one it was built for. Only the
              // journal tip carries the repaired digest; entries between the two still carry the
              // pre-repair one, so an override stamped lower would be applied to a boundary whose
              // metadata expects the old digest and fail that checkpoint outright.
              val replacement = DictionaryOverride(repaired.cursor, minerTree.dictionary)
              val replacements = dictionaryOverrides + (DictionaryId.Miner -> replacement)
              repairWithinJournalBudget(repaired, replacements) match {
                case Left(failed) =>
                  logger.warn(s"Did not install the rebuilt Miner Dictionary: $failed. " +
                    "Its existing fault remains isolated and canonical synchronization continues.")
                  requester ! BlockRejected(committed.map(_.cursor), "", failed)
                case Right(_) => publishTransition(committed, repaired) match {
                  case Right(_) =>
                    updateJournalTipMetadata(repaired)
                    dictionaryOverrides = replacements
                    remember(keyFor(DictionaryId.Miner, minerTree.dictionary), minerTree.dictionary)
                    committed = Some(dematerialize(repaired))
                    stateEpoch += 1L
                    minerDictionaryEpoch += 1L
                    logger.info(s"Miner Dictionary restored at ${atCursor.blockId}@${atCursor.height} " +
                      s"and installed at ${repaired.cursor.blockId}@${repaired.cursor.height} with " +
                      s"${minerTree.numMiners} miner(s); registration and commitment fraud proofs are " +
                      "available again")
                    publishStatus(status)
                    // Durable now rather than at the next interval, or a restart repeats the whole repair.
                    offerSnapshot(force = true)
                    requester ! BlockCommitted(repaired.cursor, repaired.version, 0, 0)
                  case Left(failed) =>
                    markRuntimeFailure("Could not publish the rebuilt Miner Dictionary",
                      new IllegalStateException(failed))
                    requester ! BlockRejected(committed.map(_.cursor), "", failed)
                }
              }
            // Defence in depth: a reorg below the rebuild height also advances the dictionary epoch,
            // so the permit check above has already refused this.
            case Some(state) =>
              requester ! BlockRejected(Some(state.cursor), "",
                s"dictionary was rebuilt at ${atCursor.blockId}@${atCursor.height} but the cursor has " +
                  s"gone back to ${state.cursor.blockId}@${state.cursor.height}")
            case None =>
              requester ! BlockRejected(None, "", "no committed state to repair")
          }
      }
  }

  private def markRuntimeFailure(message: String, reason: String): Unit =
    markRuntimeFailure(message, new IllegalStateException(reason))

  private def issueRepairPermit(permit: RepairPermit): String = {
    sequence += 1L
    val token = s"$incarnation:repair:$sequence"
    // Permits are single-use and short-lived. Bounding protects an abandoned ask from leaking state.
    repairPermits += token -> permit
    repairPermitOrder = (repairPermitOrder.filterNot(_ == token) :+ token).takeRight(32)
    repairPermits = repairPermits.filter { case (key, _) => repairPermitOrder.contains(key) }
    token
  }

  private def consumeRepairPermit(token: String,
                                  cursor: SyncCursor,
                                  rollupId: Option[String],
                                  minerDictionary: Boolean): Either[String, Unit] = {
    val permit = repairPermits.get(token)
    repairPermits -= token
    repairPermitOrder = repairPermitOrder.filterNot(_ == token)
    permit.toRight("repair permit is absent, already consumed, or from another actor incarnation")
      .flatMap { issued =>
        // Each rebuild is pinned to the state of the entity it rebuilds rather than to the global
        // epoch, which advances on every commit and so could never be survived by a walk longer
        // than one block. Both entity epochs advance on every reorg, restore and reset, and on any
        // sign the chain touched the entity while it was untracked, so a permit still cannot cross
        // branches or admit a stale rebuild.
        val superseded =
          if (minerDictionary) issued.minerEpoch != minerDictionaryEpoch
          else issued.quarantineEpoch != quarantineEpoch
        if (issued.cursor != cursor || superseded)
          Left("repair permit no longer names the exact committed state")
        else if (issued.minerDictionary != minerDictionary)
          Left("repair permit names a different synchronization entity")
        else if (rollupId.exists(id => !issued.rollups(id)))
          Left(s"repair permit does not authorize rollup ${rollupId.get}")
        else Right(())
      }
  }

  /** Installs a rebuilt rollup, or records the attempt so the fault stops being retried eventually. */
  private def applyRepair(fault: QuarantineFault,
                          outcome: RollupRepairResult): Either[String, CommittedSyncState] =
    committed.toRight("no committed state to repair").flatMap { state =>
      val (next, forceSnapshot, report) = outcome match {
        case RollupRepairResult.Rebuilt(tree) =>
          (state.copy(rollups = state.rollups + (fault.rollupId -> tree),
            routes = state.routes + (tree.utxoId -> fault.rollupId),
            rollupOrigins = state.rollupOrigins + (fault.rollupId -> fault.collateralBoxId),
            quarantined = state.quarantined - fault.rollupId), true, () =>
              logger.warn(s"Rollup ${fault.rollupId} is tracked again after ${fault.attempts + 1} " +
                s"rebuild attempt(s); this miner can act on it once more"))
        case RollupRepairResult.Terminated =>
          (state.copy(quarantined = state.quarantined - fault.rollupId), true, () =>
            logger.info(s"Quarantined rollup ${fault.rollupId} had already ended on chain; " +
              "dropping its fault"))
        case RollupRepairResult.Failed(reason, retryable) =>
          val failed = fault.attempted.copy(reason = reason, retryable = retryable)
          val attempted =
            if (failed.repairable(repairAttempts)) failed
            else failed.scheduleRemoval(quarantineRetentionBlocks)
          val log = () => attempted.removalHeight match {
            case Some(removeAt) =>
              logger.error(s"Rollup ${fault.rollupId} stays quarantined after " +
                s"${attempted.attempts} attempt(s): $reason. It will not be rebuilt again, and this " +
                s"miner earns nothing from it. Its diagnostic record will be removed at height $removeAt.")
            case None =>
              logger.warn(s"Rebuild ${attempted.attempts} of ${fault.rollupId} failed: $reason")
          }
          (state.copy(quarantined = state.quarantined + (fault.rollupId -> attempted)),
            false, log)
      }
      val replacements = outcome match {
        case RollupRepairResult.Rebuilt(tree) =>
          dictionaryOverrides + (DictionaryId.Rollup(fault.rollupId) ->
            DictionaryOverride(next.cursor, tree.dictionary))
        case _ => dictionaryOverrides
      }
      repairWithinJournalBudget(next, replacements) match {
        case Left(failed) =>
          logger.warn(s"Did not install repair result for rollup ${fault.rollupId}: $failed. " +
            "Its existing quarantine remains isolated and canonical synchronization continues.")
          Left(failed)
        case Right(_) => publishTransition(committed, next) match {
          case Right(_) =>
          outcome match {
            case RollupRepairResult.Rebuilt(tree) =>
              updateJournalTipMetadata(next)
              dictionaryOverrides = replacements
              remember(keyFor(DictionaryId.Rollup(fault.rollupId), tree.dictionary), tree.dictionary)
            case _ => updateJournalTipMetadata(next)
          }
          committed = Some(dematerialize(next))
          stateEpoch += 1L
          // Every outcome here rewrites the quarantine map, so no permit taken before it may install.
          quarantineEpoch += 1L
          publishStatus(status)
          // A rebuilt dictionary or terminal repair outcome is durable immediately. Failed-attempt
          // counts and retention deadlines use ordinary cadence: the deadline is deterministically
          // derived from genesis height, so catch-up assigns or expires it at the same chain height.
          offerSnapshot(force = forceSnapshot)
          report()
          Right(next)
          case Left(failed) =>
            markRuntimeFailure(s"Could not publish the rebuilt rollup ${fault.rollupId}", failed)
            Left(failed)
        }
      }
    }

  /** Runs one logical operation after exactly the dictionaries it names have been reconstructed. */
  private def withMaterialized(epoch: Long,
                               targets: Set[MaterializationKey],
                               priority: MaterializationPriority = MaterializationPriority.Normal)
                              (complete: Map[MaterializationKey, AuthenticatedDictionaryView] => Unit)
                              (fail: MaterializationFailure => Unit): Unit = {
    val available = targets.flatMap { key =>
      currentDictionary(key).filter(_.materialized).orElse(cachedDictionary(key)).map(key -> _)
    }.toMap
    val remaining = targets -- available.keySet
    if (remaining.isEmpty) complete(available)
    else {
      sequence += 1L
      val operationId = sequence
      val deadline = context.system.scheduler.scheduleOnce(materializationDeadline, self,
        MaterializationExpired(incarnation, operationId))(context.dispatcher, self)
      operations += operationId -> PendingOperation(epoch, remaining, available, complete, fail,
        deadline)
      remaining.foreach { key =>
        if (operations.contains(operationId)) loadsByKey.get(key) match {
          case Some(load) =>
            val updated = load.copy(operations = load.operations + operationId)
            loadsByKey += key -> updated
            loadsByToken += load.token -> updated
            if (priority == MaterializationPriority.Critical)
              snapshotActor ! PromoteMaterialization(load.token)
          case None => materializationSource(key) match {
            case Left(reason) => failOperation(operationId, key, reason)
            case Right(source) =>
              sequence += 1L
              val token = s"$incarnation:dictionary:$sequence"
              val load = ActiveLoad(key, token, Set(operationId))
              loadsByKey += key -> load
              loadsByToken += token -> load
              snapshotActor ! MaterializeDictionary(source, token, priority)
          }
        }
      }
    }
  }

  private def finishDictionaryLoad(digest: String,
                                   token: String,
                                   result: Either[String, AuthenticatedDictionaryView]): Unit =
    loadsByToken.get(token).foreach { load =>
      loadsByToken -= token
      loadsByKey -= load.key
      result match {
        case Right(dictionary) if digest == load.key.digest &&
          Hex.toHexString(dictionary.digest) == load.key.digest =>
          remember(load.key, dictionary)
          load.operations.foreach(completeOperation(_, load.key, dictionary))
        case Right(dictionary) =>
          val actual = Hex.toHexString(dictionary.digest)
          load.operations.foreach(failOperation(_, load.key,
            s"materializer returned $actual for ${load.key.digest}"))
        case Left(reason) => load.operations.foreach(failOperation(_, load.key, reason))
      }
    }

  private def completeOperation(operationId: Long,
                                key: MaterializationKey,
                                dictionary: AuthenticatedDictionaryView): Unit =
    operations.get(operationId).foreach { operation =>
      val next = operation.copy(remaining = operation.remaining - key,
        dictionaries = operation.dictionaries + (key -> dictionary))
      if (next.remaining.nonEmpty) operations += operationId -> next
      else {
        operations -= operationId
        next.deadline.cancel()
        if (stateEpoch == next.epoch) next.complete(next.dictionaries)
        else next.fail(MaterializationFailure(None,
          "committed state changed during dictionary materialization"))
      }
    }

  private def failOperation(operationId: Long,
                            key: MaterializationKey,
                            reason: String): Unit =
    operations.get(operationId).foreach { operation =>
      operations -= operationId
      operation.deadline.cancel()
      operation.fail(MaterializationFailure(Some(key), reason))
    }

  private def expireOperation(operationId: Long): Unit =
    operations.get(operationId).foreach { operation =>
      operations -= operationId
      operation.remaining.foreach(releaseLoad(operationId, _))
      logger.error(s"Dictionary materialization for ${operation.remaining.size} target(s) exceeded " +
        s"$materializationDeadline with no answer from the persistence actor. Check the log for a " +
        "snapshot database that will not open.")
      operation.fail(MaterializationFailure(None,
        s"dictionary materialization exceeded $materializationDeadline"))
    }

  /**
   * Ends every barrier before the state they were waiting for is thrown away.
   *
   * Clearing the map alone left each caller waiting out its own ask timeout for a completion that
   * could no longer be produced.
   */
  private def abandonOperations(reason: String): Unit = {
    operations.values.foreach { operation =>
      operation.deadline.cancel()
      operation.fail(MaterializationFailure(None, reason))
    }
    operations = Map.empty
  }

  /** Forgets a load once no pending operation is waiting on it. */
  private def releaseLoad(operationId: Long, key: MaterializationKey): Unit =
    loadsByKey.get(key).foreach { load =>
      val waiting = load.operations - operationId
      if (waiting.isEmpty) {
        loadsByKey -= key
        loadsByToken -= load.token
      } else {
        val updated = load.copy(operations = waiting)
        loadsByKey += key -> updated
        loadsByToken += load.token -> updated
      }
    }

  private def materializationSource(key: MaterializationKey): Either[String, SnapshotDictionarySource] =
    (journalBase, journal, committed) match {
      case (Some(base), Some(active), Some(current)) =>
        currentDictionary(key).toRight(s"${key.dictionaryId.value} is no longer tracked").map { target =>
          val replacement = dictionaryOverrides.get(key.dictionaryId)
          val baseDictionary = dictionaryFrom(base, key.dictionaryId)
          val starting = replacement.map(value =>
            SnapshotDictionaryBase.Materialized(value.dictionary)).getOrElse {
            baseDictionary match {
              case Some(dictionary) if dictionary.materialized =>
                SnapshotDictionaryBase.Materialized(dictionary)
              case Some(dictionary) =>
                SnapshotDictionaryBase.Persisted(Hex.toHexString(dictionary.digest))
              case None => SnapshotDictionaryBase.Empty
            }
          }
          val transforms = active.entries.iterator
            .filter(entry => replacement.forall(_.at.height < entry.cursor.height))
            .flatMap(_.transforms.iterator).filter(_.dictionaryId == key.dictionaryId).toVector
          SnapshotDictionarySource(key.digest, target.flags, target.parameters, starting, transforms)
        }
      case _ => Left("Synchronization has no checkpoint-backed transform journal")
    }

  private def currentDictionary(key: MaterializationKey): Option[AuthenticatedDictionaryView] =
    committed.flatMap(dictionaryFrom(_, key.dictionaryId))
      .filter(dictionary => Hex.toHexString(dictionary.digest) == key.digest)

  private def dictionaryFrom(state: CommittedSyncState,
                             id: DictionaryId): Option[AuthenticatedDictionaryView] = id match {
    case DictionaryId.Rollup(blockId) => state.rollups.get(blockId).map(_.dictionary)
    case DictionaryId.Miner => Some(state.minerTree.dictionary)
  }

  private def installDictionaries(state: CommittedSyncState,
                                  dictionaries: Map[MaterializationKey, AuthenticatedDictionaryView]):
    CommittedSyncState = dictionaries.foldLeft(state) { case (next, (key, dictionary)) =>
    key.dictionaryId match {
      case DictionaryId.Rollup(blockId) => next.rollups.get(blockId) match {
        case Some(rollup) => next.copy(rollups = next.rollups +
          (blockId -> rollup.copy(dictionary = dictionary)))
        case None => next
      }
      case DictionaryId.Miner => next.copy(minerTree = next.minerTree.copy(dictionary = dictionary))
    }
  }

  private def remember(key: MaterializationKey, dictionary: AuthenticatedDictionaryView): Unit = {
    forget(key)
    val weight = dictionary.estimatedHeapBytes
    if (weight <= maxMaterializedDictionaryCacheBytes) {
      dictionaryCache += key -> dictionary
      dictionaryCacheBytes = boundedAdd(dictionaryCacheBytes, weight)
      cacheOrder :+= key
      while (cacheOrder.size > syncConfig.materializedDictionaryCacheEntries ||
        dictionaryCacheBytes > maxMaterializedDictionaryCacheBytes) forget(cacheOrder.head)
    } else {
      logger.warn(s"Materialized ${key.dictionaryId.value} ${key.digest} has a conservative " +
        s"retained-heap weight of $weight bytes, over the $maxMaterializedDictionaryCacheBytes-byte " +
        "cache budget. It remains usable for this operation but will not be retained.")
    }
  }

  private def cachedDictionary(key: MaterializationKey): Option[AuthenticatedDictionaryView] =
    dictionaryCache.get(key).map { dictionary =>
      cacheOrder = cacheOrder.filterNot(_ == key) :+ key
      dictionary
    }

  private def forget(key: MaterializationKey): Unit = dictionaryCache.get(key).foreach { dictionary =>
    dictionaryCache -= key
    cacheOrder = cacheOrder.filterNot(_ == key)
    dictionaryCacheBytes = math.max(0L, dictionaryCacheBytes - dictionary.estimatedHeapBytes)
    projections = key.dictionaryId match {
      case DictionaryId.Rollup(blockId) => projections - blockId
      case DictionaryId.Miner => projections
    }
  }

  private def boundedAdd(left: Long, right: Long): Long =
    if (left >= Long.MaxValue - right) Long.MaxValue else left + right

  /** Keeps only the bounded cache materialized in the always-resident committed state. */
  private def dematerialize(state: CommittedSyncState): CommittedSyncState = {
    def retained(id: DictionaryId,
                 dictionary: AuthenticatedDictionaryView): AuthenticatedDictionaryView = {
      val key = MaterializationKey(id, Hex.toHexString(dictionary.digest))
      dictionaryCache.getOrElse(key,
        DeferredDictionary(dictionary.digest, dictionary.flags, dictionary.parameters))
    }
    state.copy(
      rollups = state.rollups.map { case (id, rollup) =>
        id -> rollup.copy(dictionary = retained(DictionaryId.Rollup(id), rollup.dictionary))
      },
      minerTree = state.minerTree.copy(
        dictionary = retained(DictionaryId.Miner, state.minerTree.dictionary)))
  }

  private def keyFor(id: DictionaryId, dictionary: AuthenticatedDictionaryView): MaterializationKey =
    MaterializationKey(id, Hex.toHexString(dictionary.digest))

  /** Follows same-block UTXO chains so every dictionary the reducer can mutate is ready up front. */
  private def dictionariesTouchedBy(state: CommittedSyncState,
                                    block: BlockInfo): Set[MaterializationKey] = {
    var routes = state.routes
    var minerUtxo = state.minerTree.utxoId
    var touched = Set.empty[MaterializationKey]
    block.txs.foreach { tx =>
      tx.inputs.headOption.flatMap(input => routes.get(input.id)).foreach { blockId =>
        state.rollups.get(blockId).foreach { rollup =>
          touched += keyFor(DictionaryId.Rollup(blockId), rollup.dictionary)
          routes -= tx.inputs.head.id
          tx.outputs.headOption.foreach(output => routes += output.id -> blockId)
        }
      }
      val dictionaryOutput = tx.outputs.headOption.filter(_.assets.exists(token =>
        token.id == protocol.minerDictionaryToken && token.amount == 1L))
      if (state.minerDictionaryFault.isEmpty &&
        dictionaryOutput.exists(_.id != protocol.minerDictionaryGenesisId)) {
        if (tx.inputs.headOption.exists(_.id == minerUtxo))
          touched += keyFor(DictionaryId.Miner, state.minerTree.dictionary)
        dictionaryOutput.foreach(output => minerUtxo = output.id)
      }
    }
    touched
  }

  private def respondMinerDictionary(requester: ActorRef): Unit = committed match {
    case Some(state) if status.isInstanceOf[Ready] && state.minerDictionaryFault.isEmpty =>
      val epoch = stateEpoch
      val key = keyFor(DictionaryId.Miner, state.minerTree.dictionary)
      withMaterialized(epoch, Set(key)) { dictionaries =>
        committed match {
          case Some(current) if stateEpoch == epoch && current.cursor == state.cursor =>
            requester ! CurrentMinerDictionary(
              current.minerTree.copy(dictionary = dictionaries(key)))
          case _ => requester ! SyncUnavailable(status)
        }
      }(failure => requester ! SyncUnavailable(Stale(state.cursor, failure.reason)))
    case _ => requester ! SyncUnavailable(status)
  }

  private def respondCurrentRollup(blockId: String,
                                   requester: ActorRef,
                                   priority: MaterializationPriority): Unit = committed match {
    case Some(state) if status.isInstanceOf[Ready] => state.rollups.get(blockId) match {
      case Some(rollup) =>
        val epoch = stateEpoch
        val projectionAtRequest = projectionFingerprint(blockId, rollup.utxoId)
        val key = keyFor(DictionaryId.Rollup(blockId), rollup.dictionary)
        withMaterialized(epoch, Set(key), priority) { dictionaries =>
          committed match {
            case Some(current) if stateEpoch == epoch &&
              projectionFingerprint(blockId, rollup.utxoId) == projectionAtRequest =>
              current.rollups.get(blockId) match {
                case Some(latest) if Hex.toHexString(latest.dictionary.digest) == key.digest =>
                  val materialized = latest.copy(dictionary = dictionaries(key))
                  requester ! CurrentRollup(materialized.utxoId, materialized,
                    projectedRollup(installDictionaries(current, dictionaries), blockId, materialized))
                case _ => requester ! NoRollupFound()
              }
            case _ => respondCurrentRollup(blockId, requester, priority)
          }
        } { failure =>
          logger.error(s"Could not materialize rollup $blockId: ${failure.reason}")
          requester ! RollupUnavailable(failure.reason)
        }
      case None => requester ! NoRollupFound()
    }
    case _ => requester ! RollupUnavailable(SyncHandler.describe(status))
  }

  /** Materializes only rollups with active mempool chains; the global confirmed view stays metadata-only. */
  private def respondSynced(requester: ActorRef): Unit = committed match {
    case Some(state) if status.isInstanceOf[Ready] =>
      val epoch = stateEpoch
      val revision = mempool.revision
      val usable = usableRollups(state)
      val targets = usable.flatMap { case (_, metadata) =>
        state.rollups.get(metadata.blockId).filter(tree =>
          mempool.chains.contains(tree.utxoId) &&
            unchangedProjection(state, tree.blockId, tree).isEmpty)
          .map(tree => keyFor(DictionaryId.Rollup(tree.blockId), tree.dictionary))
      }.toSet
      withMaterialized(epoch, targets) { dictionaries =>
        committed match {
          case Some(current) if stateEpoch == epoch && mempool.revision == revision =>
            val working = installDictionaries(current, dictionaries)
            val projected = usable.flatMap { case (_, metadata) =>
              working.rollups.get(metadata.blockId).flatMap { tree =>
                unchangedProjection(working, tree.blockId, tree)
                  .orElse(if (tree.dictionary.materialized)
                    projectedRollup(working, tree.blockId, tree) else None).map { projection =>
                  tree.blockId -> MempoolRollupMetadata(projection.asInput,
                    projection.rollup.metadata, projection.toBeRemoved)
                }
              }
            }.toMap
            requester ! FullSync(usable, projected)
          case _ => respondSynced(requester)
        }
      }(failure => requester ! SyncUnavailable(Stale(state.cursor, failure.reason)))
    case _ => requester ! SyncUnavailable(status)
  }

  private def projectionFingerprint(blockId: String,
                                    confirmedUtxo: String): (Vector[String], Option[String], Boolean, Boolean) =
    (mempool.chains.get(confirmedUtxo).toVector.flatMap(_.transforms.iterator.map(_.tx.id)),
      mempool.endInputs.get(confirmedUtxo).map(_.id.toString),
      suppressedProjections.get(blockId).contains(mempool.revision),
      mempool.blockedRoots(confirmedUtxo))

  private def prepareBlock(block: BlockInfo, requester: ActorRef): Unit = committed match {
    case Some(state) if state.cursor.blockId != block.id =>
      prepareBlockAgainst(block, requester, state, stateEpoch)
    case _ => applyAndReply(block, requester, None)
  }

  /** A cold-load failure removes only the entity that depends on that prover, then retries the block. */
  private def prepareBlockAgainst(block: BlockInfo,
                                  requester: ActorRef,
                                  base: CommittedSyncState,
                                  epoch: Long): Unit = {
    val targets = dictionariesTouchedBy(base, block)
    withMaterialized(epoch, targets, MaterializationPriority.Critical) { dictionaries =>
      if (stateEpoch != epoch || committed.forall(_.cursor != base.cursor))
        requester ! BlockRejected(committed.map(_.cursor), block.id,
          "committed state changed while block dictionaries were materializing")
      else applyAndReply(block, requester, Some(installDictionaries(base, dictionaries)))
    } { failure =>
      if (stateEpoch != epoch || committed.forall(_.cursor != base.cursor))
        requester ! BlockRejected(committed.map(_.cursor), block.id,
          "committed state changed while block dictionaries were materializing")
      else failure.key match {
        case Some(key) =>
          val degraded = degradeMaterializationFailure(base, key, failure.reason)
          prepareBlockAgainst(block, requester, degraded, epoch)
        case None =>
          requester ! BlockRejected(Some(base.cursor), block.id,
            s"Could not materialize dictionaries for block ${block.id}: ${failure.reason}")
      }
    }
  }

  private def degradeMaterializationFailure(base: CommittedSyncState,
                                            key: MaterializationKey,
                                            reason: String): CommittedSyncState =
    key.dictionaryId match {
      case DictionaryId.Rollup(blockId) => base.rollups.get(blockId) match {
        case None => base
        case Some(tree) =>
          val origin = base.rollupOrigins.get(blockId)
          val detail = s"Could not materialize rollup dictionary ${key.digest}: $reason"
          val fault = QuarantineFault(blockId, tree.startHeight, origin.getOrElse(""), detail,
            retryable = origin.isDefined, utxoId = tree.utxoId)
          logger.error(s"Quarantined rollup $blockId before block application: $detail. " +
            "The canonical cursor and unrelated rollups will continue.")
          base.copy(rollups = base.rollups - blockId,
            routes = base.routes.filterNot(_._2 == blockId),
            rollupOrigins = base.rollupOrigins - blockId,
            quarantined = base.quarantined + (blockId -> fault))
      }
      case DictionaryId.Miner =>
        val detail = s"Could not materialize Miner Dictionary ${key.digest}: $reason"
        logger.error(s"Miner Dictionary stopped before block application: $detail. " +
          "The canonical cursor and rollups will continue.")
        base.copy(minerDictionaryFault = Some(detail))
    }

  private def applyAndReply(block: BlockInfo,
                            requester: ActorRef,
                            preparedBase: Option[CommittedSyncState]): Unit = {
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
        val base = preparedBase.getOrElse(committed.get)
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
            val (maintainedState, warnings, removed) = maintainQuarantines(transition.state)
            val maintained = transition.copy(state = maintainedState)
            val entry = TransformJournalEntry(maintained.state.cursor,
              CommittedSyncMetadata.from(maintained.state), maintained.dictionaryTransforms)
            val nextJournal = try journal.map(_.append(entry)).orElse {
              journalBase.map(checkpoint => TransformJournal(checkpoint.cursor).append(entry))
            }.toRight("Synchronization has no checkpoint-backed transform journal")
            catch {
              case NonFatal(error) => Left(Option(error.getMessage)
                .getOrElse(error.getClass.getSimpleName))
            }
            val bounded = nextJournal.flatMap { candidate =>
              val bytes = volatileJournalBytes(candidate, dictionaryOverrides)
              if (bytes <= maxTransformJournalBytes) Right(candidate)
              else Left(s"Volatile synchronization journal would retain $bytes bytes, over the " +
                s"$maxTransformJournalBytes-byte bound")
            }
            bounded match {
              case Left(reason) =>
                logger.error(s"Deferred block ${block.id} at height ${block.height}: $reason. " +
                  "Forcing checkpoint compaction before retrying the same block.")
                offerSnapshot(force = true)
                requester ! BlockRejected(committed.map(_.cursor), block.id, reason)
              case Right(candidateJournal) => publishTransition(committed, maintained.state) match {
              case Left(reason) =>
                status = SyncFailed(committed.map(_.cursor), reason)
                publishStatus(status)
                logger.error(s"Failed to publish block ${block.id}: $reason")
                requester ! BlockRejected(committed.map(_.cursor), block.id, reason)
              case Right(_) =>
                journal = Some(candidateJournal)
                if (minerDictionaryChanged(committed, maintained.state) ||
                  maintained.minerDictionaryMoved) minerDictionaryEpoch += 1L
                if (quarantineChanged(committed, maintained.state)) quarantineEpoch += 1L
                maintained.dictionaryTransforms.foreach { transform =>
                  dictionaryFrom(maintained.state, transform.dictionaryId).foreach { dictionary =>
                    remember(MaterializationKey(transform.dictionaryId,
                      Hex.toHexString(dictionary.digest)), dictionary)
                  }
                }
                committed = Some(dematerialize(maintained.state))
                stateEpoch += 1L
                knownCursors = (knownCursors :+ maintained.state.cursor).takeRight(syncConfig.cursorWindow + 1)
                projections = Map.empty
                suppressedProjections = Map.empty
                reportFaults(block, maintained)
                reportQuarantineRetention(warnings, removed, maintained.state.cursor.height)
                reportProgress(block, maintained)
                status = status match {
                  case CatchingUp(_, target) => CatchingUp(maintained.state.cursor, target)
                  case Ready(_) => Ready(maintained.state.cursor)
                  case Reorganizing(_, target) => Reorganizing(maintained.state.cursor, target)
                  case _ => CatchingUp(maintained.state.cursor, maintained.state.cursor.height)
                }
                publishStatus(status)
                // Retention deadlines need no immediate snapshot: they are derived from genesis height,
                // so restart catch-up assigns or expires them at the same deterministic block height.
                offerSnapshot(force = false)
                requester ! BlockCommitted(maintained.state.cursor, maintained.state.version,
                  maintained.relevantEvents, maintained.stagedDictionaryCopies)
              }
            }
        }
    }
  }

  /**
   * Whether a commit moved anything a Miner Dictionary rebuild reproduces. Compared on the digest
   * rather than the transform list so a repair install and a reorg are covered by the same test.
   */
  private def minerDictionaryChanged(previous: Option[CommittedSyncState],
                                     next: CommittedSyncState): Boolean = previous.forall { before =>
    !java.util.Arrays.equals(before.minerTree.dictionary.digest, next.minerTree.dictionary.digest) ||
      before.minerTree.utxoId != next.minerTree.utxoId ||
      before.minerDictionaryFault != next.minerDictionaryFault ||
      before.dataBoxToken != next.dataBoxToken
  }

  /**
   * Whether a commit moved anything a rollup rebuild reproduces.
   */
  private def quarantineChanged(previous: Option[CommittedSyncState],
                                next: CommittedSyncState): Boolean = previous.forall { before =>
    before.quarantined.keySet != next.quarantined.keySet ||
      before.quarantined.exists { case (id, fault) =>
        next.quarantined.get(id).exists(_.utxoId != fault.utxoId)
      }
  }

  private def volatileJournalBytes(active: TransformJournal,
                                   overrides: Map[DictionaryId, DictionaryOverride]): Long =
    overrides.values.foldLeft(active.accountedBytes) { (bytes, replacement) =>
      boundedAdd(bytes, replacement.dictionary.estimatedHeapBytes)
    }

  /** Same-cursor repairs retain full dictionary overrides until their forced checkpoint commits. */
  private def repairWithinJournalBudget(
    state: CommittedSyncState,
    replacements: Map[DictionaryId, DictionaryOverride]): Either[String, Unit] = journal match {
    case Some(active) =>
      val prospective =
        if (active.entries.nonEmpty && active.entries.last.cursor == state.cursor)
          Right(active.replaceTipMetadata(CommittedSyncMetadata.from(state)))
        else if (active.entries.isEmpty && journalBase.exists(_.cursor == state.cursor)) Right(active)
        else Left(s"Cannot account for a same-cursor repair at ${state.cursor.blockId}@" +
          s"${state.cursor.height}")
      prospective.flatMap { next =>
        val bytes = volatileJournalBytes(next, replacements)
        Either.cond(bytes <= maxTransformJournalBytes, (),
          s"Repair would retain $bytes volatile synchronization bytes, over the " +
            s"$maxTransformJournalBytes-byte bound")
      }
    case None => Left("Synchronization has no checkpoint-backed transform journal")
  }

  /** Logs isolated rollup and dictionary faults that do not stop the canonical cursor. */
  private def reportFaults(block: BlockInfo, transition: BlockTransition): Unit = {
    transition.quarantined.foreach { fault =>
      val ending = transition.state.quarantined.get(fault.rollupId) match {
        case Some(retained) if retained.repairable(repairAttempts) =>
          s"Rebuilding it from the indexed chain, up to $repairAttempts time(s)."
        case Some(retained) => retained.removalHeight.fold(
          "Re-reading it cannot give a different answer, so it will not be rebuilt.")(height =>
          s"It will not be rebuilt; its diagnostic record will be removed at height $height.")
        case None => "Its diagnostic retention ended at this height."
      }
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

  /**
   * Starts retention only when a quarantine episode is terminal or out of retries, emits one warning
   * near its deadline, and removes the diagnostic record at that committed height.
   */
  private def maintainQuarantines(state: CommittedSyncState):
    (CommittedSyncState, Seq[QuarantineFault], Seq[QuarantineFault]) = {
    val height = state.cursor.height
    val scheduled = state.quarantined.map { case (id, fault) =>
      val normalized =
        if (fault.repairable(repairAttempts))
          fault.copy(removalHeight = None, removalWarningLogged = false)
        else fault.scheduleRemoval(quarantineRetentionBlocks)
      id -> normalized
    }
    val (expired, retained) = scheduled.partition { case (_, fault) =>
      fault.removalHeight.exists(_ <= height)
    }
    val warnings = retained.values.filter { fault =>
      !fault.removalWarningLogged && fault.removalHeight.exists { removal =>
        height.toLong >= removal.toLong - quarantineWarningBlocks.toLong
      }
    }.toSeq.sortBy(_.rollupId)
    val warningIds = warnings.iterator.map(_.rollupId).toSet
    val updated = retained.map { case (id, fault) =>
      id -> (if (warningIds(id)) fault.copy(removalWarningLogged = true) else fault)
    }
    (state.copy(quarantined = updated), warnings,
      expired.values.toSeq.sortBy(_.rollupId))
  }

  private def reportQuarantineRetention(warnings: Seq[QuarantineFault],
                                        removed: Seq[QuarantineFault],
                                        height: Int): Unit = {
    warnings.foreach { fault =>
      val removal = fault.removalHeight.get
      logger.error(s"Quarantined rollup ${fault.rollupId} is terminal or out of rebuild attempts and " +
        s"will be removed from diagnostic retention at height $removal, in ${removal - height} block(s). " +
        "This miner earns nothing from it.")
    }
    removed.foreach { fault =>
      logger.error(s"Removed quarantined rollup ${fault.rollupId} from diagnostic retention at height " +
        s"$height after it became terminal or exhausted its rebuild attempts. Last fault: ${fault.reason}")
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
        s"attempts, ${terminal.size} not rebuildable. Terminal and exhausted records are retained for " +
        s"$quarantineRetentionBlocks block(s). This miner earns nothing from them.")
    }

  private def rollback(blockId: String): Unit = {
    val requester = sender()
    val targetResult: Either[String, (CommittedSyncMetadata, Vector[TransformJournalEntry], TransformJournal)] =
      (journalBase, journal) match {
        case (Some(base), Some(active)) if base.cursor.blockId == blockId =>
          Right((CommittedSyncMetadata.from(base), Vector.empty, TransformJournal(base.cursor)))
        case (Some(base), Some(active)) => active.indexOf(blockId) match {
          case -1 => Left(s"Block $blockId is outside the volatile transform journal")
          case index => Right((active.entries(index).metadata, active.entries.take(index + 1),
            active.through(index)))
        }
        case _ => Left("Synchronization has no transform journal")
      }
    val materialized = targetResult.flatMap { case (metadata, prefix, retained) =>
      checkpointSources(metadata, prefix).flatMap { sources =>
        val rollups = metadata.rollups.map { case (id, rollup) =>
          val source = sources(DictionaryId.Rollup(id))
          id -> DeferredDictionary(Hex.decode(rollup.dictionaryDigest), source.flags, source.parameters)
        }
        val minerSource = sources(DictionaryId.Miner)
        val miner = DeferredDictionary(Hex.decode(metadata.minerDictionary.dictionaryDigest),
          minerSource.flags, minerSource.parameters)
        CommittedSyncMetadata.materialize(metadata, rollups, miner).map(_ -> retained)
      }
    }
    materialized match {
      case Left(reason) => requester ! RollbackRejected(reason)
      case Right((target, retainedJournal)) =>
        status = committed.map(s => Reorganizing(s.cursor, target.cursor.height)).getOrElse(Starting)
        publishStatus(status)
        publishTransition(committed, target) match {
          case Left(reason) =>
            status = SyncFailed(committed.map(_.cursor), reason)
            publishStatus(status)
            requester ! RollbackRejected(reason)
          case Right(_) =>
            committed = Some(dematerialize(target))
            journal = Some(retainedJournal)
            dictionaryOverrides = dictionaryOverrides.filter { case (_, replacement) =>
              replacement.at.height <= target.cursor.height
            }
            stateEpoch += 1L
            // Unconditional: a rollback can move either entity to another branch's contents, and it
            // is what stops a repair permit issued before the fork from installing after it.
            minerDictionaryEpoch += 1L
            quarantineEpoch += 1L
            knownCursors = knownCursors.filterNot(_.height > target.cursor.height)
            projections = Map.empty
            suppressedProjections = Map.empty
            status = Reorganizing(target.cursor, target.cursor.height)
            publishStatus(status)
            requester ! RollbackCompleted(target.cursor)
        }
    }
  }

  private def restore(state: CommittedSyncState,
                      cursors: Vector[SyncCursor]): Either[String, Unit] = {
    publishTransition(committed, state).map { _ =>
      abandonOperations("synchronization state was replaced by a restore")
      loadsByKey = Map.empty
      loadsByToken = Map.empty
      dictionaryCache = Map.empty
      cacheOrder = Vector.empty
      dictionaryCacheBytes = 0L
      dictionaryOverrides = Map.empty
      repairPermits = Map.empty
      repairPermitOrder = Vector.empty
      checkpointInFlight = None
      checkpointRequested = false
      checkpointRequestedForce = false
      checkpointRetry.foreach(_.cancel())
      checkpointRetry = None
      journalBase = Some(state)
      journal = Some(TransformJournal(state.cursor))
      committed = Some(dematerialize(state))
      knownCursors =
        (cursors.filter(_.height < state.cursor.height) :+ state.cursor).takeRight(syncConfig.cursorWindow + 1)
      projections = Map.empty
      suppressedProjections = Map.empty
      initialReadySnapshotRequested = false
      stateEpoch += 1L
      // A restore replaces both wholesale, so no permit issued before it may install.
      minerDictionaryEpoch += 1L
      quarantineEpoch += 1L
      status = CatchingUp(state.cursor, state.cursor.height)
      publishStatus(status)
    }
  }

  private def reset(): Either[String, Unit] =
    repository.clear().map { _ =>
      committed = None
      journalBase = None
      journal = None
      knownCursors = Vector.empty
      projections = Map.empty
      suppressedProjections = Map.empty
      initialReadySnapshotRequested = false
      abandonOperations("synchronization state was reset for a full rescan")
      loadsByKey = Map.empty
      loadsByToken = Map.empty
      dictionaryCache = Map.empty
      cacheOrder = Vector.empty
      dictionaryCacheBytes = 0L
      dictionaryOverrides = Map.empty
      repairPermits = Map.empty
      repairPermitOrder = Vector.empty
      checkpointInFlight = None
      checkpointRequested = false
      checkpointRequestedForce = false
      checkpointRetry.foreach(_.cancel())
      checkpointRetry = None
      stateEpoch += 1L
      // A reset discards both entirely, so no permit issued before it may install.
      minerDictionaryEpoch += 1L
      quarantineEpoch += 1L
      status = Starting
      publishStatus(status)
    }

  private def publishTransition(previous: Option[CommittedSyncState],
                                next: CommittedSyncState): Either[String, Unit] =
    repository.publish(previous, next)

  private def updateJournalTipMetadata(state: CommittedSyncState): Unit = journal match {
    case Some(active) if active.entries.nonEmpty && active.entries.last.cursor == state.cursor =>
      journal = Some(active.replaceTipMetadata(CommittedSyncMetadata.from(state)))
    case _ if journalBase.exists(_.cursor == state.cursor) => journalBase = Some(state)
    case _ => throw new IllegalStateException(
      s"Cannot update journal metadata at ${state.cursor.blockId}@${state.cursor.height}")
  }

  /** Rollups this client may act on. Cheap: no projection is built, so a commit can publish it. */
  private def usableRollups(state: CommittedSyncState): Seq[(String, lfsm.states.RollupMetadata)] =
    state.routedRollups.map { case (utxoId, rollup) => utxoId -> rollup.metadata }

  private def projectedRollup(state: CommittedSyncState,
                              blockId: String,
                              tree: lfsm.states.Rollup): Option[MempoolRollupState] = {
    if (suppressedProjections.get(blockId).contains(mempool.revision)) None
    else mempool.chains.get(tree.utxoId) match {
      case None =>
        projections -= blockId
        None
      case Some(chain) =>
        val stamp = projectionStamp(state, tree, chain)
        projections.get(blockId) match {
          case Some(cached) if cached.stamp == stamp => Some(cached.state)
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
              projections += blockId -> CachedProjection(stamp, projection)
              projection
            }
        }
    }
  }

  /**
   * Selects an immutable journal prefix. Ordinary cadence leaves a live suffix for short reorgs;
   * forced durability selects the tip. The persistence actor reconstructs one dictionary at a time.
   */
  private def offerSnapshot(force: Boolean): Unit = {
    val due = committed.exists(state => force ||
      state.cursor.height % syncConfig.snapshotInterval == 0)
    if (syncConfig.snapshotsEnabled && due && checkpointRetry.nonEmpty) {
      checkpointRequested = true
      checkpointRequestedForce = checkpointRequestedForce || force
    } else if (syncConfig.snapshotsEnabled && due) checkpointInFlight match {
      case Some(_) =>
        checkpointRequested = true
        checkpointRequestedForce = checkpointRequestedForce || force
      case None => (journalBase, journal) match {
        case (Some(base), Some(active)) =>
          val leave = math.max(1, syncConfig.snapshotInterval / 2)
          val cutEntries =
            if (force) active.entries.size
            else if (active.entries.size > leave) active.entries.size - leave
            else active.entries.size
          val prefix = active.entries.take(cutEntries)
          val boundary = prefix.lastOption.map(_.metadata)
            .getOrElse(CommittedSyncMetadata.from(base))
          checkpointSources(boundary, prefix) match {
            case Left(reason) => logger.error(
              s"Could not prepare checkpoint ${boundary.cursor.blockId}@${boundary.cursor.height}: $reason")
            case Right(sources) =>
              checkpointSequence += 1L
              val token = s"$incarnation:checkpoint:$checkpointSequence"
              val cursors = (knownCursors.filter(_.height < boundary.cursor.height) :+ boundary.cursor)
                .takeRight(syncConfig.cursorWindow + 1)
              checkpointInFlight = Some(PendingCheckpoint(token, boundary, cutEntries, sources, force))
              checkpointRequested = false
              checkpointRequestedForce = false
              snapshotActor ! SnapshotCandidate(
                SnapshotCheckpoint(boundary, cursors, sources), token)
          }
        case _ => logger.warn("Skipping checkpoint because synchronization has no transform journal")
      }
    }
  }

  private def projectionStamp(state: CommittedSyncState,
                              tree: lfsm.states.Rollup,
                              chain: MempoolChain): ProjectionStamp =
    ProjectionStamp(state.cursor, Hex.toHexString(tree.dictionary.digest), tree.utxoId,
      mempool.revision, chain.transforms.iterator.map(_.tx.id).toVector)

  /** A projection is usable without reconstructing its unchanged confirmed dictionary again. */
  private def unchangedProjection(state: CommittedSyncState,
                                  blockId: String,
                                  tree: lfsm.states.Rollup): Option[MempoolRollupState] =
    if (suppressedProjections.get(blockId).contains(mempool.revision)) None
    else mempool.chains.get(tree.utxoId).flatMap { chain =>
      val stamp = projectionStamp(state, tree, chain)
      projections.get(blockId).filter(_.stamp == stamp).map(_.state)
    }

  private def checkpointSources(boundary: CommittedSyncMetadata,
                                prefix: Vector[TransformJournalEntry]):
    Either[String, Map[DictionaryId, SnapshotDictionarySource]] = journalBase.toRight(
    "no checkpoint base").map { base =>
    val ids: Set[DictionaryId] =
      boundary.rollups.keySet.map(id => DictionaryId.Rollup(id): DictionaryId) + DictionaryId.Miner
    ids.map { id =>
      val targetDigest = id match {
        case DictionaryId.Rollup(blockId) => boundary.rollups(blockId).dictionaryDigest
        case DictionaryId.Miner => boundary.minerDictionary.dictionaryDigest
      }
      val baseDictionary = dictionaryFrom(base, id)
      val replacement = dictionaryOverrides.get(id).filter(_.at.height <= boundary.cursor.height)
      val currentDictionary = committed.flatMap(dictionaryFrom(_, id))
      val shape = replacement.map(_.dictionary).orElse(baseDictionary)
        .orElse(currentDictionary).getOrElse(PlasmaDictionary.empty())
      val starting = replacement.map(value =>
        SnapshotDictionaryBase.Materialized(value.dictionary)).getOrElse {
        baseDictionary match {
          case Some(dictionary) if dictionary.materialized => SnapshotDictionaryBase.Materialized(dictionary)
          case Some(dictionary) => SnapshotDictionaryBase.Persisted(Hex.toHexString(dictionary.digest))
          case None => SnapshotDictionaryBase.Empty
        }
      }
      val transforms = prefix.iterator
        .filter(entry => replacement.forall(_.at.height < entry.cursor.height))
        .flatMap(_.transforms.iterator)
        .filter(_.dictionaryId == id).toVector
      id -> SnapshotDictionarySource(targetDigest, shape.flags, shape.parameters, starting, transforms)
    }.toMap
  }

  private def checkpointSaved(cursor: SyncCursor, token: String): Unit = checkpointInFlight match {
    case Some(pending) if pending.token == token && pending.boundary.cursor == cursor =>
      val compacted = for {
        active <- journal.toRight("transform journal disappeared while the checkpoint was saving")
        currentBoundary <-
          if (pending.cutEntries == 0 && active.baseCursor == cursor)
            journalBase.map(CommittedSyncMetadata.from)
              .toRight("checkpoint base disappeared while the checkpoint was saving")
          else if (pending.cutEntries > 0)
            active.entries.lift(pending.cutEntries - 1).filter(_.cursor == cursor).map(_.metadata)
              .toRight("canonical journal no longer contains the saved checkpoint boundary")
          else Left("canonical journal no longer contains the saved checkpoint boundary")
        _ <- Either.cond(currentBoundary == pending.boundary, (),
          "canonical state at the saved checkpoint cursor changed while the checkpoint was saving")
        rollupDictionaries = pending.boundary.rollups.map { case (id, metadata) =>
          val source = pending.sources(DictionaryId.Rollup(id))
          id -> DeferredDictionary(Hex.decode(metadata.dictionaryDigest), source.flags, source.parameters)
        }
        minerSource = pending.sources(DictionaryId.Miner)
        minerDictionary = DeferredDictionary(Hex.decode(
          pending.boundary.minerDictionary.dictionaryDigest), minerSource.flags, minerSource.parameters)
        base <- CommittedSyncMetadata.materialize(pending.boundary, rollupDictionaries, minerDictionary)
      } yield base -> TransformJournal(cursor, active.entries.drop(pending.cutEntries))

      compacted match {
        case Right((base, suffix)) =>
          journalBase = Some(base)
          journal = Some(suffix)
          dictionaryOverrides = dictionaryOverrides.filter { case (_, replacement) =>
            replacement.at.height > cursor.height
          }
          logger.info(s"Compacted ${pending.cutEntries} transform journal entry/entries into " +
            s"checkpoint ${cursor.blockId}@${cursor.height}; ${suffix.entries.size} remain volatile")
        case Left(reason) =>
          logger.warn(s"Saved checkpoint ${cursor.blockId}@${cursor.height}, but did not compact the " +
            s"current journal: $reason")
      }
      checkpointInFlight = None
      if (checkpointRequested) offerSnapshot(force = checkpointRequestedForce)
    case _ => () // stale completion from another actor incarnation or superseded chain
  }

  private def checkpointFailed(cursor: SyncCursor, token: String, reason: String): Unit =
    checkpointInFlight match {
      case Some(pending) if pending.token == token =>
        checkpointInFlight = None
        logger.error(s"Failed synchronization checkpoint ${cursor.blockId}@${cursor.height}: $reason. " +
          s"The volatile journal remains intact and restart will use an older retained checkpoint. " +
          s"Retrying after $checkpointRetryDelay.")
        checkpointRequested = true
        checkpointRequestedForce = checkpointRequestedForce || pending.forced
        if (checkpointRetry.isEmpty)
          checkpointRetry = Some(context.system.scheduler.scheduleOnce(checkpointRetryDelay,
            self, RetryCheckpoint)(context.dispatcher, self))
      case _ => ()
    }

  /** Test seams; production intentionally keeps these process-wide safety bounds non-configurable. */
  protected def maxMaterializedDictionaryCacheBytes: Long =
    SyncHandler.MaxMaterializedDictionaryCacheBytes
  protected def maxTransformJournalBytes: Long = SyncHandler.MaxTransformJournalBytes
  protected def checkpointRetryDelay: FiniteDuration = SyncHandler.CheckpointRetryDelay
  protected def materializationDeadline: FiniteDuration = SyncHandler.MaterializationDeadline

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
      if (state.cursor.height % syncConfig.snapshotInterval != 0) offerSnapshot(force = true)
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
      minerDictionaryMetadata = committed.map(_.minerTree.metadata),
      dataBoxToken = committed.flatMap(_.dataBoxToken),
      canonical = canonical,
      minerDictionary = dictionary,
      mempool = mempoolCapability))
  }
}

object SyncHandler {
  // Sized according to NISP weights
  private[synchronization] final val MaxMaterializedDictionaryCacheBytes = 384L * 1024L * 1024L
  private[synchronization] final val MaxTransformJournalBytes = 256L * 1024L * 1024L
  private[synchronization] final val CheckpointRetryDelay: FiniteDuration = 30.seconds

  /**
   * Longer than the persistence actor's own five-minute ownership, so its terminal reply wins
   * whenever one is coming. This only ends barriers nothing will ever answer.
   */
  private[synchronization] final val MaterializationDeadline: FiniteDuration = 6.minutes

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
