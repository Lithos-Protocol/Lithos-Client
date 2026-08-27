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
import scala.util.{Failure, Success, Try}

object StateFrame {
  private case object Tick

  private sealed trait PollResult
  private final case class BatchFetched(blocks: Seq[BlockInfo], tipHeight: Int) extends PollResult
  private final case class AtTip(tipHeight: Int) extends PollResult
  private final case class ForkDetected(cursor: SyncCursor, canonicalId: String, tipHeight: Int) extends PollResult
  private final case class PollFinished(result: Try[PollResult])
  private final case class CommitFinished(block: BlockInfo, tipHeight: Int, result: Try[Any])
  private final case class StartupState(result: Try[Option[CommittedSyncState]])
  private final case class CursorsLoaded(result: Try[Seq[SyncCursor]], tipHeight: Int)
  private final case class AncestorLocated(result: Try[Option[SyncCursor]], tipHeight: Int)
  private final case class RollbackFinished(result: Try[Any], tipHeight: Int)
  private final case class ResetFinished(result: Try[Any])
  private final case class RecoveryPrepared(result: Try[Option[CommittedSyncState]])
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
  extends Actor with Subscribable {

  import StateFrame._

  private implicit val timeout: Timeout = Timeout(30.seconds)
  private implicit val actorContext: ExecutionContext = context.dispatcher
  private val pollingContext: ExecutionContext =
    context.system.dispatchers.lookup(Contexts.key(Contexts.Polling))

  private val syncConfig = new SyncConfig(config)
  private val source = new CanonicalBlockSource(nodeContext.getNodeApi)
  private val batchBlocks = syncConfig.catchUpBatchBlocks
  private val retriesBeforeAlarm = syncConfig.retriesBeforeAlarm
  // Dictionary history is bootstrapped separately, so canonical block sync starts here.
  private val firstHeight = syncConfig.startHeight

  override val logger: Logger = LoggerFactory.getLogger("StateFrame")
  override var currentHeight: Int = firstHeight - 1
  override var subscribers: Set[ActorRef] = Set.empty

  private var cursor = Option.empty[SyncCursor]
  private var nextHeight = firstHeight
  private var active = false
  private var inFlight = false
  // Fetched as a batch but committed one at a time in chain order.
  private var pending = Vector.empty[BlockInfo]
  // Consecutive failures at one height; moving to another height resets the alarm.
  private var stalledAt = Option.empty[Int]
  private var stalledAttempts = 0
  private var stalledSince = 0L

  private val ticker: Cancellable =
    context.system.scheduler.scheduleWithFixedDelay(5.seconds, 5.seconds, self, Tick)

  override def postStop(): Unit = ticker.cancel()

  override def receive: Receive = handleSubscriptions orElse {
    case StartSynchronization if !active =>
      active = true
      inFlight = true
      prepareStartup(allowExisting = true)
        .map(state => StartupState(Success(state)))
        .recover { case ex => StartupState(Failure(ex)) }
        .pipeTo(self)

    case StartSynchronization => self ! Tick
    case CheckBlock => self ! Tick
    case Tick if active && !inFlight => beginPoll()
    case Tick => ()

    case StartupState(Success(saved)) =>
      saved.foreach { state =>
        cursor = Some(state.cursor)
        currentHeight = state.cursor.height
        nextHeight = state.cursor.height + 1
      }
      inFlight = false
      self ! Tick

    case StartupState(Failure(ex)) => failPoll("Could not read startup sync state", ex)

    case PollFinished(Success(BatchFetched(blocks, tip))) =>
      syncHandler ! BeginCatchUp(tip)
      pending = blocks.toVector
      commitNext(tip)

    case PollFinished(Success(AtTip(_))) =>
      inFlight = false
      cursor match {
        case Some(_) => syncHandler ! MarkReady
        case None => logger.warn(s"Node tip is below synchronization start height $firstHeight")
      }

    case PollFinished(Success(ForkDetected(at, canonicalId, tip))) =>
      logger.warn(s"Canonical header at ${at.height} changed from ${at.blockId} to $canonicalId")
      syncHandler ! MarkStale(s"Canonical header changed at height ${at.height}")
      (syncHandler ? GetRetainedCursors)
        .map {
          case RetainedCursors(values) => CursorsLoaded(Success(values), tip)
          case other => CursorsLoaded(Failure(new IllegalStateException(
            s"Unexpected retained-cursor response $other")), tip)
        }
        .recover { case ex => CursorsLoaded(Failure(ex), tip) }
        .pipeTo(self)

    case PollFinished(Failure(ex)) => failPoll("Canonical block poll failed", ex)

    case CommitFinished(block, tip, Success(committedBlock: BlockCommitted)) =>
      cursor = Some(committedBlock.cursor)
      currentHeight = committedBlock.cursor.height
      nextHeight = currentHeight + 1
      clearStall()
      subscribers.foreach(_ ! NewBlock(block))
      if (pending.nonEmpty) commitNext(tip)
      else {
        inFlight = false
        if (currentHeight >= tip) syncHandler ! MarkReady else self ! Tick
      }

    // Discard later blocks and derive the next batch from committed state.
    case CommitFinished(block, _, Success(rejected: BlockRejected)) =>
      inFlight = false
      pending = Vector.empty
      logger.error(s"Synchronization rejected block ${block.id}: ${rejected.reason}")
      recordStall(block.height, rejected.reason)
      syncHandler ! MarkStale(rejected.reason)

    case CommitFinished(block, _, Success(other)) =>
      failPoll(s"Unexpected block commit response for ${block.id}",
        new IllegalStateException(other.toString))

    case CommitFinished(block, _, Failure(ex)) => failPoll(s"Block commit ask failed for ${block.id}", ex)

    case CursorsLoaded(Success(cursors), tip) =>
      Future(source.commonAncestor(cursors))(pollingContext)
        .map(result => AncestorLocated(result, tip))
        .recover { case ex => AncestorLocated(Failure(ex), tip) }
        .pipeTo(self)

    case CursorsLoaded(Failure(ex), _) => failPoll("Could not read retained rollback cursors", ex)

    case AncestorLocated(Success(Some(ancestor)), tip) =>
      (syncHandler ? RollbackTo(ancestor.blockId))
        .map(result => RollbackFinished(Success(result), tip))
        .recover { case ex => RollbackFinished(Failure(ex), tip) }
        .pipeTo(self)

    case AncestorLocated(Success(None), _) => beginSnapshotRecovery()
    case AncestorLocated(Failure(ex), _) => failPoll("Common-ancestor search failed", ex)

    case RollbackFinished(Success(done: RollbackCompleted), tip) =>
      cursor = Some(done.cursor)
      currentHeight = done.cursor.height
      nextHeight = currentHeight + 1
      inFlight = false
      syncHandler ! BeginCatchUp(tip)
      self ! Tick

    // Persisted cursors can locate an ancestor whose exact state is no longer in memory.
    case RollbackFinished(Success(_: RollbackRejected), _) => beginSnapshotRecovery()
    case RollbackFinished(Success(other), _) =>
      failPoll("Unexpected rollback response", new IllegalStateException(other.toString))
    case RollbackFinished(Failure(ex), _) => failPoll("Rollback ask failed", ex)

    case ResetFinished(Success(SyncStateReset)) =>
      cursor = None
      currentHeight = firstHeight - 1
      nextHeight = firstHeight
      inFlight = false
      self ! Tick
    case ResetFinished(Success(other)) =>
      failPoll("Unexpected state reset response", new IllegalStateException(other.toString))
    case ResetFinished(Failure(ex)) => failPoll("State reset failed", ex)

    case RecoveryPrepared(Success(Some(state))) =>
      cursor = Some(state.cursor)
      currentHeight = state.cursor.height
      nextHeight = currentHeight + 1
      inFlight = false
      self ! Tick
    case RecoveryPrepared(Success(None)) => beginFullRescan()
    case RecoveryPrepared(Failure(ex)) => failPoll("Snapshot reorg recovery failed", ex)

    // MarkReady may reply to ask-based callers; this producer needs no response.
    case _: SyncStatus => ()
  }

  private def beginPoll(): Unit = {
    inFlight = true
    Future(pollCanonical())(pollingContext)
      .map(result => PollFinished(result))
      .recover { case ex => PollFinished(Failure(ex)) }
      .pipeTo(self)
  }

  /** Hand the next fetched block to the state owner and wait for its commit before the one after. */
  private def commitNext(tip: Int): Unit = {
    val block = pending.head
    pending = pending.tail
    inFlight = true
    (syncHandler ? ApplyBlock(block))
      .map(result => CommitFinished(block, tip, Success(result)))
      .recover { case ex => CommitFinished(block, tip, Failure(ex)) }
      .pipeTo(self)
  }

  private def prepareStartup(allowExisting: Boolean): Future[Option[CommittedSyncState]] =
    (snapshotActor ? RestoreLatest).flatMap {
      case SnapshotLoaded(Some(snapshot), _) =>
        (syncHandler ? RestoreCommittedState(snapshot.state, snapshot.recentCursors)).map {
          case _: BlockCommitted => Some(snapshot.state)
          case rejected: BlockRejected => throw new IllegalStateException(rejected.reason)
          case other => throw new IllegalStateException(s"Unexpected snapshot restore response $other")
        }
      case SnapshotLoaded(None, warning) =>
        warning.foreach(logger.warn)
        if (allowExisting)
          (syncHandler ? GetCommittedState).flatMap {
            case CommittedState(state) => Future.successful(Some(state))
            case _ => seedFromMinerDictionary()
          }
        else Future.successful(None)
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
        logger.error(s"Miner Dictionary bootstrap failed: $reason. Mining and submission continue; " +
          "registering a new miner and raising commitment fraud proofs do not.")
      }
      seedCursor().flatMap { cursor =>
        val seed = CommittedSyncState(cursor, 0L, Map.empty, Map.empty,
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
    prepareStartup(allowExisting = false)
      .map(state => RecoveryPrepared(Success(state)))
      .recover { case ex => RecoveryPrepared(Failure(ex)) }
      .pipeTo(self)
  }

  /** Fetches the committed header, successor headers, and matching blocks as one canonical batch. */
  private def pollCanonical(): Try[PollResult] =
    source.tipHeight.flatMap { tip =>
      if (nextHeight > tip) Success(AtTip(tip))
      else {
        val last = math.min(nextHeight + batchBlocks - 1, tip)
        cursor match {
          case Some(at) =>
            source.headerSlice(at.height, last).flatMap { headers =>
              headers.headOption match {
                case Some(committedHeader) if committedHeader.id != at.blockId =>
                  Success(ForkDetected(at, committedHeader.id, tip))
                case Some(_) => source.blocksFor(headers.tail).map(BatchFetched(_, tip))
                case None => Failure(new NoSuchElementException(
                  s"Canonical chain slice returned no header at committed height ${at.height}"))
              }
            }
          case None =>
            source.headerSlice(nextHeight, last).flatMap(headers =>
              source.blocksFor(headers).map(BatchFetched(_, tip)))
        }
      }
    }

  private def beginFullRescan(): Unit = {
    logger.warn(s"Reorganization exceeds the retained window; rescanning from height $firstHeight")
    (syncHandler ? ResetSyncState)
      .map(result => ResetFinished(Success(result)))
      .recover { case ex => ResetFinished(Failure(ex)) }
      .pipeTo(self)
  }

  private def failPoll(message: String, ex: Throwable): Unit = {
    inFlight = false
    // Discard blocks fetched from a chain that is no longer being followed.
    pending = Vector.empty
    val reason = s"$message: ${Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName)}"
    logger.error(reason, ex)
    recordStall(nextHeight, reason)
    syncHandler ! MarkStale(reason)
  }

  /** Reports persistent failures at one height while retries continue. */
  private def recordStall(height: Int, reason: String): Unit = {
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
