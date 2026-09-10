package transactions.engine

import akka.actor.{ActorRef, Cancellable}
import configs.NodeContext
import javax.inject.{Inject, Named}
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}
import transactions.engine.execution.{ConsolidationExecution, DexExecution, RollupExecution}
import transactions.engine.wallet.{EngineFunding, EngineWalletState}

object TransactionEngine {
  case object RegisterMiner
  final case class JoinCollateral(request: api.models.CollateralJoinExecuteRequest)

  final case class Submit(intent: EngineIntent)
  /** Admit work that only becomes eligible later. Same lifecycle as an immediate request. */
  final case class Schedule(intent: EngineIntent, notBeforeMillis: Long, expiresAtMillis: Long)
  case object GetConsolidationStatus

  private case object Maintenance
  /** A worker finished. `attempt` is checked against the schedule so stale results are dropped. */
  private case class WorkFinished(key: String, attempt: UUID, result: Try[Any])
  private case class Reconciled(attempt: UUID, result: Try[Unit])

  /** What the engine tells a caller. A transaction id is returned whenever one is known to exist. */
  sealed trait Outcome { def key: String }
  final case class Accepted(key: String, txId: String) extends Outcome
  /** Sent, but the node's answer was lost. The inputs stay owned until reconciliation. */
  final case class Uncertain(key: String, txId: String, reason: String) extends Outcome
  final case class Rejected(key: String, reason: String, txId: Option[String] = None) extends Outcome
  /** The chain already reflects this work, so there is nothing left to send. */
  final case class Completed(key: String) extends Outcome
  /** Not attempted now: refused at admission, or ineligible until state moves. */
  final case class Deferred(key: String, reason: String) extends Outcome

  private[engine] case object Reconcile

  /**
   * Queue depth per lane.
   *
   * Optional depth has to exceed the number of rollups this client tracks: a processing tick offers
   * one holding transform per rollup at once, each a distinct key, and admission refuses on queue
   * size rather than on how fast the lane drains. Sized too small, the excess is rejected the moment
   * it arrives however quickly work completes. Entries are a key and a small intent, so depth is
   * cheap; per-key coalescing is what actually bounds the set.
   */
  private final val MaxCriticalQueued = 256
  private final val MaxOptionalQueued = 256
  /** Callers that may wait on one key before duplicates are refused rather than queued. */
  private final val MaxWaitersPerKey = 8
  /** Longest an admitted intent may sit before it is dropped as stale. */
  private final val DefaultExpiry = 5.minutes
  /** Upper bound on a caller-supplied deadline, so a bad request cannot pin a queue slot forever. */
  private final val MaxExpiry = 7.days
  private final val MaintenanceInterval = 15000L
}

/**
 * The single owner of wallet spend authority. It admits typed intents, runs one at a time per lane
 * on bounded workers, and reconciles anything whose send outcome is unknown.
 *
 * The mailbox only validates identity and updates owned state; every build, signature and node call
 * happens on a worker, so optional work can never delay mining or a fraud proof.
 */
class TransactionEngine @Inject()(node: NodeContext,
  @Named("sync-handler") sync: ActorRef,
  @Named("mempool-view") mempool: ActorRef,
  protected val cacheApi: play.api.cache.SyncCacheApi,
  protected val config: play.api.Configuration,
  protected val dataBoxes: transactions.rollups.DataBoxSource)
  extends EngineWalletState(node, configs.WalletConfig(config)) with EngineRollupCandidates with transactions.emissions.EmissionsCore {
  import TransactionEngine._
  import ExecutionSchedule._

  override protected def emissionNodeContext: NodeContext = node
  override protected def emissionWalletManager: ActorRef = self
  override protected def emissionNodeApi: _root_.node.NodeApi = nodeApi
  override lazy val nodeApi: _root_.node.NodeApi = _root_.node.rest.RestNodeApi(
    _root_.node.rest.NodeHttpConfig(node.getNodeUrl, Some(node.getNodeKey),
      maxResponseBytes = 2 * 1024 * 1024, callTimeoutMs = 10000L))

  private val logger: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger("TransactionEngine")

  private val rollupWorker = context.system.dispatchers.lookup("lithos-contexts.critical-tx-dispatcher")

  /**
   * By selection rather than injection: `RollupProcessor` already injects this engine by name, so
   * naming it back would be a construction cycle.
   */
  private def discardRollupStubs(blockId: String, reason: String): Unit =
    context.actorSelection("/user/transaction-processor") !
      transactions.rollups.TransactionMessages.DropRollupStubs(blockId, reason)

  private def newRollupExecution(eligible: () => Boolean): RollupExecution =
    new RollupExecution(node, self, sync, mempool, config, dataBoxes, nodeApi, eligible, rollupWorker,
      discardRollupStubs)

  override protected def candidateExecution(eligible: () => Boolean): RollupExecution = newRollupExecution(eligible)
  private val worker = context.system.dispatchers.lookup("lithos-contexts.engine-io-dispatcher")

  /** Cleared on stop so an outstanding worker abandons its build instead of signing into a dead actor. */
  private val alive = new AtomicBoolean(true)


  /** Engine-wide: resolves outstanding sends for every operation */
  protected lazy val reconciler = new EngineReconciler(node, self, mempool)

  // Sized from the lane depths so neither lane can be refused by a smaller shared cap.
  private val schedule = new ExecutionSchedule[EngineIntent](MaxCriticalQueued + MaxOptionalQueued)
  /** Callers waiting on an outcome, by intent key. Timer-driven work has none. */
  private var waiters = Map.empty[String, Vector[ActorRef]]
  /** Entries a worker currently owns, so a completion can be matched back to its intent kind. */
  private var running = Map.empty[String, Entry[EngineIntent]]
  private var reconciling: Option[UUID] = None
  private var reconcileDue = true
  private var nextMaintenance = 0L
  private var ticker: Option[Cancellable] = None

  protected val walletConfig: configs.WalletConfig = configs.WalletConfig(config)
  private val consolidationEnabled = walletConfig.consolidation.enabled
  private val consolidationTarget = walletConfig.consolidation.targetUtxos
  require(consolidationTarget > 0, "consolidation target must be positive")
  /** A floor between passes, not a schedule: a pass also needs the critical lane clear. */
  private var nextConsolidation = 0L
  private var consolidationStatus = ConsolidationExecution.Status(0, consolidationTarget, 0, None, None, None,
    targetUnreachable = false,
    if (consolidationEnabled) ConsolidationExecution.OutcomeNotObserved else ConsolidationExecution.OutcomeDisabled)

  private lazy val consolidation = new ConsolidationExecution(node, nodeApi, self, walletConfig)
  private lazy val dex = new DexExecution(node, EngineFunding(self, EngineFunding.askTimeout(config), ec), () => alive.get()) {
    override protected def executionNode: _root_.node.NodeApi = TransactionEngine.this.nodeApi
  }
  private lazy val dexCache = new cache.LDCache(cacheApi)

  override def preStart(): Unit = {
    super.preStart()
    // Consolidation is invisible otherwise: it has no log of its own, and its status is only
    // readable over the API, so "nothing happened" and "it is switched off" look identical.
    if (consolidationEnabled)
      logger.info(s"Consolidation ON: target ${consolidationTarget} UTXOs, at most every " +
        s"${walletConfig.consolidation.intervalMs}ms, at least ${walletConfig.consolidation.minInputs} " +
        "inputs per pass. Deferred while critical work is queued.")
    else
      logger.info("Consolidation OFF (wallet.consolidation.enabled)")
    logger.info(s"Wallet limits: maxInputs=${walletConfig.maxInputs} pageSize=${walletConfig.pageSize} " +
      s"maxDescriptors=${walletConfig.maxDescriptors} " +
      s"reservationTimeout=${walletConfig.reservationTimeoutMs}ms")
    ticker = Some(context.system.scheduler.scheduleWithFixedDelay(1.second, 1.second, self, Maintenance))
    self ! Reconcile
  }

  override def postStop(): Unit = { alive.set(false); ticker.foreach(_.cancel()); super.postStop() }

  override def receive: Receive = engineReceive.orElse(super.receive)

  private def engineReceive: Receive = {
    case state.synchronization.CompleteMempool.Refresh =>
      mempool.forward(state.synchronization.CompleteMempool.Refresh)
    case GetConsolidationStatus => sender() ! consolidationStatus

    case Submit(intent) => admit(intent, sender())
    case intent: DexIntent => admit(EngineIntent.Dex(intent), sender())
    case JoinCollateral(request) => admit(EngineIntent.Join(request), sender())
    case RegisterMiner => admit(EngineIntent.Register, sender())

    // A batch is acknowledged as soon as it is admitted; the submitter does not wait for the sends.
    case transactions.rollups.TransactionMessages.RollupBatch(stubs) if stubs.nonEmpty && stubs.size <= 100 =>
      if (admit(EngineIntent.Rollups(stubs.toVector), context.system.deadLetters))
        sender() ! transactions.rollups.TransactionMessages.BatchAccepted(stubs)
    case _: transactions.rollups.TransactionMessages.RollupBatch => ()

    case transactions.emissions.EmissionsCore.DriveQueue =>
      admit(EngineIntent.Queue, context.system.deadLetters)
    case transactions.emissions.EmissionsCore.Collateralize =>
      admit(EngineIntent.Collateralize, context.system.deadLetters)
    case Schedule(intent, notBefore, expiry) => admit(intent, sender(), Some(Scheduled(notBefore, expiry)))

    case Maintenance =>
      val now = System.currentTimeMillis()
      schedule.expire(now).foreach(entry =>
        completeWaiters(entry.key, Deferred(entry.key, "intent expired before dispatch")))
      if (now >= nextMaintenance) {
        nextMaintenance = now + MaintenanceInterval
        reconcileDue = true
        // Blocked by critical work only. Requiring the whole schedule to be empty meant a client
        // tracking rollups never consolidated at all, because the optional lane is rarely idle.
        // Consolidation still runs in the optional lane, so it cannot delay a NISP or a fraud proof.
        if (consolidationEnabled && now >= nextConsolidation &&
          !schedule.occupied(Lane.Critical) && reconciling.isEmpty) {
          nextConsolidation = now + walletConfig.consolidation.intervalMs
          logger.info(s"Consolidation pass starting: target $consolidationTarget UTXOs")
          admit(EngineIntent.Consolidate, context.system.deadLetters)
        }
      }
      drive()

    case Reconcile => reconcileDue = true; drive()

    case Reconciled(attempt, result) if reconciling.contains(attempt) =>
      reconciling = None
      result.failed.foreach(ex => logger.warn(s"Engine reconciliation deferred: ${ex.getMessage}"))
      drive(preferReconciliation = false)

    case WorkFinished(key, attempt, result) if running.get(key).exists(_.attempt.contains(attempt)) =>
      val entry = running(key)
      // Release the per-kind lock the mixin took when the work started.
      entry.work match {
        case _: EngineIntent.Join | EngineIntent.Queue | EngineIntent.Collateralize => finishEmission()
        case EngineIntent.Consolidate =>
          consolidationStatus = result.map(_.asInstanceOf[ConsolidationExecution.Status])
            .getOrElse(consolidationStatus.copy(outcome = ConsolidationExecution.OutcomeDeferred))
          // Say what the pass concluded. The status was previously readable only over the API, so a
          // wallet that never shrank gave no clue whether it was refused, empty, or never observed.
          result match {
            case Success(_) =>
              val s = consolidationStatus
              logger.info(s"Consolidation pass ${s.outcome}: ${s.total} wallet UTXO(s), " +
                s"${s.eligible} eligible, target ${s.target}" +
                (if (s.targetUnreachable) ", target unreachable from eligible boxes alone" else ""))
            case Failure(ex) =>
              logger.warn(s"Consolidation pass failed: ${ex.getMessage}")
          }
        case _ => ()
      }
      running -= key
      val retry = result match {
        // Ineligible for now, not wrong: the next attempt sees fresher state.
        case Success(_: Deferred) => true
        // The node saw the transaction. Rebuilding it would compete with the copy already in flight.
        case Failure(_: EngineBroadcast.SubmissionOutcomeException) => false
        // A malformed request cannot become valid by being retried.
        case Failure(_: IllegalArgumentException) => false
        case Failure(_) => true
        case _ => false
      }
      schedule.finish(key, attempt, retry, System.currentTimeMillis())
      // Only answer callers once the key is gone for good; a queued retry still owes them a result.
      if (!schedule.contains(key)) completeWaiters(key, result.fold(akka.actor.Status.Failure(_), identity))
      drive()

    case _: WorkFinished | _: Reconciled => ()
  }

  private def completeWaiters(key: String, value: Any): Unit = {
    waiters.getOrElse(key, Vector.empty).foreach(_ ! value)
    waiters -= key
  }

  /**
   * Validate, bound and queue one intent, attaching `reply` as a waiter unless the work is
   * timer-driven. Returns whether it was admitted; a refusal always answers with [[Deferred]].
   */
  private def admit(work: EngineIntent, reply: ActorRef, timing: Option[Timing] = None): Boolean = {
    val now = System.currentTimeMillis()
    val valid = work match {
      case EngineIntent.Dex(request, requestId) => DexIntent.fitsBudget(request) && requestId.length <= 128
      case EngineIntent.Rollups(stubs) =>
        stubs.nonEmpty && stubs.size <= 100 && stubs.forall(_.rollupBlockId.matches("[0-9a-f]{64}"))
      case EngineIntent.Join(request, requestId) =>
        request.count > 0 && request.count <= 10 && requestId.length <= 128
      case _ => true
    }
    val key = work.key
    // A one-off API request is never retried: the caller has already been answered by then.
    val policy = work match {
      case _: EngineIntent.Dex | _: EngineIntent.Join => OneOff
      case _: EngineIntent.Rollups => Critical
      case _ => Automatic
    }
    val when = timing.getOrElse(Immediate(now + DefaultExpiry.toMillis))
    val queueLimit = if (work.lane == Lane.Critical) MaxCriticalQueued else MaxOptionalQueued
    if (!valid || when.expires <= math.max(now, when.notBefore) || when.expires - now > MaxExpiry.toMillis ||
      (!schedule.contains(key) && schedule.size >= queueLimit)) {
      reply ! Deferred(key, "invalid intent, deadline, or admission limit")
      false
    } else if (waiters.getOrElse(key, Vector.empty).size >= MaxWaitersPerKey) {
      reply ! Deferred(key, "duplicate waiter limit")
      false
    } else {
      if (reply != context.system.deadLetters) waiters += key -> (waiters.getOrElse(key, Vector.empty) :+ reply)
      // Registering an existing key is a no-op, so a duplicate request coalesces onto the running one.
      schedule.register(Entry(key, work, work.lane, when, policy))
      drive()
      true
    }
  }

  /**
   * Start whatever may run now. Critical work is offered its lane first and unconditionally;
   * reconciliation and optional work share the other one, with reconciliation preferred so
   * uncertain inputs are resolved before more of them are created.
   */
  private def drive(preferReconciliation: Boolean = true): Unit = {
    val now = System.currentTimeMillis()
    schedule.next(Lane.Critical, now).foreach(start)
    if (!schedule.busy(Lane.Optional) && reconciling.isEmpty) {
      if (reconcileDue && (preferReconciliation || !schedule.nonEmpty)) {
        reconcileDue = false
        val attempt = UUID.randomUUID()
        reconciling = Some(attempt)
        Try(Future(reconciler.reconcile())(worker).onComplete(result => self ! Reconciled(attempt, result)))
          .failed.foreach(ex => self ! Reconciled(attempt, Failure(ex)))
      } else schedule.next(Lane.Optional, now).foreach(start)
    }
  }

  /**
   * Hand one entry to a worker. Rollup, emission and registration work already returns a Future on
   * its own dispatcher; the rest is wrapped here. Dispatch failure is reported as an ordinary
   * completion so a saturated pool cannot leave the entry stuck in `running`.
   */
  private def start(entry: Entry[EngineIntent]): Unit = {
    running += entry.key -> entry
    val stillEligible = () => alive.get() && System.currentTimeMillis() < entry.timing.expires
    val dispatched: Try[Future[Any]] = Try(entry.work match {
      case EngineIntent.Rollups(stubs) => newRollupExecution(stillEligible).execute(stubs)
      case work @ (_: EngineIntent.Join | EngineIntent.Queue | EngineIntent.Collateralize) => executeEmission(work)
      case EngineIntent.Register => Future(newRollupExecution(stillEligible).register())(worker)
      case work => Future {
        require(stillEligible(), "engine attempt expired")
        executeOptional(work, stillEligible)
      }(worker)
    })
    dispatched match {
      case Success(future) => future.onComplete(result => self ! WorkFinished(entry.key, entry.attempt.get, result))
      case Failure(ex) => self ! WorkFinished(entry.key, entry.attempt.get, Failure(ex))
    }
  }
  /** Blocking optional operations run on the engine worker after admission and expiry checks. */
  protected def executeOptional(work: EngineIntent, stillEligible: () => Boolean): Any = work match {
    case EngineIntent.Dex(request, _) => request.execute(dex, dexCache)
    case EngineIntent.Consolidate =>
      consolidation.execute(consolidationTarget, stillEligible,
        walletConfig.consolidation.minInputs, walletConfig.consolidation.transactions)
    case _ => throw new IllegalArgumentException("unsupported engine intent")
  }
}
