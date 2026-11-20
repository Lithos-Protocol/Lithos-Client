package transactions.batching.sigmafi.zc

import configs.{NodeContext, ZcConfig}
import node.NodeApi
import node.model.{IndexedBox, MempoolOptions, NodeHeader, Paging, SortDirection}
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import sigmafi.zc.ZcContracts
import state.messages.BlockInfo
import state.synchronization.{CanonicalBlockSource, CompleteMempool}
import transactions.batching.tracking.BlockFollow

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.{Inject, Singleton}
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

/**
 * Every unspent SigmaFi ZC box, live. Blocks arrive from the canonical sync as it commits them ([[offer]]), so
 * a block that extends the book costs no node read; the tracker reads blocks itself only to close the gap after
 * its seed scan or to cross a fork, and rescans the ZC trees when a fork is deeper than its history. The mempool
 * is laid over the book from the shared observation ([[observe]]) when that observation was taken on the book's
 * block, and [[view]] publishes both with a revision that changes only when what a reader sees changes.
 *
 * One step runs at a time, off the caller's thread. Readers get immutable snapshots and never wait.
 */
@Singleton
class ZcTracker(api: () => NodeApi, io: ExecutionContext, settings: ZcConfig,
                reseedBlocks: Int = ZcTracker.ReseedBlocks, retryMs: Long = ZcTracker.RetryMs,
                recoveryBlocks: Int = ZcTracker.RecoveryBlocks, maxScriptChars: Long = ZcTracker.MaxScriptChars) {

  @Inject def this(nodeContext: NodeContext, config: Configuration) =
    this(() => nodeContext.getNodeApi, ZcTracker.ownThread(), ZcConfig(config))

  import ZcTracker._

  def enabled: Boolean = settings.enabled

  private val logger: Logger = LoggerFactory.getLogger("ZcTracker")

  private val lock = new Object
  @volatile private var confirmed: Option[ZcBook] = None
  @volatile private var published: Option[ZcView] = None
  /** The newest block the sync committed and offered. Guarded by `lock`. */
  private var target: Option[BlockInfo] = None
  /** The newest block a candidate build asked to follow: height and id. Guarded by `lock`. */
  private var followed: Option[(Int, String)] = None
  /** The newest mempool observation, and its revision. Guarded by `lock`. */
  private var mempool: Option[CompleteMempool.Snapshot] = None
  private var mempoolRevision: Long = -1L
  /** Completed once the step currently running finishes publishing. Guarded by `lock`. */
  private var running: Option[Future[Unit]] = None
  /** Whether the overlay needs rebuilding: a new observation, or a failed step to retry. Guarded by `lock`. */
  private var stale = false
  /**
   * When the last failed step ended and how many failed in a row, so the next waits `retryMs` doubled per failure.
   * A failing seed is a full scan, so retrying it every few seconds would load the node. Guarded by `lock`.
   */
  private var failedAt: Option[Long] = None
  private var failures = 0
  private var warnedFor: Option[String] = None
  /** The lowest height a rescan may run at, pushed on after one fails. Guarded by `lock`. */
  private var rescanFrom = 0
  private var revision = 0L
  private var fingerprint: Option[ZcView.Fingerprint] = None
  private var warnedCut = false

  /**
   * Scripts found behind party hashes, by hash, within `maxScriptChars` in all. A hash never changes meaning;
   * when full, scripts no box in the book names any more are dropped to make room.
   */
  private val scripts = new ConcurrentHashMap[String, String]()
  private val scriptChars = new AtomicLong(0L)

  /** A block the sync committed. The newest one offered is the one the book moves to. */
  def offer(block: BlockInfo): Unit = if (enabled) lock.synchronized {
    target = Some(block)
    kick()
  }

  /**
   * Says a candidate builds on block `headerId` at `height`, and starts moving the book to it if no step is
   * running. A candidate parent can arrive ahead of canonical sync, so this follows it directly without waiting.
   */
  def follow(height: Int, headerId: String): Unit =
    if (enabled && height > 0 && headerId.nonEmpty) lock.synchronized {
      if (!followed.contains(height -> headerId)) {
        followed = Some(height -> headerId)
        if (!warnedFor.contains(headerId)) { failedAt = None; failures = 0 }
      }
      kick()
    }

  /**
   * The confirmed book as of the block last followed at `height`, waiting up to `wait` for an in-flight step
   * to reach it. None when tracking is off, no block at `height` was followed, or the step did not finish in time.
   */
  def at(height: Int, wait: FiniteDuration): Option[ZcBook] =
    if (!enabled) None
    else {
      val until = Deadline.now + wait
      var answer = Option.empty[ZcBook]
      var going = true
      while (going) {
        val (wanted, pending) = lock.synchronized {
          kick()
          (followed, running)
        }
        wanted match {
          case Some((h, id)) if h == height =>
            confirmed.filter(_.headerId == id) match {
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

  /** A mempool observation. One with a revision already seen changes nothing. */
  def observe(observation: CompleteMempool.Observation): Unit = if (enabled) lock.synchronized {
    if (observation.revision != mempoolRevision && observation.snapshot.isDefined) {
      mempoolRevision = observation.revision
      mempool = observation.snapshot
      stale = true
    }
    kick()
  }

  /** Starts the seed, a retry or a pending step if none is running. */
  def poke(): Unit = if (enabled) lock.synchronized(kick())

  /** The newest snapshot of the market, or None before the first seed. */
  def view: Option[ZcView] = published

  /** The confirmed book alone. */
  def book: Option[ZcBook] = confirmed

  /** The script behind `hash`, when a transaction the tracker applied disclosed it. */
  def script(hash: String): Option[String] = Option(scripts.get(hash.toLowerCase))

  /** Picks the desired target: a candidate follow at or ahead of sync wins unless sync already gave that exact block. */
  private def desiredTarget(): Option[Either[BlockInfo, (Int, String)]] = (target, followed) match {
    case (Some(b), Some((_, id))) if b.id == id => Some(Left(b))
    case (Some(b), Some(f @ (h, _))) if h >= b.height => Some(Right(f))
    case (Some(b), _) => Some(Left(b))
    case (None, Some(f)) => Some(Right(f))
    case (None, None) => None
  }

  /** Starts a step if one is due and none is running. Holds `lock`. */
  private def kick(): Unit = if (running.isEmpty) {
    val book = confirmed
    val wanted = desiredTarget()
    val due = book.isEmpty || stale || wanted.exists(t => !holdsTarget(book.get, t)) || book.exists(rescanDue)
    val backoff = math.min(MaxRetryMs, retryMs << math.min(failures - 1, 16).max(0))
    val waiting = failedAt.exists(at => System.currentTimeMillis() - at < backoff)
    if (due && !waiting) {
      val snapshot = mempool
      stale = false
      val done = Promise[Unit]()
      running = Some(done.future)
      val work = Try(Future(step(book, wanted, snapshot))(io)) match {
        case Success(f) => f
        case Failure(ex) => Future.failed(ex)
      }
      work.onComplete { result =>
        try lock.synchronized {
          running = None
          result match {
            case Success(_) =>
              failedAt = None
              failures = 0
              warnedFor = None
            case Failure(ex) =>
              failedAt = Some(System.currentTimeMillis())
              failures += 1
              val key = wanted.map(_.fold(_.id, _._2)).getOrElse("seed")
              if (!warnedFor.contains(key)) logger.warn(s"ZC tracking could not reach ${wanted.fold("its seed")(_.fold(
                b => s"block ${b.id} at ${b.height}", { case (h, id) => s"block $id at $h" }))}; " +
                s"ZC reads stay at ${confirmed.fold("nothing")(b => s"height ${b.height}")}: ${ex.getMessage}")
              warnedFor = Some(key)
              stale = true
          }
          kick()
        } finally done.trySuccess(())
      }(io)
    }
  }

  /**
   * A rescan is due a day after the last, to check the book against the index, or as soon as a book that had to
   * evict boxes is back under three quarters of the cap, so boxes left out under pressure return. Holds `lock`.
   */
  private def rescanDue(book: ZcBook): Boolean = book.height >= rescanFrom && (
    book.height - book.scannedAt >= reseedBlocks ||
      (book.cut && book.boxes.size < settings.maxBoxes * 3 / 4 && book.height - book.scannedAt >= recoveryBlocks))

  /** Moves the book to `wanted` (or seeds it), runs a due rescan, then republishes the view. */
  private def step(book: Option[ZcBook], wanted: Option[Either[BlockInfo, (Int, String)]],
                   snapshot: Option[CompleteMempool.Snapshot]): Unit = {
    val known: String => Boolean = h => scripts.containsKey(h)
    val (moved, found) = advanceTarget(api(), book, wanted, settings.maxBoxes, known, logger)
    remember(found, moved)
    val next = checked(moved, known)
    if (next.cut && !warnedCut)
      logger.warn(s"ZC tracking holds its cap of ${settings.maxBoxes} boxes; a new box joins only in place of one " +
        "holding less ERG (sigmafi.zc.maxBoxes)")
    warnedCut = next.cut
    confirmed = Some(next)
    val previous = published.filter(_.book.headerId == next.headerId).flatMap(_.mempool)
    val view = ZcView(next, snapshot.flatMap(ZcMempool.over(next, _, previous)), 0L)
    lock.synchronized {
      val print = view.fingerprint
      if (!fingerprint.contains(print)) {
        revision += 1
        fingerprint = Some(print)
      }
      published = Some(view.copy(revision = revision))
    }
  }

  /** Keeps `found`, first dropping scripts no box in `book` names when it would not fit. */
  private def remember(found: Map[String, String], book: ZcBook): Unit = if (found.nonEmpty) {
    val needed = found.valuesIterator.map(_.length.toLong).sum
    if (scriptChars.get() + needed > maxScriptChars) {
      val it = scripts.entrySet().iterator()
      while (it.hasNext) {
        val e = it.next()
        if (!book.byParty.contains(e.getKey)) {
          it.remove()
          scriptChars.addAndGet(-e.getValue.length.toLong)
        }
      }
    }
    found.foreach { case (h, t) =>
      if (scriptChars.get() + t.length <= maxScriptChars && scripts.putIfAbsent(h, t) == null)
        scriptChars.addAndGet(t.length.toLong)
    }
  }

  /**
   * `book` replaced by a rescan when one is due, warning when they differ. Anything short of a rescan at the book's
   * own block keeps `book` and pushes the next attempt on: one block when the index has passed the book, so the two
   * cannot be compared yet, ten after a failure. Without that a due rescan would rerun every step.
   */
  private def checked(book: ZcBook, known: String => Boolean): ZcBook = {
    val due = lock.synchronized(rescanDue(book))
    def later(blocks: Int): ZcBook = { lock.synchronized(rescanFrom = book.height + blocks); book }
    if (!due) book
    else Try(rescan(api(), book.height, book.headerId, settings.maxBoxes, known, logger)) match {
      case Success((scanned, found)) if scanned.headerId == book.headerId =>
        remember(found, scanned)
        val (a, b) = (book.boxes.keySet, scanned.boxes.keySet)
        if (a != b && !book.cut && !scanned.cut)
          logger.warn(s"ZC tracking at ${book.height} held ${(a -- b).size} box(es) a rescan did not find and missed " +
            s"${(b -- a).size}; replaced with the rescan")
        // Origins come only from blocks applied, so the rescan keeps the book's for tokens it still holds state of
        scanned.copy(origins = book.origins.filter { case (token, _) => scanned.stateOf(token).isDefined })
      case Success(_) => later(RescanBackoffBlocks)
      case Failure(_: IndexAhead) => later(1)
      case Failure(ex) =>
        logger.warn(s"ZC tracking's rescan at ${book.height} failed, keeping the book: ${ex.getMessage}")
        later(RescanBackoffBlocks)
    }
  }
}

object ZcTracker {

  /** Most blocks read one by one to reach a target; further behind, a rescan is cheaper. */
  final val MaxCatchUp = 32

  /**
   * Pages read per tree in a scan. The scan keeps only the `maxBoxes` boxes holding most ERG as it goes, so this
   * bounds start-up reads, not memory.
   */
  final val MaxPages = 200
  final val PageSize = 100

  /** Blocks between check rescans: about a day. */
  final val ReseedBlocks = 720

  /** The first retry after a failed step; each further failure doubles it, up to [[MaxRetryMs]]. */
  final val RetryMs = 2000L
  final val MaxRetryMs = 60000L
  final val RescanBackoffBlocks = 10

  /**
   * Blocks a cut book waits between recovery rescans. A tree flooded past [[MaxPages]] pages keeps the book cut,
   * and without this floor it would rescan every block.
   */
  final val RecoveryBlocks = 30

  /** Characters of party scripts kept, about 8 MB. */
  final val MaxScriptChars = 8000000L

  /** The node's index is past the block a check rescan was asked for, so the two cannot be compared yet. */
  private[zc] final class IndexAhead(message: String) extends IllegalStateException(message)

  /**
   * The tracker's own daemon thread. It never shares a bounded dispatcher, which runs a rejected task on the
   * submitting thread: a seed scan there would hold an actor's thread for as long as the scan takes.
   */
  def ownThread(): ExecutionContext = ExecutionContext.fromExecutorService(
    java.util.concurrent.Executors.newSingleThreadExecutor { (r: Runnable) =>
      val t = new Thread(r, "zc-tracker")
      t.setDaemon(true)
      t
    })

  /** Whether `book` already has block `block`, or is past it and cannot check it. */
  private[zc] def holds(book: ZcBook, block: BlockInfo): Boolean =
    block.height < book.base || book.idAt(block.height).contains(block.id)

  /** Whether `book` already satisfies a target (sync block or candidate follow header). */
  private[zc] def holdsTarget(book: ZcBook, wanted: Either[BlockInfo, (Int, String)]): Boolean = wanted match {
    case Left(block) => holds(book, block)
    case Right((h, id)) => h < book.base || book.idAt(h).contains(id)
  }

  /**
   * The book moved to `wanted`: kept when it holds the block, the block applied when it extends the book, else the
   * canonical blocks to its parent read and applied first, else a rescan. With no book, a seed scan.
   */
  private[zc] def advance(api: NodeApi, book: Option[ZcBook], wanted: Option[BlockInfo], cap: Int,
                          known: String => Boolean, logger: Logger): (ZcBook, Map[String, String]) =
    advanceTarget(api, book, wanted.map(Left(_)), cap, known, logger)

  /** Moves `book` to either a sync `BlockInfo` (zero-read apply) or a followed `(height, headerId)`. */
  private[zc] def advanceTarget(api: NodeApi, book: Option[ZcBook], wanted: Option[Either[BlockInfo, (Int, String)]],
                                cap: Int, known: String => Boolean, logger: Logger): (ZcBook, Map[String, String]) = {
    var found = Map.empty[String, String]
    def knows(h: String) = known(h) || found.contains(h)
    def apply(b: ZcBook, height: Int, id: String, parentId: String, txs: Seq[ZcTx]): ZcBook = {
      val (next, scripts) = b.applied(height, id, parentId, txs, cap, knows)
      found ++= scripts
      next
    }
    def offered(b: ZcBook, block: BlockInfo): ZcBook =
      apply(b, block.height, block.id, block.parentId, block.txs.map(ZcTx.of(_, block.resolvedInputs)))
    def read(b: ZcBook, header: NodeHeader): ZcBook = {
      val txs = api.blockTransactions(header.id).get
        .getOrElse(throw new NoSuchElementException(s"the node has no transactions for block ${header.id}"))
      apply(b, header.height, header.id, header.parentId, txs.transactions.map(ZcTx.of))
    }
    val source = new CanonicalBlockSource(api)
    def reseeded(): ZcBook = {
      val (seed, scripts) = rescan(api, -1, "", cap, knows, logger)
      found ++= scripts
      wanted match {
        case Some(Left(block)) if block.height > seed.height =>
          BlockFollow.follow(source, seed, block.height - 1, block.parentId, MaxCatchUp)(read)
            .map(offered(_, block))
            .getOrElse(throw new IllegalStateException(
              s"block ${block.id} at ${block.height} does not descend from the scan at ${seed.height}"))
        case Some(Left(block)) if !holds(seed, block) =>
          throw new IllegalStateException(s"the node's index has another block than ${block.id} at ${block.height}")
        case Some(Right((h, id))) if h > seed.height =>
          BlockFollow.follow(source, seed, h, id, MaxCatchUp)(read)
            .getOrElse(throw new IllegalStateException(
              s"block $id at $h does not descend from the scan at ${seed.height}"))
        case Some(Right((h, id))) if h == seed.height && seed.headerId != id =>
          throw new IllegalStateException(s"the node's index has another block than $id at $h")
        case _ => seed
      }
    }
    val next = (book, wanted) match {
      case (Some(b), None) => b
      case (Some(b), Some(t)) if holdsTarget(b, t) => b
      case (Some(b), Some(Left(block))) if block.height == b.height + 1 && block.parentId == b.headerId =>
        offered(b, block)
      case (Some(b), Some(Left(block))) =>
        BlockFollow.follow(source, b, block.height - 1, block.parentId, MaxCatchUp)(read).map(offered(_, block))
          .getOrElse {
            logger.info(s"ZC tracking is more than ${b.history.size} blocks off block ${block.id} at " +
              s"${block.height}; rescanning the ZC trees")
            reseeded()
          }
      case (Some(b), Some(Right((h, id)))) =>
        BlockFollow.follow(source, b, h, id, MaxCatchUp)(read).getOrElse {
          logger.info(s"ZC tracking is more than ${b.history.size} blocks off block $id at $h; rescanning the ZC trees")
          reseeded()
        }
      case (None, _) => reseeded()
    }
    next -> found
  }

  /**
   * Every ZC tree and the oracle NFT scanned at the node's indexed height, keeping the `cap` boxes holding most ERG,
   * then for a check the blocks from there to `height` applied as well; `height` -1 keeps the scan where the index
   * is. The index has to hold still while it is read, or the scan fails.
   */
  private[zc] def rescan(api: NodeApi, height: Int, headerId: String, cap: Int, known: String => Boolean,
                         logger: Logger): (ZcBook, Map[String, String]) = {
    val source = new CanonicalBlockSource(api)
    val indexed = api.indexedHeight().get.indexedHeight
    if (height >= 0 && indexed > height)
      throw new IndexAhead(s"the node's index is at $indexed, past the book at $height")
    val base = source.headerAt(indexed).get
    val best = new Richest(cap)
    var cut = false
    ZcContracts.scripts.foreach { script =>
      val exhausted = scan(p => api.unspentBoxesByErgoTree(script.ergoTreeHex, p, SortDirection.Desc,
        MempoolOptions.ConfirmedOnly))(b => ZcBoxes.read(b.box, script).foreach(r => best.offer(ZcEntry(r, b.inclusionHeight))))
      if (!exhausted) {
        cut = true
        logger.warn(s"ZC tracking stopped the ${script.label} scan after $MaxPages pages; boxes past it are not tracked")
      }
    }
    var oracle = Vector.empty[(node.model.NodeBox, Int)]
    scan(p => api.unspentBoxesByTokenId(ZcContracts.OracleNft, p, SortDirection.Desc, MempoolOptions.ConfirmedOnly)) { b =>
      if (oracle.size < MaxOracleBoxes) oracle :+= b.box -> b.inclusionHeight
    }
    val after = api.indexedHeight().get.indexedHeight
    require(after == indexed && source.headerAt(indexed).get.id == base.id,
      s"the node's index moved from $indexed while the ZC trees were scanned")
    val seeded = ZcBook.seeded(indexed, base.id, best.result, oracle, cut || best.dropped)
    logger.info(s"ZC tracking scanned ${seeded.boxes.size} box(es) at $indexed" +
      (if (best.dropped) s", keeping the $cap holding most ERG" else ""))
    if (height < 0 || height == seeded.height) seeded -> Map.empty
    else {
      var found = Map.empty[String, String]
      val followed = BlockFollow.follow(source, seeded, height, headerId, MaxCatchUp) { (b, header) =>
        val txs = api.blockTransactions(header.id).get
          .getOrElse(throw new NoSuchElementException(s"the node has no transactions for block ${header.id}"))
        val (next, scripts) = b.applied(header.height, header.id, header.parentId, txs.transactions.map(ZcTx.of),
          cap, h => known(h) || found.contains(h))
        found ++= scripts
        next
      }.getOrElse(throw new IllegalStateException(s"block $headerId at $height does not descend from the scan at $indexed"))
      followed -> found
    }
  }

  /** Boxes carrying the oracle NFT kept from a scan; one is expected, and more than one leaves no oracle. */
  final val MaxOracleBoxes = 8

  /** The `cap` entries holding most nanoERG offered to it, kept in a min-heap so a scan never holds more. */
  private final class Richest(cap: Int) {
    private val heap = mutable.PriorityQueue.empty[ZcEntry](Ordering.by[ZcEntry, (Long, String)](e => (e.value, e.boxId)).reverse)
    private val ids = mutable.HashSet.empty[String]
    var dropped = false

    def offer(e: ZcEntry): Unit = if (!ids.contains(e.boxId)) {
      if (heap.size < cap) { heap.enqueue(e); ids += e.boxId }
      else {
        dropped = true
        if (heap.head.value < e.value) {
          ids -= heap.dequeue().boxId
          heap.enqueue(e)
          ids += e.boxId
        }
      }
    }

    def result: Seq[ZcEntry] = heap.toVector
  }

  /** Feeds every box `read` pages through to `keep`, to [[MaxPages]] pages, and says whether it reached the end. */
  private def scan(read: Paging => Try[Seq[IndexedBox]])(keep: IndexedBox => Unit): Boolean = {
    var offset = 0
    var exhausted = false
    while (!exhausted && offset < MaxPages * PageSize) {
      val page = read(Paging(offset, PageSize)).get
      page.foreach(keep)
      exhausted = page.size < PageSize
      offset += page.size
    }
    exhausted
  }
}
