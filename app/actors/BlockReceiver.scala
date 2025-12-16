package actors

import actors.BlockReceiver.{Genesis, RelevantTransactions, Transform}
import akka.actor.{Actor, ActorRef}
import akka.util.Timeout
import configs.NodeConfig
import lfsm.{LFSMPhase, NISPTree}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{BlockchainContext, ErgoClient, ErgoProver, ErgoValue}
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import play.api.cache.SyncCacheApi
import play.api.libs.concurrent.InjectedActorSupport
import sigma.data.AvlTreeFlags
import state.{BlockInfo, BlockTx, TxInput, TxOutput}
import utils.Helpers.{compileEval, compileHolding, compilePayout}
import utils.{Helpers, TreeCache}
import work.lithos.plasma.PlasmaParameters
import work.lithos.plasma.collections.PlasmaMap

import javax.inject.{Inject, Named}
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

class BlockReceiver @Inject() (config: Configuration, @Named("sync-coordinator") syncCoordinator: ActorRef,
                               cacheApi: SyncCacheApi) extends Actor with InjectedActorSupport {
  implicit val timeout: Timeout = 10 seconds

  val logger: Logger                  = LoggerFactory.getLogger("BlockReceiver")
  val nodeConfig: NodeConfig          = new NodeConfig(config)
  val client: ErgoClient              = nodeConfig.getClient
  val prover: ErgoProver              = nodeConfig.prover
  lazy val relErgoTrees: Seq[String]  = Helpers.relevantErgoTrees(client)
  val treeCache: TreeCache            = TreeCache(cacheApi)

  override def receive: Receive = {
    case BlockMessage(blockInfo) =>
      logger.info(s"Got block message ${blockInfo.id} with height ${blockInfo.height}")

      val relevantTransactions = buildRelevantTransactions(blockInfo)
      relevantTransactions.transforms.foreach(syncCoordinator ! _)
      relevantTransactions.genTxs.foreach(syncCoordinator ! _)
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

  private def relevantInput(tx: BlockTx): Option[NISPTree] = {
    treeCache.get(tx.inputs.head.id)
  }

}

object BlockReceiver {
  case class Genesis(tree: NISPTree, blockInfo: BlockInfo)
  case class RelevantTransactions(genTxs: Seq[Genesis], transforms: Seq[Transform])
  case class Transform(blockInfo: BlockInfo, tx: BlockTx) {
    val input: TxInput   = tx.inputs.head
    val output: TxOutput = tx.outputs.head
    override def toString: String = s"Transform(${blockInfo.height}: ${input.id} => ${output.id})"
  }
  case class StopTracking(utxoId: String, blockId: String)

  object RelevantTransactions {
    def empty = RelevantTransactions(Seq.empty[Genesis], Seq.empty[Transform])
  }
}
