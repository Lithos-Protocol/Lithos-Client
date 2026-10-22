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

  /**
   * Boxes that are due but carry re-emission tokens, which no rent transaction may spend today.
   * Kept so they need not be rediscovered when the node closes that gap.
   */
  private var deferred: Set[String] = Set.empty

  private var scanning: Boolean = false
  private var ticker: Option[Cancellable] = None

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
      held ++= pass.found
      deferred ++= pass.blocked
      if (pass.found.nonEmpty || pass.blocked.nonEmpty)
        logger.info(s"Rent scan ${pass.from}..${pass.nextHeight - 1} found ${pass.found.size} " +
          s"unspent and ${pass.blocked.size} re-emission boxes; holding ${held.size}")

    case Scanned(Failure(ex)) =>
      scanning = false
      // The cursor stays put, so the next pass reads the same blocks again.
      logger.warn(s"Rent scan from ${cursor.getOrElse("its start")} failed, retrying next pass: " +
        ex.getMessage)

    // Found spent while a sweep was being built. Forgotten rather than retried: nothing brings a
    // spent box back, and the walk will never offer it again.
    case Spent(ids) =>
      held --= ids
      deferred --= ids

    // Refused by the node's own rent rule while a sweep was being built. Dropped until restart, when
    // the walk starts over and finds them again; offered every block, each would cost its sweep.
    case Refused(ids) =>
      held --= ids
      logger.warn(s"Dropped ${ids.size} boxes the node's rent rule refuses; holding ${held.size}")

    // ── the candidate path ──────────────────────────────────────────────────

    case PrepareBlockTxs(blockHeight, _) => startBuild(blockHeight, None)

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
    // Read here rather than inside the build: everything the build touches has to be a value it
    // was handed, because it runs off the mailbox.
    val ids = dueAt(held, blockHeight)
    if (!limits.enabled || ids.isEmpty) preparation.answerEmpty(blockHeight, replyTo)
    else preparation.start(blockHeight, replyTo)(sweep(ids, blockHeight))
  }

  /**
   * Read every remembered box back, keep what still exists, and build one sweep out of what fits.
   *
   * The read is the revalidation. Ids that do not come back name boxes somebody has already spent,
   * and they are forgotten rather than retried.
   */
  private def sweep(ids: Seq[String], blockHeight: Int): Seq[CandidateBundle] =
    nodeContext.getClient.execute { ctx =>
      val params = ctx.getDataSource.getParameters
      val live = nodeApi.boxesWithPoolByIds(ids).getOrElse(Seq.empty)
      val gone = ids.toSet -- live.map(_.boxId).toSet
      if (gone.nonEmpty) self ! Spent(gone)

      val protocol = ProtocolBoxes(ctx)
      val candidates = live.flatMap { box =>
        val input: InputUTXO = box.toInputUTXO(ctx)
        StorageRent.plan(input, box.creationHeight, blockHeight, params, ctx.getNetworkType, protocol)
          .map(RentCandidate(input, _))
      }
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

    val until = math.min(from + rentConfig.blocksPerScan, ceiling + 1)
    var found = Map.empty[String, Int]
    var blocked = Set.empty[String]
    var reached = from
    (from until until).foreach { height =>
      val outputs = nodeApi.blockAt(height).getOrElse(Seq.empty)
        .flatMap(_.blockTransactions.transactions.flatMap(_.outputs))
      val (open, reEmission) =
        StorageRent.sortByAge(outputs, ceiling, nodeContext.getNetwork, protocol)
      val unspent = if (open.isEmpty) Set.empty[String]
        else nodeApi.boxesWithPoolByIds(open.toSeq).getOrElse(Seq.empty).map(_.boxId).toSet
      found ++= outputs.collect { case box if unspent.contains(box.boxId) => box.boxId -> box.creationHeight }
      blocked ++= reEmission
      reached = height + 1
    }
    Pass(from, reached, found, blocked)
  }
}

object StorageRentSource {
  /** Scheduler tick: read the next stretch of the chain. */
  private[rent] case object ScanTick

  /**
   * What one pass read, from where, and the height the next one starts at. `found` maps id to
   * creation height.
   */
  private[rent] final case class Pass(from: Int, nextHeight: Int, found: Map[String, Int],
                                      blocked: Set[String])

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

  /** Held ids whose creation height makes them collectable in a block at `blockHeight`. */
  def dueAt(held: Map[String, Int], blockHeight: Int): Seq[String] =
    held.collect { case (id, created) if blockHeight.toLong - created >= StorageRent.StoragePeriod => id }
      .toSeq

  private[rent] final case class Scanned(result: Try[Pass])

  /** Boxes a build found already spent, so they stop being offered. */
  private[rent] final case class Spent(ids: Set[String])

  /** Boxes the node's rent rule refused during a build, so they stop being offered. */
  private[rent] final case class Refused(ids: Set[String])

}
