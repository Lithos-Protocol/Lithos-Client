package state.persistence

import akka.actor.{Actor, ActorRef}
import akka.pattern.pipe
import configs.{Contexts, NodeContext, SyncConfig}
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import state.messages.SyncMessages.SyncCursor
import state.synchronization.{CanonicalBlockSource, CommittedSyncState, SyncProtocolContext}
import storage.LevelDbKeyValueStore

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object StateSnapshotActor {
  case object RestoreLatest
  final case class SnapshotLoaded(snapshot: Option[PersistedSyncState], warning: Option[String] = None)
  final case class SnapshotCandidate(state: CommittedSyncState,
                                     recentCursors: Vector[SyncCursor],
                                     force: Boolean = false)

  private final case class ValidationFinished(requester: ActorRef,
                                              result: Try[Either[String, PersistedSyncState]])
  private final case class SaveFinished(state: CommittedSyncState, result: Either[SnapshotError, Unit])
}

/** Persists snapshots off the actor mailbox and coalesces queued writes. */
class StateSnapshotActor @Inject()(config: Configuration,
                                   nodeContext: NodeContext,
                                   protocol: SyncProtocolContext) extends Actor {
  import StateSnapshotActor._

  private implicit val actorContext: ExecutionContext = context.dispatcher
  private val pollingContext = context.system.dispatchers.lookup(Contexts.key(Contexts.Polling))
  private val logger: Logger = LoggerFactory.getLogger("StateSnapshotActor")
  private val syncConfig = new SyncConfig(config)
  private val source = new CanonicalBlockSource(nodeContext.getNodeApi)
  private val snapshotIdentity = StateSnapshotIdentity(protocol)
  private val snapshotStore =
    if (syncConfig.snapshotsEnabled)
      Some(new LevelDbStateSnapshotStore(
        LevelDbKeyValueStore.openOrThrow(syncConfig.storagePath),
        syncConfig.manifestDepth,
        syncConfig.snapshotRetention,
        snapshotIdentity))
    else None

  private var writing = false
  private var pending = Option.empty[PersistedSyncState]

  override def postStop(): Unit = snapshotStore.foreach { store =>
    store.close().left.foreach(error => logger.error(error.message))
  }

  override def receive: Receive = {
    case RestoreLatest => restore(sender())

    case SnapshotCandidate(state, cursors, force) if snapshotStore.isDefined &&
      (force || state.cursor.height % syncConfig.snapshotInterval == 0) =>
      val candidate = PersistedSyncState(state, cursors)
      if (writing) pending = Some(candidate)
      else save(candidate)

    case _: SnapshotCandidate => ()

    case ValidationFinished(requester, Success(Right(snapshot))) =>
      logger.info(s"Restoring synchronization from snapshot ${snapshot.state.cursor.blockId}@" +
        s"${snapshot.state.cursor.height} with ${snapshot.recentCursors.size} retained cursor(s)")
      requester ! SnapshotLoaded(Some(snapshot))
    case ValidationFinished(requester, Success(Left(reason))) =>
      requester ! SnapshotLoaded(None, Some(reason))
    case ValidationFinished(requester, Failure(ex)) =>
      requester ! SnapshotLoaded(None, Some(
        s"Could not validate snapshot cursor: ${Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName)}"))

    case SaveFinished(state, Right(_)) =>
      writing = false
      logger.info(s"Saved synchronization snapshot ${state.cursor.blockId}@${state.cursor.height}")
      savePending()
    case SaveFinished(state, Left(error)) =>
      writing = false
      logger.error(s"Failed to save synchronization snapshot at ${state.cursor.height}: ${error.message}")
      savePending()
  }

  // Snapshot reads run off the mailbox because rebuilding AVL provers can block.
  private def restore(requester: ActorRef): Unit = snapshotStore match {
    case None => requester ! SnapshotLoaded(None)
    case Some(store) =>
      Future(findCanonical(store))(pollingContext)
        .map(result => ValidationFinished(requester, result))
        .recover { case ex => ValidationFinished(requester, Failure(ex)) }
        .pipeTo(self)
  }

  /** Loads generations newest-first and returns the first one still on the canonical chain. */
  private def findCanonical(store: StateSnapshotStore): Try[Either[String, PersistedSyncState]] =
    store.generationKeys() match {
      case Left(error) => Success(Left(error.message))
      case Right(Seq()) => Success(Left("No retained synchronization snapshot"))
      case Right(keys) =>
        val corrupt = Vector.newBuilder[String]
        keys.foldLeft[Try[Either[String, PersistedSyncState]]](Success(Left(""))) {
          case (found@Success(Right(_)), _) => found
          case (Failure(ex), _) => Failure(ex)
          case (_, key) => store.load(key) match {
            case Left(error) =>
              corrupt += error.message
              Success(Left(""))
            case Right(snapshot) =>
              source.headerAt(snapshot.state.cursor.height).map { header =>
                if (header.id == snapshot.state.cursor.blockId &&
                  header.parentId == snapshot.state.cursor.parentId) Right(snapshot)
                else Left("")
              }
          }
        }.map {
          case Right(snapshot) => Right(snapshot)
          case Left(_) =>
            val failures = corrupt.result()
            Left(if (failures.isEmpty) "No retained synchronization snapshot is canonical"
            else s"No retained synchronization snapshot is usable: ${failures.mkString("; ")}")
        }
    }

  private def save(persisted: PersistedSyncState): Unit = {
    writing = true
    val store = snapshotStore.get
    Future(store.save(persisted))(context.dispatcher)
      .map(result => SaveFinished(persisted.state, result))
      .recover { case ex => SaveFinished(persisted.state, Left(SnapshotError.Corrupt(
        Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName)))) }
      .pipeTo(self)
  }

  private def savePending(): Unit = pending match {
    case Some(candidate) =>
      pending = None
      save(candidate)
    case None => ()
  }
}
