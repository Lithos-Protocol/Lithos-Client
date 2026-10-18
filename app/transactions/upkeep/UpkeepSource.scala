package transactions.upkeep

import akka.actor.{Actor, ActorRef, Cancellable}
import configs.{CandidateSourceConfig, NodeContext, UpkeepConfig}
import node.MutationConversions._
import node.NodeApi
import node.model.NodeBox
import org.slf4j.{Logger, LoggerFactory}
import transactions.candidate.BlockTxMessages.{BlockTxsReady, CandidateTxsDropped, PrepareBlockTxs, RequestBlockTxs}
import transactions.candidate.{CandidateBundle, CandidateCapital}
import work.lithos.mutations.InputUTXO

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

/**
 * Advances the boxes its jobs maintain inside this miner's own block. Every input must be a box
 * its job reported and this build read back, none at this wallet's keys, and every output must
 * sit at an input's script or this miner's collection contract; a [[ScriptJob]] also signs with no
 * key and pays no fee.
 *
 * Two halves, as in the storage-rent source. A timer asks each job what it maintains and keeps only
 * ids; the candidate path reads the boxes back, has each job build the due ones until the share is
 * spent, and answers through [[transactions.candidate.CandidatePreparation]]. The read-back is the
 * node's mempool-adjusted view: a box that does not come back is spent, or spent by a pending
 * transaction, and is dropped until a later scan finds it again. The build closes over values it
 * is handed and reports back by message, so no actor field is read off the mailbox.
 *
 * Never extractive. Nothing here looks at what pending transactions do; the read-back only skips a
 * box one of them already spends, and a spend that reaches the mempool after the read-back loses
 * to this miner's own block, as with any block producer. A job's transaction may only spend the
 * boxes that job reported from discovery, which holds a job to its own word; the shipped job
 * reports only boxes at its own script and signs with no key and no fee.
 *
 * Holds outlive the actor in a [[UpkeepSource.Memory]], so a restart does not offer the node the
 * same transaction again. With `verifyWithNode`, each admitted successor goes through the node's
 * transaction check in the build for that height: normally the one `PrepareBlockTxs` starts, off
 * the request path, but a request that finds nothing prepared starts the build and waits on it,
 * checks included, inside the shared source deadline. The node evaluates the check at its own next
 * height, normally the candidate's but later when blocks come fast, and a refusal for that reason
 * is not remembered. In observe mode a request is answered empty at once and the build runs as a
 * task no request waits for, holding nothing back and forgetting nothing.
 */
class UpkeepSource(nodeContext: NodeContext,
                   upkeepConfig: UpkeepConfig,
                   limits: CandidateSourceConfig,
                   jobs: Seq[UpkeepJob],
                   memory: UpkeepSource.Memory,
                   useTrueProp: Boolean,
                   firstScanDelay: FiniteDuration = UpkeepSource.FirstScanDelay) extends Actor {

  import UpkeepSource._

  require(runs(limits, jobs), s"an upkeep source needs to be enabled with a job: enabled=${limits.enabled}, " +
    s"jobs=${jobs.map(_.name).mkString(", ")}")
  require(jobs.map(_.name).distinct.size == jobs.size,
    s"upkeep job names must be distinct: ${jobs.map(_.name).mkString(", ")}")

  private val logger: Logger = LoggerFactory.getLogger("UpkeepSource")

  private implicit val ec: ExecutionContext = context.dispatcher
  private val candidateWorker: ExecutionContext =
    context.system.dispatchers.lookup(configs.Contexts.key(configs.Contexts.Polling))
  private val preparation = new transactions.candidate.CandidatePreparation(candidateWorker, self)

  protected def nodeApi: NodeApi = nodeContext.getNodeApi

  /** What each job's last successful discovery returned, less what builds have since found spent. */
  private var tracked: Map[String, Set[String]] = Map.empty

  private var scanning: Boolean = false
  private var ticker: Option[Cancellable] = None

  /** What the last scan line reported, so an unchanged holding is logged at debug. */
  private var lastHolding: Option[(Map[String, Int], Int, Int)] = None

  /** Every id each job's last successful pass found, held ones included, so holds survive a failed pass. */
  private var lastFound: Map[String, Set[String]] = Map.empty

  /** The highest height observe mode has started a task for, so each height is observed once. */
  private var observedThrough: Int = Int.MinValue

  /** Whether an observe task is still running, so a slow node does not pile them up. */
  private var observing: Boolean = false

  /** What the last build admitted, so an unchanged block logs at debug and a changed one at info. */
  private val lastAdmitted = new java.util.concurrent.atomic.AtomicReference[String]("")

  override def preStart(): Unit = {
    if (!upkeepConfig.verifyWithNode && !upkeepConfig.observing)
      logger.warn("Upkeep runs with verifyWithNode = false: a successor the node would refuse costs the block every " +
        "inserted transaction")
    logger.info(s"UpkeepSource started: mode=${upkeepConfig.mode}, verifyWithNode=${upkeepConfig.verifyWithNode}, " +
      s"jobs=${jobs.map(_.name).mkString(", ")}, scanIntervalMs=${upkeepConfig.scanIntervalMs}, " +
      s"maxBoxesPerJob=${upkeepConfig.maxBoxesPerJob}, retryAfterScans=${memory.retryAfterScans}, " +
      s"share: txs=${limits.maxTxs}, bytes=${limits.maxBytes}, cost=${limits.maxCost}; " +
      s"refusedHeld=${memory.refusedIds.size}, exhaustedHeld=${memory.exhaustedIds.size}")
    ticker = Some(context.system.scheduler.scheduleWithFixedDelay(
      firstScanDelay, upkeepConfig.scanIntervalMs.milliseconds, self, ScanTick)(context.dispatcher))
  }

  override def postStop(): Unit = ticker.foreach(_.cancel())

  override def receive: Receive = upkeepReceive.orElse(preparation.receive)

  private def upkeepReceive: Receive = {

    case ScanTick if !scanning =>
      scanning = true
      Future(scan())(candidateWorker).onComplete(result => self ! Scanned(result))

    case ScanTick => ()

    // A job whose discovery failed is absent from the pass and keeps what it had: stale ids cost
    // one read each and are forgotten by it, while dropping them would lose the boxes until the
    // node recovers.
    case Scanned(Success(pass)) =>
      scanning = false
      tracked ++= pass.map { case (job, found) => job -> found.kept }
      lastFound ++= pass.map { case (job, found) => job -> found.found }
      // Holds are kept for every box a pass found, kept under the cap or not: a box the cap left out
      // because it is held must stay held, or it would win the cap again next pass. A job whose
      // discovery failed this pass keeps what it last found, so its holds survive the failure too.
      val known = tracked.values.flatten.toSet ++ lastFound.values.flatten
      val retried = memory.passed(known)
      if (retried.nonEmpty)
        logger.info(s"Upkeep offers ${retried.size} refused boxes again after ${memory.retryAfterScans} " +
          s"passes: ${retried.toSeq.sorted.map(_.take(8)).mkString(", ")}")
      val holding = (tracked.map { case (job, ids) => job -> ids.size }, memory.refusedIds.size, memory.exhaustedIds.size)
      val line = s"Upkeep scan holds ${known.size} boxes: " +
        tracked.map { case (job, ids) => s"$job=${ids.size}" }.mkString(", ") +
        s"; ${holding._2} refused, ${holding._3} cannot pay"
      if (lastHolding.contains(holding)) logger.debug(line) else logger.info(line)
      lastHolding = Some(holding)

    case Scanned(Failure(ex)) =>
      scanning = false
      logger.warn(s"Upkeep scan failed, retrying next pass: ${ex.getMessage}")

    // Found spent while a build was reading them back. Forgotten rather than retried: nothing
    // brings a spent box back, and its successor has an id the next scan will find on its own.
    case Spent(ids) =>
      tracked = tracked.map { case (job, held) => job -> (held -- ids) }
      memory.forget(ids)

    // A build failed, or made a transaction this source or the node will not carry. Offered every
    // block, each would cost a build for the same answer, so the box sits out a number of passes.
    case Refused(ids) =>
      memory.refuse(ids)
      logger.warn(s"Dropped ${ids.size} upkeep boxes whose builds were refused; holding ${memory.refusedIds.size}")

    // The job says the box cannot pay for its successor. That does not pass with time, so the box
    // is held until a scan stops finding it, and each one is reported once.
    case Exhausted(ids) =>
      val fresh = memory.exhaust(ids)
      if (fresh.nonEmpty)
        logger.info(s"Upkeep holds back ${fresh.size} boxes that cannot pay for their successors until a " +
          s"scan stops finding them: ${fresh.toSeq.sorted.map(_.take(8)).mkString(", ")}")

    // ── the candidate path ──────────────────────────────────────────────────

    case PrepareBlockTxs(blockHeight, _) =>
      if (upkeepConfig.observing) observe(blockHeight) else startBuild(blockHeight, None)

    case RequestBlockTxs(blockHeight, _, refresh) =>
      val replyTo = sender()
      if (upkeepConfig.observing) {
        replyTo ! BlockTxsReady(blockHeight, Seq.empty[CandidateBundle])
        observe(blockHeight)
      } else preparation.preparedFor(blockHeight) match {
        // A refresh at the same height is answered from what was prepared: a successor is a fixed
        // function of its box and the height, so rebuilding would sign the same transactions and
        // ask the node the same checks again, inside the deadline every source shares.
        case Some(bundles) => replyTo ! BlockTxsReady(blockHeight, bundles)
        case None => startBuild(blockHeight, Some(replyTo))
      }

    // Nothing to reconcile: a successor holds no wallet input and was never broadcast, so a
    // dropped height leaves only the work itself, which is rebuilt for the next one.
    case CandidateTxsDropped(blockHeight) => preparation.drop(blockHeight)

    case Observed => observing = false
  }

  /** Each job's work for one build, read here because the build runs off the mailbox. */
  private def offered(): Seq[JobWork] = {
    val held = memory.heldIds
    jobs.flatMap { job =>
      val discovered = tracked.getOrElse(job.name, Set.empty[String])
      val offered = discovered -- held
      if (offered.isEmpty) None else Some(JobWork(job, discovered, offered))
    }
  }

  private def startBuild(blockHeight: Int, replyTo: Option[ActorRef]): Unit = {
    val work = offered()
    if (work.isEmpty) preparation.answerEmpty(blockHeight, replyTo)
    else preparation.start(blockHeight, replyTo) {
      val chosen = advance(work, blockHeight)
      (if (upkeepConfig.verifyWithNode) verified(chosen, blockHeight) else chosen).map(_.bundle)
    }
  }

  /**
   * Observe mode's task for one height: the build and a node check of each successor, started once
   * per height and never awaited, so a slow node costs no request anything. A height with nothing
   * to build is not counted, so the first request after a scan lands starts the task.
   */
  private def observe(blockHeight: Int): Unit =
    if (blockHeight > observedThrough && !observing) {
      val work = offered()
      if (work.nonEmpty) {
        observedThrough = blockHeight
        observing = true
        Try(Future(advance(work, blockHeight, remember = false).foreach(report(_, blockHeight)))(candidateWorker)
          .onComplete(_ => self ! Observed)(candidateWorker))
          .failed.foreach { ex =>
            observing = false
            logger.warn(s"Upkeep could not observe $blockHeight: ${ex.getMessage}")
          }
      }
    }

  /**
   * Read every offered box back and build a successor for each due one until the share is spent.
   *
   * The read, in chunks of [[ReadChunk]], is the node's mempool-adjusted view: an id that does not
   * come back is spent, or spent by a pending transaction, and is skipped. A failed read builds
   * nothing. A box is sized before it is signed, and building stops at a full share, after
   * [[MaxRefusedPerBuild]] refusals, or after [[MaxDeferredPerBuild]] successors were signed and
   * then found not to fit, so a block whose share is nearly spent does not sign every due box for
   * nothing. The order starts at `blockHeight` modulo the number of boxes, so a box deferred at the
   * head does not starve the ones behind it. With `remember` off (observe mode) refusals and
   * exhaustion are logged and not held, so every box stays watched.
   */
  private def advance(work: Seq[JobWork], blockHeight: Int, remember: Boolean = true): Vector[Upkeep.Prepared] =
    Try(nodeContext.getClient.execute { ctx =>
      val ids = work.flatMap(_.offered).distinct
      val live = ids.grouped(ReadChunk).flatMap { chunk =>
        nodeApi.boxesWithPoolByIds(chunk) match {
          case Success(boxes) => boxes
          case Failure(ex) =>
            throw new IllegalStateException(s"could not read ${chunk.size} upkeep boxes back: ${ex.getMessage}")
        }
      }.toVector
      val byId = live.map(box => box.boxId -> box).toMap
      val treeOf = byId.map { case (id, box) => id -> box.ergoTree }
      val gone = ids.toSet -- byId.keySet
      if (remember && gone.nonEmpty) self ! Spent(gone)

      // Read once for the block: every job builds against the same parameters, height and payTo.
      val bc = BuildContext(ctx, blockHeight,
        CandidateCapital.collectionContract(nodeContext.getNodeWallet, useTrueProp))
      var share = Upkeep.Share.of(limits.maxTxs, limits.budget)
      var refused = Set.empty[String]
      var exhausted = Set.empty[String]
      var due = 0
      var deferred = 0
      var signedForNothing = 0
      val ordered = work.flatMap(item => item.offered.toSeq.sorted.flatMap(byId.get).map(item -> _))
      val queue = rotated(ordered, blockHeight).iterator
      while (!share.full && refused.size < MaxRefusedPerBuild && signedForNothing < MaxDeferredPerBuild &&
        queue.hasNext) {
        val (item, box) = queue.next()
        attempt(bc, item, box, share, treeOf) match {
          case Attempt.Ready(successor, admitted) =>
            due += 1
            share = admitted
            logger.debug(s"Upkeep ${successor.label} fits at $blockHeight: " +
              s"${successor.tx.sizeBytes}B, ${successor.tx.cost} cost")
          case Attempt.NotDue => ()
          case Attempt.Deferred(reason, built) =>
            due += 1
            deferred += 1
            if (built) signedForNothing += 1
            logger.debug(s"Upkeep job ${item.job.name} leaves ${box.boxId} for a later block: $reason")
          case Attempt.Exhausted =>
            due += 1
            exhausted += box.boxId
          case Attempt.Refused(reason) =>
            logger.warn(s"Upkeep job ${item.job.name} cannot advance ${box.boxId} at $blockHeight: $reason")
            refused += box.boxId
        }
      }
      if (remember && refused.nonEmpty) self ! Refused(refused)
      if (remember && exhausted.nonEmpty) self ! Exhausted(exhausted)
      if (!remember && exhausted.nonEmpty)
        logger.info(s"Upkeep observe at $blockHeight: ${exhausted.size} box(es) cannot pay for a successor: " +
          exhausted.toSeq.sorted.map(_.take(8)).mkString(", "))
      if (refused.size >= MaxRefusedPerBuild && queue.hasNext)
        logger.warn(s"Upkeep stopped building at $blockHeight after $MaxRefusedPerBuild refusals; " +
          "the boxes not yet tried wait for a later block")
      if (signedForNothing >= MaxDeferredPerBuild && queue.hasNext)
        logger.info(s"Upkeep stopped building at $blockHeight after $MaxDeferredPerBuild successors did not " +
          "fit the share left; the boxes not yet tried wait for a later block")

      val chosen = share.chosen
      val admitted = chosen.map(_.label).mkString(", ")
      val changed = lastAdmitted.getAndSet(admitted) != admitted
      if (chosen.nonEmpty || deferred > 0)
        (if (changed) logger.info(_: String) else logger.debug(_: String))(
          s"Upkeep ${if (upkeepConfig.observing) "would offer" else "admits"} " +
          s"${chosen.size} of $due due successors at $blockHeight" +
          (if (deferred > 0) s", $deferred left for a later block" else "") +
          (if (share.full && queue.hasNext) ", share full before every box was tried" else "") +
          s": ${chosen.map(_.label).mkString(", ")}; share used " +
          s"txs=${chosen.size}/${limits.maxTxs}, bytes=${share.usedBytes(limits.budget)}/${limits.maxBytes}, " +
          s"cost=${share.usedCost(limits.budget)}/${limits.maxCost}")
      chosen
    }) match {
      case Success(chosen) => chosen
      case Failure(ex) =>
        logger.warn(s"Upkeep built nothing for $blockHeight: ${ex.getMessage}")
        Vector.empty[Upkeep.Prepared]
    }

  /**
   * The successors the node's check accepts. One it refuses, or cannot check, is left out of this
   * height, because a package the node rejects loses every inserted transaction with it. It is not
   * remembered: the check runs at the node's next height, which can already be past the height this
   * build stamped when blocks come fast, and the next height builds a different transaction anyway.
   * A box the job itself cannot advance is caught before this, by the build.
   */
  private def verified(chosen: Vector[Upkeep.Prepared], blockHeight: Int): Vector[Upkeep.Prepared] = {
    val (accepted, refused) = chosen.partition { successor =>
      Try(nodeApi.checkTransaction(successor.tx.json)).flatten match {
        case Success(_) => true
        case Failure(ex) =>
          logger.warn(s"Upkeep ${successor.label} at $blockHeight is refused by the node's check: ${ex.getMessage}")
          false
      }
    }
    if (chosen.nonEmpty) logger.info(s"Upkeep offers ${accepted.size} of ${chosen.size} successors at $blockHeight " +
      s"after the node's check: ${accepted.map(_.label).mkString(", ")}")
    accepted
  }

  /**
   * One box through its job: due, worth building against the share, and admitted, or why not. Every
   * call into the job is caught, so a throw on one box costs only that box. A box whose bytes do not
   * hash to the id the node gave is refused, since a transaction from it would spend nothing real.
   */
  private def attempt(bc: BuildContext, item: JobWork, box: NodeBox, share: Upkeep.Share,
                      treeOf: Map[String, String]): Attempt = {
    val job = item.job
    Try(box.toInputUTXO(bc.ctx)) match {
      case Failure(ex) => Attempt.Refused(s"the box cannot be read: ${ex.getMessage}")
      case Success(input) if input.id.toString != box.boxId =>
        Attempt.Refused(s"the box serializes to ${input.id.toString}, not the id the node reports")
      case Success(input) => Try(job.due(input, bc.height)) match {
        case Failure(ex) => Attempt.Refused(s"due() failed: ${ex.getMessage}")
        case Success(false) => Attempt.NotDue
        case Success(true) =>
          val (floorBytes, floorCost) = Upkeep.floor(input, bc.params)
          if (!share.affords(floorBytes, floorCost))
            Attempt.Deferred(s"at least ${floorBytes}B and $floorCost cost, over the " +
              s"${share.bytes}B and ${share.cost} cost left of the share")
          else build(bc, item, input, treeOf) match {
            case Left(outcome) => outcome
            case Right(successor) => share.admit(successor) match {
              case Some(admitted) => Attempt.Ready(successor, admitted)
              case None if successor.tx.inputIds.exists(share.claimed.contains) =>
                Attempt.Deferred("its successor spends a box one already admitted this block spends", built = true)
              case None => Attempt.Deferred(s"built at ${successor.tx.sizeBytes}B and ${successor.tx.cost} cost, " +
                s"over the ${share.bytes}B and ${share.cost} cost left of the share", built = true)
            }
          }
      }
    }
  }

  /**
   * The job's successor for one due box, sized, or why there is none: [[Attempt.Exhausted]] when
   * the job says the box cannot pay, [[Attempt.Refused]] when the build throws, spends a box the
   * job never discovered or this build did not read back, spends a box at one of this wallet's
   * keys (its P2PK trees and its miner-reward trees), or sends value anywhere but an input's own
   * script or this miner's collection contract (the fee proposition included). These hold for
   * every job, direct or script; [[ScriptJob]] adds the box as the only input and exact balance.
   */
  private def build(bc: BuildContext, item: JobWork, input: InputUTXO,
                    treeOf: Map[String, String]): Either[Attempt, Upkeep.Prepared] =
    Try(item.job.build(input, bc)).flatMap[Either[Attempt, Upkeep.Prepared]] {
      case None => Success(Left(Attempt.Exhausted))
      case Some(built) => Try {
        // Every input must be a box this build read back: discovered, live, and of a known script.
        // A discovered box held back from this build was not read back, so it does not pass either.
        val inputIds = transactions.engine.execution.RollupExecution.signedInputIds(built.tx)
        val foreign = Upkeep.undiscoveredInputs(built.tx, item.discovered) ++ (inputIds -- treeOf.keySet)
        val wallet = nodeContext.getNodeWallet
        val ours = Upkeep.walletInputs(built.tx, treeOf, wallet.signableTrees ++ wallet.rewardTrees.keySet)
        // Every output must sit at an input's own script or at this miner's collection contract, and
        // none at the fee proposition: value leaves the maintained boxes only as this miner's revenue.
        val allowedOut = inputIds.flatMap(treeOf.get) + bc.payTo.ergoTreeHex
        val astray = Upkeep.strayOutputs(built.tx, allowedOut)
        if (foreign.nonEmpty)
          Left(Attempt.Refused(s"its successor spends ${foreign.size} box(es) the job never discovered or this " +
            s"build did not read back: ${foreign.mkString(", ")}"))
        else if (ours.nonEmpty)
          Left(Attempt.Refused(s"its successor spends ${ours.size} box(es) at this wallet's keys: " +
            ours.mkString(", ")))
        else if (astray.nonEmpty)
          Left(Attempt.Refused(s"its successor has ${astray.size} output(s) at neither an input's script nor this " +
            s"miner's collection contract (a fee output counts): ${astray.mkString(", ")}"))
        else Right(Upkeep.Prepared(item.job.name, input.id.toString,
          Upkeep.member(built.tx, item.job.name, input, bc.params), built.capital))
      }
    } match {
      case Failure(ex) => Left(Attempt.Refused(s"build failed: ${ex.getMessage}"))
      case Success(outcome) => outcome
    }

  /**
   * Observe mode's report on one successor: the node's verdict, and what it would have cost and paid.
   * A refusal is logged and nothing more, as is every refusal in observe mode, because a box set
   * aside would stop being watched.
   */
  private def report(successor: Upkeep.Prepared, blockHeight: Int): Unit = {
    val offer = s"Upkeep observe at $blockHeight: ${successor.label} as tx ${successor.tx.id}, " +
      s"${successor.tx.sizeBytes}B, ${successor.tx.cost} cost, paying " +
      s"${successor.capital.map(_.value).sum} nanoERG to this miner"
    Try(nodeApi.checkTransaction(successor.tx.json)).flatten match {
      case Success(_) => logger.info(s"$offer: the node's check accepts it")
      case Failure(ex) => logger.warn(s"$offer: the node's check refuses it: ${ex.getMessage}")
    }
  }

  /**
   * One pass over every job. A job that throws is logged and left out of the pass, so one
   * protocol's node trouble cannot stop the others; the actor keeps what that job last found.
   * Boxes the memory holds as unable to pay are left out of the capped part of what is kept, so
   * that they do not fill the cap and keep payable boxes behind them out; they are still reported
   * as found, so their holds survive the pass. A refused box stays tracked: it sits out a bounded
   * number of passes and is then tried again from where it is.
   */
  private def scan(): Map[String, Pass] =
    nodeContext.getClient.execute { ctx =>
      val height = ctx.getHeight
      val held = memory.exhaustedIds
      jobs.flatMap { job =>
        Try(job.discover(ctx, nodeApi, height)) match {
          // The ids the operator listed are kept whatever their number; the cap cuts only the
          // rest, which a job returns in its own priority order.
          case Success(found) =>
            val (listed, rest) = found.distinct.partition(job.configured.contains)
            val offerable = rest.filterNot(held.contains)
            if (offerable.size > upkeepConfig.maxBoxesPerJob)
              logger.warn(s"Upkeep job ${job.name} found ${offerable.size} boxes beyond its configured ones; " +
                s"keeping the first ${upkeepConfig.maxBoxesPerJob} (stratum.candidate.sources.upkeep.maxBoxesPerJob)")
            Some(job.name -> Pass((listed ++ offerable.take(upkeepConfig.maxBoxesPerJob)).toSet, found.toSet))
          case Failure(ex) =>
            logger.warn(s"Upkeep job ${job.name} failed to discover its boxes, keeping the last pass: ${ex.getMessage}")
            None
        }
      }.toMap
    }
}

object UpkeepSource {

  /**
   * Boxes refused in one build before the build stops. A job that throws after real work, such as
   * signing, would otherwise cost a build one signing for every box it holds, hundreds of them, on
   * every block until the refusals land; the boxes not tried wait for the next block.
   */
  final val MaxRefusedPerBuild = 16

  /**
   * Successors one build signs and then cannot admit (the share left is too small, or the box is
   * one an admitted successor spends) before it stops. Sizing before signing catches most of these;
   * this bounds the rest, since a job may hold thousands of due boxes.
   */
  final val MaxDeferredPerBuild = 16

  /** Ids per read-back call, so a job holding thousands of boxes never makes one huge request. */
  final val ReadChunk = 256

  /** When the first scan runs after start: soon, so a fresh client has boxes before its first block. */
  final val FirstScanDelay: FiniteDuration = 2.seconds

  /** Whether a source should exist at all. One that would have nothing to do is never created. */
  def runs(limits: CandidateSourceConfig, jobs: Seq[UpkeepJob]): Boolean = limits.enabled && jobs.nonEmpty

  /** `xs` started at `height` modulo its length, wrapping around. */
  private[upkeep] def rotated[A](xs: Seq[A], height: Int): Seq[A] =
    if (xs.isEmpty) xs
    else {
      val start = Math.floorMod(height, xs.size)
      xs.drop(start) ++ xs.take(start)
    }

  /**
   * Boxes held back from builds, built once where the source is wired so a restarted actor, whose
   * fields Akka empties, keeps them. Only the actor's thread writes it, and every write is a whole
   * replacement; the scan reads the exhausted set off the actor, which the atomics make safe.
   *
   * A refused box, whose build failed, may have met a passing fault, so it is offered again after
   * `retryAfterScans` passes. An exhausted box, which cannot pay, will not pay later either, so it is
   * held until a scan stops finding it. Either is dropped once no pass finds the box.
   *
   * @param retryAfterScans passes a refused box sits out, counted from the first that lands after
   *                        the refusal
   */
  final class Memory(val retryAfterScans: Int) {
    require(retryAfterScans > 0, s"retryAfterScans must be positive, not $retryAfterScans")

    private val refused = new AtomicReference[Map[String, Int]](Map.empty[String, Int])
    private val exhausted = new AtomicReference[Set[String]](Set.empty[String])

    def refusedIds: Set[String] = refused.get().keySet

    def exhaustedIds: Set[String] = exhausted.get()

    /** Every box no build is offered. */
    def heldIds: Set[String] = refusedIds ++ exhaustedIds

    /** Passes `id` has left to sit out, or nothing when it is not refused. */
    def passesLeft(id: String): Option[Int] = refused.get().get(id)

    def refuse(ids: Set[String]): Unit =
      refused.set(refused.get() ++ ids.map(_ -> retryAfterScans))

    /** Holds `ids` as unable to pay, and returns the ones not already held, to be reported. */
    def exhaust(ids: Set[String]): Set[String] = {
      val before = exhausted.get()
      exhausted.set(before ++ ids)
      refused.set(refused.get() -- ids)
      ids -- before
    }

    /** Boxes a build found spent: nothing to retry. */
    def forget(ids: Set[String]): Unit = {
      refused.set(refused.get() -- ids)
      exhausted.set(exhausted.get() -- ids)
    }

    /**
     * One pass landed, finding `known`. Drops every hold outside it, counts the pass against the
     * refusals left, and returns the refused ids that have sat out their passes and are offered again.
     */
    def passed(known: Set[String]): Set[String] = {
      exhausted.set(exhausted.get().intersect(known))
      val kept = refused.get().filter { case (id, _) => known.contains(id) }
      val (expired, remaining) = kept.partition { case (_, left) => left <= 1 }
      refused.set(remaining.map { case (id, left) => id -> (left - 1) })
      expired.keySet
    }
  }

  /** Scheduler tick: ask every job what it maintains. */
  private[upkeep] case object ScanTick

  /** What one pass found per job; a job whose discovery failed is absent. */
  private[upkeep] final case class Scanned(result: Try[Map[String, Pass]])

  /** Boxes a build found already spent, so they stop being offered. */
  private[upkeep] final case class Spent(ids: Set[String])

  /** An observe task has finished, so the next height may start one. */
  private[upkeep] case object Observed

  /** One job's pass: the ids kept under the cap, and every id the pass found, held ones included. */
  private[upkeep] final case class Pass(kept: Set[String], found: Set[String])

  /** Boxes whose builds were refused, so they sit out passes before they are offered again. */
  private[upkeep] final case class Refused(ids: Set[String])

  /** Boxes their job says cannot pay, so they are not offered until a scan stops finding them. */
  private[upkeep] final case class Exhausted(ids: Set[String])

  /**
   * One job's share of a build: what it discovered, which the undiscovered-input check is made
   * against, and what is still offered, which is that less what the memory holds.
   */
  private final case class JobWork(job: UpkeepJob, discovered: Set[String], offered: Set[String])

  private sealed trait Attempt

  private object Attempt {
    /** Built, measured and admitted; `share` is what is left after it. */
    final case class Ready(successor: Upkeep.Prepared, share: Upkeep.Share) extends Attempt
    case object NotDue extends Attempt
    /**
     * Due, but not this block: nothing is wrong with the box, the share has no room for it, or an
     * admitted successor already spends its box. `built` when the successor was signed before that
     * was found, which the build counts.
     */
    final case class Deferred(reason: String, built: Boolean = false) extends Attempt
    /** Due, and the job says the box cannot pay for its successor. */
    case object Exhausted extends Attempt
    final case class Refused(reason: String) extends Attempt
  }
}
