package api

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import api.LithosApiErrors.LithosUnavailable
import api.models._
import configs.{ForwardsOffersConfig, NodeContext}
import javax.inject.{Inject, Named, Singleton}
import org.ergoplatform.appkit.NetworkType
import play.api.Configuration
import stats.{MiningStatsRefresh, NextDifficulty, NextDifficultyEstimate}
import transactions.batching.forwards.ForwardsBatcher
import transactions.engine.{EngineIntent, ForwardsIntent, TransactionEngine}
import transactions.engine.execution.ForwardsAPIExecution
import transactions.engine.wallet.EngineFunding

import java.util.concurrent.TimeoutException
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

/**
 * Reads and quotes are served here; everything that signs is handed to the transaction engine, which owns
 * the wallet. The next epoch's difficulty comes from the mining statistics' difficulty table.
 */
@Singleton
class ForwardsApiImpl @Inject()(node: NodeContext,
                                @Named("transaction-engine") engine: ActorRef,
                                mining: MiningStatsRefresh,
                                config: Configuration,
                                system: ActorSystem) extends ForwardsApi {

  /** Most a read waits on the mining statistics for the difficulty table. */
  private val DifficultyWait = 5.seconds

  /** How long the caller waits for the engine's answer. */
  protected def answerWait: FiniteDuration = ForwardsApiImpl.AnswerWait

  private val reads = new ForwardsAPIExecution(node, EngineFunding(engine, EngineFunding.AskTimeout, system.dispatcher),
    ForwardsBatcher.deployment(node.getNetwork), ForwardsOffersConfig(config), nextDifficulty = () => nextDifficulty())

  /**
   * The engine's answer. A 503 says nothing was sent when the engine refused or dropped the request, and says the
   * outcome is unknown when no answer came in time.
   */
  private def submit[A: ClassTag](intent: ForwardsIntent): A = {
    val now = System.currentTimeMillis()
    val scheduled = TransactionEngine.Schedule(EngineIntent.Forwards(intent), now,
      now + ForwardsApiImpl.SendWindow.toMillis)
    val answer = try Await.result(engine.ask(scheduled)(Timeout(answerWait)), answerWait) catch {
      case _: TimeoutException => throw LithosUnavailable(s"the transaction engine did not answer within " +
        s"${answerWait.toSeconds} s. The request may still have reached the node, so check this wallet's offers " +
        "and positions before sending it again")
    }
    answer match {
      case result: A => result
      case TransactionEngine.Deferred(_, reason) =>
        throw LithosUnavailable(s"the transaction engine did not run the request, so nothing was sent: $reason")
      case other => throw new IllegalStateException(s"the transaction engine answered ${other.getClass.getSimpleName}")
    }
  }

  /** From the 9 newest epochs the table holds, the complete one before the current, and the current one. */
  private def nextDifficulty(): Either[String, NextDifficultyEstimate] =
    Try(Await.result(mining.difficulty(None, None, NextDifficulty.UseLastEpochs + 1), DifficultyWait)) match {
      case Failure(ex) => Left(s"the mining statistics did not answer: ${ex.getMessage}")
      case Success(history) =>
        history.current.toRight("the mining statistics have not read the current epoch yet").flatMap(current =>
          NextDifficulty.estimate(history.epochs ++ history.previous, current,
            NextDifficulty.rule(node.getNetwork == NetworkType.MAINNET)))
    }

  override def getAccumulator: DFAccumulatorInfo = reads.getAccumulator
  override def listOffers(offset: Option[Int], limit: Option[Int]): DFOfferBook = reads.listOffers(offset, limit)
  override def checkOffer(request: DFOfferRequest): DFOfferQuote = reads.checkOffer(request)
  override def createOffer(request: DFOfferRequest): DFOfferResult =
    submit[DFOfferResult](ForwardsIntent.CreateOffer(request))
  override def checkFill(boxId: String): DFFillQuote = reads.checkFill(boxId)
  override def fill(boxId: String): DFFillResult = submit[DFFillResult](ForwardsIntent.Fill(boxId))
  override def checkCancel(boxId: String): DFCancelQuote = reads.checkCancel(boxId)
  override def cancel(boxId: String): DFCancelResult = submit[DFCancelResult](ForwardsIntent.Cancel(boxId))
  override def getMarket: DFMarket = reads.getMarket
  override def listPositions: DFPositionList = reads.listPositions
  override def getHistory: DFHistory = reads.getHistory
  override def checkSettle(boxId: String): DFSettleQuote = reads.checkSettle(boxId)
  override def settle(boxId: String): DFSettleResult = submit[DFSettleResult](ForwardsIntent.Settle(boxId))
}

object ForwardsApiImpl {

  /**
   * Longest a mutation may take to reach the node, queueing included. Past it the engine signs and sends nothing,
   * so a caller told it failed never finds it sent later, and a retried offer cannot lock its LIT twice.
   */
  final val SendWindow: FiniteDuration = 30.seconds

  /** The window and a node call's worth, kept under the HTTP server's 75 s idle timeout. */
  final val AnswerWait: FiniteDuration = SendWindow + 40.seconds
}
