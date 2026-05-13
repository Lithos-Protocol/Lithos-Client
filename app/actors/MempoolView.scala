package actors

import akka.actor.{Actor, ActorRef, Cancellable}
import cache.{PDCache, RollupCache}
import configs.NodeConfig
import lfsm.LFSMHelpers
import org.ergoplatform.appkit.{ErgoId, JavaHelpers}
import org.ergoplatform.appkit.impl.NodeAndExplorerDataSourceImpl
import org.slf4j.{Logger, LoggerFactory}
import plasmadex.PDHelpers
import play.api.cache.SyncCacheApi
import play.api.libs.concurrent.InjectedActorSupport
import state.messages.MempoolMessages.{BuildRollupChains, MempoolChain, MempoolTransform}
import state.messages.{BlockInfo, BlockTx}
import state.messages.StateFrameMessages._
import utils.{Globals, Helpers}

import javax.inject.{Inject, Named}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object MempoolView {
  private case object Tick
}

class MempoolView @Inject()(@Named("state-frame") stateFrame: ActorRef,
                            @Named("rollup-coordinator") rollupCoordinator: ActorRef,
                            @Named("pd-synchronizer") pdSynchronizer: ActorRef,
                            cacheApi: SyncCacheApi) extends Actor with InjectedActorSupport {
  import MempoolView._

  private val logger: Logger = LoggerFactory.getLogger("MempoolView")
  private val nodeConfig: NodeConfig = Globals.getNodeConfig

  private val dataSource: NodeAndExplorerDataSourceImpl =
    nodeConfig.getClient.getDataSource.asInstanceOf[NodeAndExplorerDataSourceImpl]
  private val rollupCache = new RollupCache(cacheApi)
  private val pdCache = new PDCache(cacheApi)
  private var currentHeight: Int = chainHeight
  private var subscribers: Set[ActorRef] = Set.empty
  private var lastTxs: Seq[BlockTx] = Seq.empty
  private var rollupChains: Seq[MempoolChain] = Seq.empty
  private var pdChain: Option[MempoolChain] = None

  private var mdSet: Set[MempoolTransform] = Set.empty
  private var pdSet: Set[MempoolTransform] = Set.empty
  private val ticker: Cancellable =
    context.system.scheduler.scheduleWithFixedDelay(5.seconds, 5.seconds, self, Tick)(context.dispatcher)

  logger.info(s"MempoolView initialized.")
  stateFrame ! AutoSubscribe(self)
  override def postStop(): Unit = ticker.cancel()

  override def receive: Receive = {

    case Tick =>
      fetchTxs match {
        case Failure(exception) =>
          logger.error("Failed to update mempool", exception)
        case Success(txs) =>
          if(txs != lastTxs){
            val rollupMap = buildRollupChains(txs)
            if(rollupChains != rollupMap.values.toSeq){
              rollupMap.foreach(rollupCoordinator ! _._2)
              rollupChains = rollupMap.values.toSeq
            }
//            val pdMap = buildMempoolChains(pdCache.getPD.map(_.utxoId).toSet, txs)
//            if(pdChain.contains(pdMap.values.toSeq)){
//              rollupMap.foreach(rollupCoordinator ! _._2)
//              rollupChains = rollupMap.values.toSeq
//            }
            // TODO ADD MORE HERE
            lastTxs = txs
          }
      }

    case NewBlock =>
      self ! Tick
    case BuildRollupChains =>
      fetchTxs match {
        case Failure(exception) =>
          logger.error("Failed to update mempool", exception)
        case Success(txs) =>
          logger.info("Got request to rebuild rollup mempool chains")
          val rollupMap = buildRollupChains(txs)
          rollupMap.foreach(rollupCoordinator ! _._2)
          rollupChains = rollupMap.values.toSeq
          lastTxs = txs
      }
  }

  private def chainHeight: Int =
    nodeConfig.getClient.execute(ctx => ctx.getHeight)

  private def buildRollupChains(mempoolTxs: Seq[BlockTx]) = {
    buildMempoolChains(rollupCache.getTreeSet, mempoolTxs)
  }
  private def buildMempoolChains(utxoIds: Set[String], mempoolTxs: Seq[BlockTx]): Map[String, MempoolChain] = {
    mempoolTxs.foldLeft(Map.empty[String, MempoolChain]){
      (z, t) =>
        if(utxoIds.contains(t.inputs.head.id)){
          if(z.contains(t.inputs.head.id)) {
            logger.warn(s"Replacing existing mempool chain for utxo ${t.inputs.head.id}")
          }

          z + (t.inputs.head.id -> MempoolChain(Seq(MempoolTransform(t))))
        }else {
          val chain = z.find(c => c._2.transforms.exists(_.output.id == t.inputs.head.id))
          if(chain.isDefined)
            z + (chain.get._1 -> MempoolChain(chain.get._2.transforms :+ MempoolTransform(t)))
          else {
            //logger.warn(s"Dropping ${t.id} due to lack of viable tx chain")
            z
          }
        }
    }
  }

  private def fetchTxs: Try[Seq[BlockTx]] = Try {
    val mempoolTxs = dataSource
      .getNodeTransactionsApi.getUnconfirmedTransactions(100, 0)
      .execute()
      .body()


    val txsList = JavaHelpers.toIndexedSeq(mempoolTxs)
    //logger.info(s"Txs: ${txsList.map(_.getId).mkString(", ")}")
    txsList.map(BlockTx.fromSync)
  }
}
