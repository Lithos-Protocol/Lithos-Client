package transactions.batching.forwards

import akka.actor.ActorRef
import configs.{ForwardsBatchingConfig, NodeContext}
import forwards.contracts.DFContracts
import forwards.{DFHelpers, DFMath}
import node.model.{MempoolOptions, NodeBox, Paging, SortDirection}
import org.ergoplatform.appkit.{BlockchainContext, NetworkType, Parameters}
import play.api.Configuration
import state.synchronization.CompleteMempool
import transactions.batching.Batcher.{BroadcastResult, Tracked}
import transactions.batching.{Batcher, BatchingMempool}
import transactions.candidate.BlockTxMessages.Supersede
import transactions.candidate.CandidateBundle
import transactions.engine.EngineBroadcast
import transactions.engine.wallet.{EngineFunding, FundingAllocation}
import work.lithos.mutations.{InputUTXO, UTXO}

import javax.inject.{Inject, Named}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

/**
 * Keeps the difficulty forward running: records Ergo's difficulty into the accumulator once per epoch and
 * settles positions whose window it holds, taking the LIT rewards each pays its builder.
 *
 * Every spend goes through the one accumulator box, so a block's or a broadcast's work is one chain of
 * transactions, each spending the accumulator the one before left. Each epoch is split at
 * `broadcastAfterBlocks`: before it, this miner's own block carries the chain fee-less; from it on, the chain is
 * broadcast onto the accumulator's mempool tip with fees, and the block offers none. Both building on the
 * same box at once would have the block displace the broadcast from this node's mempool.
 *
 * Neither path displaces a live transaction. The block leaves the accumulator alone while anything in the
 * mempool spends it, unless that spend records an epoch already over, which no block can include.
 */
class ForwardsBatcher(nodeContext: NodeContext,
                      settings: ForwardsBatchingConfig,
                      servesCandidates: Boolean,
                      engine: ActorRef,
                      deployment: Option[DFContracts],
                      fundingTimeout: FiniteDuration = ForwardsBatcher.FundingTimeout,
                      buildBudgetMs: Long = Batcher.DefaultBuildBudgetMs)
  extends Batcher(nodeContext, settings.batching, servesCandidates, engine, configs.Contexts.ForwardsBatchingIo,
    buildBudgetMs) {

  import ForwardsBatcher._

  /** Built by `Module`, reading every setting from the application configuration. */
  @Inject()
  def this(nodeContext: NodeContext, config: Configuration, @Named("transaction-engine") engine: ActorRef) =
    this(nodeContext, ForwardsBatchingConfig(config), Batcher.servesCandidates(config, ForwardsBatchingConfig.Name),
      engine, ForwardsBatcher.deployment(nodeContext.getNetwork), EngineFunding.askTimeout(config),
      Batcher.buildBudgetMs(config))

  override protected val label: String = "Difficulty forward"

  private val batching = settings.batching

  /** What the last scan read, for deciding on the mailbox whether a broadcast pass has work. */
  @volatile private var scanned: Option[Scanned] = None

  /** Whether the last scan found the accumulator, so its absence is logged once rather than every scan. */
  @volatile private var accumulatorFound: Option[Boolean] = None

  /**
   * The recording this miner's blocks carry on their own, with the one wallet box it holds. Kept across
   * heights while the epoch is unrecorded, so at most one box is ever withheld from the wallet's other uses.
   * Written by builds on the worker, so only under `heldLock`, and never held across a wallet call.
   */
  private var held: Option[HeldRecording] = None
  private val heldLock = new Object

  override def preStart(): Unit =
    if (deployment.isEmpty)
      logger.info(s"ForwardsBatcher is idle: no difficulty accumulator is deployed on ${nodeContext.getNetwork}")
    else super.preStart()

  override def postStop(): Unit = {
    endLease()
    super.postStop()
  }

  /**
   * Gives the held box back to the wallet as uncertain, since a block may already carry its recording: a
   * wallet refresh decides whether it was spent. Nothing broadcasts it, so nothing else would end the hold.
   */
  private def endLease(): Unit =
    heldLock.synchronized { val ended = held; held = None; ended }.foreach(_.allocation.uncertain())

  /** Whether `tip`, the block a spend is built after, is at or past `broadcastAfterBlocks` into its epoch. */
  private def broadcastWindow(tip: Int): Boolean = blocksIntoEpoch(tip) >= settings.broadcastAfterBlocks

  override protected def broadcastDue(tracked: Tracked): Boolean =
    scanned.exists(s => broadcastWindow(s.tip) && (tracked.nonEmpty || s.lastEpoch < DFMath.epochOf(s.tip)))

  /**
   * Reads the accumulator and every position, tracking those whose window ends by the tip's epoch, oldest
   * window first. Positions are paged and only the ids of due ones kept.
   */
  override protected def discover(): Tracked = deployment.fold(Map.empty: Tracked) { contracts =>
    val tip = nodeApi.info().get.fullHeight.getOrElse(throw new IllegalStateException("the node did not report a height"))
    val acc = indexedAccumulator(contracts)
    if (!accumulatorFound.contains(acc.isDefined))
      if (acc.isDefined) logger.info(s"Difficulty accumulator ${acc.get.boxId} found, last recorded epoch ${acc.get.lastEpoch}")
      else logger.warn(s"No confirmed box carrying ${contracts.accNFT} reads as the difficulty accumulator; " +
        "nothing is recorded or settled until one does")
    accumulatorFound = Some(acc.isDefined)
    scanned = acc.map(a => Scanned(tip, a.lastEpoch))

    val newest = DFMath.epochOf(tip)
    val skipped = skippedOrders()
    var offset = 0
    var exhausted = false
    var due = Vector.empty[(Int, String)]
    while (!exhausted && offset < Batcher.MaxScannedPerTemplate) {
      val page = nodeApi.unspentBoxesByErgoTree(contracts.position.ergoTreeHex, Paging(offset, Batcher.PageSize),
        SortDirection.Asc, MempoolOptions.ConfirmedOnly).get
      due ++= page.flatMap(indexed => DFBoxes.position(indexed.box, contracts))
        .filter(p => p.windowEnd <= newest && !skipped.contains(p.boxId))
        .map(p => p.windowEnd -> p.boxId)
      exhausted = page.size < Batcher.PageSize
      offset += page.size
    }
    if (!exhausted)
      logger.warn(s"Difficulty forward scan stopped after ${Batcher.MaxScannedPerTemplate} positions; " +
        "newer positions were not read this pass")
    due.sorted.take(batching.maxTrackedOrders).map(_._2 -> contracts.accNFT.toString).toMap
  }

  /**
   * The chain for this miner's block: due settlements on the confirmed accumulator, carrying the epoch's
   * recording, or the recording on its own when nothing carries it. Offers nothing in the broadcast window,
   * when nothing can be due, or while a live mempool transaction spends the accumulator.
   */
  override protected def executions(orders: Tracked, blockHeight: Int, slots: Int,
                                    deadline: Deadline): Seq[CandidateBundle] = deployment match {
    case None => Seq.empty
    case Some(contracts) =>
      val tip = blockHeight - 1
      val recordable = blocksIntoEpoch(tip) > 0 && scanned.exists(_.lastEpoch < DFMath.epochOf(tip))
      if ((batching.broadcast && broadcastWindow(tip)) || (orders.isEmpty && !recordable)) {
        endLease()
        Seq.empty
      } else {
        val observed = observation(Some(deadline))
        if (!observed.fresh) {
          logger.info(s"No fresh mempool observation for block $blockHeight, offering no difficulty forward work: " +
            observed.failure.getOrElse("observation too old"))
          Seq.empty
        } else nodeContext.getClient.execute(ctx =>
          candidateChain(ctx, contracts, orders, blockHeight, slots, deadline, observed.snapshot.get))
      }
  }

  /** [[executions]] once the mempool is observed. A build that offers no recording on its own ends the lease. */
  private def candidateChain(ctx: BlockchainContext, contracts: DFContracts, orders: Tracked, blockHeight: Int,
                             slots: Int, deadline: Deadline, snapshot: CompleteMempool.Snapshot): Seq[CandidateBundle] = {
    val tip = blockHeight - 1
    val spenders = BatchingMempool.spenders(snapshot)
    val (bundle, leaseUsed) = currentAccumulator(contracts, ctx).fold((Option.empty[CandidateBundle], false)) {
      case (acc, box) =>
        // A recording is only valid in the block after the tip it was built at
        val recording = ForwardsTransactions.recordingAt(ctx).filter(r => ctx.getHeight == tip && r.epoch > acc.lastEpoch)
        val claims = spenders.getOrElse(acc.boxId, Vector.empty)
        val dead = claims.nonEmpty && claims.forall(tx => staleRecording(tx, acc, contracts, DFMath.epochOf(tip)))
        if (claims.nonEmpty && !dead) {
          logger.debug(s"The difficulty accumulator is spent in the mempool; block $blockHeight leaves it to that spend")
          (None, false)
        } else {
          val positions = livePositions(contracts, ctx, orders).filterNot { case (p, _) => snapshot.spent.contains(p.boxId) }
          within(deadline, "reading the accumulator and positions")
          val opening = DFAccState(acc, box)
          val run = ForwardsTransactions.settleAll(ctx, nodeContext.getNodeWallet, contracts, opening, positions,
            recording, 0L, slots - recording.size, deadline)
          settle(run, blockHeight)
          val tail = run.last.map(_.next).getOrElse(opening)
          val alone = run.recordingLeft.filter(_ => run.steps.size < slots && !deadline.isOverdue())
            .flatMap(r => candidateRecording(ctx, contracts, tail, r, blockHeight, deadline))
          val steps = run.steps ++ alone
          logger.debug(s"Block $blockHeight carries ${run.steps.size} difficulty forward settlement(s)" +
            steps.find(_.recorded).fold("")(s => s" and records epoch ${s.next.lastEpoch}"))
          // A stale recording's descendants die with it, so naming the recording is enough
          val offered = if (steps.isEmpty) None else Some(CandidateBundle(steps.map(_.candidateTx),
            if (dead) Seq(Supersede(claims.map(_.id).toSet)) else Seq.empty))
          (offered, alone.isDefined)
        }
    }
    if (!leaseUsed) endLease()
    bundle.toSeq
  }

  /**
   * The recording on its own, funded by one confirmed wallet box, since a fee-less transaction has no fee to
   * fold change into and the accumulator's value is fixed. The box comes back whole as the reward's change box.
   *
   * The box already held is reused: as is while the recording still spends the same accumulator, rebuilt when
   * a settlement moved it. None, said loudly, when no box can be held.
   */
  private def candidateRecording(ctx: BlockchainContext, contracts: DFContracts, acc: DFAccState,
                                 recording: DFRecording, blockHeight: Int, deadline: Deadline): Option[DFStep] = {
    val wallet = nodeContext.getNodeWallet
    heldLock.synchronized(held) match {
      case Some(lease) if lease.step.spent.box.id.toString == acc.box.id.toString &&
        lease.step.next.lastEpoch == recording.epoch => Some(lease.step)

      case Some(lease) =>
        Try(ForwardsTransactions.record(ctx, wallet, contracts, acc, recording, lease.allocation.inputs, 0L)) match {
          case Success(step) =>
            heldLock.synchronized(if (held.contains(lease)) held = Some(lease.copy(step = step)))
            Some(step)
          case Failure(ex) =>
            logger.warn(s"Could not rebuild the recording of epoch ${recording.epoch} for block $blockHeight on " +
              s"the wallet box it holds, giving the box back: ${ex.getMessage}")
            endLease()
            None
        }

      case None =>
        val wait = fundingTimeout min deadline.timeLeft
        if (wait <= Duration.Zero) None
        else Try(EngineFunding(engine, wait, worker).reserveCoveringConfirmed(UTXO.MIN_CHANGE)) match {
          case Failure(ex) =>
            logger.warn(s"No confirmed wallet box could fund recording epoch ${recording.epoch} in block " +
              s"$blockHeight; it is left to a later block or a broadcast: ${ex.getMessage}")
            None
          case Success(allocation) =>
            Try(ForwardsTransactions.record(ctx, wallet, contracts, acc, recording, allocation.inputs, 0L)) match {
              case Failure(ex) =>
                allocation.release()
                logger.warn(s"Could not build the recording of epoch ${recording.epoch} for block $blockHeight: " +
                  ex.getMessage)
                None
              case Success(step) => Try(allocation.holdForCandidate()) match {
                case Success(_) =>
                  // A build running alongside may have held one first; this one was offered too, so it ends uncertain
                  val kept = heldLock.synchronized {
                    if (held.isEmpty) held = Some(HeldRecording(allocation, step))
                    held.exists(_.allocation eq allocation)
                  }
                  if (!kept) allocation.uncertain()
                  Some(step)
                case Failure(ex) =>
                  logger.warn(s"The wallet box funding the recording for block $blockHeight could not be held: " +
                    ex.getMessage)
                  None
              }
            }
        }
    }
  }

  /**
   * In the broadcast window, settles due positions on the accumulator's mempool tip and records the epoch if
   * nothing has, paying fees: a settlement from the position's own ERG, a recording on its own from a wallet
   * box. Does nothing while the tip is contested or carries a recording of an epoch already over, which the
   * node will drop: chaining onto it would only build more of the same.
   */
  override protected def broadcastPass(orders: Tracked, refused: Map[String, String]): BroadcastResult =
    deployment.fold(BroadcastResult(Map.empty)) { contracts =>
      nodeContext.getClient.execute { ctx =>
        val tip = ctx.getHeight
        val epoch = DFMath.epochOf(tip)
        // The scan's tip can be a block behind, so the window is checked again before the mempool is walked
        lazy val observed = observation()
        lazy val snapshot = {
          require(observed.fresh, s"no fresh mempool observation: ${observed.failure.getOrElse("too old")}")
          observed.snapshot.get
        }
        lazy val spenders = BatchingMempool.spenders(snapshot)
        val tipAccumulator = if (!broadcastWindow(tip)) None else currentAccumulator(contracts, ctx).flatMap {
          case (confirmed, _) =>
            BatchingMempool.poolTip(confirmed.box, contracts.accNFT.toString, spenders) match {
              case None =>
                logger.warn("The difficulty accumulator is contested in the mempool; no broadcast this pass")
                None
              case Some(tipBox) =>
                val read = for {
                  acc <- DFBoxes.accumulator(tipBox, contracts)
                  box <- DFBoxes.input(tipBox, ctx)
                } yield DFAccState(acc, box)
                read.filter { acc =>
                  val stale = acc.lastEpoch != confirmed.lastEpoch && acc.lastEpoch != epoch
                  if (stale) logger.warn(s"The difficulty accumulator's mempool tip records epoch ${acc.lastEpoch}, " +
                    s"which is over; waiting for the node to drop it")
                  !stale
                }
            }
        }
        tipAccumulator.fold(BroadcastResult(Map.empty)) { acc =>
          val positions = livePositions(contracts, ctx, orders).filterNot { case (p, _) =>
            snapshot.spent.contains(p.boxId) || refused.get(p.boxId).contains(acc.box.id.toString)
          }
          val recording = ForwardsTransactions.recordingAt(ctx).filter(_.epoch > acc.lastEpoch)
          val limit = batching.maxOrdersPerBlock
          val run = ForwardsTransactions.settleAll(ctx, nodeContext.getNodeWallet, contracts, acc, positions, recording,
            Parameters.MinFee, limit - recording.size, BroadcastBudget.fromNow)
          settle(run, tip)
          val tail = run.last.map(_.next).getOrElse(acc)
          val alone = run.recordingLeft.filter(_ => run.steps.size < limit)
            .flatMap(r => fundedRecording(ctx, contracts, tail, r))
          BroadcastResult(send(run.steps.map(_ -> None) ++ alone.map { case (step, a) => step -> Some(a) }, observed))
        }
      }
    }

  /** The recording on its own for a broadcast, with the wallet allocation paying its fee and change box. */
  private def fundedRecording(ctx: BlockchainContext, contracts: DFContracts, acc: DFAccState,
                              recording: DFRecording): Option[(DFStep, FundingAllocation)] =
    Try(EngineFunding(engine, fundingTimeout, worker).reserve(Parameters.MinFee + UTXO.MIN_CHANGE, Seq.empty)) match {
      case Failure(ex) =>
        logger.warn(s"No wallet funds for the fee recording epoch ${recording.epoch}; not broadcast: ${ex.getMessage}")
        None
      case Success(allocation) =>
        Try(ForwardsTransactions.record(ctx, nodeContext.getNodeWallet, contracts, acc, recording, allocation.inputs,
          Parameters.MinFee)) match {
          case Success(step) => Some(step -> allocation)
          case Failure(ex) =>
            allocation.release()
            logger.warn(s"Could not build the recording of epoch ${recording.epoch} for broadcast: ${ex.getMessage}")
            None
        }
    }

  /**
   * Sends a chain in order, stopping at the first transaction not accepted, since every later one spends its
   * output. Returns each refused settlement's position with the accumulator box it spent.
   */
  private def send(chain: Vector[(DFStep, Option[FundingAllocation])],
                   observed: CompleteMempool.Observation): Map[String, String] = {
    val sender = broadcaster()
    var refused = Map.empty[String, String]
    var going = true
    chain.foreach { case (step, allocation) =>
      val what = step.position.fold(s"recording of epoch ${step.next.lastEpoch}")(p => s"settlement of ${p.boxId}")
      if (!going) allocation.foreach(_.release())
      else {
        val funding = allocation.map(a => EngineBroadcast.Funding(a.reservationId, a.inputs.map(_.id.toString).toSet))
        Try(sender.sendOwned(step.signed, funding.toSeq, ForwardsTransactions.Kind, () => alive.get(),
          observed = Some(observed))) match {
          case Success(sent) if sent.outcome == EngineBroadcast.Accepted =>
            logger.info(s"Broadcast difficulty forward $what as ${sent.txId}")
          case Success(sent) =>
            going = false
            logger.warn(s"Difficulty forward $what (${sent.txId}) was ${sent.outcome}: " +
              s"${sent.reason.getOrElse("no reason given")}; the rest of its chain is not sent")
            if (sent.outcome == EngineBroadcast.Rejected)
              step.position.foreach(p => refused += p.boxId -> step.spent.box.id.toString)
          case Failure(ex) =>
            going = false
            logger.warn(s"Difficulty forward $what was not sent: ${ex.getMessage}")
        }
      }
    }
    refused
  }

  /** Leaves this run's unbuildable positions out from now on, and says when it stopped with positions left. */
  private def settle(run: DFRun, height: Int): Unit = {
    run.unbuildable.foreach(skipOrder)
    if (run.cutShort)
      logger.warn(s"Difficulty forward run at $height stopped with due positions left after " +
        s"${run.steps.size} settlement(s): it reached its slots or its budget")
  }

  /**
   * Whether `tx`, a mempool spend of the confirmed accumulator `acc`, records an epoch other than `epoch`, the
   * only one the next block accepts. Such a spend and everything chained on it can never be mined.
   */
  private def staleRecording(tx: CompleteMempool.MempoolTx, acc: DFAccumulator, contracts: DFContracts,
                             epoch: Int): Boolean =
    tx.body.outputs.find(_.assets.headOption.exists(_.tokenId == contracts.accNFT.toString))
      .flatMap(DFBoxes.accumulator(_, contracts))
      .exists(next => next.lastEpoch != acc.lastEpoch && next.lastEpoch != epoch)

  /** The one confirmed box the index lists with the accumulator NFT, read. */
  private def indexedAccumulator(contracts: DFContracts): Option[DFAccumulator] = {
    val listed = nodeApi.unspentBoxesByTokenId(contracts.accNFT.toString, Paging(0, 2), SortDirection.Desc,
      MempoolOptions.ConfirmedOnly).get
    if (listed.size == 1) DFBoxes.accumulator(listed.head.box, contracts) else None
  }

  /** The indexed accumulator, rechecked against the UTXO set, since the index can list a spent box. */
  private def currentAccumulator(contracts: DFContracts, ctx: BlockchainContext): Option[(DFAccumulator, InputUTXO)] =
    indexedAccumulator(contracts).flatMap { listed =>
      nodeApi.boxesWithPoolByIds(Seq(listed.boxId)).get.find(_.boxId == listed.boxId)
        .flatMap(box => DFBoxes.accumulator(box, contracts).flatMap(acc => DFBoxes.input(box, ctx).map(acc -> _)))
    }

  /** Tracked positions reloaded and read, reporting ids the node no longer holds as spent. */
  private def livePositions(contracts: DFContracts, ctx: BlockchainContext,
                            orders: Tracked): Seq[(DFPosition, InputUTXO)] = {
    val live: Seq[NodeBox] = if (orders.isEmpty) Seq.empty else nodeApi.boxesWithPoolByIds(orders.keys.toSeq).get
    forget(orders.keySet -- live.map(_.boxId))
    live.flatMap(box => DFBoxes.position(box, contracts).flatMap(p => DFBoxes.input(box, ctx).map(p -> _)))
  }
}

object ForwardsBatcher {

  /** The batching block and candidate source key this batcher reads. */
  final val Name: String = ForwardsBatchingConfig.Name

  /** Most a broadcast pass waits on the wallet for a recording's funding. */
  final val FundingTimeout: FiniteDuration = 10.seconds

  /** A broadcast pass is not bound by the stratum's deadline; this only ends a run that would not. */
  private final val BroadcastBudget = 60.seconds

  /** What a scan read: the tip it ran at, and the confirmed accumulator's newest recorded epoch. */
  private final case class Scanned(tip: Int, lastEpoch: Int)

  /** A recording offered to this miner's blocks, and the wallet box held to fund it. */
  private final case class HeldRecording(allocation: FundingAllocation, step: DFStep)

  /** Blocks of the tip's epoch before the tip: 0 at an epoch's first block. */
  def blocksIntoEpoch(tip: Int): Int = (tip - 1) % DFHelpers.EPOCH_LENGTH

  /** This network's contracts, or None while `DFHelpers` holds no accumulator NFT for it. */
  def deployment(network: NetworkType): Option[DFContracts] =
    DFHelpers.getAccNFT(network).map(nft => DFContracts(nft, DFHelpers.getLitId(network)))
}
