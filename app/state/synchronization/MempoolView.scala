package state.synchronization

import akka.actor.{Actor, ActorRef, Cancellable}
import configs.NodeContext
import org.slf4j.{Logger, LoggerFactory}
import state.messages.MempoolMessages._
import state.messages.StateFrameMessages.NewBlock
import state.messages.SyncMessages.Ready
import state.messages.{BlockTx, NodeSync}

import javax.inject.{Inject, Named}
import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object MempoolView {
  /** One mempool walk finishing. `id` fences results from a superseded attempt. */
  private case class WalkFinished(id: java.util.UUID, result: Try[Walk])

  /**
   * What one walk produced. `observation` is what engine and emission consumers read; `projection`
   * is the rollup view derived from it, which can fail on its own without costing them the
   * observation.
   */
  private final case class Walk(observation: CompleteMempool.Snapshot, projection: Try[BuiltSnapshot])

  /** Askers that may queue behind one walk before further requests are refused. */
  private final val MaxCompleteWaiters = 32
  private case object Tick

  final case class Graph(chains: Map[String, MempoolChain], blockedRoots: Set[String])
  private final case class BuiltSnapshot(fingerprint: Set[String],
                                         chains: Map[String, MempoolChain],
                                         endInputs: Map[String, work.lithos.mutations.InputUTXO],
                                         blockedRoots: Set[String])

  /**
   * Builds parent-before-child chains independently of the order returned by the node.
   * Only roots this client tracks are followed: anyone can pay a rollup script, and nothing else reads.
   */
  def buildGraph(transactions: Seq[BlockTx], trackedRoots: Set[String]): Graph = {
    val inputGroups = transactions.flatMap(tx => tx.inputs.headOption.map(_.id -> tx)).groupBy(_._1)
      .map { case (input, entries) => input -> entries.map(_._2) }
    val conflictingInputs = inputGroups.collect { case (input, txs) if txs.size != 1 => input }.toSet
    val unique = inputGroups.collect { case (input, Seq(tx)) => input -> tx }
    val malformedInputs = unique.collect {
      case (input, tx) if tx.outputs.headOption.isEmpty => input
    }.toSet
    val usable = unique.filter { case (_, tx) => tx.outputs.headOption.isDefined }
    val produced = usable.values.flatMap(_.outputs.headOption.map(_.id)).toSet
    val roots = ((usable.keySet -- produced) ++ conflictingInputs ++ malformedInputs)
      .filter(trackedRoots.contains)

    roots.foldLeft(Graph(Map.empty, Set.empty)) { (graph, root) =>
      follow(root, usable, conflictingInputs ++ malformedInputs) match {
        case Right(transforms) if transforms.nonEmpty =>
          graph.copy(chains = graph.chains + (root -> MempoolChain(transforms.map(MempoolTransform))))
        case Right(_) => graph
        case Left(_) => graph.copy(blockedRoots = graph.blockedRoots + root)
      }
    }
  }

  private[synchronization] def shouldPublish(lastFingerprint: Set[String],
                                             healthy: Boolean,
                                             nextFingerprint: Set[String]): Boolean =
    !healthy || nextFingerprint != lastFingerprint

  private def follow(root: String,
                     byInput: Map[String, BlockTx],
                     blockedInputs: Set[String]): Either[Unit, Seq[BlockTx]] = {
    @tailrec
    def loop(nextInput: String, seen: Set[String], result: Vector[BlockTx]): Either[Unit, Seq[BlockTx]] = {
      if (blockedInputs.contains(nextInput) || seen.contains(nextInput)) Left(())
      else byInput.get(nextInput) match {
        case None => Right(result)
        case Some(tx) => tx.outputs.headOption match {
          case None => Left(())
          case Some(output) => loop(output.id, seen + nextInput, result :+ tx)
        }
      }
    }
    loop(root, Set.empty, Vector.empty)
  }
}

/** Publishes complete mempool revisions; canonical projections remain lazy in SyncHandler. */
class MempoolView @Inject()(config: play.api.Configuration,
                            nodeContext: NodeContext,
                            protocol: SyncProtocolContext,
                            @Named("state-frame") stateFrame: ActorRef,
                            @Named("sync-handler") syncHandler: ActorRef)
  extends Actor {

  import MempoolView._

  private implicit val actorContext: ExecutionContext = context.dispatcher
  private val logger: Logger = LoggerFactory.getLogger("MempoolView")
  /**
   * Its own client rather than the shared one: the walk pulls every mempool body, so it needs a
   * response-byte ceiling and a whole-call deadline that ordinary reads do not want.
   */
  protected lazy val completeNode: node.NodeApi = node.rest.RestNodeApi(node.rest.NodeHttpConfig(
    nodeContext.getNodeUrl, Some(nodeContext.getNodeKey), maxResponseBytes = 2 * 1024 * 1024,
    callTimeoutMs = 10000L))
  private lazy val completeWorker = context.system.dispatchers.lookup("lithos-contexts.mempool-io-dispatcher")
  private var walkAttempt: Option[java.util.UUID] = None
  private var completeWaiters = Vector.empty[ActorRef]
  /** Starts unusable, so nothing can act on a complete view before one has ever been taken. */
  private var completeObservation = CompleteMempool.Observation(0L, None, Some("not observed"))
  private val syncConfig = new configs.SyncConfig(config)
  private val maxTransactions = syncConfig.mempoolMaxTransactions
  // Relevant ErgoTrees to search mempool for
  // TODO: Could be expanded in the future for mempool support of other UTXOs?
  private val rollupTrees =
    Seq(protocol.holdingErgoTree, protocol.evaluationErgoTree, protocol.payoutErgoTree)

  private var lastFingerprint = Set.empty[String]
  private var revision = 0L
  private var healthy = false

  private val ticker: Cancellable =
    context.system.scheduler.scheduleWithFixedDelay(
      syncConfig.mempoolRefreshInterval, syncConfig.mempoolRefreshInterval, self, Tick)

  override def preStart(): Unit = self ! Tick

  override def postStop(): Unit = ticker.cancel()

  override def receive: Receive = {
    // Concurrent askers share one walk: the mempool is the same for all of them, and a walk each
    // would multiply node load without producing different answers. The periodic tick uses that
    // same walk, so a rollup refresh and an engine request never read the mempool separately.
    case CompleteMempool.Refresh =>
      if (completeWaiters.size >= MaxCompleteWaiters)
        sender() ! completeObservation.copy(failure = Some("mempool reader capacity exhausted"))
      else {
        completeWaiters :+= sender()
        startWalk()
      }

    // Skip projections before readiness; no consumer can use them during catch-up.
    case Tick =>
      stateFrame ! AutoSubscribable.AutoSubscribe(self)
      if (ready) startWalk()
    case NewBlock(_) => self ! Tick
    case RebuildMempoolChains => self ! Tick

    case WalkFinished(attempt, result) if walkAttempt.contains(attempt) =>
      walkAttempt = None
      // A failed walk keeps the last snapshot but records the failure, so `fresh` turns false and
      // no consumer can treat it as evidence that an input or a lender key is free.
      completeObservation = result match {
        case Success(walk) =>
          CompleteMempool.Observation(completeObservation.revision + 1L, Some(walk.observation), None)
        case Failure(ex) => completeObservation.copy(failure = Some(ex.getMessage))
      }
      completeWaiters.foreach(_ ! completeObservation)
      completeWaiters = Vector.empty
      publishProjection(result.flatMap(_.projection))

    case _: WalkFinished => ()
  }

  /** Publish the rollup view from one walk's derivation, or withdraw it when that derivation failed. */
  private def publishProjection(projection: Try[BuiltSnapshot]): Unit = projection match {
    case Success(snapshot) =>
      if (shouldPublish(lastFingerprint, healthy, snapshot.fingerprint)) {
        revision += 1L
        lastFingerprint = snapshot.fingerprint
        logger.debug(s"Publishing mempool revision $revision: ${snapshot.fingerprint.size} " +
          s"transaction(s), ${snapshot.chains.size} chain(s), ${snapshot.blockedRoots.size} blocked root(s)")
        syncHandler ! MempoolSnapshot(revision, snapshot.chains, snapshot.endInputs, snapshot.blockedRoots)
      }
      healthy = true

    // A raced walk is expected traffic: arrivals, confirmations and evictions all cause it. The last
    // revision stays published and the next tick re-reads, because withdrawing projections on
    // ordinary churn would drop them exactly when the mempool is busiest.
    case Failure(raced: CompleteMempool.Raced) =>
      logger.debug(s"Retrying the mempool view: ${raced.getMessage}")

    case Failure(ex) =>
      healthy = false
      val detail = Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName)
      val reason = s"Failed to refresh the complete mempool view: $detail"
      logger.error(reason, ex)
      syncHandler ! MempoolUnavailable(reason)
  }

  /** One walk at a time. A request arriving while one runs waits for it rather than starting another. */
  private def startWalk(): Unit = if (walkAttempt.isEmpty) {
    val attempt = java.util.UUID.randomUUID()
    walkAttempt = Some(attempt)
    Try(Future(buildWalk())(completeWorker).foreach(result => self ! WalkFinished(attempt, result)))
      .failed.foreach(ex => self ! WalkFinished(attempt, Failure(ex)))
  }

  /** Collect once, then derive the rollup view from what that collection retained. */
  private def buildWalk(): Try[Walk] =
    CompleteMempool.collect(completeNode).map(observed => Walk(observed, Try(project(observed))))

  /**
   * Derive the rollup chains from one complete observation, rather than paging the rollup scripts
   * separately. The observation already holds every body, so this adds no node traffic, and both
   * views describe the same mempool at the same chain anchor instead of two reads that can disagree.
   */
  private def project(observed: CompleteMempool.Snapshot): BuiltSnapshot = {
    // A transaction matters to a rollup chain if it recreates a rollup box, or if it spends a root
    // this client tracks. The second case is what surfaces a competing spend of a tracked root,
    // which has to block that root rather than be followed.
    val roots = trackedRoots
    val relevant = observed.transactions.iterator
      .map(entry => NodeSync.blockTx(entry.body))
      .filter(tx => tx.outputs.exists(out => rollupTrees.contains(out.ergoTree)) ||
        tx.inputs.headOption.exists(in => roots.contains(in.id)))
      .toVector
    // The bound stays on the rollup transactions, not the whole mempool: an unrelated flood must
    // not withdraw this client's chains.
    if (relevant.size > maxTransactions)
      throw new IllegalStateException(
        s"Unconfirmed rollup transactions exceed sync.mempool.maxTransactions ($maxTransactions); " +
          "mempool chaining is unavailable, and transactions will build on confirmed state")
    val graph = buildGraph(relevant, roots)
    // Opening a context is two node calls, so it is only worth it when a chain needs converting.
    val endInputs =
      if (graph.chains.isEmpty) Map.empty[String, work.lithos.mutations.InputUTXO]
      else nodeContext.getClient.execute { ctx =>
        graph.chains.map { case (root, chain) => root -> chain.transforms.last.output.toInput(ctx) }
      }
    BuiltSnapshot(relevant.map(_.id).toSet, graph.chains, endInputs, graph.blockedRoots)
  }

  /** UTXOs this client tracks. Chains rooted anywhere else have no reader. */
  private def trackedRoots: Set[String] = utils.Globals.syncView.rollups.map(_._1).toSet

  /** Readiness is owned by SyncHandler and published, rather than asked for on every tick. */
  private def ready: Boolean = utils.Globals.syncView.canonical.available
}
