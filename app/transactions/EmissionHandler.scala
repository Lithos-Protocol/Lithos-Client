package transactions

import akka.actor.{Actor, ActorRef, Cancellable}
import akka.pattern.ask
import akka.util.Timeout
import configs.{EmissionConfig, NodeContext}
import mutations.NotEnoughInputsException
import node.NodeApi
import node.rest.NodeCodecs
import org.ergoplatform.appkit.{ErgoClient, SignedTransaction}
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import play.api.libs.concurrent.InjectedActorSupport
import transactions.BlockTxMessages.{BlockTxsReady, CandidateTx, RequestBlockTxs}
import transactions.EmissionHandler._
import transactions.WalletMessages.{ReleaseInputs, RetrieveInputs, ReturnInputs, WalletInputs}
import work.lithos.mutations.{InputUTXO, Token}

import javax.inject.{Inject, Named}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
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

  private val txs = new EmissionTransactions(
    nodeConfig.getNodeWallet, nodeApi, emissionConfig, new ManagedWallet(walletManager))

  private implicit val ec: ExecutionContext =
    Try(context.system.dispatchers.lookup("lithos-contexts.tx-dispatcher"))
      .getOrElse(context.dispatcher)

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
            spends.map(s => (s.kind, send(ctx, s.tx)))
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
      logger.error(s"Emission $what failed: ${ex.getMessage}", ex)

    // ------------------------------------------------------------------
    // A block is being assembled — hand back fee-less copies
    // ------------------------------------------------------------------

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

  private def send(ctx: org.ergoplatform.appkit.BlockchainContext, sTx: SignedTransaction): Try[String] =
    Try(ctx.sendTransaction(sTx).replace("\"", ""))

  /** Hold the first pass back, but never longer than the timer's own interval. */
  private def firstDelay(interval: Int): FiniteDuration =
    math.min(interval.toLong, FirstPassDelayMs).milliseconds
}

/**
 * [[WalletSource]] backed by [[WalletManager]], which is the only thing that selects wallet UTXOs.
 *
 * The `Await` is the same pattern `SubmissionHandler` uses. It is safe here because it never runs on
 * the block-assembly path: `buildQueueSpends(funded = false)` takes no wallet inputs at all. Anything
 * that changes that would put a blocking call in front of a miner waiting for a candidate.
 */
class ManagedWallet(walletManager: ActorRef) extends WalletSource {

  private implicit val timeout: Timeout = Timeout(ManagedWallet.AskTimeout)

  def take(value: Long, token: Option[Token]): Seq[InputUTXO] = {
    val inputs = Await.result(
      (walletManager ? RetrieveInputs(value, token.toSeq, trackUsed = true)).mapTo[WalletInputs],
      ManagedWallet.AskTimeout).inputs
    if (inputs.isEmpty)
      throw new NotEnoughInputsException(
        s"WalletManager returned nothing for $value nanoERG" +
          token.map(t => s" and ${t.amount} of ${t.id}").getOrElse(""))
    inputs
  }

  def giveBack(boxes: Seq[InputUTXO]): Unit = walletManager ! ReturnInputs(boxes)

  def release(boxes: Seq[InputUTXO]): Unit = walletManager ! ReleaseInputs(boxes)
}

object ManagedWallet {
  val AskTimeout: FiniteDuration = 10.seconds
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
