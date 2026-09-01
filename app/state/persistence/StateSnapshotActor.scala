package state.persistence

import akka.actor.{Actor, ActorRef, Cancellable}
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
import scala.collection.immutable.Queue
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object StateSnapshotActor {
  private[persistence] final val MaxConcurrentMaterializations = 2
  private[persistence] final val OperationTimeout: FiniteDuration = 5.minutes
  /** Backoff between attempts to open a database that would not open, so retries cost one call. */
  private[persistence] final val StoreOpenRetryMs: Long = 30000L

  case object RestoreLatest

  /**
   * The restored generation, or why there is none.
   */
  final case class SnapshotLoaded(snapshot: Option[PersistedSyncState],
                                  warning: Option[String] = None,
                                  retryable: Boolean = false)

  /** Why no generation was restored, and whether asking again could answer differently. */
  private[persistence] final case class NoSnapshot(reason: String, retryable: Boolean)
  /** An immutable checkpoint boundary. Serialization runs on the isolated snapshot I/O dispatcher. */
  final case class SnapshotCandidate(snapshot: SnapshotCheckpoint, checkpointToken: String) {
    def cursor: SyncCursor = snapshot.metadata.cursor
    def version: Long = snapshot.metadata.version
  }
  final case class SnapshotSaved(cursor: SyncCursor, checkpointToken: String)
  final case class SnapshotSaveFailed(cursor: SyncCursor, checkpointToken: String, reason: String)
  sealed trait MaterializationPriority
  object MaterializationPriority {
    case object Critical extends MaterializationPriority
    case object Normal extends MaterializationPriority
  }
  final case class MaterializeDictionary(source: SnapshotDictionarySource,
                                         requestToken: String,
                                         priority: MaterializationPriority =
                                           MaterializationPriority.Normal)
  /** Raises queued work without duplicating or preempting a physical reconstruction. */
  final case class PromoteMaterialization(requestToken: String)
  final case class DictionaryLoaded(digest: String,
                                    requestToken: String,
                                    dictionary: AuthenticatedDictionaryView)
  final case class DictionaryLoadFailed(digest: String, requestToken: String, reason: String)

  private sealed trait ActorCompletion {
    def incarnation: String
  }
  private final case class ValidationFinished(incarnation: String,
                                              restoreToken: String,
                                              result: Try[Either[NoSnapshot, PersistedSyncState]])
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
  private final case class WorkExpired(incarnation: String, kind: String, token: String)
}

/** Persists snapshots off the actor mailbox and coalesces queued writes. */
class StateSnapshotActor @Inject()(config: Configuration,
                                   nodeContext: NodeContext,
                                   protocol: SyncProtocolContext) extends Actor {
  import StateSnapshotActor._

  private implicit val actorContext: ExecutionContext = context.dispatcher
  private val snapshotIoContext = context.system.dispatchers.lookup(Contexts.key(Contexts.SnapshotIo))
  private val logger: Logger = LoggerFactory.getLogger("StateSnapshotActor")
  private val incarnation = UUID.randomUUID().toString
  private val syncConfig = new SyncConfig(config)
  private val source = new CanonicalBlockSource(nodeContext.getNodeApi)
  private val snapshotIdentity = StateSnapshotIdentity(protocol)
  // Written only on the actor thread; read from the snapshot-I/O threads through `currentStore`.
  @volatile private var openedStore = Option.empty[LevelDbStateSnapshotStore]
  private var lastOpenAttempt = 0L

  /**
   * The store, opened on first use.
   * Call only from the actor thread: it opens, and it writes actor-owned fields. Work already
   * running on the snapshot-I/O dispatcher reads `currentStore`.
   */
  private def snapshotStore: Option[StateSnapshotStore] =
    if (!syncConfig.snapshotsEnabled) None
    else openedStore.orElse {
      val now = System.currentTimeMillis()
      if (now - lastOpenAttempt < StateSnapshotActor.StoreOpenRetryMs) None
      else {
        lastOpenAttempt = now
        KeyValueStore.open(syncConfig.storageBackend, syncConfig.storagePath) match {
          case Right(backend) =>
            val store = new LevelDbStateSnapshotStore(backend, syncConfig.snapshotRetention,
              snapshotIdentity)
            openedStore = Some(store)
            Some(store)
          case Left(error) =>
            logger.error(s"Could not open the synchronization snapshot database at " +
              s"${syncConfig.storagePath}: ${error.message}. Checkpoints and cold dictionary " +
              "reconstruction are unavailable until it opens; canonical synchronization continues " +
              "from whatever is already materialized. Check that no other client instance holds it.")
            None
        }
      }
    }

  private final case class SaveWork(candidate: SnapshotCandidate,
                                    requester: ActorRef,
                                    deadline: Cancellable,
                                    expired: Boolean = false)
  private final case class RestoreWork(token: String,
                                       requester: ActorRef,
                                       deadline: Cancellable,
                                       expired: Boolean = false)
  private final case class LoadWork(source: SnapshotDictionarySource,
                                    requestToken: String,
                                    requester: ActorRef,
                                    deadline: Cancellable,
                                    priority: MaterializationPriority,
                                    expired: Boolean = false)
  private var activeSave = Option.empty[SaveWork]
  private var pending = Option.empty[SaveWork]
  private var activeRestore = Option.empty[RestoreWork]
  private var activeLoads = Map.empty[String, LoadWork]
  private var queuedCriticalLoads = Queue.empty[LoadWork]
  private var queuedNormalLoads = Queue.empty[LoadWork]

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    abortOwnedWork(s"Snapshot actor restarted: ${detail(reason)}")
    super.preRestart(reason, message)
  }

  override def postStop(): Unit = {
    abortOwnedWork("Snapshot actor stopped before the operation completed")
    // Never opens
    openedStore.foreach { store =>
      store.close().left.foreach(error => logger.error(error.message))
    }
  }

  /** The store if it is already open. Safe from the snapshot-I/O threads; never opens one. */
  private def currentStore: Option[StateSnapshotStore] = openedStore

  override def receive: Receive = {
    // A persistence Future can finish after Akka has replaced this actor instance. Its completion no
    // longer owns the replacement's writer/materializer state and must be ignored.
    case completion: ActorCompletion if completion.incarnation != incarnation => ()
    case WorkExpired(other, _, _) if other != incarnation => ()

    case WorkExpired(_, kind, token) if kind == "save" &&
      activeSave.exists(work => work.candidate.checkpointToken == token && !work.expired) =>
      val reason = s"Synchronization checkpoint $token exceeded $operationTimeout"
      activeSave.foreach { work =>
        work.deadline.cancel()
        work.requester ! SnapshotSaveFailed(work.candidate.cursor, work.candidate.checkpointToken, reason)
        activeSave = Some(work.copy(expired = true))
      }
      logger.error(s"$reason. The underlying snapshot I/O remains isolated and keeps its execution " +
        "slot until it returns; no replacement write will be started over it.")

    case WorkExpired(_, kind, token) if kind == "save" &&
      pending.exists(work => work.candidate.checkpointToken == token && !work.expired) =>
      val reason = s"Queued synchronization checkpoint $token exceeded $operationTimeout"
      pending.foreach { work =>
        work.deadline.cancel()
        work.requester ! SnapshotSaveFailed(work.candidate.cursor, work.candidate.checkpointToken, reason)
      }
      pending = None
      logger.error(s"$reason before the active snapshot write released its execution slot.")

    case WorkExpired(_, kind, token) if kind == "dictionary" &&
      activeLoads.get(token).exists(!_.expired) =>
      val reason = s"Dictionary materialization $token exceeded $operationTimeout"
      activeLoads.get(token).foreach { work =>
        work.deadline.cancel()
        work.requester ! DictionaryLoadFailed(work.source.expectedDigest, work.requestToken, reason)
        activeLoads += token -> work.copy(expired = true)
      }
      logger.error(s"$reason. Its isolated execution slot remains occupied until the underlying work " +
        "returns; the two-worker physical limit will not be exceeded.")

    case WorkExpired(_, kind, token) if kind == "dictionary" && queuedLoad(token).isDefined =>
      val reason = s"Queued dictionary materialization $token exceeded $operationTimeout"
      val expired = removeQueuedLoad(token)
      expired.foreach { work =>
        work.deadline.cancel()
        work.requester ! DictionaryLoadFailed(work.source.expectedDigest, work.requestToken, reason)
      }
      logger.error(s"$reason before an isolated reconstruction slot became available.")

    case WorkExpired(_, kind, token) if kind == "restore" &&
      activeRestore.exists(work => work.token == token && !work.expired) =>
      val reason = s"Synchronization snapshot restore $token exceeded $operationTimeout"
      activeRestore.foreach { work =>
        work.deadline.cancel()
        work.requester ! SnapshotLoaded(None, Some(reason), retryable = true)
        activeRestore = Some(work.copy(expired = true))
      }
      logger.error(s"$reason. Later callers wait and retry rather than launch another restore over " +
        "the blocked read.")

    case _: WorkExpired => ()

    case RestoreLatest => restore(sender())

    case candidate: SnapshotCandidate if snapshotStore.isDefined =>
      val work = saveWork(candidate, sender())
      if (activeSave.isDefined) {
        pending.foreach { superseded =>
          superseded.deadline.cancel()
          superseded.requester ! SnapshotSaveFailed(superseded.candidate.cursor,
            superseded.candidate.checkpointToken,
            "Checkpoint was superseded by a newer pending boundary")
        }
        pending = Some(work)
      }
      else save(work)

    case candidate: SnapshotCandidate =>
      sender() ! SnapshotSaveFailed(candidate.cursor, candidate.checkpointToken,
        if (syncConfig.snapshotsEnabled)
          s"The synchronization snapshot database at ${syncConfig.storagePath} is not open"
        else "Synchronization snapshots are disabled")

    case MaterializeDictionary(source, requestToken, priority) =>
      val requester = sender()
      // Reconstruction runs on the snapshot-I/O dispatcher and reads the already-open handle, so a
      // persisted base has to open the store here, on the actor thread, before the work is
      // dispatched.
      source.base match {
        case _: SnapshotDictionaryBase.Persisted => snapshotStore
        case _ => ()
      }
      if (activeLoads.contains(requestToken) || queuedLoad(requestToken).isDefined)
        requester ! DictionaryLoadFailed(source.expectedDigest, requestToken,
          "Dictionary materialization token is already active")
      else {
        val deadline = context.system.scheduler.scheduleOnce(operationTimeout, self,
          WorkExpired(incarnation, "dictionary", requestToken))(context.dispatcher, self)
        val work = LoadWork(source, requestToken, requester, deadline, priority)
        priority match {
          case MaterializationPriority.Critical =>
            queuedCriticalLoads = queuedCriticalLoads.enqueue(work)
          case MaterializationPriority.Normal =>
            queuedNormalLoads = queuedNormalLoads.enqueue(work)
        }
        startLoads()
      }

    case PromoteMaterialization(requestToken) =>
      queuedNormalLoads.find(_.requestToken == requestToken).foreach { work =>
        queuedNormalLoads = Queue(queuedNormalLoads.filterNot(
          _.requestToken == requestToken): _*)
        queuedCriticalLoads = queuedCriticalLoads.enqueue(
          work.copy(priority = MaterializationPriority.Critical))
        startLoads()
      }

    case DictionaryLoadFinished(_, _, digest, requestToken, Right(dictionary)) =>
      activeLoads.get(requestToken).foreach { work =>
        work.deadline.cancel()
        activeLoads -= requestToken
        if (work.expired)
          logger.warn(s"Dictionary materialization $requestToken completed after its deadline; " +
            "discarding the unowned result")
        else work.requester ! DictionaryLoaded(digest, requestToken, dictionary)
        startLoads()
      }
    case DictionaryLoadFinished(_, _, digest, requestToken, Left(error)) =>
      activeLoads.get(requestToken).foreach { work =>
        work.deadline.cancel()
        activeLoads -= requestToken
        if (work.expired)
          logger.warn(s"Dictionary materialization $requestToken failed after its deadline: ${error.message}")
        else work.requester ! DictionaryLoadFailed(digest, requestToken, error.message)
        startLoads()
      }

    case ValidationFinished(_, token, result) => finishRestore(token, result)

    case SaveFinished(_, candidate, requester, Right(_)) =>
      finishSave(candidate, requester, Right(()))
    case SaveFinished(_, candidate, requester, Left(error)) =>
      finishSave(candidate, requester, Left(error))
  }

  // Snapshot reads run off the mailbox because database scans and canonical validation can block.
  // Only one physical restore may exist. A retry receives an explicit fallback instead of starting a
  // second read that could outlive its caller and amplify a stalled backend.
  private def restore(requester: ActorRef): Unit = snapshotStore match {
    // Disabled is a definitive answer; a database that would not open is not.
    case None if !syncConfig.snapshotsEnabled => requester ! SnapshotLoaded(None)
    case None => requester ! SnapshotLoaded(None, Some(
      s"The synchronization snapshot database at ${syncConfig.storagePath} is not open"),
      retryable = true)
    case Some(_) if activeRestore.isDefined =>
      requester ! SnapshotLoaded(None, Some(
        "A synchronization snapshot restore is already in progress; not launching duplicate " +
          "blocking storage work"), retryable = true)
    case Some(store) =>
      val token = UUID.randomUUID().toString
      val deadline = context.system.scheduler.scheduleOnce(operationTimeout, self,
        WorkExpired(incarnation, "restore", token))(context.dispatcher, self)
      activeRestore = Some(RestoreWork(token, requester, deadline))
      Future(findCanonical(store))(snapshotIoContext)
        .map(result => ValidationFinished(incarnation, token, result))
        .recover { case ex => ValidationFinished(incarnation, token, Failure(ex)) }
        .pipeTo(self)
  }

  /** Loads generations newest-first and returns the first one still on the canonical chain. */
  protected def findCanonical(store: StateSnapshotStore): Try[Either[NoSnapshot, PersistedSyncState]] =
    store.generationKeys() match {
      // A storage failure says nothing about whether a usable generation exists.
      case Left(error) => Success(Left(NoSnapshot(error.message, retryable = true)))
      case Right(Seq()) =>
        Success(Left(NoSnapshot("No retained synchronization snapshot", retryable = false)))
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
          // Unproven, not disproved. Reporting this as "no snapshot" is what made a node that was
          // still starting cost the client its persisted state and a rescan from sync.startHeight.
          case None if pending.nonEmpty =>
            Success(Left(NoSnapshot(s"Could not verify retained snapshot(s) ${pending.mkString(", ")} " +
              "against the node; keeping them and retrying rather than discarding recoverable state",
              retryable = true)))
          case None =>
            val failures = rejected.result()
            Success(Left(NoSnapshot(
              if (failures.isEmpty) "No retained synchronization snapshot is canonical"
              else s"No retained synchronization snapshot is usable: ${failures.mkString("; ")}",
              retryable = false)))
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

  protected def reconstruct(source: SnapshotDictionarySource):
    Either[SnapshotError, AuthenticatedDictionaryView] = {
    val starting: Either[SnapshotError, AuthenticatedDictionaryView] = source.base match {
      case SnapshotDictionaryBase.Materialized(dictionary) => Right(dictionary)
      // Runs on the snapshot-I/O dispatcher, so it reads the open handle rather than opening one.
      case SnapshotDictionaryBase.Persisted(digest) => currentStore match {
        case Some(store) => store.loadDictionary(digest)
        case None => Left(SnapshotError.Corrupt(
          s"Cannot load persisted dictionary $digest without an open snapshot database"))
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

  private def saveWork(candidate: SnapshotCandidate, requester: ActorRef): SaveWork = {
    val deadline = context.system.scheduler.scheduleOnce(operationTimeout, self,
      WorkExpired(incarnation, "save", candidate.checkpointToken))(context.dispatcher, self)
    SaveWork(candidate, requester, deadline)
  }

  private def save(work: SaveWork): Unit = {
    activeSave = Some(work)
    Future(persist(work.candidate))(snapshotIoContext)
      .map(result => SaveFinished(incarnation, work.candidate, work.requester, result))
      .recover { case ex => SaveFinished(incarnation, work.candidate, work.requester,
        Left(SnapshotError.Corrupt(Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName)))) }
      .pipeTo(self)
  }

  private def startLoads(): Unit = {
    while (activeLoads.size < StateSnapshotActor.MaxConcurrentMaterializations && hasQueuedLoad) {
      val work = dequeueLoad()
      activeLoads += work.requestToken -> work
      Future(reconstruct(work.source))(snapshotIoContext)
        .map(result => DictionaryLoadFinished(incarnation, work.requester,
          work.source.expectedDigest, work.requestToken, result))
        .recover { case ex => DictionaryLoadFinished(incarnation, work.requester,
          work.source.expectedDigest, work.requestToken, Left(SnapshotError.Corrupt(
            Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName)))) }
        .pipeTo(self)
    }
  }

  private def hasQueuedLoad: Boolean =
    queuedCriticalLoads.nonEmpty || queuedNormalLoads.nonEmpty

  private def queuedLoad(token: String): Option[LoadWork] =
    queuedCriticalLoads.find(_.requestToken == token)
      .orElse(queuedNormalLoads.find(_.requestToken == token))

  private def removeQueuedLoad(token: String): Option[LoadWork] = {
    val found = queuedLoad(token)
    queuedCriticalLoads = Queue(queuedCriticalLoads.filterNot(_.requestToken == token): _*)
    queuedNormalLoads = Queue(queuedNormalLoads.filterNot(_.requestToken == token): _*)
    found
  }

  private def dequeueLoad(): LoadWork = {
    if (queuedCriticalLoads.nonEmpty) {
      val (work, remaining) = queuedCriticalLoads.dequeue
      queuedCriticalLoads = remaining
      work
    } else {
      val (work, remaining) = queuedNormalLoads.dequeue
      queuedNormalLoads = remaining
      work
    }
  }

  private def savePending(): Unit = pending match {
    case Some(work) =>
      pending = None
      save(work)
    case None => ()
  }

  private def finishSave(candidate: SnapshotCandidate,
                         requester: ActorRef,
                         result: Either[SnapshotError, Unit]): Unit =
    activeSave.filter(_.candidate.checkpointToken == candidate.checkpointToken).foreach { work =>
      work.deadline.cancel()
      activeSave = None
      if (work.expired) result match {
        case Right(_) => logger.warn(s"Synchronization snapshot ${candidate.cursor.blockId}@" +
          s"${candidate.cursor.height} committed after its deadline; the late unowned result is ignored")
        case Left(error) => logger.warn(s"Synchronization snapshot at ${candidate.cursor.height} failed " +
          s"after its deadline: ${error.message}")
      }
      else result match {
        case Right(_) =>
          logger.info(s"Saved synchronization snapshot ${candidate.cursor.blockId}@${candidate.cursor.height}")
          requester ! SnapshotSaved(candidate.cursor, candidate.checkpointToken)
        case Left(error) =>
          logger.error(s"Failed to save synchronization snapshot at ${candidate.cursor.height}: ${error.message}")
          requester ! SnapshotSaveFailed(candidate.cursor, candidate.checkpointToken, error.message)
      }
      savePending()
    }

  private def finishRestore(token: String,
                            result: Try[Either[NoSnapshot, PersistedSyncState]]): Unit =
    activeRestore.filter(_.token == token).foreach { work =>
      work.deadline.cancel()
      activeRestore = None
      if (work.expired)
        logger.warn(s"Synchronization snapshot restore $token completed after its deadline; " +
          "discarding the unowned result")
      else result match {
        case Success(Right(snapshot)) =>
          logger.info(s"Restoring synchronization from snapshot ${snapshot.state.cursor.blockId}@" +
            s"${snapshot.state.cursor.height} with ${snapshot.recentCursors.size} retained cursor(s)")
          work.requester ! SnapshotLoaded(Some(snapshot))
        case Success(Left(absent)) =>
          work.requester ! SnapshotLoaded(None, Some(absent.reason), absent.retryable)
        // An exception thrown while validating proves nothing about the retained generations.
        case Failure(ex) =>
          work.requester ! SnapshotLoaded(None, Some(
            s"Could not validate snapshot cursor: ${Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName)}"),
            retryable = true)
      }
    }

  /** Runs on the snapshot-I/O dispatcher; the store was opened when the request was accepted. */
  protected def persist(candidate: SnapshotCandidate): Either[SnapshotError, Unit] =
    currentStore match {
      case Some(store) => store.save(candidate.snapshot, syncConfig.snapshotMaxEntryBytes)
      case None => Left(SnapshotError.Corrupt("snapshot database is not open"))
    }

  /** Test seam; production fixes save, reconstruction and restore ownership at five minutes. */
  protected def operationTimeout: FiniteDuration = StateSnapshotActor.OperationTimeout

  /** Every accepted request receives a terminal reply even if Akka replaces this actor instance. */
  private def abortOwnedWork(reason: String): Unit = {
    activeSave.foreach { work =>
      if (!work.expired)
        work.requester ! SnapshotSaveFailed(work.candidate.cursor, work.candidate.checkpointToken, reason)
      work.deadline.cancel()
    }
    pending.foreach { work =>
      if (!work.expired)
        work.requester ! SnapshotSaveFailed(work.candidate.cursor, work.candidate.checkpointToken, reason)
      work.deadline.cancel()
    }
    activeRestore.foreach { work =>
      // Owner replaced mid-read; the generations on disk are untouched by that.
      if (!work.expired) work.requester ! SnapshotLoaded(None, Some(reason), retryable = true)
      work.deadline.cancel()
    }
    activeLoads.values.foreach { work =>
      work.deadline.cancel()
      if (!work.expired)
        work.requester ! DictionaryLoadFailed(work.source.expectedDigest, work.requestToken, reason)
    }
    (queuedCriticalLoads.iterator ++ queuedNormalLoads.iterator).foreach { work =>
      work.deadline.cancel()
      work.requester ! DictionaryLoadFailed(work.source.expectedDigest, work.requestToken, reason)
    }
    activeSave = None
    pending = None
    activeRestore = None
    activeLoads = Map.empty
    queuedCriticalLoads = Queue.empty
    queuedNormalLoads = Queue.empty
  }

  private def detail(reason: Throwable): String =
    Option(reason.getMessage).getOrElse(reason.getClass.getSimpleName)
}
