package state.persistence

import akka.actor.{Actor, ActorRef}
import akka.pattern.pipe
import configs.{Contexts, NodeContext, SyncConfig}
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import state.messages.SyncMessages.SyncCursor
import state.synchronization.{CanonicalBlockSource, CommittedSyncState, SyncProtocolContext}
import storage.KeyValueStore

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
        KeyValueStore.openOrThrow(syncConfig.storageBackend, syncConfig.storagePath),
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

    // Cadence and the defensive copy are decided by the state owner, which is the thread that owns
    // the dictionaries this will serialize.
    case SnapshotCandidate(state, cursors, _) if snapshotStore.isDefined =>
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
        val rejected = Vector.newBuilder[String]
        val unchecked = Vector.newBuilder[String]

        val found = keys.foldLeft(Option.empty[PersistedSyncState]) { (chosen, key) =>
          if (chosen.isDefined) chosen
          else store.load(key) match {
            case Left(error) =>
              rejected += s"${name(key)}: ${error.message}"
              None
            case Right(snapshot) => validate(snapshot) match {
              case Right(())     => Some(snapshot)
              case Left(Some(w)) => rejected += s"${name(key)}: $w"; None
              // Unproven, so try older generations but never report "none is canonical".
              case Left(None)    => unchecked += name(key); None
            }
          }
        }

        val pending = unchecked.result()
        found match {
          case Some(snapshot) => Success(Right(snapshot))
          case None if pending.nonEmpty =>
            Success(Left(s"Could not verify retained snapshot(s) ${pending.mkString(", ")} against the " +
              "node; keeping them and retrying rather than discarding recoverable state"))
          case None =>
            val failures = rejected.result()
            Success(Left(if (failures.isEmpty) "No retained synchronization snapshot is canonical"
            else s"No retained synchronization snapshot is usable: ${failures.mkString("; ")}"))
        }
    }

  /**
   * `Right` when the generation is canonical, `Left(Some(reason))` when it provably is not, and
   * `Left(None)` when the node could not answer.
   */
  private def validate(snapshot: PersistedSyncState): Either[Option[String], Unit] =
    semanticProblem(snapshot) match {
      case Some(problem) => Left(Some(problem))
      case None => source.headerAt(snapshot.state.cursor.height) match {
        case Failure(ex) =>
          logger.warn(s"Could not read the canonical header at ${snapshot.state.cursor.height} to " +
            s"verify a snapshot: ${Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName)}")
          Left(None)
        case Success(header) =>
          if (header.id == snapshot.state.cursor.blockId &&
            header.parentId == snapshot.state.cursor.parentId) Right(())
          else Left(Some(s"header at ${snapshot.state.cursor.height} is ${header.id}, " +
            s"not ${snapshot.state.cursor.blockId}"))
      }
    }

  /**
   * Cross-field checks the codec's checksum cannot make.
   */
  private def semanticProblem(snapshot: PersistedSyncState): Option[String] = {
    val state = snapshot.state
    val cursors = snapshot.recentCursors
    if (cursors.map(_.height) != cursors.map(_.height).sorted.distinct)
      Some("retained cursors are not strictly ascending")
    else if (cursors.nonEmpty && cursors.last.height != state.cursor.height)
      Some(s"retained cursors end at ${cursors.last.height}, not the committed ${state.cursor.height}")
    else if (state.dataBoxToken.isDefined && !state.minerTree.hasMiner)
      Some("a local data-box token is stored while the dictionary does not hold this miner")
    else if (state.minerTree.minerMap.size > state.minerTree.numMiners)
      Some(s"dictionary holds ${state.minerTree.minerMap.size} mapped miners but counts " +
        s"${state.minerTree.numMiners}")
    else None
  }

  private def name(key: Array[Byte]): String =
    new String(key, java.nio.charset.StandardCharsets.UTF_8).split('/').last

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
