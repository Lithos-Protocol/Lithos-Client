package state.synchronization

import akka.actor.{Actor, ActorRef, Cancellable}
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import configs.{Contexts, NodeContext, SyncConfig}
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import state.messages.StateFrameMessages._
import state.messages.SyncMessages._
import state.messages.BlockInfo
import state.persistence.StateSnapshotActor.{RestoreLatest, SnapshotLoaded}

import javax.inject.{Inject, Named}
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

object StateFrame {
  private case object Tick

  private sealed trait PollResult
  private final case class BatchFetched(blocks: Seq[BlockInfo], tipHeight: Int) extends PollResult
  private final case class AtTip(tipHeight: Int) extends PollResult
  private final case class ForkDetected(cursor: SyncCursor, detail: String, heights: NodeHeights)
    extends PollResult
  /** The header chain still covers the cursor; only the index cannot serve it yet. */
  private final case class IndexBehind(cursor: SyncCursor, heights: NodeHeights) extends PollResult
  private final case class PollFinished(result: Try[PollResult])
  private final case class CommitFinished(block: BlockInfo, tipHeight: Int, result: Try[Any])
  private final case class StartupState(result: Try[Option[CommittedSyncState]])
  private final case class CursorsLoaded(result: Try[Seq[SyncCursor]], heights: NodeHeights)
  private final case class AncestorLocated(result: Try[Option[SyncCursor]], heights: NodeHeights)
  private final case class RollbackFinished(result: Try[Any], tipHeight: Int)
  private final case class ResetFinished(result: Try[Any])
  private final case class RecoveryPrepared(result: Try[Option[CommittedSyncState]])
  private final case class DictionaryRepaired(atCursor: SyncCursor,
                                              result: Try[Either[String, MinerDictionarySeed]])
  private final case class QuarantinesLoaded(result: Try[Any])
  private final case class RollupRepaired(rollupId: String,
                                         atCursor: SyncCursor,
                                         result: Try[RollupRepairResult])
  private final case class RollupRepairPublished(rollupId: String,
                                                 atCursor: SyncCursor,
                                                 result: Try[Any])
}

/**
 * Produces canonical blocks with one commit in flight.
 * Node I/O runs on the polling dispatcher; parent mismatches enter reorg recovery.
 */
class StateFrame @Inject()(config: Configuration,
                           nodeContext: NodeContext,
                           protocol: SyncProtocolContext,
                           @Named("sync-handler") syncHandler: ActorRef,
                           @Named("state-snapshot") snapshotActor: ActorRef)
  extends Actor with AutoSubscribable {

  import StateFrame._

  private implicit val timeout: Timeout = Timeout(30.seconds)
  private implicit val actorContext: ExecutionContext = context.dispatcher
  private val pollingContext: ExecutionContext =
    context.system.dispatchers.lookup(Contexts.key(Contexts.Polling))

  private val syncConfig = new SyncConfig(config)
  private val source = new CanonicalBlockSource(nodeContext.getNodeApi)
  private val quarantineAttempts = syncConfig.quarantineRepairAttempts
  private val quarantineCheckpoints = syncConfig.quarantineMaintenanceCheckpoints
  private val checkpointInterval = syncConfig.quarantineCheckpointIntervalBlocks
  private val batchBlocks = syncConfig.catchUpBatchBlocks
  private val retriesBeforeAlarm = syncConfig.retriesBeforeAlarm
  private val tipRevalidation = syncConfig.revalidationChecks
  private val repairInterval = syncConfig.dictionaryRepairInterval
  // Dictionary history is bootstrapped separately, so canonical block sync starts here.
  private val firstHeight = syncConfig.startHeight

  override val logger: Logger = LoggerFactory.getLogger("StateFrame")
  override var subscribers: Set[ActorRef] = Set.empty

  private var cursor = Option.empty[SyncCursor]
  private var nextHeight = firstHeight
  private var active = false
  private var inFlight = false
  // At the live tip rebuilds run beside polling. During catch-up, configured maintenance checkpoints
  // deliberately hold the producer so a long repair has one stable cursor at which it can install.
  private var repairing = false
  // A maintenance checkpoint keeps `inFlight` true and the cursor frozen until its repair reply.
  private var maintenanceTip = Option.empty[Int]
  private var committedSinceCheckpoint = 0
  // Fetched as a batch but committed one at a time in chain order.
  private var pending = Vector.empty[BlockInfo]
  // Consecutive failures at one height; moving to another height resets the alarm.
  private var stalledAt = Option.empty[Int]
  private var stalledAttempts = 0
  private var stalledSince = 0L
  // Written on the polling dispatcher, read there too; safe by the pipe-to-self between the two.
  private var lastTipCheck = 0L
  // Last attempt to rebuild a faulted Miner Dictionary.
  private var lastDictionaryRepair = 0L

  private val ticker: Cancellable =
    context.system.scheduler.scheduleWithFixedDelay(
      syncConfig.pollInterval, syncConfig.pollInterval, self, Tick)

  override def postStop(): Unit = ticker.cancel()

  /**
   * Nothing here may throw. A restart resets `active`, and the one message that sets it is scheduled,
   * so an escaped exception would leave this producer idle with a live ticker and no way back.
   */
  override def receive: Receive = handleSubscriptions orElse {
    case message if handlers.isDefinedAt(message) =>
      try handlers(message)
      catch {
        case NonFatal(ex) =>
          failPoll(s"Unhandled failure processing ${message.getClass.getSimpleName}", ex)
      }
  }

  private val handlers: Receive = {
    case StartSynchronization if !active =>
      active = true
      inFlight = true
      establishSeed()
        .map(state => StartupState(Success(state)))
        .recover { case ex => StartupState(Failure(ex)) }
        .pipeTo(self)

    case StartSynchronization => self ! Tick
    case CheckBlock => self ! Tick
    case Tick =>
      if (active && !repairing) beginRepairs()
      if (active && !inFlight) beginPoll()

    case StartupState(Success(saved)) =>
      saved.foreach { state =>
        cursor = Some(state.cursor)
          nextHeight = state.cursor.height + 1
      }
      inFlight = false
      self ! Tick

    case StartupState(Failure(ex)) => failPoll("Could not read startup sync state", ex)

    case PollFinished(Success(BatchFetched(blocks, tip))) =>
      syncHandler ! BeginCatchUp(tip)
      pending = blocks.toVector
      if (pending.isEmpty)
        failPoll("Canonical block poll returned no blocks for a non-empty range",
          new IllegalStateException(s"expected at least one block from height $nextHeight"))
      else commitNext(tip)

    case PollFinished(Success(AtTip(_))) =>
      inFlight = false
      cursor match {
        case Some(_) => syncHandler ! MarkReady
        // Unreachable: a seed establishes the cursor before the first poll.
        case None => failPoll("Reached the tip with no committed cursor",
          new IllegalStateException("canonical polling ran before synchronization was seeded"))
      }

    // The chain is intact and only the index trails the cursor, so waiting is the correct option.
    case PollFinished(Success(IndexBehind(at, heights))) =>
      inFlight = false
      val reason = s"node index is at height ${heights.usable}, below the committed height " +
        s"${at.height} (headers reach ${heights.chain}); waiting for indexing to catch up"
      logger.warn(reason)
      recordStall(reason)
      syncHandler ! MarkStale(reason)

    case PollFinished(Success(ForkDetected(at, detail, heights))) =>
      logger.warn(s"Canonical chain diverged at height ${at.height}: $detail")
      syncHandler ! MarkStale(s"Canonical chain diverged at height ${at.height}: $detail")
      (syncHandler ? GetRetainedCursors)
        .map {
          case RetainedCursors(values) => CursorsLoaded(Success(values), heights)
          case other => CursorsLoaded(Failure(new IllegalStateException(
            s"Unexpected retained-cursor response $other")), heights)
        }
        .recover { case ex => CursorsLoaded(Failure(ex), heights) }
        .pipeTo(self)

    case PollFinished(Failure(ex)) => failPoll("Canonical block poll failed", ex)

    case CommitFinished(block, tip, Success(committedBlock: BlockCommitted)) =>
      cursor = Some(committedBlock.cursor)
      nextHeight = committedBlock.cursor.height + 1
      committedSinceCheckpoint += 1
      clearStall()
      subscribers.foreach(_ ! NewBlock(block))
      if (maintenanceCheckpointDue(committedBlock.cursor, tip)) beginMaintenanceCheckpoint(tip)
      else continueCanonical(tip)

    // The state owner lost its state, so re-establish the seed rather than retrying blocks it
    // cannot place. Retrying would stall at this height until the process restarted.
    case CommitFinished(_, _, Success(SyncUnseeded)) =>
      pending = Vector.empty
      cursor = None
      nextHeight = firstHeight
      logger.error("Synchronization holds no committed state; re-establishing it before continuing")
      establishSeed()
        .map(state => StartupState(Success(state)))
        .recover { case ex => StartupState(Failure(ex)) }
        .pipeTo(self)

    // Discard later blocks and derive the next batch from committed state.
    case CommitFinished(block, _, Success(rejected: BlockRejected)) =>
      inFlight = false
      pending = Vector.empty
      logger.error(s"Synchronization rejected block ${block.id}: ${rejected.reason}")
      recordStall(rejected.reason)
      syncHandler ! MarkStale(rejected.reason)

    case CommitFinished(block, _, Success(other)) =>
      failPoll(s"Unexpected block commit response for ${block.id}",
        new IllegalStateException(other.toString))

    case CommitFinished(block, _, Failure(ex)) => failPoll(s"Block commit ask failed for ${block.id}", ex)

    case CursorsLoaded(Success(cursors), heights) =>
      Future(source.commonAncestor(cursors, heights.chain))(pollingContext)
        .map(result => AncestorLocated(result, heights))
        .recover { case ex => AncestorLocated(Failure(ex), heights) }
        .pipeTo(self)

    case CursorsLoaded(Failure(ex), _) => failPoll("Could not read retained rollback cursors", ex)

    case AncestorLocated(Success(Some(ancestor)), heights) =>
      (syncHandler ? RollbackTo(ancestor.blockId))
        .map(result => RollbackFinished(Success(result), heights.usable))
        .recover { case ex => RollbackFinished(Failure(ex), heights.usable) }
        .pipeTo(self)

    case AncestorLocated(Success(None), _) => beginSnapshotRecovery()
    case AncestorLocated(Failure(ex), _) => failPoll("Common-ancestor search failed", ex)

    case RollbackFinished(Success(done: RollbackCompleted), tip) =>
      cursor = Some(done.cursor)
      nextHeight = done.cursor.height + 1
      inFlight = false
      syncHandler ! BeginCatchUp(tip)
      self ! Tick

    // Persisted cursors can locate an ancestor whose exact state is no longer in memory.
    case RollbackFinished(Success(_: RollbackRejected), _) => beginSnapshotRecovery()
    case RollbackFinished(Success(other), _) =>
      failPoll("Unexpected rollback response", new IllegalStateException(other.toString))
    case RollbackFinished(Failure(ex), _) => failPoll("Rollback ask failed", ex)

    // A reset discards dictionary state, and catch-up starts above the dictionary genesis, so ordinary
    // polling could never rediscover it. Re-seed before resuming rather than committing against an
    // empty dictionary the client would treat as correct.
    case ResetFinished(Success(SyncStateReset)) =>
      cursor = None
      nextHeight = firstHeight
      logger.warn("Full rescan cleared synchronization state; rebuilding the Miner Dictionary before " +
        "canonical catch-up resumes")
      seedFromMinerDictionary()
        .map(state => StartupState(Success(state)))
        .recover { case ex => StartupState(Failure(ex)) }
        .pipeTo(self)
    case ResetFinished(Success(other)) =>
      failPoll("Unexpected state reset response", new IllegalStateException(other.toString))
    case ResetFinished(Failure(ex)) => failPoll("State reset failed", ex)

    case RecoveryPrepared(Success(Some(state))) =>
      cursor = Some(state.cursor)
      nextHeight = state.cursor.height + 1
      inFlight = false
      self ! Tick
    case RecoveryPrepared(Success(None)) => beginFullRescan()
    case RecoveryPrepared(Failure(ex)) => failPoll("Snapshot reorg recovery failed", ex)

    case DictionaryRepaired(at, Success(Right(seed))) =>
      (syncHandler ? RepairMinerDictionary(at, seed.minerTree, seed.dataBoxToken))
        .map(result => QuarantinesLoaded(Success(result)))
        .recover { case ex => QuarantinesLoaded(Failure(ex)) }
        .pipeTo(self)

    case DictionaryRepaired(_, Success(Left(reason))) =>
      repairing = false
      logger.warn(s"Miner Dictionary bootstrap retry failed: $reason. Registration and commitment " +
        s"fraud proofs stay unavailable; retrying in $repairInterval")

    case DictionaryRepaired(_, Failure(ex)) =>
      repairing = false
      logger.warn(s"Miner Dictionary bootstrap retry could not run: ${message(ex)}", ex)

    // Names reason for Miner Dictionary failure
    case QuarantinesLoaded(Success(rejected: BlockRejected)) =>
      val operation = if (maintenanceTip.isDefined) "Quarantine maintenance checkpoint"
      else "Rebuilt Miner Dictionary"
      logger.warn(s"$operation was not accepted: ${rejected.reason}")
      completeRepairCycle()

    case QuarantinesLoaded(Success(RepairableQuarantines(faults, at))) =>
      faults.headOption match {
        case None => completeRepairCycle()
        case Some(fault) =>
          logger.info(s"Rebuilding quarantined rollup ${fault.rollupId} from height " +
            s"${fault.genesisHeight}, attempt ${fault.attempts + 1} of $quarantineAttempts")
          Future(new RollupRepair(nodeContext.getNodeApi, protocol,
            syncConfig.quarantineMaxTransforms).run(fault, at.height))(pollingContext)
            .map(result => RollupRepaired(fault.rollupId, at, Success(result)))
            .recover { case ex => RollupRepaired(fault.rollupId, at, Failure(ex)) }
            .pipeTo(self)
      }

    case QuarantinesLoaded(Success(_)) => completeRepairCycle()

    case QuarantinesLoaded(Failure(ex)) =>
      logger.warn(s"Could not read repairable quarantines: ${message(ex)}", ex)
      completeRepairCycle()

    case RollupRepaired(rollupId, at, Success(outcome)) =>
      publishRollupRepair(rollupId, at, outcome)

    case RollupRepaired(rollupId, at, Failure(ex)) =>
      publishRollupRepair(rollupId, at,
        RollupRepairResult.Failed(s"rebuild could not run: ${message(ex)}", retryable = true))

    case RollupRepairPublished(_, _, Success(_: BlockCommitted)) =>
      completeRepairCycle()

    case RollupRepairPublished(rollupId, at, Success(rejected: BlockRejected)) =>
      logger.warn(s"Repair result for rollup $rollupId rebuilt at ${at.blockId}@${at.height} was not " +
        s"installed: ${rejected.reason}")
      completeRepairCycle()

    case RollupRepairPublished(rollupId, at, Success(other)) =>
      logger.warn(s"Unexpected repair publication response for rollup $rollupId rebuilt at " +
        s"${at.blockId}@${at.height}: $other")
      completeRepairCycle()

    case RollupRepairPublished(rollupId, at, Failure(ex)) =>
      logger.warn(s"Could not publish repair result for rollup $rollupId rebuilt at " +
        s"${at.blockId}@${at.height}: ${message(ex)}", ex)
      completeRepairCycle()

    // MarkReady may reply to ask-based callers; this producer needs no response.
    case _: SyncStatus => ()
    case _: BlockCommitted => ()
  }

  /** Continue the retained batch, or return to polling after the last fetched block. */
  private def continueCanonical(tip: Int): Unit =
    if (pending.nonEmpty) commitNext(tip)
    else {
      inFlight = false
      if (cursor.exists(_.height >= tip)) {
        committedSinceCheckpoint = 0
        syncHandler ! MarkReady
      } else self ! Tick
    }

  private def maintenanceCheckpointDue(at: SyncCursor, tip: Int): Boolean =
    quarantineCheckpoints && quarantineAttempts > 0 && at.height < tip &&
      committedSinceCheckpoint >= checkpointInterval

  /**
   * Keep the canonical producer occupied while one repair is selected, rebuilt, and published.
   * Because no next commit can start, the exact cursor guard can accept a long repair during catch-up.
   */
  private def beginMaintenanceCheckpoint(tip: Int): Unit = {
    maintenanceTip = Some(tip)
    repairing = true
    (syncHandler ? GetRepairableQuarantines)
      .map(result => QuarantinesLoaded(Success(result)))
      .recover { case ex => QuarantinesLoaded(Failure(ex)) }
      .pipeTo(self)
  }

  private def publishRollupRepair(rollupId: String,
                                  at: SyncCursor,
                                  outcome: RollupRepairResult): Unit =
    (syncHandler ? RepairRollup(rollupId, at, outcome))
      .map(result => RollupRepairPublished(rollupId, at, Success(result)))
      .recover { case ex => RollupRepairPublished(rollupId, at, Failure(ex)) }
      .pipeTo(self)

  /** Resume a frozen catch-up batch only after the repair owner has accepted or refused the result. */
  private def completeRepairCycle(): Unit = {
    repairing = false
    maintenanceTip.foreach { tip =>
      maintenanceTip = None
      committedSinceCheckpoint = 0
      continueCanonical(tip)
    }
  }

  private def beginPoll(): Unit = {
    inFlight = true
    Future(pollCanonical())(pollingContext)
      .map(result => PollFinished(result))
      .recover { case ex => PollFinished(Failure(ex)) }
      .pipeTo(self)
  }

  /** Hand the next fetched block to the state owner and wait for its commit before the one after. */
  private def commitNext(tip: Int): Unit = pending.headOption match {
    case None =>
      inFlight = false
      self ! Tick
    case Some(block) =>
      pending = pending.tail
      inFlight = true
      (syncHandler ? ApplyBlock(block))
        .map(result => CommitFinished(block, tip, Success(result)))
        .recover { case ex => CommitFinished(block, tip, Failure(ex)) }
        .pipeTo(self)
  }

  /**
   * Get initial source for seed state. Checks committed state first in case
   * this is an actor restart.
   */
  private def establishSeed(): Future[Option[CommittedSyncState]] =
    (syncHandler ? GetCommittedState).flatMap {
      // The owner outlived this producer, so its state is ahead of any snapshot and restoring one
      // would discard everything committed since.
      case CommittedState(state) =>
        logger.info(s"Resuming from state the owner still holds at ${state.cursor.blockId}@" +
          s"${state.cursor.height} rather than restoring a snapshot")
        Future.successful(Some(state))
      case _ => restoreSnapshot().flatMap {
        case Some(state) => Future.successful(Some(state))
        case None => seedFromMinerDictionary()
      }
    }

  /**
   * The newest canonical snapshot, or `None` when none is. Used alone by reorg recovery, where `None`
   * indicates a full rescan rather than to seed.
   */
  private def restoreSnapshot(): Future[Option[CommittedSyncState]] =
    (snapshotActor ? RestoreLatest).flatMap {
      case SnapshotLoaded(Some(snapshot), _) =>
        (syncHandler ? RestoreCommittedState(snapshot.state, snapshot.recentCursors)).map {
          case _: BlockCommitted => Some(snapshot.state)
          case rejected: BlockRejected => throw new IllegalStateException(rejected.reason)
          case other => throw new IllegalStateException(s"Unexpected snapshot restore response $other")
        }
      case SnapshotLoaded(None, warning) =>
        warning.foreach(logger.warn)
        Future.successful(None)
      case other => Future.failed(new IllegalStateException(s"Unexpected snapshot response $other"))
    }

  /**
   * Seeds dictionary state from its indexed spend chain.
   * A bootstrap failure is stored on the seed while block and rollup sync remain available.
   */
  private def seedFromMinerDictionary(): Future[Option[CommittedSyncState]] =
    Future {
      if (!syncConfig.minerDictionaryBootstrap)
        Left("sync.minerDictionary.bootstrap is disabled, so the dictionary is not tracked")
      else {
        logger.info(s"Rebuilding Miner Dictionary state up to height ${firstHeight - 1}")
        new MinerDictionaryBootstrap(nodeContext.getNodeApi, protocol,
          syncConfig.minerDictionaryMaxTransforms).run(firstHeight - 1)
      }
    }(pollingContext).flatMap { rebuilt =>
      rebuilt.left.foreach { reason =>
        // Disabling it is a configuration choice, not a failure to chase.
        if (!syncConfig.minerDictionaryBootstrap) logger.info(reason)
        else logger.error(s"Miner Dictionary bootstrap failed: $reason. Mining and submission " +
          "continue; registering a new miner and raising commitment fraud proofs do not.")
      }
      seedCursor().flatMap { cursor =>
        val seed = CommittedSyncState(cursor, 0L, Map.empty, Map.empty, Map.empty,
          rebuilt.map(_.minerTree).getOrElse(lfsm.states.MinerTree.initialState),
          rebuilt.toOption.flatMap(_.dataBoxToken), rebuilt.left.toOption)
        (syncHandler ? RestoreCommittedState(seed, Vector(cursor))).map {
          case _: BlockCommitted => Some(seed)
          case rejected: BlockRejected => throw new IllegalStateException(rejected.reason)
          case other => throw new IllegalStateException(s"Unexpected seed restore response $other")
        }
      }
    }

  /** The real header below the start height, so the seed can be snapshotted and revalidated later. */
  private def seedCursor(): Future[SyncCursor] =
    Future(source.headerAt(firstHeight - 1))(pollingContext).flatMap {
      case Success(header) => Future.successful(SyncCursor(header.height, header.id, header.parentId))
      case Failure(ex) => Future.failed(ex)
    }

  private def beginSnapshotRecovery(): Unit = {
    logger.warn("Reorganization exceeds the in-memory window; checking retained snapshots")
    restoreSnapshot()
      .map(state => RecoveryPrepared(Success(state)))
      .recover { case ex => RecoveryPrepared(Failure(ex)) }
      .pipeTo(self)
  }

  /**
   * Fetches the committed header, successor headers, and matching blocks as one canonical batch.
   *
   * Both indexed and node heights used to determine synchronicity and the next action.
   */
  private def pollCanonical(): Try[PollResult] =
    source.heights.flatMap { heights =>
      cursor match {
        case Some(at) if heights.chain < at.height =>
          Success(ForkDetected(at,
            s"node header height ${heights.chain} is below the committed height ${at.height}", heights))
        case Some(at) if heights.usable < at.height => Success(IndexBehind(at, heights))
        case Some(at) if nextHeight > heights.usable => revalidateTip(at, heights.usable)
        case _ if nextHeight > heights.usable => Success(AtTip(heights.usable))
        case _ => fetchBatch(heights)
      }
    }

  private def revalidateTip(at: SyncCursor, tip: Int): Try[PollResult] =
    if (System.currentTimeMillis() - lastTipCheck < tipRevalidation.toMillis) Success(AtTip(tip))
    else source.headerAt(at.height).map { canonical =>
      lastTipCheck = System.currentTimeMillis()
      if (canonical.id != at.blockId)
        ForkDetected(at, s"header changed from ${at.blockId} to ${canonical.id}",
          NodeHeights(math.max(tip, at.height), tip))
      else AtTip(tip)
    }

  private def fetchBatch(heights: NodeHeights): Try[PollResult] = {
    val tip = heights.usable
    val last = math.min(nextHeight + batchBlocks - 1, tip)
    cursor match {
      case Some(at) =>
        source.headerSlice(at.height, last).flatMap { headers =>
          headers.headOption match {
            case Some(committedHeader) if committedHeader.id != at.blockId =>
              Success(ForkDetected(at,
                s"header changed from ${at.blockId} to ${committedHeader.id}", heights))
            case Some(_) => source.blocksFor(headers.tail).map(BatchFetched(_, tip))
            case None => Failure(new NoSuchElementException(
              s"Canonical chain slice returned no header at committed height ${at.height}"))
          }
        }
      // Unreachable: the producer never polls before a seed establishes the cursor.
      // Reaching it would mean committing blocks onto no base.
      case None => Failure(new IllegalStateException(
        "Canonical polling began before synchronization was seeded"))
    }
  }

  /** Whether a faulted dictionary is due another bootstrap attempt. */
  private def dictionaryRepairDue: Boolean =
    syncConfig.minerDictionaryBootstrap && cursor.isDefined &&
      utils.Globals.syncView.canonical.available &&
      !utils.Globals.syncView.minerDictionary.available &&
      System.currentTimeMillis() - lastDictionaryRepair >= repairInterval.toMillis

  /**
   * Rebuilds faulted protocol state beside block production. The dictionary comes first because it
   * gates registration; a quarantined rollup only costs its own payout.
   */
  private def beginRepairs(): Unit =
    if (dictionaryRepairDue) beginDictionaryRepair()
    else if (quarantineAttempts > 0 && cursor.isDefined &&
      utils.Globals.syncView.canonical.available) askForQuarantines()

  private def beginDictionaryRepair(): Unit = {
    repairing = true
    lastDictionaryRepair = System.currentTimeMillis()
    val at = cursor.get
    logger.info(s"Retrying the Miner Dictionary bootstrap at ${at.blockId}@${at.height}")
    Future(Try(new MinerDictionaryBootstrap(nodeContext.getNodeApi, protocol,
      syncConfig.minerDictionaryMaxTransforms).run(at.height)))(pollingContext)
      .map(result => DictionaryRepaired(at, result))
      .recover { case ex => DictionaryRepaired(at, Failure(ex)) }
      .pipeTo(self)
  }

  private def askForQuarantines(): Unit = {
    repairing = true
    (syncHandler ? GetRepairableQuarantines)
      .map(result => QuarantinesLoaded(Success(result)))
      .recover { case ex => QuarantinesLoaded(Failure(ex)) }
      .pipeTo(self)
  }

  private def beginFullRescan(): Unit = {
    logger.warn(s"Reorganization exceeds the retained window; rescanning from height $firstHeight")
    (syncHandler ? ResetSyncState)
      .map(result => ResetFinished(Success(result)))
      .recover { case ex => ResetFinished(Failure(ex)) }
      .pipeTo(self)
  }

  private def message(ex: Throwable): String =
    Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName)

  private def failPoll(message: String, ex: Throwable): Unit = {
    inFlight = false
    // Discard blocks fetched from a chain that is no longer being followed.
    pending = Vector.empty
    val reason = s"$message: ${Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName)}"
    logger.error(reason, ex)
    recordStall(reason)
    syncHandler ! MarkStale(reason)
  }

  /**
   * Reports persistent failures at one cursor while retries continue.
   * Keyed on the committed height, so alternating failure shapes cannot reset the count forever.
   */
  private def recordStall(reason: String): Unit = {
    val height = cursor.map(_.height).getOrElse(nextHeight)
    if (!stalledAt.contains(height)) {
      stalledAt = Some(height)
      stalledAttempts = 0
      stalledSince = System.currentTimeMillis()
    }
    stalledAttempts += 1
    if (stalledAttempts >= retriesBeforeAlarm && stalledAttempts % retriesBeforeAlarm == 0) {
      val stalledFor = (System.currentTimeMillis() - stalledSince) / 1000
      logger.error(s"Synchronization has not advanced past height $height in $stalledAttempts " +
        s"attempts over ${stalledFor}s. Submissions, fraud proofs and payouts are stopped until it " +
        s"does. Last failure: $reason. Retries continue; check that the node is reachable, caught up, " +
        "and finished indexing.")
    }
  }

  private def clearStall(): Unit = {
    stalledAt = None
    stalledAttempts = 0
  }
}
