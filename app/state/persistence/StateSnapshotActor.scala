package state.persistence

import akka.actor.{Actor, ActorRef}
import akka.pattern.pipe
import configs.{Contexts, NodeContext, SyncConfig}
import lfsm.states.{AuthenticatedDictionaryView, PlasmaDictionary}
import org.bouncycastle.util.encoders.Hex
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import state.messages.SyncMessages.SyncCursor
import state.synchronization.{CanonicalBlockSource, CommittedSyncState, SyncProtocolContext}
import storage.KeyValueStore

import javax.inject.Inject
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object StateSnapshotActor {
  case object RestoreLatest
  final case class SnapshotLoaded(snapshot: Option[PersistedSyncState], warning: Option[String] = None)
  /** An immutable checkpoint boundary. Serialization runs on the persistence dispatcher. */
  final case class SnapshotCandidate(snapshot: SnapshotCheckpoint, checkpointToken: String) {
    def cursor: SyncCursor = snapshot.metadata.cursor
    def version: Long = snapshot.metadata.version
  }
  final case class SnapshotSaved(cursor: SyncCursor, checkpointToken: String)
  final case class SnapshotSaveFailed(cursor: SyncCursor, checkpointToken: String, reason: String)
  final case class MaterializeDictionary(source: SnapshotDictionarySource, requestToken: String)
  final case class DictionaryLoaded(digest: String,
                                    requestToken: String,
                                    dictionary: AuthenticatedDictionaryView)
  final case class DictionaryLoadFailed(digest: String, requestToken: String, reason: String)

  private sealed trait ActorCompletion {
    def incarnation: String
  }
  private final case class ValidationFinished(incarnation: String,
                                              requester: ActorRef,
                                              result: Try[Either[String, PersistedSyncState]])
    extends ActorCompletion
  private final case class SaveFinished(incarnation: String,
                                        candidate: SnapshotCandidate,
                                        requester: ActorRef,
                                        result: Either[SnapshotError, Unit]) extends ActorCompletion
  private final case class DictionaryLoadFinished(incarnation: String,
                                                  requester: ActorRef,
                                                  digest: String,
                                                  requestToken: String,
                                                  result: Either[SnapshotError, AuthenticatedDictionaryView])
    extends ActorCompletion
}

/** Persists snapshots off the actor mailbox and coalesces queued writes. */
class StateSnapshotActor @Inject()(config: Configuration,
                                   nodeContext: NodeContext,
                                   protocol: SyncProtocolContext) extends Actor {
  import StateSnapshotActor._

  private implicit val actorContext: ExecutionContext = context.dispatcher
  private val pollingContext = context.system.dispatchers.lookup(Contexts.key(Contexts.Polling))
  private val logger: Logger = LoggerFactory.getLogger("StateSnapshotActor")
  private val incarnation = UUID.randomUUID().toString
  private val syncConfig = new SyncConfig(config)
  private val source = new CanonicalBlockSource(nodeContext.getNodeApi)
  private val snapshotIdentity = StateSnapshotIdentity(protocol)
  private val snapshotStore =
    if (syncConfig.snapshotsEnabled)
      Some(new LevelDbStateSnapshotStore(
        KeyValueStore.openOrThrow(syncConfig.storageBackend, syncConfig.storagePath),
        syncConfig.snapshotRetention,
        snapshotIdentity))
    else None

  private var writing = false
  private var pending = Option.empty[(SnapshotCandidate, ActorRef)]

  override def postStop(): Unit = snapshotStore.foreach { store =>
    store.close().left.foreach(error => logger.error(error.message))
  }

  override def receive: Receive = {
    // A persistence Future can finish after Akka has replaced this actor instance. Its completion no
    // longer owns the replacement's writer/materializer state and must be ignored.
    case completion: ActorCompletion if completion.incarnation != incarnation => ()

    case RestoreLatest => restore(sender())

    case candidate: SnapshotCandidate if snapshotStore.isDefined =>
      if (writing) {
        pending.foreach { case (superseded, requester) =>
          requester ! SnapshotSaveFailed(superseded.cursor, superseded.checkpointToken,
            "Checkpoint was superseded by a newer pending boundary")
        }
        pending = Some(candidate -> sender())
      }
      else save(candidate, sender())

    case candidate: SnapshotCandidate =>
      sender() ! SnapshotSaveFailed(candidate.cursor, candidate.checkpointToken,
        "Synchronization snapshots are disabled")

    case MaterializeDictionary(source, requestToken) =>
      val requester = sender()
      Future(materialize(source))(pollingContext)
        .map(result => DictionaryLoadFinished(incarnation, requester, source.expectedDigest,
          requestToken, result))
        .recover { case ex => DictionaryLoadFinished(incarnation, requester, source.expectedDigest,
          requestToken, Left(SnapshotError.Corrupt(
            Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName)))) }
        .pipeTo(self)

    case DictionaryLoadFinished(_, requester, digest, requestToken, Right(dictionary)) =>
      requester ! DictionaryLoaded(digest, requestToken, dictionary)
    case DictionaryLoadFinished(_, requester, digest, requestToken, Left(error)) =>
      requester ! DictionaryLoadFailed(digest, requestToken, error.message)

    case ValidationFinished(_, requester, Success(Right(snapshot))) =>
      logger.info(s"Restoring synchronization from snapshot ${snapshot.state.cursor.blockId}@" +
        s"${snapshot.state.cursor.height} with ${snapshot.recentCursors.size} retained cursor(s)")
      requester ! SnapshotLoaded(Some(snapshot))
    case ValidationFinished(_, requester, Success(Left(reason))) =>
      requester ! SnapshotLoaded(None, Some(reason))
    case ValidationFinished(_, requester, Failure(ex)) =>
      requester ! SnapshotLoaded(None, Some(
        s"Could not validate snapshot cursor: ${Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName)}"))

    case SaveFinished(_, candidate, requester, Right(_)) =>
      writing = false
      logger.info(s"Saved synchronization snapshot ${candidate.cursor.blockId}@${candidate.cursor.height}")
      requester ! SnapshotSaved(candidate.cursor, candidate.checkpointToken)
      savePending()
    case SaveFinished(_, candidate, requester, Left(error)) =>
      writing = false
      logger.error(s"Failed to save synchronization snapshot at ${candidate.cursor.height}: ${error.message}")
      requester ! SnapshotSaveFailed(candidate.cursor, candidate.checkpointToken, error.message)
      savePending()
  }

  // Snapshot reads run off the mailbox because database scans and canonical validation can block.
  private def restore(requester: ActorRef): Unit = snapshotStore match {
    case None => requester ! SnapshotLoaded(None)
    case Some(store) =>
      Future(findCanonical(store))(pollingContext)
        .map(result => ValidationFinished(incarnation, requester, result))
        .recover { case ex => ValidationFinished(incarnation, requester, Failure(ex)) }
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
    val expectedRoutes = state.rollups.values.map(rollup => rollup.utxoId -> rollup.blockId).toMap
    if (cursors.isEmpty)
      Some("retained cursors do not include the checkpoint boundary")
    else if (cursors.map(_.height) != cursors.map(_.height).sorted.distinct)
      Some("retained cursors are not strictly ascending")
    else if (cursors.zip(cursors.drop(1)).exists { case (left, right) =>
      right.height != left.height + 1 || right.parentId != left.blockId
    })
      Some("retained cursors do not form one contiguous canonical chain")
    else if (cursors.last != state.cursor)
      Some(s"retained cursors end at ${cursors.last.height}, not the committed ${state.cursor.height}")
    else if (state.rollups.exists { case (id, rollup) => id != rollup.blockId })
      Some("a tracked rollup is stored under a different block identity")
    else if (expectedRoutes.size != state.rollups.size || state.routes != expectedRoutes)
      Some("rollup routes do not exactly name every tracked rollup UTXO")
    else if (state.rollupOrigins.keySet != state.rollups.keySet)
      Some("rollup origins do not exactly name every tracked rollup")
    else if (state.rollups.values.exists(rollup => rollup.numMiners < 0 || rollup.totalScore < 0 ||
      (rollup.hasMiner && rollup.numMiners == 0)))
      Some("tracked rollup counters or local-miner membership are impossible")
    else if (state.minerTree.numMiners < 0 || state.minerTree.hasMiner && state.minerTree.numMiners == 0)
      Some("Miner Dictionary count or local-miner membership is impossible")
    else if (state.dataBoxToken.isDefined && !state.minerTree.hasMiner)
      Some("a local data-box token is stored while the dictionary does not hold this miner")
    else if (state.minerTree.dictionary.materialized &&
      state.minerTree.dictionary.foldKeys(0)((count, _) => count + 1) != state.minerTree.numMiners)
      Some(s"Miner Dictionary authenticated leaf count does not match ${state.minerTree.numMiners}")
    else if (state.quarantined.keySet.exists(state.rollups.contains))
      Some("a rollup is recorded as both quarantined and tracked")
    else if (state.quarantined.exists { case (id, fault) => id != fault.rollupId })
      Some("a quarantine is stored under a different rollup identity")
    else None
  }

  private def name(key: Array[Byte]): String =
    new String(key, java.nio.charset.StandardCharsets.UTF_8).split('/').last

  private def materialize(source: SnapshotDictionarySource):
    Either[SnapshotError, AuthenticatedDictionaryView] = {
    val starting: Either[SnapshotError, AuthenticatedDictionaryView] = source.base match {
      case SnapshotDictionaryBase.Materialized(dictionary) => Right(dictionary)
      case SnapshotDictionaryBase.Persisted(digest) => snapshotStore match {
        case Some(store) => store.loadDictionary(digest)
        case None => Left(SnapshotError.Corrupt(
          s"Cannot load persisted dictionary $digest while snapshots are disabled"))
      }
      case SnapshotDictionaryBase.Empty => Right(PlasmaDictionary.empty(source.flags, source.parameters))
    }
    for {
      dictionary <- source.transforms.foldLeft(starting) { (result, transform) =>
        result.flatMap(current => transform.replay(current).left.map(SnapshotError.Corrupt))
      }
      actual = Hex.toHexString(dictionary.digest)
      _ <- if (actual == source.expectedDigest) Right(()) else Left(SnapshotError.Corrupt(
        s"Materialized dictionary $actual, expected ${source.expectedDigest}"))
    } yield dictionary
  }

  private def save(candidate: SnapshotCandidate, requester: ActorRef): Unit = {
    writing = true
    val store = snapshotStore.get
    Future(store.save(candidate.snapshot, syncConfig.snapshotMaxEntryBytes))(pollingContext)
      .map(result => SaveFinished(incarnation, candidate, requester, result))
      .recover { case ex => SaveFinished(incarnation, candidate, requester,
        Left(SnapshotError.Corrupt(Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName)))) }
      .pipeTo(self)
  }

  private def savePending(): Unit = pending match {
    case Some((candidate, requester)) =>
      pending = None
      save(candidate, requester)
    case None => ()
  }
}
