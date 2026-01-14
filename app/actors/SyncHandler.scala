package actors

import akka.actor.{Actor, ActorRef}
import akka.pattern.ask
import akka.util.Timeout
import cache.RollupCache
import configs.NodeConfig
import lfsm.LFSMPhase
import lfsm.states.NISPTree
import org.ergoplatform.appkit.{BlockchainContext, ErgoClient, ErgoProver, ErgoValue}
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import play.api.cache.SyncCacheApi
import play.api.libs.concurrent.InjectedActorSupport
import sigma.data.AvlTreeFlags
import state.messages.{BlockInfo, BlockMessage, BlockTx}
import state.messages.RollupMessages._
import state.messages.SyncMessages._
import utils.{Globals, Helpers}
import work.lithos.plasma.PlasmaParameters
import work.lithos.plasma.collections.PlasmaMap

import javax.inject.{Inject, Named}
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import scala.util.{Failure, Success}

class SyncHandler @Inject()(config: Configuration, @Named("rollup-coordinator") rollupCoordinator: ActorRef,
                            cacheApi: SyncCacheApi) extends Actor with InjectedActorSupport {
  implicit val timeout: Timeout = 5 seconds

  private val logger: Logger          = LoggerFactory.getLogger("SyncHandler")
  val nodeConfig: NodeConfig          = Globals.getNodeConfig
  val client: ErgoClient              = nodeConfig.getClient
  val prover: ErgoProver              = nodeConfig.prover
  lazy val relErgoTrees: Seq[String]  = Helpers.rollupErgoTrees(client)
  val rollupCache: RollupCache            = RollupCache(cacheApi)
  import context.dispatcher


  override def receive: Receive = {
    case BlockMessage(blockInfo) =>
      logger.info(s"Got block message ${blockInfo.id} with height ${blockInfo.height}")

      val relevantTransactions = buildRelevantTransactions(blockInfo)
      relevantTransactions.transforms.foreach(rollupCoordinator ! _)
      relevantTransactions.genTxs.foreach(rollupCoordinator ! _)
    case GetSynced =>
      logger.info("Got initial sync message")
      context.become(postSync())
  }

  private def postSync(): Receive = {
    case BlockMessage(blockInfo) =>
      logger.info(s"Post-sync block message ${blockInfo.id} with height ${blockInfo.height}")
      val relevantTransactions = buildRelevantTransactions(blockInfo)
      relevantTransactions.transforms.foreach(rollupCoordinator ! _)
      relevantTransactions.genTxs.foreach(rollupCoordinator ! _)
      sender() ! HandledBlock(blockInfo)
    case GetSynced =>
      val originalSender = sender()
      (rollupCoordinator ? GetSynced).mapTo[SyncMessage].onComplete {
        case Failure(exception) =>
          // match errors
          logger.error("Got error while asking for sync", exception)
        case Success(msg) =>
          originalSender ! msg
      }
    case removeTree: RemoveRollup =>
      logger.info(s"Removing tree ${removeTree.blockId} due to: ${removeTree.reason}")
      rollupCoordinator ! removeTree
  }
  // efficiency matters here
  private def buildRelevantTransactions(blockInfo: BlockInfo): RelevantTransactions = {
    client.execute{
      ctx =>
        blockInfo.txs.foldLeft(RelevantTransactions.empty){
          (rel, tx) =>
            val transformInput = relevantInput(tx)
            transformInput match {
              case Some(_) =>
                rel.copy(transforms = rel.transforms ++ Seq(Transform(blockInfo, tx)))
              case None =>
                val optGenesis = makeGenesis(ctx, blockInfo, tx)
                if(optGenesis.isDefined){
                  rel.copy(genTxs = rel.genTxs ++ Seq(Genesis(optGenesis.get, blockInfo)))
                }else if(isRelevant(tx)){
                  rel.copy(transforms = rel.transforms ++ Seq(Transform(blockInfo, tx)))
                }else{
                  rel
                }
            }
        }
    }
  }

  private def makeGenesis(ctx: BlockchainContext, blockInfo: BlockInfo, tx: BlockTx): Option[NISPTree] = {
    // Is holding contract
    tx.outputs.find(o => o.ergoTree == relErgoTrees.head).flatMap{
      output =>
        val numMiners = output.registers.applyOrElse(1, "none")
        numMiners match {
          case "none" =>
            logger.warn("Got malformed holding output")
            None
          case ergoVal: String =>
            //TODO: Add checks for collateral utxo and others to prevent sync issues from malformed transactions
            if (ergoVal == ErgoValue.of(0).toHex) {
              val holdingBox = output.toInput(ctx)
              val newTree    = PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.AllOperationsAllowed, PlasmaParameters.default)
              Some(
                NISPTree(newTree, 0, BigInt(0), Some(holdingBox.registers(3).getValue.asInstanceOf[Long]),
                  holdingBox.value, holdingBox.registers(3).getValue.asInstanceOf[Long].toInt, hasMiner = false, LFSMPhase.HOLDING,
                  blockId = blockInfo.id, utxoId = output.id)
              )
            } else {
              //logger.warn("Got malformed holding output")
              None
            }
        }
    }
  }
  private def isRelevant(tx: BlockTx): Boolean = {
    relErgoTrees.contains(tx.outputs.head.ergoTree)
  }
  // TODO: Analyze closely for potentially dropped blocks

  private def relevantInput(tx: BlockTx): Option[String] = {
    rollupCache.getTreeSet.find(_ == tx.inputs.head.id)
  }

}
