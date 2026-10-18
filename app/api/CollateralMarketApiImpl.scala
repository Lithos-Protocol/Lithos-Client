package api

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.{AskTimeoutException, ask}
import akka.util.Timeout
import api.models._
import configs.NodeContext
import javax.inject.{Inject, Named, Singleton}
import play.api.Configuration
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}
import transactions.engine.TransactionEngine
import transactions.engine.execution.CollateralExecution

@Singleton
class CollateralMarketApiImpl @Inject()(nodeContext: NodeContext, config: Configuration,
                                        system: ActorSystem,
                                        @Named("transaction-engine") engine: ActorRef)
  extends CollateralMarketApi {
  private val reads = new CollateralExecution(nodeContext, config, system, engine)
  private implicit val timeout: Timeout = Timeout(180.seconds)

  override def getMarketInfo: CollateralMarketInfo = reads.getMarketInfo
  override def getQueue(limit: Option[Int], offset: Option[Int]): Seq[CollateralQueueEntry] = reads.getQueue(limit, offset)
  override def getActiveSet(limit: Option[Int], offset: Option[Int]): Seq[CollateralActiveEntry] = reads.getActiveSet(limit, offset)
  override def getPermitHistory(limit: Option[Int]): CollateralPermitHistory = reads.getPermitHistory(limit)
  override def getWalletStatus: WalletCollateralStatus = reads.getWalletStatus
  override def getRewardSummary: CollateralRewardSummary = reads.getRewardSummary
  override def claimUnlockedRewards: CollateralRewardClaimResult = reads.claimUnlockedRewards
  override def checkJoin(request: CollateralJoinCheckRequest): CollateralJoinQuote = reads.checkJoin(request)
  // The engine answers a join it would not run with Deferred, never with a result, so each answer is
  // matched rather than cast.
  override def join(request: CollateralJoinExecuteRequest): CollateralJoinResult =
    try Try(Await.result(engine ? TransactionEngine.JoinCollateral(request), timeout.duration)) match {
      case Success(result: CollateralJoinResult) => result
      case Success(TransactionEngine.Deferred(_, reason)) =>
        throw LithosApiErrors.LithosUnavailable(s"the transaction engine did not run the join, so nothing was sent: $reason")
      case Success(other) => throw new IllegalStateException(s"unexpected answer from the transaction engine: $other")
      case Failure(_: AskTimeoutException | _: java.util.concurrent.TimeoutException) =>
        throw LithosApiErrors.LithosUnavailable("the transaction engine has not answered and may still send. " +
          "Read GET /collateral/wallet before trying again")
      case Failure(ex) => throw ex
    } finally reads.invalidateSnapshots()
}
