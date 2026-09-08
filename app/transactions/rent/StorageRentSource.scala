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
 * id of every box old enough to collect; the candidate path takes those ids, reads the boxes back
 * and builds one sweep. Only ids are kept, because the read that fetches a box back is also the
 * only check that matters — a box that does not come back has already been spent.
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
  private val preparation = new transactions.candidate.CandidatePreparation(candidateWorker, self)

  protected def nodeApi: NodeApi = nodeContext.getNodeApi

  /** Next inclusion height the walk will read. */
  private var cursor: Int = rentConfig.startHeight

  /** Boxes old enough to collect, as ids. The box itself is read back before anything is built. */
  private var eligible: Set[String] = Set.empty

  /**
   * Boxes that are due but carry re-emission tokens, which no rent transaction may spend today.
   * Kept so they need not be rediscovered when the node closes that gap.
   */
  private var deferred: Set[String] = Set.empty

  private var scanning: Boolean = false
  private var ticker: Option[Cancellable] = None

  override def preStart(): Unit = {
    logger.info(s"StorageRentSource started: startHeight=${rentConfig.startHeight}, " +
      s"blocksPerScan=${rentConfig.blocksPerScan}, period=${StorageRent.StoragePeriod}")
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
      Future(scan(from))(candidateWorker).onComplete(result => self ! Scanned(from, result))

    case ScanTick => ()

    case Scanned(from, Success(pass)) =>
      scanning = false
      // Only advance past what was actually read, so a short pass resumes where it stopped rather
      // than skipping the blocks it never reached.
      cursor = math.max(cursor, pass.nextHeight)
      eligible ++= pass.found
      deferred ++= pass.blocked
      if (pass.found.nonEmpty || pass.blocked.nonEmpty)
        logger.info(s"Rent scan $from..${pass.nextHeight - 1} found ${pass.found.size} " +
          s"collectable and ${pass.blocked.size} re-emission boxes; holding ${eligible.size}")

    case Scanned(from, Failure(ex)) =>
      scanning = false
      // The cursor stays put, so the next pass reads the same blocks again.
      logger.warn(s"Rent scan from $from failed, retrying next pass: ${ex.getMessage}")

    // Found spent while a sweep was being built. Forgotten rather than retried: nothing brings a
    // spent box back, and the walk will never offer it again.
    case Spent(ids) =>
      eligible --= ids
      deferred --= ids

    // ── the candidate path ──────────────────────────────────────────────────

    case PrepareBlockTxs(blockHeight, _) => startBuild(blockHeight, None)

    case RequestBlockTxs(blockHeight, _) =>
      val replyTo = sender()
      preparation.preparedFor(blockHeight) match {
        case Some(bundles) => replyTo ! BlockTxsReady(blockHeight, bundles)
        case None => startBuild(blockHeight, Some(replyTo))
      }

    // Nothing to reconcile: a rent sweep holds no wallet input and was never broadcast, so a
    // dropped height leaves only the work itself, which cannot be offered to a later one.
    case CandidateTxsDropped(blockHeight) => preparation.drop(blockHeight)
  }

  private def startBuild(blockHeight: Int, replyTo: Option[ActorRef]): Unit =
    if (!limits.enabled || eligible.isEmpty) preparation.answerEmpty(blockHeight, replyTo)
    else {
      // Read here rather than inside the build: everything the build touches has to be a value it
      // was handed, because it runs off the mailbox.
      val ids = eligible.toSeq
      preparation.start(blockHeight, replyTo)(sweep(ids, blockHeight))
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

      val candidates = live.flatMap { box =>
        val input: InputUTXO = box.toInputUTXO(ctx)
        StorageRent.plan(input, box.creationHeight, blockHeight, params, ctx.getNetworkType)
          .map(RentCandidate(input, _))
      }
      val chosen = StorageRent.fitting(candidates, limits.budget, params)
      if (chosen.isEmpty) Seq.empty[CandidateBundle]
      else StorageRent.build(ctx, nodeContext.getNodeWallet, chosen, blockHeight, useTrueProp).toSeq
    }

  /**
   * One pass of the walk, bounded by `blocksPerScan` and by how far behind the tip a box has to be.
   *
   * Two node reads a block: the block itself for what it created, then one batch read to keep only
   * the boxes still unspent. Storing the spent ones instead would mean remembering every output the
   * chain ever made below the threshold.
   */
  private def scan(from: Int): Pass = {
    val tip = nodeApi.info().toOption.flatMap(_.fullHeight).getOrElse(
      throw new IllegalStateException("the node did not report a height"))
    val threshold = tip - StorageRent.StoragePeriod
    if (from > threshold) return Pass(from, Set.empty, Set.empty)

    val until = math.min(from + rentConfig.blocksPerScan, threshold + 1)
    var found = Set.empty[String]
    var blocked = Set.empty[String]
    var reached = from
    (from until until).foreach { height =>
      val outputs = nodeApi.blockAt(height).getOrElse(Seq.empty)
        .flatMap(_.blockTransactions.transactions.flatMap(_.outputs))
      val (open, reEmission) = StorageRent.sortByAge(outputs, threshold, nodeContext.getNetwork)
      val unspent = if (open.isEmpty) Set.empty[String]
        else nodeApi.boxesWithPoolByIds(open.toSeq).getOrElse(Seq.empty).map(_.boxId).toSet
      found ++= unspent
      blocked ++= reEmission
      reached = height + 1
    }
    Pass(reached, found, blocked)
  }
}

object StorageRentSource {
  /** Scheduler tick: read the next stretch of the chain. */
  private[rent] case object ScanTick

  /** What one pass read, and the height the next one starts at. */
  private[rent] final case class Pass(nextHeight: Int, found: Set[String], blocked: Set[String])

  private[rent] final case class Scanned(from: Int, result: Try[Pass])

  /** Boxes a build found already spent, so they stop being offered. */
  private[rent] final case class Spent(ids: Set[String])

}
