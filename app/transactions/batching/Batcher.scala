package transactions.batching

import akka.actor.{Actor, ActorRef, Cancellable}
import akka.pattern.ask
import akka.util.Timeout
import configs.{BatchingConfig, CandidateConfig, NodeContext, TasksConfig}
import node.NodeApi
import node.model.{MempoolOptions, NodeBox, Paging, SortDirection}
import org.ergoplatform.appkit.{BlockchainContext, SignedTransaction}
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import state.synchronization.CompleteMempool
import transactions.candidate.BlockTxMessages.{BlockTxsReady, CandidateTxsDropped, PrepareBlockTxs, RequestBlockTxs}
import transactions.candidate.{CandidateBundle, CandidatePreparation}
import transactions.engine.EngineBroadcast

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

/** Signed executions in spend order, as a broadcast pass sends them. */
trait BatchRun {
  def transactions: Vector[SignedTransaction]

  /** Box id of the order the execution at `index` fills. */
  def orderIdAt(index: Int): String

  /** Box id of the pool the execution at `index` spends. */
  def poolIdAt(index: Int): String

  /** Names the builder, so a refused transaction says where it came from. */
  def kind: String
}

/**
 * A batching adapter's actor: scans on a timer, builds candidate bundles and broadcast runs on a worker
 * dispatcher, and remembers which orders a node refused.
 *
 * Started from `Module`, independent of the stratum, so a node that does not mine can still broadcast.
 * A subclass supplies what a scan tracks, what a build offers and what a broadcast sends. Everything
 * deciding when those run, how a saturated worker is handled and which orders are not retried is here.
 *
 * @param servesCandidates whether this miner's stratum asks for block transactions. When false, every
 *                         candidate request is answered with nothing.
 * @param dispatcherKey    the worker dispatcher. Each adapter needs its own: a build blocks for up to
 *                         [[Batcher.ObservationTimeout]] on a mempool walk, and
 *                         [[Batcher.MaxBuildsRunning]] is sized against one adapter's queue.
 * @param buildBudgetMs    how long one candidate build may run. The stratum waits for every source up to
 *                         one shared deadline and drops the whole block's extras when any is late, so this
 *                         sits under that deadline.
 */
abstract class Batcher(nodeContext: NodeContext,
                       batching: BatchingConfig,
                       servesCandidates: Boolean,
                       engine: ActorRef,
                       dispatcherKey: String,
                       buildBudgetMs: Long) extends Actor {

  import Batcher._

  /** Names the adapter in log lines. */
  protected def label: String

  protected val logger: Logger = LoggerFactory.getLogger(getClass.getSimpleName)

  protected val worker: ExecutionContext =
    context.system.dispatchers.lookup(configs.Contexts.key(dispatcherKey))
  /** Delivers completions only. Kept off the worker so a full worker queue cannot drop one. */
  private val completions: ExecutionContext = context.dispatcher

  /** Counts unfinished builds to keep worker saturation from running work on the mailbox. */
  private val buildsRunning = new AtomicInteger(0)
  private val preparation =
    new CandidatePreparation(worker, self, onStart = _ => buildsRunning.incrementAndGet())

  protected def nodeApi: NodeApi = nodeContext.getNodeApi

  /** Cleared on stop, so a broadcast still running sends nothing further. */
  protected val alive = new AtomicBoolean(true)

  /** Orders to read back at build time. Replaced whole by each successful scan. */
  private var tracked: Tracked = Map.empty

  /** Rejected orders mapped to the pool box used; retries resume when that box changes. */
  private var rejected: Map[String, String] = Map.empty

  /** A scan, or the broadcast that follows it, is running. */
  private var busy: Boolean = false
  private var ticker: Option[Cancellable] = None

  /**
   * Order box ids that priced but could not be built, each with the time it was last refused. Written by
   * workers, so it is an atomic snapshot rather than actor state. Each entry is a hex id and a timestamp.
   */
  private val skipList = new AtomicReference(Map.empty[String, Long])

  /** Wallet placements this adapter carried into candidates, added back to observations after the node evicts them. */
  protected val evictedPlacements = new EvictedPlacements

  /** Scans for executable orders. Runs on the worker; a failure leaves the previous set tracked. */
  protected def discover(): Tracked

  /** The bundles offered for one block, built before `deadline`. Runs on the worker; a failure offers nothing. */
  protected def executions(orders: Tracked, blockHeight: Int, slots: Int, deadline: Deadline): Seq[CandidateBundle]

  /** Sends runs to the mempool. Returns order id to pool box id for each refused execution. */
  protected def broadcastPass(orders: Tracked, refused: Map[String, String]): Map[String, String]

  override def preStart(): Unit = {
    val name = getClass.getSimpleName
    if (!batching.enabled)
      logger.info(s"$name was disabled")
    // Scans are a standing load on the node, so they run only when something uses what they find
    else if (!batching.broadcast && !servesCandidates)
      logger.info(s"$name is idle: broadcast is off and no stratum asks it for block transactions")
    else {
      logger.info(s"$name started: scanIntervalMs=${batching.scanIntervalMs}, " +
        s"maxTrackedOrders=${batching.maxTrackedOrders}, maxOrdersPerBlock=${batching.maxOrdersPerBlock}, " +
        s"broadcast=${batching.broadcast}, servesCandidates=$servesCandidates")
      ticker = Some(context.system.scheduler.scheduleWithFixedDelay(
        1.second, batching.scanIntervalMs.milliseconds, self, ScanTick)(context.dispatcher))
    }
  }

  override def postStop(): Unit = { alive.set(false); ticker.foreach(_.cancel()) }

  override def receive: Receive = batchingReceive.orElse(preparation.receive)

  private def batchingReceive: Receive = {

    case ScanTick if !busy =>
      busy = true
      offMailbox(discover())(Scanned)

    case ScanTick => ()

    case Scanned(Success(found)) =>
      if (found.size != tracked.size)
        logger.info(s"$label scan holds ${found.size} executable order(s) across " +
          s"${found.values.toSet.size} pool(s)")
      tracked = found
      rejected = rejected.filter { case (orderId, _) => tracked.contains(orderId) }
      if (batching.broadcast && tracked.nonEmpty) {
        val (orders, refused) = (tracked, rejected)
        offMailbox(broadcastPass(orders, refused))(Broadcasted)
      } else busy = false

    case Scanned(Failure(ex)) =>
      busy = false
      // The previous set stands: ids that went stale are dropped when a build fails to read them.
      logger.warn(s"$label scan failed, keeping ${tracked.size} tracked order(s): ${ex.getMessage}")

    case Broadcasted(result) =>
      busy = false
      result match {
        case Success(refused) =>
          rejected = (rejected ++ refused).filter { case (orderId, _) => tracked.contains(orderId) }
        case Failure(ex) => logger.warn(s"$label broadcast pass failed: ${ex.getMessage}")
      }

    // Read back as spent while a build ran. Nothing brings a spent order back.
    case Spent(ids) =>
      tracked --= ids
      rejected --= ids

    case PrepareBlockTxs(blockHeight, limit) => startBuild(blockHeight, limit, None)

    case RequestBlockTxs(blockHeight, limit, refresh) =>
      val replyTo = sender()
      preparation.preparedFor(blockHeight).filterNot(_ => refresh) match {
        case Some(bundles) => replyTo ! BlockTxsReady(blockHeight, bundles)
        case None => startBuild(blockHeight, limit, Some(replyTo))
      }

    // Nothing to reconcile: a candidate execution holds no wallet input and was never broadcast.
    case CandidateTxsDropped(blockHeight) => preparation.drop(blockHeight)

    case HeldPlacements => sender() ! Held(evictedPlacements.placements)
  }

  /** Stops reading back orders a build found spent. Safe to call from the worker. */
  protected def forget(ids: Set[String]): Unit = if (ids.nonEmpty) self ! Spent(ids)

  /** Run `work` on the worker and deliver its result as `wrap`, including a refused submission. */
  private def offMailbox[T](work: => T)(wrap: Try[T] => Any): Unit =
    Try(Future(work)(worker).onComplete(result => self ! wrap(result))(completions))
      .failed.foreach(ex => self ! wrap(Failure(ex)))

  private def startBuild(blockHeight: Int, limit: Int, replyTo: Option[ActorRef]): Unit = {
    val slots = math.min(limit, batching.maxOrdersPerBlock)
    // Built even with nothing tracked, because the mempool can hold orders the scan never sees.
    if (!batching.enabled || !servesCandidates || slots <= 0) preparation.answerEmpty(blockHeight, replyTo)
    else if (buildsRunning.get >= MaxBuildsRunning) {
      logger.warn(s"$MaxBuildsRunning $label builds are still running; offering nothing for block $blockHeight")
      preparation.answerEmpty(blockHeight, replyTo)
    } else {
      // Capture actor state before handing the build to a worker.
      val orders = tracked
      preparation.start(blockHeight, replyTo) {
        val deadline = Deadline.now + buildBudgetMs.milliseconds
        try executions(orders, blockHeight, slots, deadline)
        catch {
          case late: BuildDeadlinePassed =>
            logger.warn(s"$label build for block $blockHeight ran past its ${buildBudgetMs}ms budget " +
              s"${late.getMessage}, offering nothing")
            Seq.empty[CandidateBundle]
          case NonFatal(ex) =>
            logger.warn(s"$label build for block $blockHeight failed, offering nothing: ${ex.getMessage}")
            Seq.empty[CandidateBundle]
        } finally buildsRunning.decrementAndGet()
      }
    }
  }

  /** Stops a build that has used its budget. `doing` finishes "ran past its budget ...". */
  protected def within(deadline: Deadline, doing: String): Unit =
    if (deadline.isOverdue()) throw new BuildDeadlinePassed(doing)

  /** Orders to leave out of scans and builds: refused within the last `skippedOrderTtlMs`. */
  protected def skippedOrders(): Set[String] = {
    val cutoff = System.currentTimeMillis() - batching.skippedOrderTtlMs
    skipList.get.collect { case (id, at) if at > cutoff => id }.toSet
  }

  /** Leaves the order `id` out until `skippedOrderTtlMs` passes, keeping the newest `maxSkippedOrders`. */
  protected def skipOrder(id: String): Unit = {
    val now = System.currentTimeMillis()
    skipList.updateAndGet { held =>
      val live = held.filter { case (_, at) => at > now - batching.skippedOrderTtlMs } + (id -> now)
      if (live.size <= batching.maxSkippedOrders) live
      else live.toSeq.sortBy(-_._2).take(batching.maxSkippedOrders).toMap
    }
  }

  /** The parsed orders at one template, newest first, capped at [[MaxScannedPerTemplate]] boxes. */
  protected def scanTemplate[O](templateHash: String, name: String)(parse: NodeBox => Option[O]): Vector[O] = {
    var offset = 0
    var exhausted = false
    var found = Vector.empty[O]
    while (!exhausted && offset < MaxScannedPerTemplate) {
      val page = nodeApi.unspentBoxesByTemplateHash(templateHash, Paging(offset, PageSize),
        SortDirection.Desc, MempoolOptions.ConfirmedOnly).get
      found ++= page.flatMap(indexed => parse(indexed.box))
      exhausted = page.size < PageSize
      offset += page.size
    }
    if (!exhausted)
      logger.warn(s"$label scan stopped after $MaxScannedPerTemplate $name boxes; " +
        "older orders were not read this pass")
    found
  }

  /**
   * The placement chains whose every transaction `accepts`, keyed by the placing transaction's id.
   * Confirmed inputs load in one call, and `accepts` is given every input box of the chain.
   */
  protected def walletPlacements(txs: Seq[CompleteMempool.MempoolTx],
                                 creators: Map[String, CompleteMempool.MempoolTx],
                                 spenders: BatchingMempool.Spenders,
                                 limit: Int,
                                 accepts: (CompleteMempool.MempoolTx, Map[String, NodeBox]) => Boolean)
  : Map[String, Vector[CompleteMempool.MempoolTx]] = {
    val chains = txs.groupBy(_.id).values.map(_.head).toSeq.flatMap(tx =>
      BatchingMempool.placementChain(tx, creators, limit).map(tx.id -> _))
    val distinct = chains.flatMap(_._2).groupBy(_.id).values.map(_.head).toSeq
    val inputIds = distinct.flatMap(_.body.inputs.map(_.boxId)).distinct.filterNot(creators.contains)
    val confirmed =
      if (inputIds.isEmpty) Map.empty[String, NodeBox]
      else nodeApi.boxesWithPoolByIds(inputIds).get.map(box => box.boxId -> box).toMap
    val inputBoxes = confirmed ++ distinct.flatMap(_.body.outputs.map(box => box.boxId -> box))
    val valid = distinct.filter(accepts(_, inputBoxes)).map(_.id).toSet
    chains.filter { case (_, chain) => chain.forall(tx => valid.contains(tx.id)) }.toMap
  }

  /** A complete mempool observation, waiting no longer than [[ObservationTimeout]] or what `deadline` leaves. */
  protected def observation(deadline: Option[Deadline] = None): CompleteMempool.Observation = {
    val wait = deadline.map(_.timeLeft min ObservationTimeout).getOrElse(ObservationTimeout)
    if (wait <= Duration.Zero) throw new BuildDeadlinePassed("before asking for a mempool observation")
    try Await.result((engine ? CompleteMempool.Refresh)(Timeout(wait)).mapTo[CompleteMempool.Observation], wait)
    catch {
      case _: java.util.concurrent.TimeoutException if wait < ObservationTimeout =>
        throw new BuildDeadlinePassed("waiting for a mempool observation")
    }
  }

  /** Sends a run in order, stopping on any unaccepted send or exception. Returns a refused order. */
  protected def send(sender: EngineBroadcast, run: BatchRun,
                     observed: CompleteMempool.Observation): Option[(String, String)] = {
    var index = 0
    var refused = Option.empty[(String, String)]
    var going = true
    while (going && index < run.transactions.size) {
      val orderId = run.orderIdAt(index)
      Try(sender.sendOwned(run.transactions(index), Seq.empty, run.kind,
        () => alive.get(), observed = Some(observed))) match {
        case Success(sent) if sent.outcome == EngineBroadcast.Accepted =>
          logger.info(s"Broadcast $label execution ${sent.txId} of order $orderId")
        case Success(sent) =>
          going = false
          logger.warn(s"$label execution ${sent.txId} of order $orderId was ${sent.outcome}: " +
            s"${sent.reason.getOrElse("no reason given")}; the rest of its run is not sent")
          if (sent.outcome == EngineBroadcast.Rejected) refused = Some(orderId -> run.poolIdAt(index))
        case Failure(ex) =>
          going = false
          logger.warn(s"$label execution of order $orderId was not sent: ${ex.getMessage}")
      }
      index += 1
    }
    refused
  }

  /** A sender for one broadcast pass, on this adapter's worker. */
  protected def broadcaster(): EngineBroadcast = new EngineBroadcast(engine, nodeApi, ObservationTimeout)(worker)
}

object Batcher {

  /**
   * Whether this miner's stratum will ask the named batcher for block transactions: the stratum task
   * runs, it inserts block transactions at all, the batcher is enabled, and its candidate source is on.
   * `name` is both the batching block and the candidate source key.
   */
  def servesCandidates(config: Configuration, name: String): Boolean = {
    val candidate = CandidateConfig(config)
    TasksConfig.isEnabled(config, TasksConfig.StratumServer) && candidate.blockTransactions &&
      BatchingConfig(config, name).enabled && candidate.sources.get(name).exists(_.enabled)
  }

  /** Orders held between scans, as order box id to the pool NFT that order names. */
  type Tracked = Map[String, String]

  /** Scheduler tick: rescan, then broadcast if that is on. */
  private[batching] case object ScanTick

  private[batching] final case class Scanned(result: Try[Tracked])

  private[batching] final case class Broadcasted(result: Try[Map[String, String]])

  /** Orders a build found already spent, so they stop being read back. */
  private[batching] final case class Spent(ids: Set[String])

  /** Asks for the wallet placements this batcher carried into candidates, answered with [[Held]]. */
  case object HeldPlacements

  /** Placements the node may have evicted from its mempool after a candidate carried them. */
  final case class Held(placements: Vector[CompleteMempool.MempoolTx])

  /** Boxes per indexer page. */
  final val PageSize = 100

  /** Maximum boxes read from one template during a scan. */
  final val MaxScannedPerTemplate = 2000

  /** Ask timeout for the mempool observation. Above the walk's own 30-second deadline. */
  final val ObservationTimeout: FiniteDuration = 40.seconds

  /** Together with one scan, these builds fit a worker of two threads and four queued tasks. */
  final val MaxBuildsRunning = 3

  /** Maximum unconfirmed orders examined during one build. */
  final val MaxMempoolOrders = 64

  /** Orders one run passes over as unbuildable before it stops trying: about 0.25 s of failed signing. */
  final val MaxUnbuildablePerRun = 32

  /**
   * A candidate build's budget: three quarters of the stratum's collection deadline, leaving the rest for
   * the reply and package admission. The build starts when the height is known, before collection does.
   */
  def buildBudgetMs(config: Configuration): Long = CandidateConfig(config).blockTxTimeout * 3L / 4L

  /** The same for a batcher built without a configuration. */
  final val DefaultBuildBudgetMs: Long = CandidateConfig.Default.blockTxTimeout * 3L / 4L

  /** A build used its budget; the message says what it was doing. */
  final class BuildDeadlinePassed(doing: String) extends RuntimeException(doing)

  /**
   * `signed`, or an exception when an output holds less than the node's minimum for its size. Signing does
   * not check this and the node drops such a transaction from a candidate without a word. An order's owner
   * script sets its reward box's size, so an order can make its own reward too small to exist.
   */
  def withNodeMinimums(ctx: BlockchainContext, signed: SignedTransaction): SignedTransaction = {
    val perByte = ctx.getDataSource.getParameters.getMinValuePerByte.toLong
    signed.getOutputsToSpend.asScala.zipWithIndex.foreach { case (box, index) =>
      val bytes = box.getBytes.length
      require(box.getValue >= bytes * perByte,
        s"output $index holds ${box.getValue} nanoERG, under the node's ${bytes * perByte} minimum for $bytes bytes")
    }
    signed
  }
}
