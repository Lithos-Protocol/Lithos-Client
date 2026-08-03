package state.synchronization

import akka.actor.{Actor, ActorRef, Cancellable}
import akka.util.Timeout
import cache.MempoolCache
import configs.NodeConfig
import node.NodeApi
import node.model.Paging
import org.slf4j.{Logger, LoggerFactory}
import play.api.cache.SyncCacheApi
import play.api.libs.concurrent.InjectedActorSupport
import AutoSubscribable.AutoSubscribe
import state.messages.{BlockTx, NodeSync}
import state.messages.MempoolMessages.{MempoolChain, MempoolTransform, RebuildMempoolChains, UpdatedMempoolChains}
import state.messages.StateFrameMessages._
import utils.Globals

import javax.inject.{Inject, Named}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object MempoolView {
  private case object Tick

  private final val MempoolLimit = 100
}

/**
 * Actor whose job is to publish current set of mempool chains to cache, and message subscribers
 * when updates have occurred.
 * @param stateFrame
 * @param rollupCoordinator
 * @param pdSynchronizer
 * @param cacheApi
 */
class MempoolView @Inject()(@Named("state-frame") stateFrame: ActorRef,
                            @Named("rollup-coordinator") rollupCoordinator: ActorRef,
                            @Named("pd-synchronizer") pdSynchronizer: ActorRef,
                            cacheApi: SyncCacheApi) extends Actor with InjectedActorSupport with AutoSubscribable {
  import MempoolView._
  implicit val timeout: Timeout = 5.seconds

  private val nodeConfig: NodeConfig = Globals.getNodeConfig

  private val nodeApi: NodeApi = nodeConfig.getNodeApi

  private val mempoolCache = MempoolCache(cacheApi)

  override val logger: Logger = LoggerFactory.getLogger("MempoolView")
  override var subscribers: Set[ActorRef] = Set.empty

  private var lastTxs: Seq[BlockTx] = Seq.empty


  private val ticker: Cancellable =
    context.system.scheduler.scheduleWithFixedDelay(5.seconds, 5.seconds, self, Tick)(context.dispatcher)

  logger.info(s"MempoolView initialized.")
  stateFrame ! AutoSubscribe(self)
  override def postStop(): Unit = ticker.cancel()

  override def receive: Receive = handleSubscriptions orElse {

    case Tick =>
      fetchTxs match {
        case Failure(exception) =>
          logger.error("Failed to update mempool", exception)
          lastTxs = Seq.empty
        case Success(txs) =>
          // Build mempool chains only if a change has been recognized in the mempool
          if(txs != lastTxs){
            val mempoolChains = buildTotalMempoolState(txs)
            mempoolChains.foreach(c => mempoolCache.setMempoolChain(c._1, c._2))
            subscribers.foreach(_ ! UpdatedMempoolChains)
            lastTxs = txs
          }
      }

    case NewBlock =>
      self ! Tick
    case RebuildMempoolChains => // Forcefully rebuild mempool chains and send updates out to all subscribers.
      fetchTxs match {
        case Failure(exception) =>
          logger.error("Failed to update mempool", exception)
          lastTxs = Seq.empty
        case Success(txs) =>
          //logger.info("Got request to rebuild mempool state")
          val mempoolChains = buildTotalMempoolState(txs)
          mempoolChains.foreach(c => mempoolCache.setMempoolChain(c._1, c._2))
          subscribers.foreach(_ ! UpdatedMempoolChains)
          lastTxs = txs
      }
  }

  /**
   * Build map of unique input utxoIds to the mempool chains they create
   * @param mempoolTxs Mempool Txs to build map of
   * @return Map of unique input utxoIds to chains of mempool txs. The key set of utxoIds
   *         are ids which are guaranteed to never be used as inputs for any txs besides
   *         the first of each chain (implicitly part of the latest UTXO set).
   */
  private def buildTotalMempoolState(mempoolTxs: Seq[BlockTx]): Map[String, MempoolChain] = {
    mempoolTxs.foldLeft(Map.empty[String, MempoolChain]){
      (z, t) =>
        val chainOpt = z.find(c => c._2.endId == t.inputs.head.id)
        chainOpt match {
          case Some(existingChain) =>
            z + (existingChain._1 -> MempoolChain(existingChain._2.transforms :+ MempoolTransform(t)))
          case None =>
            // There exists some tx whose index 0 utxoId is the same as this tx's index 0 utxoId
            val hasDuplicateInput = z
              .values
              .toSeq
              .flatMap(_.transforms)
              .exists(zT => zT.input.id == t.inputs.head.id)

            if(hasDuplicateInput) {
              // If duplicate input is the start of an actual chain, then we will just replace it
              if(z.contains(t.inputs.head.id)) {
                logger.warn(s"Replacing existing mempool chain for utxo ${t.inputs.head.id}")
                z + (t.inputs.head.id -> MempoolChain(Seq(MempoolTransform(t))))
              }else{
                logger.warn("Skipping mempool transform with duplicate mid-chain input")
                z
              }
            }else{
              // Add to map as unique starting utxoId if there is no output or input associated with it
              z + (t.inputs.head.id -> MempoolChain(Seq(MempoolTransform(t))))
            }
        }
    }
  }

  private def fetchTxs: Try[Seq[BlockTx]] =
    nodeApi.unconfirmedTransactions(Paging(0, MempoolView.MempoolLimit)).map(_.map(NodeSync.blockTx))
}
