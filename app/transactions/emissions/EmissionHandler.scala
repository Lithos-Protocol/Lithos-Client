package transactions.emissions

import akka.actor.{Actor, ActorRef, Cancellable}
import configs.{EmissionConfig, NodeContext}
import node.NodeApi
import node.rest.NodeCodecs
import org.ergoplatform.appkit.{ErgoClient, SignedTransaction}
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import play.api.libs.concurrent.InjectedActorSupport
import transactions.BlockTxMessages.{BlockTxsReady, CandidateTx, CandidateTxsDropped, RequestBlockTxs}
import transactions.emissions.EmissionHandler._
import transactions.engine.{FundingAllocation, EngineFunding}
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
trait EngineEmissions extends Actor with InjectedActorSupport {
  protected def config: Configuration
  protected def emissionNodeContext: NodeContext
  protected def emissionWalletManager: ActorRef

  private val logger: Logger = LoggerFactory.getLogger("EmissionHandler")

  private val nodeConfig: NodeContext = emissionNodeContext
  private val client: ErgoClient = nodeConfig.getClient
  protected def emissionNodeApi: NodeApi = nodeConfig.getNodeApi
  private val emissionConfig: EmissionConfig = EmissionConfig(config)

  private implicit val emissionEc: ExecutionContext = context.dispatcher
  private val emissionWorker = context.system.dispatchers.lookup("lithos-contexts.engine-io-dispatcher")
  private val candidateWorker = context.system.dispatchers.lookup("lithos-contexts.engine-candidate-dispatcher")
  private val emissionIncarnation = java.util.UUID.randomUUID()
  private val emissionAlive = new java.util.concurrent.atomic.AtomicBoolean(true)
  private var candidateBusy = false
  private lazy val collateral = new transactions.engine.CollateralExecution(
    emissionNodeContext, config, context.system, emissionWalletManager, () => emissionAlive.get()) {
    override protected def executionNode: NodeApi = emissionNodeApi
  }

  private val walletSelector = EngineFunding(emissionWalletManager, EngineFunding.AskTimeout, context.dispatcher)
  private lazy val txs = new EmissionTransactions(
    nodeConfig.getNodeWallet, emissionNodeApi, emissionConfig, walletSelector, () => emissionAlive.get())

  private var collateralizeTicker: Option[Cancellable] = None
  private var queueTicker: Option[Cancellable] = None

  /**
   * One lock for BOTH timers, not one each. Join, Activate and Clear all spend the emission box and
   * all chain off `emissionTip`, so two passes running at once build on the same tip and one of them
   * is a guaranteed double spend — taking every chained transaction behind it down with it. The
   * intervals make this collide on schedule: 120s and 600s coincide every 10 minutes.
   */
  private var spending: Boolean = false

  // ─── lifecycle ────────────────────────────────────────────────────────────

  abstract override def preStart(): Unit = {
    super.preStart()
    if (!emissionConfig.enabled) {
      logger.info("EmissionHandler disabled via emission.enabled")
    } else {
      logger.info(s"EmissionHandler starting - queue every ${emissionConfig.queueInterval}ms " +
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

  abstract override def receive: Receive = emissionReceive.orElse(super.receive)
  private def emissionReceive: Receive = {
    case transactions.engine.TransactionEngine.JoinCollateral(_) if spending =>
      sender() ! akka.actor.Status.Failure(api.LithosApiErrors.LithosUnavailable("an emission submission is already running"))
    case transactions.engine.TransactionEngine.JoinCollateral(request) =>
      val reply = sender()
      spending = true
      dispatchEmission(collateral.join(request))(result =>
        self ! ManualJoined(emissionIncarnation, reply, result))
    case ManualJoined(incarnation, reply, result) if incarnation == emissionIncarnation =>
      spending = false
      result match {
        case Success(value) => reply ! value
        case Failure(ex) => reply ! akka.actor.Status.Failure(ex)
      }
    case _: ManualJoined => ()
    case EmissionResult(incarnation, result) if incarnation == emissionIncarnation => emissionReceive(result)
    case _: EmissionResult => ()
    case CandidateBuilt(incarnation, reply, height, result) if incarnation == emissionIncarnation =>
      candidateBusy = false
      reply ! BlockTxsReady(height, result.getOrElse(Seq.empty))
    case _: CandidateBuilt => ()

    // ------------------------------------------------------------------
    // Timers — build funded transactions and broadcast them
    // ------------------------------------------------------------------

    case Collateralize =>
      if (spending) logger.info("Skipping self-collateralization, an emission pass is already running")
      else {
        spending = true
        dispatchEmission(client.execute(ctx => txs.selfCollateralize(ctx,
          new transactions.engine.EngineBroadcast(emissionWalletManager, emissionNodeApi)))) {
          case Success(txIds) => self ! EmissionResult(emissionIncarnation, Collateralized(txIds))
          case Failure(ex) => self ! EmissionResult(emissionIncarnation, EmissionFailed("self-collateralization", ex))
        }
      }

    case Collateralized(txIds) =>
      spending = false
      if (txIds.nonEmpty)
        logger.info(s"Sent ${txIds.size} join transaction(s): ${txIds.map(_.take(8)).mkString(", ")}")

    case DriveQueue =>
      if (spending) logger.info("Skipping queue pass, an emission pass is already running")
      else {
        spending = true
        dispatchEmission {
          client.execute { ctx =>
            val (_, spends) = txs.buildQueueSpends(
              ctx, ctx.getHeight + 1, funded = true, emissionConfig.maxQueueSpends)
            sendQueue(ctx, spends)
          }
        } {
          case Success(sent) => self ! EmissionResult(emissionIncarnation, QueueDriven(sent))
          case Failure(ex) => self ! EmissionResult(emissionIncarnation, EmissionFailed("queue maintenance", ex))
        }
      }

    case QueueDriven(sent) =>
      spending = false
      sent.foreach {
        case (kind, Success(txId)) => logger.info(s"Sent $kind transaction $txId")
        case (kind, Failure(ex)) => logger.warn(s"Could not send $kind transaction: ${ex.getMessage}")
      }

    case EmissionFailed(what, ex) =>
      spending = false
      // The builders stop before building a spend the contracts reject, so this is state that moved
      // between reading and sending - not a defect, and not worth a stack trace every pass.
      if (Option(ex.getMessage).exists(_.contains("Script reduced to false")))
        logger.warn(s"Emission $what stopped: the contracts rejected the built transaction " +
          "- chain or active-set state moved under it. Retrying on the next pass")
      else
        logger.error(s"Emission $what failed: ${ex.getMessage}", ex)

    // ------------------------------------------------------------------
    // A block is being assembled — hand back fee-less copies
    // ------------------------------------------------------------------

    // Emission candidates reserve nothing that a dropped block has to reconcile, so there is
    // nothing to undo. Matched anyway, because every transaction source is told.

    case RequestBlockTxs(blockHeight, limit) =>
      val replyTo = sender()
      if (!emissionConfig.enabled || limit <= 0 || candidateBusy) replyTo ! BlockTxsReady(blockHeight, Seq.empty[CandidateTx])
      else {
        candidateBusy = true
        Try(Future {
          require(emissionAlive.get(), "emission engine attempt was superseded")
          client.execute { ctx =>
            val (tip, spends) = txs.buildQueueSpends(ctx, blockHeight, funded = false, limit)
            if (spends.isEmpty) Seq.empty[CandidateTx]
            else {
              // Other lenders' unconfirmed joins, first because these spends chain off them.
              // Carrying them also avoids censoring work this client happened to build on top of.
              val ancestors = tip.ancestors.map(t =>
                CandidateTx(t.id, NodeCodecs.encodeTransaction(t).toString, CandidateTx.MempoolAncestor))
              val own = spends.map(s => CandidateTx(
                s.tx.getId.replace("\"", ""), s.tx.toJson(false, false),
                if (s.kind == EmissionSpend.Clear) CandidateTx.Clear else CandidateTx.Activate))
              (ancestors ++ own).take(limit)
            }
          }
        }(candidateWorker).onComplete(result =>
          self ! CandidateBuilt(emissionIncarnation, replyTo, blockHeight, result)))
          .failed.foreach(ex => self ! CandidateBuilt(emissionIncarnation, replyTo, blockHeight, Failure(ex)))
      }
  }

  // ─── private helpers ──────────────────────────────────────────────────────
  private def dispatchEmission[A](body: => A)(finished: Try[A] => Unit): Unit =
    Try(Future { require(emissionAlive.get(), "emission engine attempt was superseded"); body }(emissionWorker)
      .onComplete(finished)).failed.foreach(ex => finished(Failure(ex)))

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

object EmissionHandler {

  /** Delay before the first pass of either timer, so the node wallet has time to unlock. */
  private[transactions] final val FirstPassDelayMs = 60000L

  /** Gap between the two timers' first passes, so they do not both fire at FirstPassDelayMs. */
  private[transactions] final val FirstPassStaggerMs = 30000L

  /** Widened from `private` so the package's tests can drive a tick instead of waiting minutes. */
  private[transactions] case object Collateralize
  private[transactions] case object DriveQueue

  private[emissions] case class Collateralized(txIds: Seq[String])
  private[emissions] case class QueueDriven(sent: Seq[(String, Try[String])])
  private[emissions] case class EmissionFailed(what: String, ex: Throwable)
  private[emissions] case class EmissionResult(incarnation: java.util.UUID, result: Any)
  private[emissions] case class ManualJoined(incarnation: java.util.UUID, reply: ActorRef,
    result: Try[api.models.CollateralJoinResult])
  private[emissions] case class CandidateBuilt(incarnation: java.util.UUID, reply: ActorRef,
    height: Int, result: Try[Seq[CandidateTx]])
}
