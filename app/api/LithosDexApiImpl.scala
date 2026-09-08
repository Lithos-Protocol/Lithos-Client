package api

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import api.models._
import cache.LDCache
import configs.NodeContext
import javax.inject.{Inject, Named}
import transactions.engine.DexIntent
import transactions.engine.execution.DexExecution
import transactions.engine.wallet.EngineFunding
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.reflect.ClassTag

class LithosDexApiImpl @Inject()(node: NodeContext,
  @Named("transaction-engine") engine: ActorRef, system: ActorSystem) extends LithosDexApi {
  private implicit val timeout: Timeout = Timeout(45.seconds)
  private val reads = new DexExecution(node, EngineFunding(engine, EngineFunding.AskTimeout, system.dispatcher))
  private def submit[A: ClassTag](intent: DexIntent): A =
    Await.result((engine ? intent).mapTo[A], timeout.duration)
  override def getPool(ldCache: LDCache): LDPoolInfo = reads.getPool(ldCache)

  override def getVault(ldCache: LDCache): LDVaultInfo = reads.getVault(ldCache)

  override def checkSwap(request: LDSwapRequest, ldCache: LDCache): LDSwapQuote = reads.checkSwap(request, ldCache)

  override def checkSwapOutput(request: LDSwapOutputRequest, ldCache: LDCache): LDSwapQuote = reads.checkSwapOutput(request, ldCache)

  override def swap(request: LDSwapExecuteRequest, ldCache: LDCache): LDSwapResult = submit[LDSwapResult](DexIntent.Swap(request))

  override def checkDeposit(request: LDDepositRequest, ldCache: LDCache): LDDepositQuote = reads.checkDeposit(request, ldCache)

  override def deposit(request: LDDepositExecuteRequest, ldCache: LDCache): LDDepositResult = submit[LDDepositResult](DexIntent.Deposit(request))

  override def checkRedeem(request: LDRedeemRequest, ldCache: LDCache): LDRedeemQuote = reads.checkRedeem(request, ldCache)

  override def redeem(request: LDRedeemRequest, ldCache: LDCache): LDRedeemResult = submit[LDRedeemResult](DexIntent.Redeem(request))

  override def listProvisions(ldCache: LDCache): LDProvisionList = reads.listProvisions(ldCache)

  override def claimProvision(boxId: String,
                     request: LDClaimRequest,
                     ldCache: LDCache): LDClaimResult = submit[LDClaimResult](DexIntent.Claim(boxId, request))

  override def checkResize(boxId: String, request: LDResizeRequest, ldCache: LDCache): LDResizeQuote = reads.checkResize(boxId, request, ldCache)

  override def resize(boxId: String, request: LDResizeRequest, ldCache: LDCache): LDResizeResult = submit[LDResizeResult](DexIntent.Resize(boxId, request))

  override def checkFlush(ldCache: LDCache): LDFlushCheck = reads.checkFlush(ldCache)

  override def flush(request: LDFlushRequest, ldCache: LDCache): LDFlushResult = submit[LDFlushResult](DexIntent.Flush(request))

  override def getFeeHistory(from: Option[Int], to: Option[Int], bucket: Option[Int], ldCache: LDCache): LDFeeHistory = reads.getFeeHistory(from, to, bucket, ldCache)

  override def getProvisionFeeHistory(boxId: String,
                             from: Option[Int],
                             to: Option[Int],
                             bucket: Option[Int],
                             ldCache: LDCache): LDProvisionFeeHistory = reads.getProvisionFeeHistory(boxId, from, to, bucket, ldCache)

  override def getPriceHistory(range: Option[String], bucket: Option[Int], ldCache: LDCache): LDPriceHistory = reads.getPriceHistory(range, bucket, ldCache)

  override def getRecentSwaps(limit: Option[Int]): LDRecentSwaps = reads.getRecentSwaps(limit)

}
