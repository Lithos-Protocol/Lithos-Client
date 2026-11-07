package api

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import api.LithosApiErrors.LithosUnavailable
import api.models._
import configs.{NodeContext, SigmaFiBondsConfig}
import play.api.Configuration
import transactions.engine.execution.SigmaFiAPIExecution
import transactions.engine.wallet.EngineFunding
import transactions.engine.{EngineIntent, SigmaFiIntent, TransactionEngine}

import java.util.concurrent.TimeoutException
import javax.inject.{Inject, Named, Singleton}
import scala.concurrent.Await
import scala.concurrent.duration._

/** Reads and checks are served here; every send is handed to the transaction engine, which owns the wallet. */
@Singleton
class SigmaFiApiImpl @Inject()(node: NodeContext,
                               @Named("transaction-engine") engine: ActorRef,
                               config: Configuration,
                               system: ActorSystem) extends SigmaFiApi {

  /** How long the caller waits for the engine's answer. */
  protected def answerWait: FiniteDuration = SigmaFiApiImpl.AnswerWait

  private val reads = new SigmaFiAPIExecution(node, EngineFunding(engine, EngineFunding.AskTimeout, system.dispatcher),
    SigmaFiBondsConfig(config))

  /**
   * The engine's answer. A 503 says nothing was sent when the engine refused or dropped the request, and says the
   * outcome is unknown when no answer came in time.
   */
  private def submit(intent: SigmaFiIntent): SFResult = {
    val now = System.currentTimeMillis()
    val scheduled = TransactionEngine.Schedule(EngineIntent.SigmaFi(intent), now, now + SigmaFiApiImpl.SendWindow.toMillis)
    val answer = try Await.result(engine.ask(scheduled)(Timeout(answerWait)), answerWait) catch {
      case _: TimeoutException => throw LithosUnavailable(s"the transaction engine did not answer within " +
        s"${answerWait.toSeconds} s. The request may still have reached the node, so check this wallet's portfolio " +
        "before sending it again")
    }
    answer match {
      case result: SFResult => result
      case TransactionEngine.Deferred(_, reason) =>
        throw LithosUnavailable(s"the transaction engine did not run the request, so nothing was sent: $reason")
      case other => throw new IllegalStateException(s"the transaction engine answered ${other.getClass.getSimpleName}")
    }
  }

  /** A check through the reads, or a send through the engine. */
  private def act(check: => Either[SFQuote, SFResult], send: Boolean, intent: => SigmaFiIntent): Either[SFQuote, SFResult] =
    if (send) Right(submit(intent)) else check

  override def listOrders(offset: Option[Int], limit: Option[Int], collateral: Option[String],
                          loan: Option[String]): SFOrderBook = reads.listOrders(offset, limit, collateral, loan)

  override def listBonds(offset: Option[Int], limit: Option[Int], all: Option[Boolean]): SFBondList =
    reads.listBonds(offset, limit, all)
  override def lookupAssets(request: SFAssetLookupRequest): Seq[SFAssetLookup] = reads.lookupAssets(request)
  override def getPortfolio: SFPortfolio = reads.getPortfolio

  override def createOrder(request: SFOrderRequest, send: Boolean): Either[SFQuote, SFResult] =
    act(reads.createOrder(request, send = false), send, SigmaFiIntent.CreateOrder(request))

  override def cancelOrder(boxId: String, send: Boolean): Either[SFQuote, SFResult] =
    act(reads.cancelOrder(boxId, send = false), send, SigmaFiIntent.CancelOrder(boxId))

  override def fillOrder(boxId: String, send: Boolean): Either[SFQuote, SFResult] =
    act(reads.fillOrder(boxId, send = false), send, SigmaFiIntent.FillOrder(boxId))

  override def repay(boxId: String, send: Boolean): Either[SFQuote, SFResult] =
    act(reads.repay(boxId, send = false), send, SigmaFiIntent.Repay(boxId))
  override def liquidate(boxId: String, send: Boolean): Either[SFQuote, SFResult] =
    act(reads.liquidate(boxId, send = false), send, SigmaFiIntent.Liquidate(boxId))
  override def claim(tokenId: String, send: Boolean): Either[SFQuote, SFResult] =
    act(reads.claim(tokenId, send = false), send, SigmaFiIntent.Claim(tokenId))
}

object SigmaFiApiImpl {

  /** Longest a send may take to reach the node, queueing included. Past it the engine signs and sends nothing. */
  final val SendWindow: FiniteDuration = 30.seconds

  /** The window and a node call's worth, kept under the HTTP server's 75 s idle timeout. */
  final val AnswerWait: FiniteDuration = SendWindow + 40.seconds
}
