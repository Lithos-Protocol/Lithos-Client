package transactions.batching.ergodex

import akka.actor.{Actor, ActorRef, Cancellable}
import akka.pattern.ask
import akka.util.Timeout
import configs.{BatchingConfig, CandidateSourceConfig, NodeContext}
import node.MutationConversions._
import node.NodeApi
import node.model.{IndexedBox, MempoolOptions, NodeBox, Paging, SortDirection}
import org.ergoplatform.appkit.BlockchainContext
import org.slf4j.{Logger, LoggerFactory}
import state.synchronization.CompleteMempool
import transactions.candidate.BlockTxMessages.{BlockTxsReady, CandidateTx, CandidateTxsDropped, PrepareBlockTxs, RequestBlockTxs}
import transactions.candidate.{CandidateBundle, CandidatePreparation}
import transactions.engine.EngineBroadcast

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

/** Scans executable native orders and builds candidate or broadcast runs on background workers. */
class ErgoDexSource(nodeContext: NodeContext,
                    batching: BatchingConfig,
                    limits: CandidateSourceConfig,
                    engine: ActorRef,
                    useTrueProp: Boolean) extends Actor {

  import ErgoDexSource._

  private val logger: Logger = LoggerFactory.getLogger("ErgoDexSource")

  private val worker: ExecutionContext =
    context.system.dispatchers.lookup(configs.Contexts.key(configs.Contexts.BatchingIo))
  /** Delivers completions only. Kept off the worker so a full worker queue cannot drop one. */
  private val completions: ExecutionContext = context.dispatcher

  /** Counts unfinished builds to keep worker saturation from running work on the mailbox. */
  private val buildsRunning = new AtomicInteger(0)
  private val preparation =
    new CandidatePreparation(worker, self, onStart = _ => buildsRunning.incrementAndGet())

  protected def nodeApi: NodeApi = nodeContext.getNodeApi

  /** Cleared on stop, so a broadcast still running sends nothing further. */
  private val alive = new AtomicBoolean(true)

  /** Orders to read back at build time. Replaced whole by each successful scan. */
  private var tracked: ErgoDexBatching.Tracked = Map.empty

  /** Rejected orders mapped to the pool box used; retries resume when that box changes. */
  private var rejected: Map[String, String] = Map.empty

  /** A scan, or the broadcast that follows it, is running. */
  private var busy: Boolean = false
  private var ticker: Option[Cancellable] = None

  override def preStart(): Unit = {
    logger.info(s"ErgoDexSource started: scanIntervalMs=${batching.scanIntervalMs}, " +
      s"maxTrackedOrders=${batching.maxTrackedOrders}, maxOrdersPerBlock=${batching.maxOrdersPerBlock}, " +
      s"broadcast=${batching.broadcast}")
    ticker = Some(context.system.scheduler.scheduleWithFixedDelay(
      1.second, batching.scanIntervalMs.milliseconds, self, ScanTick)(context.dispatcher))
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
        logger.info(s"ErgoDEX scan holds ${found.size} executable order(s) across " +
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
      logger.warn(s"ErgoDEX scan failed, keeping ${tracked.size} tracked order(s): ${ex.getMessage}")

    case Broadcasted(result) =>
      busy = false
      result match {
        case Success(refused) =>
          rejected = (rejected ++ refused).filter { case (orderId, _) => tracked.contains(orderId) }
        case Failure(ex) => logger.warn(s"ErgoDEX broadcast pass failed: ${ex.getMessage}")
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
  }

  /** Run `work` on the worker and deliver its result as `wrap`, including a refused submission. */
  private def offMailbox[T](work: => T)(wrap: Try[T] => Any): Unit =
    Try(Future(work)(worker).onComplete(result => self ! wrap(result))(completions))
      .failed.foreach(ex => self ! wrap(Failure(ex)))

  private def startBuild(blockHeight: Int, limit: Int, replyTo: Option[ActorRef]): Unit = {
    val slots = math.min(limit, batching.maxOrdersPerBlock)
    // Built even with nothing tracked, because the mempool can hold orders the scan never sees.
    if (!limits.enabled || slots <= 0) preparation.answerEmpty(blockHeight, replyTo)
    else if (buildsRunning.get >= MaxBuildsRunning) {
      logger.warn(s"$MaxBuildsRunning ErgoDEX builds are still running; offering nothing for block $blockHeight")
      preparation.answerEmpty(blockHeight, replyTo)
    } else {
      // Capture actor state before handing the build to a worker.
      val orders = tracked
      preparation.start(blockHeight, replyTo) {
        try executions(orders, blockHeight, slots)
        catch {
          case NonFatal(ex) =>
            logger.warn(s"ErgoDEX build for block $blockHeight failed, offering nothing: ${ex.getMessage}")
            Seq.empty[CandidateBundle]
        } finally buildsRunning.decrementAndGet()
      }
    }
  }

  /** Scans executable templates and their pools. A failed read leaves the previous tracked set intact. */
  private def discover(): ErgoDexBatching.Tracked = {
    val tip = nodeApi.info().get.fullHeight.getOrElse(
      throw new IllegalStateException("the node did not report a height"))
    val orders = ErgoDexContracts.orders.filter(_.executable).flatMap(scanTemplate)
    val nfts = orders.map(_.poolNft).distinct.filterNot(batching.deniedPools.contains)
    if (nfts.size > MaxPoolReads)
      logger.warn(s"ErgoDEX orders name ${nfts.size} pools; reading the first $MaxPoolReads this pass")
    val pools = nfts.take(MaxPoolReads).flatMap(nft => confirmedPool(nft).flatMap(indexed =>
      ErgoDexPool.native(indexed.box).map(_ -> indexed.inclusionHeight)))
    ErgoDexBatching.track(orders, pools, tip, batching)
  }

  /** The parsed orders at one template, newest first, capped at [[MaxScannedPerTemplate]] boxes. */
  private def scanTemplate(contract: OrderContract): Vector[ErgoDexOrder] = {
    var offset = 0
    var exhausted = false
    var found = Vector.empty[ErgoDexOrder]
    while (!exhausted && offset < MaxScannedPerTemplate) {
      val page = nodeApi.unspentBoxesByTemplateHash(contract.templateHash, Paging(offset, PageSize),
        SortDirection.Desc, MempoolOptions.ConfirmedOnly).get
      found ++= page.flatMap(indexed => ErgoDexOrder.parse(indexed.box))
      exhausted = page.size < PageSize
      offset += page.size
    }
    if (!exhausted)
      logger.warn(s"ErgoDEX scan stopped after $MaxScannedPerTemplate ${contract.name} boxes; " +
        "older orders were not read this pass")
    found
  }

  /** Returns the uniquely indexed confirmed box carrying the pool NFT. */
  private def confirmedPool(nft: String): Option[IndexedBox] = {
    val boxes = nodeApi.unspentBoxesByTokenId(nft, Paging(0, 2), SortDirection.Desc,
      MempoolOptions.ConfirmedOnly).get
    if (boxes.size == 1) boxes.headOption else None
  }

  /** Rechecks indexed pool boxes against the UTXO set to exclude spent boxes. */
  private def currentPools(nfts: Seq[String]): Map[String, NodeBox] = {
    val listed = nfts.flatMap(nft => confirmedPool(nft).map(_.boxId -> nft)).toMap
    if (listed.isEmpty) Map.empty
    else nodeApi.boxesWithPoolByIds(listed.keys.toSeq).get
      .flatMap(box => listed.get(box.boxId).map(_ -> box)).toMap
  }

  /** Reloads tracked orders, retaining mempool-claimed boxes and reporting missing ids as spent. */
  private def liveOrders(orders: ErgoDexBatching.Tracked): Seq[ErgoDexOrder] = {
    val live = if (orders.isEmpty) Seq.empty[NodeBox] else nodeApi.boxesWithPoolByIds(orders.keys.toSeq).get
    val gone = orders.keySet -- live.map(_.boxId)
    if (gone.nonEmpty) self ! Spent(gone)
    live.flatMap(ErgoDexOrder.parse).filter(order => orders.get(order.boxId).contains(order.poolNft))
  }

  private def observation(): CompleteMempool.Observation =
    Await.result((engine ? CompleteMempool.Refresh)(Timeout(ObservationTimeout))
      .mapTo[CompleteMempool.Observation], ObservationTimeout)

  /** Builds fee-less runs against confirmed pools, carrying verified P2PK ancestry for mempool orders. */
  private def executions(orders: ErgoDexBatching.Tracked, blockHeight: Int,
                         slots: Int): Seq[CandidateBundle] = {
    val observed = observation()
    if (!observed.fresh) {
      logger.info(s"No fresh mempool observation for block $blockHeight, offering no ErgoDEX " +
        s"executions: ${observed.failure.getOrElse("observation too old")}")
      Seq.empty
    } else nodeContext.getClient.execute { ctx =>
      val snapshot = observed.snapshot.get
      val spenders = ErgoDexBatching.spenders(snapshot)
      val creators = ErgoDexBatching.creators(snapshot)
      val candidates = (liveOrders(orders) ++
        ErgoDexBatching.unconfirmedOrders(snapshot, batching.deniedPools, MaxMempoolOrders))
        .groupBy(_.boxId).values.map(_.head).toSeq
        .filterNot(ErgoDexBatching.withdrawn(_, spenders))
      val pools = currentPools(candidates.map(_.poolNft).distinct)
        .filter { case (_, box) => !creators.contains(box.boxId) }
      // Load ancestry only for orders that can be executed profitably.
      val priceable = candidates.filter(order => pools.get(order.poolNft).flatMap(ErgoDexPool.native)
        .exists(pool => ErgoDexExecution.price(order, pool, batching.minRevenueNanoErg, fundsItsOwnBox = false).nonEmpty))
      val placed = walletPlacements(priceable.flatMap(order => creators.get(order.boxId)), creators, spenders, slots - 1)
      val byPool = priceable
        .filter(order => creators.get(order.boxId).forall(tx => placed.contains(tx.id)))
        .groupBy(_.poolNft)
      runs(ctx, byPool.toSeq.map { case (nft, poolOrders) => pools(nft) -> poolOrders }, slots, blockHeight,
        batching.minRevenueNanoErg, 0L, useTrueProp,
        order => creators.get(order.boxId).flatMap(tx => placed.get(tx.id)).getOrElse(Vector.empty))
        .map { case (chain, placements) =>
          val spent = chain.fills.head.pool.boxId +: chain.fills.map(_.order.boxId)
          chain.bundle(ErgoDexBatching.competitors(spenders, spent),
            placements.map(tx => CandidateTx.ancestor(tx.body)))
        }
    }
  }

  /** Verifies every transaction in each placement chain, loading confirmed inputs in one call. */
  private def walletPlacements(txs: Seq[CompleteMempool.MempoolTx],
                               creators: Map[String, CompleteMempool.MempoolTx],
                               spenders: ErgoDexBatching.Spenders,
                               limit: Int): Map[String, Vector[CompleteMempool.MempoolTx]] = {
    val chains = txs.groupBy(_.id).values.map(_.head).toSeq.flatMap(tx =>
      ErgoDexBatching.placementChain(tx, creators, limit).map(tx.id -> _))
    val distinct = chains.flatMap(_._2).groupBy(_.id).values.map(_.head).toSeq
    val inputIds = distinct.flatMap(_.body.inputs.map(_.boxId)).distinct.filterNot(creators.contains)
    val confirmed =
      if (inputIds.isEmpty) Map.empty[String, NodeBox]
      else nodeApi.boxesWithPoolByIds(inputIds).get.map(box => box.boxId -> box).toMap
    val inputBoxes = confirmed ++ distinct.flatMap(_.body.outputs.map(box => box.boxId -> box))
    val valid = distinct.filter(ErgoDexBatching.placedByWallet(_, inputBoxes, spenders)).map(_.id).toSet
    chains.filter { case (_, chain) => chain.forall(tx => valid.contains(tx.id)) }.toMap
  }

  /** Builds unclaimed orders against the mempool pool tip and records rejected executions. */
  private def broadcastPass(orders: ErgoDexBatching.Tracked,
                            refused: Map[String, String]): Map[String, String] = {
    val observed = observation()
    require(observed.fresh, s"no fresh mempool observation: ${observed.failure.getOrElse("too old")}")
    val snapshot = observed.snapshot.get
    val spenders = ErgoDexBatching.spenders(snapshot)
    val sender = new EngineBroadcast(engine, nodeApi, ObservationTimeout)(worker)
    nodeContext.getClient.execute { ctx =>
      val byPool = liveOrders(orders).filterNot(order => snapshot.spent.contains(order.boxId))
        .groupBy(_.poolNft)
      val pools = currentPools(byPool.keys.toSeq).toSeq.flatMap { case (nft, current) =>
        for {
          tip <- ErgoDexBatching.poolTip(current, nft, spenders)
          open = byPool(nft).filterNot(order => refused.get(order.boxId).contains(tip.boxId))
          if open.nonEmpty
        } yield tip -> open
      }
      runs(ctx, pools, batching.maxOrdersPerBlock, ctx.getHeight + 1, batching.broadcastMinRevenueNanoErg,
        batching.broadcastMinerFeeCeiling, useTrueProp = false)
        .flatMap { case (chain, _) => send(sender, chain, observed) }.toMap
    }
  }

  /** Sends a run in order, stopping on any unaccepted send or exception. */
  private def send(sender: EngineBroadcast, chain: ErgoDexChain,
                   observed: CompleteMempool.Observation): Option[(String, String)] = {
    var index = 0
    var refused = Option.empty[(String, String)]
    var going = true
    while (going && index < chain.transactions.size) {
      val fill = chain.fills(index)
      Try(sender.sendOwned(chain.transactions(index), Seq.empty, ErgoDexExecution.Kind,
        () => alive.get(), observed = Some(observed))) match {
        case Success(sent) if sent.outcome == EngineBroadcast.Accepted =>
          logger.info(s"Broadcast ErgoDEX execution ${sent.txId} of order ${fill.order.boxId}")
        case Success(sent) =>
          going = false
          logger.warn(s"ErgoDEX execution ${sent.txId} of order ${fill.order.boxId} was ${sent.outcome}: " +
            s"${sent.reason.getOrElse("no reason given")}; the rest of its run is not sent")
          if (sent.outcome == EngineBroadcast.Rejected) refused = Some(fill.order.boxId -> fill.pool.boxId)
        case Failure(ex) =>
          going = false
          logger.warn(s"ErgoDEX execution of order ${fill.order.boxId} was not sent: ${ex.getMessage}")
      }
      index += 1
    }
    refused
  }

  /** Builds the highest-revenue runs that fit, counting shared ancestors once. */
  private def runs(ctx: BlockchainContext, pools: Seq[(NodeBox, Seq[ErgoDexOrder])], slots: Int,
                   blockHeight: Int, minRevenue: Long, minerFeeCeiling: Long, useTrueProp: Boolean,
                   placementOf: ErgoDexOrder => Vector[CompleteMempool.MempoolTx] = _ => Vector.empty)
  : Vector[(ErgoDexChain, Vector[CompleteMempool.MempoolTx])] = {
    val priced = pools
      .flatMap { case (box, poolOrders) => ErgoDexPool.native(box).map(pool =>
        box -> ErgoDexExecution.priceChain(poolOrders, pool, minRevenue, slots, minerFeeCeiling)) }
      .filter(_._2.nonEmpty)
      .sortBy { case (box, fills) => (-fills.map(_.revenue).sum, box.boxId) }
    var left = slots
    var carried = Set.empty[String]
    priced.toVector.flatMap { case (box, fills) =>
      val (taken, placements) = ErgoDexBatching.fitting(fills, left, placementOf, carried)
      val built =
        if (taken.isEmpty) None
        else ErgoDexExecution.build(ctx, nodeContext.getNodeWallet, box.toInputUTXO(ctx),
          taken, blockHeight, minerFeeCeiling, useTrueProp)
      built.foreach { chain =>
        left -= chain.transactions.size + placements.count(tx => !carried.contains(tx.id))
        carried ++= placements.map(_.id)
      }
      built.map(_ -> placements)
    }
  }
}

object ErgoDexSource {
  /** Scheduler tick: rescan the templates, then broadcast if that is on. */
  private[ergodex] case object ScanTick

  private[ergodex] final case class Scanned(result: Try[ErgoDexBatching.Tracked])

  private[ergodex] final case class Broadcasted(result: Try[Map[String, String]])

  /** Orders a build found already spent, so they stop being read back. */
  private[ergodex] final case class Spent(ids: Set[String])

  /** Boxes per indexer page. */
  private[ergodex] final val PageSize = 100

  /** Maximum boxes read from one template during a scan. */
  private[ergodex] final val MaxScannedPerTemplate = 2000

  /** Maximum pool lookups per scan; each lookup requires a node call. */
  private[ergodex] final val MaxPoolReads = 512

  /** Ask timeout for the mempool observation. Above the walk's own 30-second deadline. */
  private[ergodex] final val ObservationTimeout: FiniteDuration = 40.seconds

  /** Together with one scan, these builds fit the worker's two threads and four queued tasks. */
  private[ergodex] final val MaxBuildsRunning = 3

  /** Maximum unconfirmed orders examined during one build. */
  private[ergodex] final val MaxMempoolOrders = 64
}
