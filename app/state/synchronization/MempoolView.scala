package state.synchronization

import akka.actor.{Actor, ActorRef, Cancellable}
import akka.pattern.pipe
import configs.{Contexts, NodeContext}
import node.model.Paging
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
  private case object Tick
  private final case class RefreshFinished(result: Try[BuiltSnapshot])

  /**
   * The page walk raced a change it could have skipped over, rather than the node failing.
   *
   * It means the previous revision is still the best answer and the next tick will do better.
   */
  final class MempoolRaced(detail: String) extends RuntimeException(detail)

  private final val PageSize = 500

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
  private val pollingContext = context.system.dispatchers.lookup(Contexts.key(Contexts.Polling))
  private val logger: Logger = LoggerFactory.getLogger("MempoolView")
  private val nodeApi = nodeContext.getNodeApi
  private val syncConfig = new configs.SyncConfig(config)
  private val maxTransactions = syncConfig.mempoolMaxTransactions
  // Relevant ErgoTrees to search mempool for
  // TODO: Could be expanded in the future for mempool support of other UTXOs?
  private val rollupTrees =
    Seq(protocol.holdingErgoTree, protocol.evaluationErgoTree, protocol.payoutErgoTree)

  private var lastFingerprint = Set.empty[String]
  private var revision = 0L
  private var inFlight = false
  private var healthy = false

  private val ticker: Cancellable =
    context.system.scheduler.scheduleWithFixedDelay(
      syncConfig.mempoolRefreshInterval, syncConfig.mempoolRefreshInterval, self, Tick)

  override def preStart(): Unit = self ! Tick

  override def postStop(): Unit = ticker.cancel()

  override def receive: Receive = {
    // Skip projections before readiness; no consumer can use them during catch-up.
    case Tick =>
      stateFrame ! AutoSubscribable.AutoSubscribe(self)
      if (!inFlight && ready) refresh()
    case NewBlock(_) => self ! Tick
    case RebuildMempoolChains => self ! Tick

    case RefreshFinished(Success(snapshot)) =>
      inFlight = false
      if (shouldPublish(lastFingerprint, healthy, snapshot.fingerprint)) {
        revision += 1L
        lastFingerprint = snapshot.fingerprint
        logger.debug(s"Publishing mempool revision $revision: ${snapshot.fingerprint.size} " +
          s"transaction(s), ${snapshot.chains.size} chain(s), ${snapshot.blockedRoots.size} blocked root(s)")
        syncHandler ! MempoolSnapshot(revision, snapshot.chains, snapshot.endInputs, snapshot.blockedRoots)
      }
      healthy = true

    // A raced page walk is expected traffic. The last revision stays published and the
    // next tick re-reads
    case RefreshFinished(Failure(raced: MempoolRaced)) =>
      inFlight = false
      logger.debug(s"Retrying the mempool view: ${raced.getMessage}")

    case RefreshFinished(Failure(ex)) =>
      inFlight = false
      healthy = false
      val detail = Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName)
      val reason = s"Failed to refresh the complete mempool view: $detail"
      logger.error(reason, ex)
      syncHandler ! MempoolUnavailable(reason)
  }

  private def refresh(): Unit = {
    inFlight = true
    Future(buildSnapshot())(pollingContext)
      .map(result => RefreshFinished(result))
      .recover { case ex => RefreshFinished(Failure(ex)) }
      .pipeTo(self)
  }

  /**
   * Reads the relevant mempool, re-reading only when a page walk could have skipped an entry.
   * A single page cannot skip, so the second read is paid for only when one script actually paged.
   */
  private def buildSnapshot(): Try[BuiltSnapshot] =
    fetchAll().flatMap { case (first, paged) =>
      if (!paged) Success(first)
      else fetchAll().flatMap { case (second, _) =>
        // Arrivals cannot cause a skip; only a disappearance shifts offsets under the walk.
        val vanished = first.map(_.id).toSet -- second.map(_.id).toSet
        if (vanished.isEmpty) Success(second)
        else Failure(new MempoolRaced(
          s"${vanished.size} unconfirmed rollup transaction(s) left the mempool while it was paged"))
      }
    }.map { transactions =>
      val graph = buildGraph(transactions, trackedRoots)
      // Opening a context is two node calls, so it is only worth it when a chain needs converting.
      val endInputs =
        if (graph.chains.isEmpty) Map.empty[String, work.lithos.mutations.InputUTXO]
        else nodeContext.getClient.execute { ctx =>
          graph.chains.map { case (root, chain) => root -> chain.transforms.last.output.toInput(ctx) }
        }
      BuiltSnapshot(transactions.map(_.id).toSet, graph.chains, endInputs, graph.blockedRoots)
    }

  /** UTXOs this client tracks. Chains rooted anywhere else have no reader. */
  private def trackedRoots: Set[String] = utils.Globals.syncView.rollups.map(_._1).toSet

  /** Every unconfirmed transaction at a rollup script, deduplicated, and whether any script paged. */
  private def fetchAll(): Try[(Seq[BlockTx], Boolean)] =
    rollupTrees.foldLeft(Try((Vector.empty[BlockTx], Set.empty[String], false))) {
      case (accumulated, tree) =>
      accumulated.flatMap { case (collected, seen, paged) =>
        fetchByTree(tree, collected, seen).map { case (next, nextSeen, treePaged) =>
          (next, nextSeen, paged || treePaged)
        }
      }
    }.map { case (collected, _, paged) => (collected, paged) }

  @tailrec
  private def fetchByTree(ergoTree: String,
                           collected: Vector[BlockTx],
                           seen: Set[String],
                           offset: Int = 0,
                           paged: Boolean = false): Try[(Vector[BlockTx], Set[String], Boolean)] =
    nodeApi.unconfirmedTransactionsByErgoTree(ergoTree, Paging(offset, PageSize)) match {
      case Failure(ex) => Failure(ex)
      case Success(page) =>
        val (next, nextSeen) = page.iterator.map(NodeSync.blockTx)
          .foldLeft((collected, seen)) { case ((kept, ids), tx) =>
            if (ids.contains(tx.id)) (kept, ids) else (kept :+ tx, ids + tx.id)
          }
        if (next.size > maxTransactions)
          Failure(new IllegalStateException(
            s"Unconfirmed rollup transactions exceed sync.mempool.maxTransactions ($maxTransactions); " +
              "mempool chaining is unavailable, and transactions will build on " +
              "confirmed state"))
        else if (page.size < PageSize) Success((next, nextSeen, paged))
        else fetchByTree(ergoTree, next, nextSeen, offset + page.size, paged = true)
    }

  /** Readiness is owned by SyncHandler and published, rather than asked for on every tick. */
  private def ready: Boolean = utils.Globals.syncView.canonical.available
}
