package transactions.emissions

import akka.actor.{Actor, ActorRef, Cancellable}
import configs.{EmissionConfig, NodeContext}
import node.NodeApi
import node.rest.NodeCodecs
import org.ergoplatform.appkit.{ErgoClient, SignedTransaction}
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import play.api.libs.concurrent.InjectedActorSupport
import transactions.candidate.BlockTxMessages
import transactions.candidate.BlockTxMessages.{BlockTxsReady, CandidateTx, CandidateTxsDropped, PrepareBlockTxs, RequestBlockTxs}
import transactions.emissions.EmissionsCore._
import transactions.engine.wallet.{EngineFunding, FundingAllocation}
import work.lithos.mutations.InputUTXO

import javax.inject.{Inject, Named}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

/**
 * Owns the collateral queue: joining it with this miner's own funds, and driving it forward with
 * Activate and Clear. Separate from the rollup actors because it is keyed by queue position rather
 * than rollup block id and must run whether or not this miner has any rollups.
 *
 * Two timers, both broadcasting to the mempool: `collateralizeInterval` tops this miner's own
 * positions back up with Joins, `queueInterval` walks the head, Clearing duplicate-key boxes that
 * would stall it and Activating the rest. Broadcasting is what actually moves the queue;
 * [[RequestBlockTxs]] only guarantees the same work lands in a block this miner finds.
 */
trait EmissionsCore extends Actor with InjectedActorSupport {
  /** Config, node and wallet come from the engine this trait is mixed into. */
  protected def config: Configuration
  protected def emissionNodeContext: NodeContext
  protected def emissionWalletManager: ActorRef

  private val logger: Logger = LoggerFactory.getLogger("EmissionsCore")

  private val nodeConfig: NodeContext = emissionNodeContext
  private val client: ErgoClient = nodeConfig.getClient
  protected def emissionNodeApi: NodeApi = nodeConfig.getNodeApi
  private val emissionConfig: EmissionConfig = EmissionConfig(config)

  private implicit val emissionEc: ExecutionContext = context.dispatcher
  private val emissionWorker = context.system.dispatchers.lookup("lithos-contexts.engine-io-dispatcher")
  private val emissionPreparation = new transactions.candidate.CandidatePreparation(
    context.system.dispatchers.lookup("lithos-contexts.engine-candidate-dispatcher"), self)
  private val emissionAlive = new java.util.concurrent.atomic.AtomicBoolean(true)
  private lazy val collateral = new transactions.engine.execution.CollateralExecution(
    emissionNodeContext, config, context.system, emissionWalletManager, () => emissionAlive.get()) {
    override protected def executionNode: NodeApi = emissionNodeApi
  }

  private val walletSelector = EngineFunding(emissionWalletManager, EngineFunding.AskTimeout, context.dispatcher)
  private lazy val txs = new EmissionTransactions(
    nodeConfig.getNodeWallet, emissionNodeApi, emissionConfig, walletSelector, () => emissionAlive.get(),
    Some(new transactions.engine.EngineJoinGuard(emissionWalletManager, emissionNodeApi)))

  private var collateralizeTicker: Option[Cancellable] = None
  private var queueTicker: Option[Cancellable] = None

  /**
   * One lock for BOTH timers, not one each. Join, Activate and Clear all spend the emission box and
   * all chain off `emissionTip`, so two passes running at once build on the same tip and one of them
   * is a guaranteed double spend — taking every chained transaction behind it down with it. The
   * intervals make this collide on schedule: 120s and 600s coincide every 10 minutes.
   */
  private var spending: Boolean = false
  protected def executeEmission(intent: transactions.engine.EngineIntent): Future[Any] = {
    require(!spending, "an emission attempt is already running")
    spending = true
    Future {
      require(emissionAlive.get(), "emission attempt was superseded")
      intent match {
        case transactions.engine.EngineIntent.Join(request, _) => collateral.join(request)
        case transactions.engine.EngineIntent.Collateralize =>
          client.execute(ctx => txs.selfCollateralize(ctx,
            new transactions.engine.EngineBroadcast(emissionWalletManager, emissionNodeApi)))
        case transactions.engine.EngineIntent.Queue => client.execute { ctx =>
          val (_, spends) = txs.buildQueueSpends(ctx, ctx.getHeight + 1, funded = true, emissionConfig.maxQueueSpends)
          val result = sendQueue(ctx, spends)
          result.collectFirst { case (_, Failure(ex)) => ex }.foreach(throw _)
          result
        }
        case _ => throw new IllegalArgumentException("unsupported emission intent")
      }
    }(emissionWorker)
  }
  protected def finishEmission(): Unit = { spending = false }

  /** Build the fee-less copies for one height, unless this source has nothing to offer. */
  private def startCandidateBuild(blockHeight: Int, limit: Int, replyTo: Option[ActorRef]): Unit =
    if (!emissionConfig.enabled || limit <= 0) emissionPreparation.answerEmpty(blockHeight, replyTo)
    else emissionPreparation.start(blockHeight, replyTo)(candidateBundles(blockHeight, limit))

  // ─── lifecycle ────────────────────────────────────────────────────────────

  abstract override def preStart(): Unit = {
    super.preStart()
    if (!emissionConfig.enabled) {
      logger.info("EmissionsCore disabled via emission.enabled")
    } else {
      logger.info(s"EmissionsCore starting - queue every ${emissionConfig.queueInterval}ms " +
        s"(max ${emissionConfig.maxQueueSpends} spends), autoCollateralize=" +
        s"${emissionConfig.autoCollateralize} every ${emissionConfig.collateralizeInterval}ms")

      // Both first passes wait rather than firing at startup: the node wallet has to be unlocked
      // and the client synced before a lender key can be derived, and a failed first pass is a
      // wasted round.
      queueTicker = Some(context.system.scheduler.scheduleWithFixedDelay(
        firstDelay(emissionConfig.queueInterval), emissionConfig.queueInterval.milliseconds,
        self, DriveQueue)(context.dispatcher))

      // Offset from the queue timer so the two first passes do not land together — `firstDelay`
      // would otherwise put both at 60s, and one would immediately skip.
      if (emissionConfig.autoCollateralize)
        collateralizeTicker = Some(context.system.scheduler.scheduleWithFixedDelay(
          firstDelay(emissionConfig.collateralizeInterval) + FirstPassStaggerMs.milliseconds,
          emissionConfig.collateralizeInterval.milliseconds,
          self, Collateralize)(context.dispatcher))
    }
  }

  abstract override def postStop(): Unit = {
    emissionAlive.set(false)
    collateralizeTicker.foreach(_.cancel())
    queueTicker.foreach(_.cancel())
    super.postStop()
  }

  // ─── receive ──────────────────────────────────────────────────────────────

  abstract override def receive: Receive =
    emissionReceive.orElse(emissionPreparation.receive).orElse(super.receive)

  /**
   * Only the candidate path is handled here. Joins, queue passes and self-collateralization are
   * admitted by the engine as intents and reach this trait through [[executeEmission]]; the timers
   * below send the same messages the engine matches on.
   */
  private def emissionReceive: Receive = {
    // ------------------------------------------------------------------
    // A block is being assembled — hand back fee-less copies
    // ------------------------------------------------------------------

    // Emission candidates reserve nothing that a dropped block has to reconcile, so there is
    // nothing to undo. Matched anyway, because every transaction source is told.

    // Building for a height that can no longer land would offer it to a later one.
    case CandidateTxsDropped(blockHeight) => emissionPreparation.drop(blockHeight)

    case PrepareBlockTxs(blockHeight, limit) => startCandidateBuild(blockHeight, limit, None)

    case RequestBlockTxs(blockHeight, limit) =>
      val replyTo = sender()
      // Already built for this height, so the request costs nothing but the reply.
      emissionPreparation.preparedFor(blockHeight) match {
        case Some(bundles) => replyTo ! BlockTxsReady(blockHeight, bundles)
        case None => startCandidateBuild(blockHeight, limit, Some(replyTo))
      }

  }

  /** One fee-less build, off the mailbox. Its result is cached and answers whoever is waiting. */
  /** One fee-less build. Runs on the candidate worker, so it reads nothing this actor owns. */
  private def candidateBundles(blockHeight: Int, limit: Int): Seq[transactions.candidate.CandidateBundle] = {
    require(emissionAlive.get(), "emission engine attempt was superseded")
    client.execute { ctx =>
        val (tip, spends) = txs.buildQueueSpends(ctx, blockHeight, funded = false, limit)
        if (spends.isEmpty) Seq.empty[transactions.candidate.CandidateBundle]
        else {
          // Other lenders' unconfirmed joins, first because these spends chain off them.
          // Carrying them also avoids censoring work this client happened to build on top of.
          val ancestors = tip.ancestors.map { t =>
            val encoded = NodeCodecs.encodeTransaction(t).toString
            // The node reports the serialized size it charges to the block; only the execution
            // cost is unknown, and the package's block share is what covers that.
            CandidateTx(t.id, encoded, CandidateTx.MempoolAncestor,
              t.inputs.map(_.boxId).toSet, t.size.getOrElse(encoded.length))
          }
          val own = spends.map(s => CandidateTx(
            s.tx.getId.replace("\"", ""), s.tx.toJson(false, false),
            if (s.kind == EmissionSpend.Clear) CandidateTx.Clear else CandidateTx.Activate,
            transactions.engine.execution.RollupExecution.signedInputIds(s.tx),
            transactions.engine.execution.RollupExecution.signedSizeBytes(s.tx), s.tx.getCost.toLong,
            transactions.engine.execution.RollupExecution.signedLeaf(s.tx)))
          // The queue spends chain off the last unconfirmed join in the emission chain, and
          // the whole chain travels with them.
          Seq(transactions.candidate.CandidateBundle((ancestors ++ own).toVector,
            ancestors.lastOption.map(a => BlockTxMessages.ChainFromMempool(a.id)).toSeq ++
              ancestors.map(a => BlockTxMessages.IncludeExisting(a.id))))
      }
    }
  }

  // ─── private helpers ──────────────────────────────────────────────────────

  private def sendQueue(ctx: org.ergoplatform.appkit.BlockchainContext,
                        spends: Seq[EmissionSpend]): Seq[(String, Try[String])] = {
    val allReservations = spends.flatMap(_.reservations)
    val finalChange =
      try spends.lastOption.toSeq.flatMap(_.tx.getOutputsToSpend.asScala).map(InputUTXO(_))
        .filter(box => nodeConfig.getNodeWallet.signableTrees.contains(box.contract.ergoTreeHex))
      catch {
        case ex: Throwable =>
          allReservations.foreach(_.release())
          throw ex
      }

    var stopped = false
    var attempted = Vector.empty[(String, Try[String])]
    spends.zipWithIndex.foreach { case (spend, idx) =>
      if (!stopped) {
        val result = Try {
          // A spend can own both ordinary wallet funding and actor-held change from its parent.
          // The engine pins the entire funding set before contacting the node.
          new transactions.engine.EngineBroadcast(emissionWalletManager, emissionNodeApi)
            .send(spend.tx, spend.reservations, "emission:" + spend.tx.getId, () => emissionAlive.get())
            .requireAccepted()
        }
        attempted :+= spend.kind -> result
        if (result.isFailure) {
          stopped = true
          spends.drop(idx + 1).flatMap(_.reservations).foreach(_.release())
        }
      }
    }
    if (!stopped && finalChange.nonEmpty) walletSelector.giveBack(finalChange)
    attempted
  }

  /** Hold the first pass back, but never longer than the timer's own interval. */
  private def firstDelay(interval: Int): FiniteDuration =
    math.min(interval.toLong, FirstPassDelayMs).milliseconds
}

object EmissionsCore {

  /** Delay before the first pass of either timer, so the node wallet has time to unlock. */
  private[transactions] final val FirstPassDelayMs = 60000L

  /** Gap between the two timers' first passes, so they do not both fire at FirstPassDelayMs. */
  private[transactions] final val FirstPassStaggerMs = 30000L

  /** Widened from `private` so the package's tests can drive a tick instead of waiting minutes. */
  private[transactions] case object Collateralize
  private[transactions] case object DriveQueue

}
