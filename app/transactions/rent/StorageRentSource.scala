package transactions.rent

import akka.actor.{Actor, ActorRef, Cancellable}
import configs.{CandidateSourceConfig, NodeContext, RentConfig}
import node.MutationConversions._
import node.NodeApi
import org.slf4j.{Logger, LoggerFactory}
import transactions.candidate.BlockTxMessages.{BlockTxsReady, CandidateTxsDropped, PrepareBlockTxs, RequestBlockTxs}
import transactions.candidate.CandidateBundle
import work.lithos.mutations.InputUTXO

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

/**
 * Finds boxes whose storage rent is due and offers them to this miner's own block.
 *
 * Two halves that never touch. A timer walks the chain four years behind the tip, remembering the
 * id and creation height of every unspent box; the candidate path takes the ids that are due at its
 * height, reads the boxes back and builds one sweep. The read that fetches a box back is also the
 * only liveness check that matters — a box that does not come back has already been spent.
 *
 * Nothing here runs on the mining path. The walk is a background timer, and the sweep is built when
 * the height is first known rather than when the package is asked for.
 */
class StorageRentSource(nodeContext: NodeContext,
                        rentConfig: RentConfig,
                        limits: CandidateSourceConfig,
                        useTrueProp: Boolean) extends Actor {

  import StorageRentSource._

  private val logger: Logger = LoggerFactory.getLogger("StorageRentSource")

  private implicit val ec: ExecutionContext = context.dispatcher
  private val candidateWorker: ExecutionContext =
    context.system.dispatchers.lookup(configs.Contexts.key(configs.Contexts.Polling))
  private val preparation =
    new transactions.candidate.CandidatePreparation(configs.BoundedWorker.unbounded(candidateWorker), self)

  protected def nodeApi: NodeApi = nodeContext.getNodeApi

  /** Next inclusion height the walk will read. Empty until the first pass places it from the tip. */
  private var cursor: Option[Int] = None

  /**
   * Unspent boxes the walk has met, by id, with the creation height their age is measured from.
   * Some are not due yet; a build offers only those its own height makes old enough.
   */
  private var held: Map[String, Int] = Map.empty

  /** The same held boxes ordered oldest first, so builds never sort the full backlog on the actor thread. */
  private var byAge: scala.collection.immutable.TreeSet[(Int, String)] = scala.collection.immutable.TreeSet.empty

  /**
   * Rotating revalidation cursor across due entries: each build reads up to [[MaxDueReads]] due boxes starting after
   * `nextDue` and wraps around, so live boxes that cannot fund a sweep never starve newer collectible boxes.
   */
  private var nextDue: Option[(Int, String)] = None

  /** Retry cooldown (block height until which a box whose read page failed is deferred while other due boxes run). */
  private var retryAfter: Map[String, Int] = Map.empty

  /** The height the walk last stopped at on a failed read, and how many passes in a row did. */
  private var stalled: Option[(Int, Int)] = None

  private var scanning: Boolean = false
  private var ticker: Option[Cancellable] = None

  private def addHeld(entries: Map[String, Int]): Unit = {
    entries.foreach { case (id, created) =>
      if (!held.contains(id)) {
        held += id -> created
        byAge += created -> id
      }
    }
    while (held.size > MaxHeld && byAge.nonEmpty) {
      val oldest @ (_, id) = byAge.head
      byAge -= oldest
      held -= id
      retryAfter -= id
    }
  }

  private def removeHeld(ids: Set[String]): Unit =
    ids.foreach { id =>
      held.get(id).foreach(created => byAge -= (created -> id))
      held -= id
      retryAfter -= id
    }

  override def preStart(): Unit = {
    logger.info(s"StorageRentSource started: startBehindBlocks=${rentConfig.startBehindBlocks}, " +
      s"blocksPerScan=${rentConfig.blocksPerScan}, lookaheadBlocks=${rentConfig.lookaheadBlocks}, " +
      s"period=${StorageRent.StoragePeriod}")
    ticker = Some(context.system.scheduler.scheduleWithFixedDelay(
      rentConfig.scanIntervalMs.milliseconds, rentConfig.scanIntervalMs.milliseconds,
      self, ScanTick)(context.dispatcher))
  }

  override def postStop(): Unit = ticker.foreach(_.cancel())

  override def receive: Receive = rentReceive.orElse(preparation.receive)

  private def rentReceive: Receive = {

    case ScanTick if !scanning =>
      scanning = true
      val from = cursor
      Future(scan(from))(candidateWorker).onComplete(result => self ! Scanned(result))

    case ScanTick => ()

    case Scanned(Success(pass)) =>
      scanning = false
      // Only advance past what was actually read, so a short pass resumes where it stopped rather
      // than skipping the blocks it never reached.
      cursor = Some(cursor.fold(pass.nextHeight)(math.max(_, pass.nextHeight)))
      addHeld(pass.found)
      if (pass.found.nonEmpty || pass.blocked.nonEmpty)
        logger.info(s"Rent scan ${pass.from}..${pass.nextHeight - 1} found ${pass.found.size} " +
          s"unspent and ${pass.blocked.size} re-emission boxes; holding ${held.size}")
      pass.stoppedBy match {
        case None => stalled = None
        case Some(reason) =>
          val stall = stalledAgain(stalled, pass.nextHeight)
          if (stall._2 >= MaxReadsPerHeight) {
            logger.error(s"Rent scan skips block ${pass.nextHeight} after ${stall._2} failed reads; " +
              s"boxes it created are not collected: $reason")
            cursor = Some(pass.nextHeight + 1)
            stalled = None
          } else {
            logger.warn(s"Rent scan stopped at ${pass.nextHeight}, read again next pass: $reason")
            stalled = Some(stall)
          }
      }

    case Scanned(Failure(ex)) =>
      scanning = false
      // The cursor stays put, so the next pass reads the same blocks again.
      logger.warn(s"Rent scan from ${cursor.getOrElse("its start")} failed, retrying next pass: " +
        ex.getMessage)

    // Found spent while a sweep was being built. Forgotten rather than retried: nothing brings a
    // spent box back, and the walk will never offer it again.
    case Spent(ids) =>
      removeHeld(ids)

    // Live due boxes that StorageRent.plan cannot collect (negative wrapped fee, over-size recreation, or at min value).
    case Uncollectible(ids) =>
      removeHeld(ids)
      logger.debug(s"Dropped ${ids.size} uncollectible due boxes; holding ${held.size}")

    // Update retry backoff for boxes whose page read failed while other pages succeeded, clearing recovered ones.
    case ReadResult(readIds, failedIds, blockHeight) =>
      readIds.foreach(retryAfter -= _)
      if (failedIds.nonEmpty) {
        val until = blockHeight + FailedPageBackoffBlocks
        failedIds.foreach(id => if (held.contains(id)) retryAfter += id -> until)
      }
      if (retryAfter.nonEmpty) retryAfter = retryAfter.filter { case (id, until) => held.contains(id) && until > blockHeight }

    // Refused by the node's own rent rule while a sweep was being built. Dropped until restart, when
    // the walk starts over and finds them again; offered every block, each would cost its sweep.
    case Refused(ids) =>
      removeHeld(ids)
      logger.warn(s"Dropped ${ids.size} boxes the node's rent rule refuses; holding ${held.size}")

    // ── the candidate path ──────────────────────────────────────────────────

    case PrepareBlockTxs(blockHeight, _, _) => startBuild(blockHeight, None)

    case RequestBlockTxs(blockHeight, _, refresh) =>
      val replyTo = sender()
      preparation.preparedFor(blockHeight).filterNot(_ => refresh) match {
        case Some(bundles) => replyTo ! BlockTxsReady(blockHeight, bundles)
        case None => startBuild(blockHeight, Some(replyTo))
      }

    // Nothing to reconcile: a rent sweep holds no wallet input and was never broadcast, so a
    // dropped height leaves only the work itself, which cannot be offered to a later one.
    case CandidateTxsDropped(blockHeight, _) => preparation.drop(blockHeight)
  }

  private def startBuild(blockHeight: Int, replyTo: Option[ActorRef]): Unit = {
    // Select up to MaxDueReads due entries from the age-ordered index without sorting all held boxes.
    val (window, advancedCursor) = selectDue(byAge, blockHeight, nextDue, retryAfter, MaxDueReads)
    nextDue = advancedCursor
    val ids = window.map(_._2)
    if (!limits.enabled || ids.isEmpty) preparation.answerEmpty(blockHeight, replyTo)
    else preparation.start(blockHeight, replyTo)(sweep(ids, blockHeight))
  }

  /**
   * Read the due boxes back in pages, keep what still exists, and build one sweep out of what fits.
   *
   * The read is the revalidation. Ids a page read does not return name boxes somebody has already
   * spent, and they are forgotten. A failed page offers nothing and forgets nothing of its own, and
   * its ids are backed off briefly while other due boxes rotate through.
   */
  private def sweep(ids: Seq[String], blockHeight: Int): Seq[CandidateBundle] = {
    val read = readDue(ids, nodeApi.boxesWithPoolByIds)
    if (read.gone.nonEmpty) self ! Spent(read.gone)
    val readOk = read.live.map(_.boxId).toSet ++ read.gone
    if (readOk.nonEmpty || read.failedIds.nonEmpty)
      self ! ReadResult(readOk, if (readOk.nonEmpty) read.failedIds else Set.empty, blockHeight)
    read.failure.foreach(reason => logger.warn(s"Rent sweep for block $blockHeight could not read back " +
      s"${read.unread} of its ${ids.size} due boxes, offering what it read: $reason"))
    if (read.live.isEmpty) Seq.empty[CandidateBundle] else build(read.live, blockHeight)
  }

  private def build(live: Seq[node.model.NodeBox], blockHeight: Int): Seq[CandidateBundle] =
    nodeContext.getClient.execute { ctx =>
      val params = ctx.getDataSource.getParameters
      val protocol = ProtocolBoxes(ctx)
      val uncollectible = Set.newBuilder[String]
      val candidates = live.flatMap { box =>
        val input: InputUTXO = box.toInputUTXO(ctx)
        StorageRent.plan(input, box.creationHeight, blockHeight, params, ctx.getNetworkType, protocol) match {
          case Some(action) => Some(RentCandidate(input, action))
          case None =>
            if (blockHeight.toLong - box.creationHeight >= StorageRent.StoragePeriod.toLong) uncollectible += box.boxId
            None
        }
      }
      val dropped = uncollectible.result()
      if (dropped.nonEmpty) self ! Uncollectible(dropped)
      val wallet = nodeContext.getNodeWallet
      val floor = StorageRent.proceedsFloor(
        transactions.candidate.CandidateCapital.collectionContract(wallet, useTrueProp), blockHeight, params)
      val chosen = StorageRent.fitting(candidates, limits.budget, params, floor)
      if (chosen.isEmpty) Seq.empty[CandidateBundle]
      else {
        val collection = StorageRent.collect(ctx, wallet, chosen, blockHeight, useTrueProp)
        if (collection.refused.nonEmpty) self ! Refused(collection.refused.map(_.box.id.toString).toSet)
        collection.bundle.toSeq
      }
    }

  /**
   * One pass of the walk, bounded by `blocksPerScan` and by `walkCeiling`. The first pass starts at
   * `walkStart`, since the tip it is measured from is only known here.
   *
   * Two node reads a block: the block itself for what it created, then one batch read to keep only
   * the boxes still unspent. Storing the spent ones instead would mean remembering every output the
   * chain ever made below the ceiling.
   */
  private def scan(cursor: Option[Int]): Pass = {
    val protocol = nodeContext.getClient.execute(ProtocolBoxes.apply)
    val tip = nodeApi.info().toOption.flatMap(_.fullHeight).getOrElse(
      throw new IllegalStateException("the node did not report a height"))
    val from = cursor.getOrElse(walkStart(tip, rentConfig.startBehindBlocks))
    val ceiling = walkCeiling(tip, rentConfig.lookaheadBlocks)
    if (from > ceiling) return Pass(from, from, Map.empty, Set.empty)

    walk(from, math.min(from + rentConfig.blocksPerScan, ceiling + 1),
      height => nodeApi.blockAt(height).map(_.flatMap(_.blockTransactions.transactions.flatMap(_.outputs))),
      ids => nodeApi.boxesWithPoolByIds(ids).map(_.map(_.boxId).toSet),
      outputs => StorageRent.sortByAge(outputs, ceiling, nodeContext.getNetwork, protocol))
  }
}

object StorageRentSource {
  /** Scheduler tick: read the next stretch of the chain. */
  private[rent] case object ScanTick

  /**
   * What one pass read, from where, and the height the next one starts at. `found` maps id to
   * creation height. `stoppedBy` is the failed read that ended the pass at `nextHeight`.
   */
  private[rent] final case class Pass(from: Int, nextHeight: Int, found: Map[String, Int],
                                      blocked: Set[String], stoppedBy: Option[String] = None)

  /** Passes in a row a height may fail before the walk skips it, so one unreadable block cannot stop it. */
  final val MaxReadsPerHeight: Int = 5

  /** The stall after a pass stopped at `stoppedAt`: one more pass if it stopped there last time too. */
  def stalledAgain(previous: Option[(Int, Int)], stoppedAt: Int): (Int, Int) =
    stoppedAt -> previous.filter(_._1 == stoppedAt).fold(1)(_._2 + 1)

  /**
   * Reads heights `from` until `until`, keeping each block's unspent boxes that `sort` keeps.
   *
   * Stops at the first height whose block or unspent read fails, without reading past it, so the
   * next pass reads that height again rather than skipping its boxes for good.
   */
  private[rent] def walk(from: Int, until: Int,
                         outputsAt: Int => Try[Seq[node.model.NodeBox]],
                         unspentAmong: Seq[String] => Try[Set[String]],
                         sort: Seq[node.model.NodeBox] => (Set[String], Set[String])): Pass = {
    var found = Map.empty[String, Int]
    var blocked = Set.empty[String]
    var reached = from
    var stoppedBy = Option.empty[String]
    while (stoppedBy.isEmpty && reached < until) {
      val read = outputsAt(reached).flatMap { outputs =>
        val (open, reEmission) = sort(outputs)
        val unspent = if (open.isEmpty) Success(Set.empty[String]) else unspentAmong(open.toSeq)
        unspent.map(kept =>
          outputs.collect { case box if kept.contains(box.boxId) => box.boxId -> box.creationHeight } -> reEmission)
      }
      read match {
        case Success((kept, reEmission)) =>
          found ++= kept
          blocked ++= reEmission
          reached += 1
        case Failure(ex) => stoppedBy = Some(s"${ex.getClass.getSimpleName}: ${ex.getMessage}")
      }
    }
    Pass(from, reached, found, blocked, stoppedBy)
  }

  /**
   * First inclusion height the walk reads, `behind` blocks before the newest block whose boxes are
   * all due. Behind the due frontier, other collectors have usually swept everything already. Never
   * below the first block, so a chain younger than `StoragePeriod` starts above its ceiling.
   */
  def walkStart(tip: Int, behind: Int): Int = math.max(1, walkCeiling(tip, 0) - behind)

  /**
   * Last inclusion height a pass may read when the node's tip is `tip`.
   *
   * Every block at or under `tip + 1 - StoragePeriod` holds only boxes due in the next block. Wallets
   * date a box a few blocks before it is included, so the walk reads `lookahead` blocks further and
   * holds what it finds until it falls due.
   */
  def walkCeiling(tip: Int, lookahead: Int): Int = tip + 1 - StorageRent.StoragePeriod + lookahead

  /** Held ids whose creation height makes them collectable in a block at `blockHeight`, oldest first. */
  def dueAt(held: Map[String, Int], blockHeight: Int): Seq[String] =
    held.toSeq.collect { case (id, created) if blockHeight.toLong - created >= StorageRent.StoragePeriod => created -> id }
      .sorted.map(_._2)

  /**
   * Selects up to `limit` due entries `(creationHeight, boxId)` from an age-ordered index, rotating past `cursor`
   * and wrapping around, while skipping entries whose retry cooldown (`retryAfter`) has not yet expired unless all
   * due entries are in cooldown. Returns `(selected, nextCursor)`.
   */
  private[rent] def selectDue(byAge: scala.collection.immutable.TreeSet[(Int, String)],
                              blockHeight: Int,
                              cursor: Option[(Int, String)],
                              retryAfter: Map[String, Int],
                              limit: Int = MaxDueReads): (Vector[(Int, String)], Option[(Int, String)]) = {
    val dueCutoffLong = blockHeight.toLong - StorageRent.StoragePeriod.toLong
    if (limit <= 0 || dueCutoffLong < 0L || byAge.isEmpty) (Vector.empty, None)
    else {
      val dueCutoff = dueCutoffLong.min(Int.MaxValue).toInt
      val ord = implicitly[Ordering[(Int, String)]]
      def collect(ignoreCooldown: Boolean): (Vector[(Int, String)], Boolean) = {
        val out = Vector.newBuilder[(Int, String)]
        var count = 0
        var wrapped = false
        def accept(e: (Int, String)): Boolean =
          ignoreCooldown || !retryAfter.get(e._2).exists(_ > blockHeight)
        def takeFrom(it: Iterator[(Int, String)]): Unit = {
          while (count < limit && it.hasNext) {
            val e = it.next()
            if (accept(e)) {
              out += e
              count += 1
            }
          }
        }
        cursor match {
          case Some(c) =>
            val after = byAge.from(c).iterator.dropWhile(e => ord.lteq(e, c)).takeWhile(_._1 <= dueCutoff)
            takeFrom(after)
            if (count < limit) {
              wrapped = true
              val head = byAge.iterator.takeWhile(e => e._1 <= dueCutoff && ord.lteq(e, c))
              takeFrom(head)
            }
          case None =>
            takeFrom(byAge.iterator.takeWhile(_._1 <= dueCutoff))
        }
        (out.result(), wrapped)
      }
      val (eligible, wrapped) = collect(ignoreCooldown = false)
      val (selected, didWrap) = if (eligible.nonEmpty) (eligible, wrapped) else collect(ignoreCooldown = true)
      val nextCursor =
        if (selected.size < limit) None
        else if (didWrap && selected.lastOption == cursor) None
        else selected.lastOption
      (selected, nextCursor)
    }
  }

  /** Most unspent box ids retained at once, bounding memory if a historical spam wave leaves many dust boxes. */
  final val MaxHeld: Int = 20000

  /** Blocks a box whose page read failed (while other pages succeeded) is deferred so healthy pages rotate through. */
  final val FailedPageBackoffBlocks: Int = 4

  /** Ids per node read of due boxes, so one response stays small whatever a block once created. */
  final val DuePage: Int = 100

  /** Most due ids one build reads back, which is also the most one sweep can take. */
  final val MaxDueReads: Int = StorageRent.MaxBoxes

  /** Failed pages one build tolerates before it stops reading, so a node that is down costs two calls. */
  final val MaxFailedPages: Int = 2

  /**
   * What reading back due boxes found: the boxes still unspent, the ids pages returned nothing for,
   * the ids whose page read failed, how many ids were left unread by failed pages or the read cap, and the last failure.
   */
  private[rent] final case class DueRead(live: Seq[node.model.NodeBox], gone: Set[String], unread: Int,
                                         failure: Option[String], failedIds: Set[String] = Set.empty)

  /**
   * Reads the first [[MaxDueReads]] of `ids` in pages of [[DuePage]]. A failed page is skipped, its ids kept,
   * and the pages after it are still read, until [[MaxFailedPages]] have failed.
   */
  private[rent] def readDue(ids: Seq[String], read: Seq[String] => Try[Seq[node.model.NodeBox]]): DueRead = {
    var live = Vector.empty[node.model.NodeBox]
    var gone = Set.empty[String]
    var failedIds = Set.empty[String]
    var failed = 0
    var readIds = 0
    var failure = Option.empty[String]
    val pages = ids.take(MaxDueReads).grouped(DuePage)
    while (pages.hasNext && failed < MaxFailedPages) {
      val page = pages.next()
      read(page) match {
        case Success(boxes) =>
          val found = boxes.filter(b => page.contains(b.boxId))
          live ++= found
          gone ++= page.toSet -- found.map(_.boxId)
          readIds += page.size
        case Failure(ex) =>
          failed += 1
          failedIds ++= page
          failure = Some(s"${ex.getClass.getSimpleName}: ${ex.getMessage}")
      }
    }
    DueRead(live, gone, ids.size - readIds, failure, failedIds)
  }

  private[rent] final case class Scanned(result: Try[Pass])

  /** Boxes a build found already spent, so they stop being offered. */
  private[rent] final case class Spent(ids: Set[String])

  /** Due boxes `StorageRent.plan` permanently cannot collect (wrapped negative fee, over-size, or no collectable ERG). */
  private[rent] final case class Uncollectible(ids: Set[String])

  /** Page read outcome, updating retry backoff for failed pages while clearing recovered ids. */
  private[rent] final case class ReadResult(readIds: Set[String], failedIds: Set[String], blockHeight: Int)

  /** Boxes the node's rent rule refused during a build, so they stop being offered. */
  private[rent] final case class Refused(ids: Set[String])

}
