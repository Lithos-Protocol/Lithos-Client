package api

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import api.models._
import configs.NodeContext
import javax.inject.{Inject, Named, Singleton}
import play.api.Configuration
import scala.concurrent.Await
import scala.concurrent.duration._
import transactions.engine.{CollateralExecution, TransactionEngine}

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
  override def join(request: CollateralJoinExecuteRequest): CollateralJoinResult =
    try Await.result((engine ? TransactionEngine.JoinCollateral(request)).mapTo[CollateralJoinResult], timeout.duration)
    finally reads.invalidateSnapshots()
}
