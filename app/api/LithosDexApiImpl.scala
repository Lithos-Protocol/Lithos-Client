package api

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import api.models._
import cache.LDCache
import configs.{LithosDexOrdersConfig, NodeContext}
import javax.inject.{Inject, Named}
import org.slf4j.LoggerFactory
import play.api.Configuration
import state.synchronization.CompleteMempool
import transactions.batching.Batcher
import transactions.engine.DexIntent
import transactions.engine.execution.DexAPIExecution
import transactions.engine.wallet.EngineFunding
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

class LithosDexApiImpl @Inject()(node: NodeContext,
  @Named("transaction-engine") engine: ActorRef,
  @Named("lithosdex-batcher") batcher: ActorRef,
  config: Configuration,
  system: ActorSystem) extends LithosDexApi {
  private implicit val timeout: Timeout = Timeout(45.seconds)
  private val logger = LoggerFactory.getLogger("LithosDexApi")
  private val reads = new DexAPIExecution(node, EngineFunding(engine, EngineFunding.AskTimeout, system.dispatcher),
    orderDefaults = LithosDexOrdersConfig(config), heldPlacements = () => heldPlacements())
  private def submit[A: ClassTag](intent: DexIntent): A =
    Await.result((engine ? intent).mapTo[A], timeout.duration)

  /**
   * Placements the LithosDex batcher carried into candidates, which the node evicts from its own mempool.
   * An unanswered ask lists orders without them, so a miner's own carried order is missing until it confirms.
   */
  private def heldPlacements(): Seq[CompleteMempool.MempoolTx] =
    Try(Await.result((batcher ? Batcher.HeldPlacements)(Timeout(5.seconds)).mapTo[Batcher.Held], 5.seconds)) match {
      case Success(held) => held.placements
      case Failure(ex) =>
        logger.warn(s"The LithosDEX batcher did not report its held placements, listing orders without them: ${ex.getMessage}")
        Seq.empty
    }

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

  override def getRecentActivity(limit: Option[Int]): LDRecentActivity = reads.getRecentActivity(limit)

  override def listOrders(ldCache: LDCache): LDOrderList = reads.listOrders(ldCache)

  override def checkSwapOrder(request: LDSwapOrderRequest, ldCache: LDCache): LDSwapOrderQuote = reads.checkSwapOrder(request, ldCache)

  override def placeSwapOrder(request: LDSwapOrderExecuteRequest, ldCache: LDCache): LDOrderPlacementResult =
    submit[LDOrderPlacementResult](DexIntent.PlaceSwapOrder(request))

  override def checkDepositOrder(request: LDDepositOrderRequest, ldCache: LDCache): LDDepositOrderQuote = reads.checkDepositOrder(request, ldCache)

  override def placeDepositOrder(request: LDDepositOrderExecuteRequest, ldCache: LDCache): LDOrderPlacementResult =
    submit[LDOrderPlacementResult](DexIntent.PlaceDepositOrder(request))

  override def checkRedeemOrder(request: LDRedeemOrderRequest, ldCache: LDCache): LDRedeemOrderQuote = reads.checkRedeemOrder(request, ldCache)

  override def placeRedeemOrder(request: LDRedeemOrderRequest, ldCache: LDCache): LDOrderPlacementResult =
    submit[LDOrderPlacementResult](DexIntent.PlaceRedeemOrder(request))

  override def cancelOrder(boxId: String, ldCache: LDCache): LDOrderCancelResult =
    submit[LDOrderCancelResult](DexIntent.CancelOrder(boxId))
}
