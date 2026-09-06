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
  private var active: Option[Active] = None
  private var pending = Vector.empty[(HoldingTransform, ActorRef)]
  private var reconciling: Option[UUID] = None
  private var reconcileDue = true
  private var ticker: Option[Cancellable] = None
  private lazy val dex = new DexExecution(node, transactions.engine.EngineFunding(self, 5.seconds, ec), () => alive.get()) {
    override protected def executionNode: _root_.node.NodeApi = TransactionEngine.this.nodeApi
  }
  private lazy val dexCache = new cache.LDCache(cacheApi)
  private var dexActive: Option[(UUID, ActorRef)] = None
  private var dexPending = Vector.empty[(DexIntent, ActorRef)]
  private var preferDex = true

  override def preStart(): Unit = {
    super.preStart()
    ticker = Some(context.system.scheduler.scheduleWithFixedDelay(15.seconds, 15.seconds, self, Reconcile))
    self ! Reconcile
  }
  override def postStop(): Unit = { alive.set(false); ticker.foreach(_.cancel()); super.postStop() }

  override def receive: Receive = engineReceive.orElse(super.receive)

  private def engineReceive: Receive = {
    case state.synchronization.CompleteMempool.Refresh =>
      mempool.forward(state.synchronization.CompleteMempool.Refresh)
    case intent: DexIntent if !DexIntent.fitsBudget(intent) || pending.size + dexPending.size >= MaxPending =>
      sender() ! akka.actor.Status.Failure(api.LithosApiErrors.LithosUnavailable("engine admission limit"))
    case intent: DexIntent => dexPending :+= intent -> sender(); drive()
    case DexFinished(id, result) if dexActive.exists(_._1 == id) =>
      val reply = dexActive.get._2
      result match {
        case Success(value) => reply ! value
        case Failure(ex) => reply ! akka.actor.Status.Failure(ex)
      }
      dexActive = None
      drive()
    case _: DexFinished => ()
    case Submit(intent) if !intent.valid => sender() ! Rejected(intent.key, "invalid holding-transform intent")
    case Submit(intent) =>
      active.filter(_.intent.key == intent.key) match {
        case Some(running) if running.waiters.size < MaxWaiters =>
          active = Some(running.copy(waiters = running.waiters :+ sender()))
        case Some(_) => sender() ! Deferred(intent.key, "duplicate waiter limit")
        case None if pending.exists(_._1.key == intent.key) => sender() ! Deferred(intent.key, "already queued")
        case None if pending.size + dexPending.size >= MaxPending => sender() ! Deferred(intent.key, "engine admission limit")
        case None => pending :+= intent -> sender(); drive()
      }
    case Finished(id, result) if active.exists(_.attempt == id) =>
      val entry = active.get
      val outcome = result.getOrElse(Deferred(entry.intent.key, result.failed.get.getMessage))
      entry.waiters.foreach(_ ! outcome)
      active = None
      drive()
    case Reconcile => reconcileDue = true; drive()
    case Reconciled(id, result) if reconciling.contains(id) =>
      reconciling = None
      result.failed.foreach(ex => context.system.log.warning("Engine reconciliation deferred: {}", ex.getMessage))
      drive(preferReconciliation = false)
    case _: Finished | _: Reconciled => ()
  }

  private def drive(preferReconciliation: Boolean = true): Unit = if (active.isEmpty && dexActive.isEmpty && reconciling.isEmpty) {
    if (reconcileDue && (preferReconciliation || (pending.isEmpty && dexPending.isEmpty))) {
      reconcileDue = false
      val id = UUID.randomUUID()
      reconciling = Some(id)
      dispatch(execution.reconcile())(r => Reconciled(id, r))
    } else if (dexPending.nonEmpty && (preferDex || pending.isEmpty)) {
      preferDex = false
      val (intent, reply) = dexPending.head
      dexPending = dexPending.tail
      val id = UUID.randomUUID()
      dexActive = Some(id -> reply)
      dispatch { require(alive.get(), "engine attempt was superseded"); intent.execute(dex, dexCache) }(r => DexFinished(id, r))
    } else if (pending.nonEmpty) {
      preferDex = true
      val (intent, reply) = pending.head
      pending = pending.tail
      val id = UUID.randomUUID()
      active = Some(Active(id, intent, Vector(reply)))
      dispatch(execution.execute(intent, () => alive.get()))(r => Finished(id, r))
    }
  }

  private def dispatch[A](body: => A)(reply: Try[A] => Any): Unit =
    Try(Future(Try(body))(worker).foreach(r => self ! reply(r)))
      .failed.foreach(ex => self ! reply(Failure(ex)))
}
