package transactions.engine

import akka.actor.{Actor, ActorRef, Cancellable}
import configs.NodeContext
import javax.inject.{Inject, Named}
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object TransactionEngine {
  case object RegisterMiner
  final case class JoinCollateral(request: api.models.CollateralJoinExecuteRequest)
  final case class HoldingTransform(blockId: String, period: Long, fee: Long) {
    def key: String = s"holding:$blockId:$period"
    def valid: Boolean = blockId.matches("[0-9a-f]{64}") && period >= 0 &&
      fee == transactions.rollups.TransactionMessages.RollupTxStub.ROLLUP_FEE
  }
  final case class Submit(intent: HoldingTransform)
  final case class Schedule(intent: EngineIntent, notBeforeMillis: Long, expiresAtMillis: Long)
  case object GetConsolidationStatus
  private case object Maintenance
  private case class WorkFinished(key: String, attempt: UUID, result: Try[Any])
  sealed trait Outcome { def key: String }
  final case class Accepted(key: String, txId: String) extends Outcome
  final case class Uncertain(key: String, txId: String, reason: String) extends Outcome
  final case class Rejected(key: String, reason: String, txId: Option[String] = None) extends Outcome
  final case class Completed(key: String) extends Outcome
  final case class Deferred(key: String, reason: String) extends Outcome
  private[engine] case object Reconcile
  private case class Finished(attempt: UUID, result: Try[Outcome])
  private case class Reconciled(attempt: UUID, result: Try[Unit])
  private case class DexFinished(attempt: UUID, result: Try[Any])
  private final val MaxPending = 32
  private final val MaxWaiters = 8
  private case class Active(attempt: UUID, intent: HoldingTransform, waiters: Vector[ActorRef])
}

/** Owns admission and attempt identity. One bounded worker performs the migrated optional path;
  * existing time-sensitive NISP and fraud work keeps its independent execution capacity.
  */
class TransactionEngine @Inject()(node: NodeContext,
  @Named("sync-handler") sync: ActorRef,
  @Named("mempool-view") mempool: ActorRef,
  override protected val cacheApi: play.api.cache.SyncCacheApi,
  override protected val config: play.api.Configuration,
  override protected val dataBoxes: transactions.rollups.DataBoxSource)
  extends EngineWalletState(node) with transactions.rollups.EngineRollups with transactions.emissions.EngineEmissions {
  import TransactionEngine._
  override protected def rollupNodeContext: NodeContext = node
  override protected def syncHandler: ActorRef = sync
  override protected def mempoolView: ActorRef = mempool
  override protected def walletManager: ActorRef = self
  override protected def rollupNodeApi: _root_.node.NodeApi = nodeApi
  override protected def emissionNodeContext: NodeContext = node
  override protected def emissionWalletManager: ActorRef = self
  override protected def emissionNodeApi: _root_.node.NodeApi = nodeApi
  override lazy val nodeApi: _root_.node.NodeApi = _root_.node.rest.RestNodeApi(
    _root_.node.rest.NodeHttpConfig(node.getNodeUrl, Some(node.getNodeKey),
      maxResponseBytes = 2 * 1024 * 1024, callTimeoutMs = 10000L))
  private val worker = context.system.dispatchers.lookup("lithos-contexts.engine-io-dispatcher")
  private val alive = new AtomicBoolean(true)
  protected lazy val execution = new HoldingTransformExecution(node, self, sync, mempool)
  import ExecutionSchedule._
  private val schedule = new ExecutionSchedule[EngineIntent]()
  private var waiters = Map.empty[String, Vector[ActorRef]]
  private var running = Map.empty[String, Entry[EngineIntent]]
  private var reconciling: Option[UUID] = None
  private var reconcileDue = true
  private var nextMaintenance = 0L
  private var ticker: Option[Cancellable] = None
  private val consolidationEnabled = config.getOptional[Boolean]("transaction-engine.consolidation.enabled").getOrElse(false)
  private val consolidationTarget = config.getOptional[Int]("transaction-engine.consolidation.target-utxos").getOrElse(100)
  require(consolidationTarget > 0, "consolidation target must be positive")
  private var consolidationStatus = ConsolidationExecution.Status(0, consolidationTarget, 0, None, None, None,
    false, if (consolidationEnabled) "not-observed" else "disabled")
  private lazy val consolidation = new ConsolidationExecution(node, nodeApi, self)
  private lazy val dex = new DexExecution(node, EngineFunding(self, 5.seconds, ec), () => alive.get()) {
    override protected def executionNode: _root_.node.NodeApi = TransactionEngine.this.nodeApi
  }
  private lazy val dexCache = new cache.LDCache(cacheApi)

  override def preStart(): Unit = {
    super.preStart()
    ticker = Some(context.system.scheduler.scheduleWithFixedDelay(1.second, 1.second, self, Maintenance))
    self ! Reconcile
  }
  override def postStop(): Unit = { alive.set(false); ticker.foreach(_.cancel()); super.postStop() }
  override def receive: Receive = engineReceive.orElse(super.receive)

  private def engineReceive: Receive = {
    case state.synchronization.CompleteMempool.Refresh => mempool.forward(state.synchronization.CompleteMempool.Refresh)
    case GetConsolidationStatus => sender() ! consolidationStatus
    case Submit(intent) if !intent.valid => sender() ! Rejected(intent.key, "invalid holding-transform intent")
    case Submit(intent) => admit(EngineIntent.Holding(intent), sender())
    case intent: DexIntent => admit(EngineIntent.Dex(intent), sender())
    case JoinCollateral(request) => admit(EngineIntent.Join(request), sender())
    case RegisterMiner => admit(EngineIntent.Register, sender())
    case transactions.rollups.TransactionMessages.RollupBatch(stubs) if stubs.nonEmpty && stubs.size <= 100 =>
      if (admit(EngineIntent.Rollups(stubs.toVector), context.system.deadLetters))
        sender() ! transactions.rollups.TransactionMessages.BatchAccepted(stubs)
    case _: transactions.rollups.TransactionMessages.RollupBatch => ()
    case transactions.emissions.EmissionHandler.DriveQueue => admit(EngineIntent.Queue, context.system.deadLetters)
    case transactions.emissions.EmissionHandler.Collateralize => admit(EngineIntent.Collateralize, context.system.deadLetters)
    case Schedule(intent, due, expiry) => admit(intent, sender(), Some(Scheduled(due, expiry)))
    case Maintenance =>
      val now = System.currentTimeMillis()
      schedule.expire(now).foreach(e => completeWaiters(e.key, Deferred(e.key, "intent expired before dispatch")))
      if (now >= nextMaintenance) {
        nextMaintenance = now + 15000L
        reconcileDue = true
        if (consolidationEnabled && !schedule.nonEmpty && reconciling.isEmpty)
          admit(EngineIntent.Consolidate, context.system.deadLetters)
      }
      drive()
    case Reconcile => reconcileDue = true; drive()
    case Reconciled(id, result) if reconciling.contains(id) =>
      reconciling = None
      result.failed.foreach(ex => context.system.log.warning("Engine reconciliation deferred: {}", ex.getMessage))
      drive(preferReconciliation = false)
    case WorkFinished(key, attempt, result) if running.get(key).exists(_.attempt.contains(attempt)) =>
      val entry = running(key)
      entry.work match {
        case _: EngineIntent.Rollups => finishRollupBatch()
        case _: EngineIntent.Join | EngineIntent.Queue | EngineIntent.Collateralize => finishEmission()
        case EngineIntent.Consolidate =>
          consolidationStatus = result.map(_.asInstanceOf[ConsolidationExecution.Status])
            .getOrElse(consolidationStatus.copy(outcome = "deferred"))
        case _ => ()
      }
      running -= key
      val retry = result match {
        case Success(_: Deferred) => true
        case Failure(_: EngineBroadcast.SubmissionOutcomeException) => false
        case Failure(_: IllegalArgumentException) => false
        case Failure(_) => true
        case _ => false
      }
      schedule.finish(key, attempt, retry, System.currentTimeMillis())
      if (!schedule.contains(key)) completeWaiters(key, result.fold(akka.actor.Status.Failure(_), identity))
      drive()
    case _: WorkFinished | _: Reconciled => ()
  }

  private def completeWaiters(key: String, value: Any): Unit = {
    waiters.getOrElse(key, Vector.empty).foreach(_ ! value)
    waiters -= key
  }
  private def admit(work: EngineIntent, reply: ActorRef, timing: Option[Timing] = None): Boolean = {
    val now = System.currentTimeMillis()
    val valid = work match {
      case EngineIntent.Holding(value) => value.valid
      case EngineIntent.Dex(value, id) => DexIntent.fitsBudget(value) && id.length <= 128
      case EngineIntent.Rollups(stubs) => stubs.nonEmpty && stubs.size <= 100 && stubs.forall(_.rollupBlockId.matches("[0-9a-f]{64}"))
      case EngineIntent.Join(request, id) => request.count > 0 && request.count <= 10 && id.length <= 128
      case _ => true
    }
    val key = work.key
    val policy = work match {
      case _: EngineIntent.Dex | _: EngineIntent.Join => OneOff
      case _: EngineIntent.Rollups => Critical
      case _ => Automatic
    }
    val when = timing.getOrElse(Immediate(now + 5.minutes.toMillis))
    if (!valid || when.expires <= math.max(now, when.notBefore) || when.expires - now > 7.days.toMillis ||
      (!schedule.contains(key) && schedule.size >= (if (work.lane == "critical") 128 else 32))) {
      reply ! Deferred(key, "invalid intent, deadline, or admission limit")
      false
    } else if (waiters.getOrElse(key, Vector.empty).size >= 8) {
      reply ! Deferred(key, "duplicate waiter limit")
      false
    } else {
      if (reply != context.system.deadLetters) waiters += key -> (waiters.getOrElse(key, Vector.empty) :+ reply)
      schedule.register(Entry(key, work, work.lane, when, policy))
      drive()
      true
    }
  }

  private def drive(preferReconciliation: Boolean = true): Unit = {
    val now = System.currentTimeMillis()
    schedule.next("critical", now).foreach(start)
    if (!schedule.busy("optional") && reconciling.isEmpty) {
      if (reconcileDue && (preferReconciliation || !schedule.nonEmpty)) {
        reconcileDue = false
        val id = UUID.randomUUID()
        reconciling = Some(id)
        Try(Future(execution.reconcile())(worker).onComplete(r => self ! Reconciled(id, r)))
          .failed.foreach(ex => self ! Reconciled(id, Failure(ex)))
      } else schedule.next("optional", now).foreach(start)
    }
  }
  private def start(entry: Entry[EngineIntent]): Unit = {
    running += entry.key -> entry
    val valid = () => alive.get() && System.currentTimeMillis() < entry.timing.expires
    val dispatched: Try[Future[Any]] = Try(entry.work match {
      case EngineIntent.Rollups(stubs) => executeRollupBatch(stubs)
      case work @ (_: EngineIntent.Join | EngineIntent.Queue | EngineIntent.Collateralize) => executeEmission(work)
      case EngineIntent.Register => executeRegistration()
      case work => Future {
        require(valid(), "engine attempt expired")
        work match {
          case EngineIntent.Holding(value) => execution.execute(value, valid)
          case EngineIntent.Dex(value, _) => value.execute(dex, dexCache)
          case EngineIntent.Consolidate => consolidation.execute(consolidationTarget, valid)
          case _ => throw new IllegalArgumentException("unsupported engine intent")
        }
      }(worker)
    })
    dispatched match {
      case Success(future) => future.onComplete(r => self ! WorkFinished(entry.key, entry.attempt.get, r))
      case Failure(ex) => self ! WorkFinished(entry.key, entry.attempt.get, Failure(ex))
    }
  }
}
