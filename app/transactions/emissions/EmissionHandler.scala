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
import transactions.wallet.{WalletReservation, WalletSelector}
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
class EmissionHandler @Inject()(config: Configuration, nodeContext: NodeContext,
                                @Named("wallet-manager") walletManager: ActorRef)
  extends Actor with InjectedActorSupport {

  private val logger: Logger = LoggerFactory.getLogger("EmissionHandler")

  private val nodeConfig: NodeContext = nodeContext
  private val client: ErgoClient = nodeConfig.getClient
  private val nodeApi: NodeApi = nodeConfig.getNodeApi
  private val emissionConfig: EmissionConfig = EmissionConfig(config)

  private implicit val ec: ExecutionContext =
    Try(context.system.dispatchers.lookup("lithos-contexts.tx-dispatcher"))
      .getOrElse(context.dispatcher)

  private val walletSelector = WalletSelector(walletManager, WalletSelector.AskTimeout, ec)
  private val txs = new EmissionTransactions(
    nodeConfig.getNodeWallet, nodeApi, emissionConfig, walletSelector)

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

  override def preStart(): Unit =
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

  override def postStop(): Unit = {
    collateralizeTicker.foreach(_.cancel())
    queueTicker.foreach(_.cancel())
  }

  // ─── receive ──────────────────────────────────────────────────────────────

  override def receive: Receive = {

    // ------------------------------------------------------------------
    // Timers — build funded transactions and broadcast them
    // ------------------------------------------------------------------

    case Collateralize =>
      if (spending) logger.info("Skipping self-collateralization, an emission pass is already running")
      else {
        spending = true
        Future(client.execute(txs.selfCollateralize)).onComplete {
          case Success(txIds) => self ! Collateralized(txIds)
          case Failure(ex) => self ! EmissionFailed("self-collateralization", ex)
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
        Future {
          client.execute { ctx =>
            val (_, spends) = txs.buildQueueSpends(
              ctx, ctx.getHeight + 1, funded = true, emissionConfig.maxQueueSpends)
            sendQueue(ctx, spends)
          }
        }.onComplete {
          case Success(sent) => self ! QueueDriven(sent)
          case Failure(ex) => self ! EmissionFailed("queue maintenance", ex)
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
    case CandidateTxsDropped(_) => ()

    case RequestBlockTxs(blockHeight, limit) =>
      val replyTo = sender()
      if (!emissionConfig.enabled || limit <= 0) replyTo ! BlockTxsReady(blockHeight, Seq.empty[CandidateTx])
      else
        Future {
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
        }.onComplete {
          case Success(built) => replyTo ! BlockTxsReady(blockHeight, built)
          case Failure(ex) =>
            logger.warn(s"No emission transactions for block $blockHeight: ${ex.getMessage}")
            replyTo ! BlockTxsReady(blockHeight, Seq.empty[CandidateTx])
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
        var submissionStarted = false
        val result = Try {
          // A spend can own both ordinary wallet funding and actor-held change from its parent.
          // Acquire the whole set before contacting the node; beginAll cancels an acknowledged
          // prefix and releases the untouched suffix if any later lease cannot begin.
          WalletReservation.beginAll(spend.reservations)
          submissionStarted = true
          ctx.sendTransaction(spend.tx).replace("\"", "")
        }
        result.foreach(_ => spend.reservations.foreach(_.commit()))
        if (result.isFailure && submissionStarted) spend.reservations.foreach(_.uncertain())
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

  private case class Collateralized(txIds: Seq[String])
  private case class QueueDriven(sent: Seq[(String, Try[String])])
  private case class EmissionFailed(what: String, ex: Throwable)
}
