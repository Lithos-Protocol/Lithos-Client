package transactions.batching.tracking

import akka.actor.ActorSystem
import configs.{Contexts, NodeContext}
import node.NodeApi
import node.model.{IndexedBox, MempoolOptions, Paging, SortDirection}
import org.ergoplatform.appkit.NetworkType
import org.slf4j.{Logger, LoggerFactory}
import state.synchronization.CanonicalBlockSource
import transactions.batching.ergodex.ErgoDexContracts

import javax.inject.{Inject, Singleton}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

/**
 * Every unspent ErgoDEX and LithosDex pool box, kept from the blocks the candidate builds on rather than scanned
 * per block. A source told the parent a candidate builds on passes it to `follow`; the tracker reads that block's
 * transactions, drops the pool boxes they spend and adds the pool boxes they create. That is two node reads per
 * block, neither waiting on the node's index. A fork of up to [[TrackedPools.MaxHistory]] blocks is undone; a
 * deeper one, a gap over [[PoolTracker.MaxCatchUp]] blocks or a first use scans the pool scripts once.
 *
 * One advance runs at a time, on its own thread. Readers get an immutable state and never wait past their own
 * deadline; one that gets None reads the node as before.
 */
@Singleton
class PoolTracker(api: () => NodeApi, network: NetworkType, io: ExecutionContext,
                  reseedBlocks: Int = PoolTracker.ReseedBlocks, retryMs: Long = PoolTracker.RetryMs) {

  @Inject def this(nodeContext: NodeContext, system: ActorSystem) =
    this(() => nodeContext.getNodeApi, nodeContext.getNetwork,
      system.dispatchers.lookup(Contexts.key(Contexts.PoolTracking)))

  import PoolTracker._

  private val logger: Logger = LoggerFactory.getLogger("PoolTracker")
  private lazy val shapes = new PoolShapes(network)

  private val lock = new Object
  @volatile private var current: Option[TrackedPools] = None
  /** The newest block a source said a candidate builds on: its height and id. Guarded by `lock`. */
  private var target: Option[(Int, String)] = None
  /** The advance or rescan running, completed once its result is in `current`. Guarded by `lock`. */
  private var running: Option[Future[Unit]] = None
  /** A target whose advance failed and when, so it is retried no sooner than `retryMs`. Guarded by `lock`. */
  private var failed: Option[((Int, String), Long)] = None
  /** The lowest height a periodic rescan may start at, pushed on after one fails. Guarded by `lock`. */
  private var rescanFrom: Int = 0

  /** Says a candidate builds on block `headerId` at `height`, and starts reading it if no advance is running. */
  def follow(height: Int, headerId: String): Unit =
    if (height > 0 && headerId.nonEmpty) lock.synchronized {
      if (!target.contains(height -> headerId)) target = Some(height -> headerId)
      kick()
    }

  /**
   * The pools as of the block last followed at `height`, waiting up to `wait` for an advance to reach it. None
   * when no block at `height` was followed, or the advance did not finish in time or failed.
   */
  def at(height: Int, wait: FiniteDuration): Option[TrackedPools] = {
    val until = Deadline.now + wait
    var answer = Option.empty[TrackedPools]
    var going = true
    while (going) {
      val (wanted, pending) = lock.synchronized {
        kick()
        (target, running)
      }
      wanted match {
        case Some((h, id)) if h == height =>
          current.filter(_.headerId == id) match {
            case found @ Some(_) => answer = found; going = false
            case None => pending match {
              case Some(work) if until.hasTimeLeft() => Try(Await.ready(work, until.timeLeft))
              case _ => going = false
            }
          }
        case _ => going = false
      }
    }
    answer
  }

  /** The newest state, whatever block it is at. */
  def latest: Option[TrackedPools] = current

  /** Starts the advance the target needs, or the periodic rescan once the state reached it. Holds `lock`. */
  private def kick(): Unit =
    if (running.isEmpty) target.foreach { case wanted @ (height, headerId) =>
      current.filter(_.headerId == headerId) match {
        case None =>
          if (failed.forall { case (t, at) => t != wanted || System.currentTimeMillis() - at >= retryMs })
            start(wanted, current)
        case Some(state) =>
          if (height - state.scannedAt >= reseedBlocks && height >= rescanFrom) start(wanted, None)
      }
    }

  /**
   * Runs the advance to `wanted` from `from`, or a rescan when `from` is None and a state is already at
   * `wanted`. A failed rescan keeps the state and waits ten blocks to try again.
   */
  private def start(wanted: (Int, String), from: Option[TrackedPools]): Unit = {
    val rescan = from.isEmpty && current.nonEmpty
    val work = Try(Future(advance(api(), shapes, from, wanted._1, wanted._2, logger))(io)) match {
      case Success(f) => f
      case Failure(ex) => Future.failed(ex)
    }
    // Completed only once the result is published, so a reader woken by it finds the state in place
    val published = Promise[Unit]()
    running = Some(published.future)
    work.onComplete { result =>
      try lock.synchronized {
        running = None
        result match {
          case Success(next) =>
            if (rescan) current.filter(_.headerId == next.headerId).foreach(compare(_, next))
            current = Some(next)
            failed = None
          case Failure(ex) if rescan =>
            logger.warn(s"Pool tracking's periodic rescan at ${wanted._1} failed, keeping the tracked pools: " +
              ex.getMessage)
            rescanFrom = wanted._1 + RescanBackoffBlocks
          case Failure(ex) =>
            if (!failed.exists(_._1 == wanted))
              logger.warn(s"Pool tracking could not reach block ${wanted._2} at ${wanted._1}; batching and arb read " +
                s"pools from the node until it does: ${ex.getMessage}")
            failed = Some(wanted -> System.currentTimeMillis())
        }
        kick()
      } finally published.trySuccess(())
    }(io)
  }

  /** Warns when a rescan finds other boxes than the blocks kept, which only a tracking fault explains. */
  private def compare(kept: TrackedPools, scanned: TrackedPools): Unit = {
    val (a, b) = (kept.boxes.keySet, scanned.boxes.keySet)
    if (a != b && !kept.cut && !scanned.cut)
      logger.warn(s"Pool tracking at ${scanned.height} held ${(a -- b).size} box(es) a rescan did not find and " +
        s"missed ${(b -- a).size}; replaced with the rescan")
  }
}

object PoolTracker {

  /** Most blocks read one by one to reach a target; further behind, a scan is cheaper. */
  final val MaxCatchUp = 32

  /** Most boxes tracked: each ErgoDEX script held a few hundred pools in 2026, and the scans stopped at 2,000. */
  final val MaxBoxes = 5000

  /** Pages read per script or template in a scan. */
  final val MaxPages = 20
  final val PageSize = 100

  /** Blocks between rescans that check the kept state against the node's index: about a day. */
  final val ReseedBlocks = 720

  /** How soon a failed target is tried again. */
  final val RetryMs = 2000L

  /** Blocks a failed periodic rescan waits before the next. */
  final val RescanBackoffBlocks = 10

  /**
   * The pools as of block `headerId` at `height`: from `from` by applying or undoing blocks where it can, else
   * from a scan at the node's indexed height and the blocks after it. Fails when the node's chain does not have
   * `headerId` at `height`, or a read fails.
   */
  def advance(api: NodeApi, shapes: PoolShapes, from: Option[TrackedPools], height: Int, headerId: String,
              logger: Logger): TrackedPools = {
    val source = new CanonicalBlockSource(api)
    from match {
      case Some(state) if state.headerId == headerId => state
      case Some(state) => follow(api, source, shapes, state, height, headerId).getOrElse {
        logger.info(s"Pool tracking is more than ${TrackedPools.MaxHistory} blocks off block $headerId at $height; " +
          "rescanning the pool scripts")
        scanThenFollow(api, source, shapes, height, headerId, logger)
      }
      case None => scanThenFollow(api, source, shapes, height, headerId, logger)
    }
  }

  /**
   * `state` moved to `headerId` at `height` by undoing blocks back to the newest one the canonical chain shares
   * with it and applying the canonical blocks after it. None when they share no retained block, or the target is
   * too far ahead.
   */
  private[tracking] def follow(api: NodeApi, source: CanonicalBlockSource, shapes: PoolShapes, state: TrackedPools,
                               height: Int, headerId: String): Option[TrackedPools] =
    if (height - state.height > MaxCatchUp || height < state.base) None
    else {
      // The tip's own height first, which is all a block extending it needs; the retained window only on a fork
      def shared(from: Int): Option[(Seq[node.model.NodeHeader], Int)] = {
        val headers = source.headerSlice(from, height).get
        if (!headers.lastOption.exists(_.id == headerId))
          throw new IllegalStateException(s"the node's chain does not have block $headerId at height $height")
        headers.filter(h => h.height <= state.height && state.idAt(h.height).contains(h.id))
          .lastOption.map(ancestor => headers -> ancestor.height)
      }
      val near = math.min(state.height, height)
      shared(near).orElse(if (state.base < near) shared(state.base) else None).map { case (headers, ancestor) =>
        headers.filter(_.height > ancestor).foldLeft(state.rolledBackTo(ancestor).get) { (at, header) =>
          val txs = api.blockTransactions(header.id).get
            .getOrElse(throw new NoSuchElementException(s"the node has no transactions for block ${header.id}"))
          at.applied(header.height, header.id, header.parentId, txs.transactions, shapes, MaxBoxes)
        }
      }
    }

  /**
   * A scan of every pool script at the node's indexed height, then the blocks from there to the target. The
   * index has to hold still and keep the same block at its height while it is read, or the scan fails.
   */
  private def scanThenFollow(api: NodeApi, source: CanonicalBlockSource, shapes: PoolShapes, height: Int,
                             headerId: String, logger: Logger): TrackedPools = {
    val indexed = api.indexedHeight().get.indexedHeight
    require(indexed <= height, s"the node's index is at $indexed, past the block asked for at $height")
    require(height - indexed <= MaxCatchUp, s"the node's index is at $indexed, too far behind $height to catch up")
    val base = source.headerAt(indexed).get
    val reads: Seq[(String, Paging => Try[Seq[IndexedBox]])] = Seq(
      "ErgoDEX native pool script" -> (p => api.unspentBoxesByErgoTree(ErgoDexContracts.NativePoolErgoTree, p,
        SortDirection.Desc, MempoolOptions.ConfirmedOnly)),
      "ErgoDEX token pool script" -> (p => api.unspentBoxesByErgoTree(ErgoDexContracts.TokenPoolErgoTree, p,
        SortDirection.Desc, MempoolOptions.ConfirmedOnly)),
      "LithosDex ERG pool template" -> (p => api.unspentBoxesByTemplateHash(shapes.n2tTemplateHash, p,
        SortDirection.Desc, MempoolOptions.ConfirmedOnly)),
      "LithosDex token pool template" -> (p => api.unspentBoxesByTemplateHash(shapes.t2tTemplateHash, p,
        SortDirection.Desc, MempoolOptions.ConfirmedOnly)))
    var cut = false
    val found = reads.flatMap { case (name, read) =>
      val (boxes, exhausted) = scan(read)(indexed => shapes.read(indexed.box, indexed.inclusionHeight))
      if (!exhausted) {
        cut = true
        logger.warn(s"Pool tracking stopped the $name scan after $MaxPages pages; pools past it are read from the node")
      }
      boxes
    }
    val after = api.indexedHeight().get.indexedHeight
    require(after == indexed && source.headerAt(indexed).get.id == base.id,
      s"the node's index moved from $indexed while the pools were scanned")
    val distinct = found.groupBy(_.boxId).values.map(_.head).toSeq
    val seeded = TrackedPools.seeded(indexed, base.id, distinct.take(MaxBoxes), cut || distinct.size > MaxBoxes)
    logger.info(s"Pool tracking scanned ${seeded.boxes.size} pool box(es) at $indexed")
    follow(api, source, shapes, seeded, height, headerId).getOrElse(
      throw new IllegalStateException(s"block $headerId at $height does not descend from the scanned block ${base.id}"))
  }

  /** What `keep` makes of every box `read` pages through, to [[MaxPages]] pages, and whether it reached the end. */
  private def scan[A](read: Paging => Try[Seq[IndexedBox]])(keep: IndexedBox => Option[A]): (Vector[A], Boolean) = {
    val found = Vector.newBuilder[A]
    var offset = 0
    var exhausted = false
    while (!exhausted && offset < MaxPages * PageSize) {
      val page = read(Paging(offset, PageSize)).get
      page.foreach(indexed => keep(indexed).foreach(found += _))
      exhausted = page.size < PageSize
      offset += page.size
    }
    found.result() -> exhausted
  }
}
