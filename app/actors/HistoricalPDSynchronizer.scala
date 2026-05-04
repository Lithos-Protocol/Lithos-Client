package actors

import akka.actor.Actor
import cache.PDCache
import configs.NodeConfig
import mutations.NodeWallet
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{BlockchainContext, ErgoClient, ErgoValue}
import org.slf4j.{Logger, LoggerFactory}
import plasmadex.PDHelpers
import plasmadex.states.LiquidityState
import play.api.cache.SyncCacheApi
import play.api.libs.concurrent.InjectedActorSupport
import scorex.utils.Longs
import sigma.Coll
import state.messages.DictionaryMessages.{DictionaryTransform, InitialLiqState}
import state.messages.SyncMessages.{GetSynced, HandledBlock}
import state.messages.{BlockInfo, BlockMessage}
import utils.Globals

import javax.inject.Inject
import scala.util.{Failure, Success, Try}

/**
 * Synchronizes Plasma Dex dictionary up to a certain digest, then resets state. Used for fee reward claims.
 * @param cacheApi
 */
class HistoricalPDSynchronizer @Inject()(cacheApi: SyncCacheApi) extends Actor with InjectedActorSupport {
  val logger: Logger = LoggerFactory.getLogger("HistoricalPDSynchronizer")
  val nodeConfig: NodeConfig  = Globals.getNodeConfig
  val client: ErgoClient      = nodeConfig.getClient
  val prover: NodeWallet      = nodeConfig.getNodeWallet
  val pdCache: PDCache = PDCache(cacheApi)


  override def receive: Receive = {
    case InitialLiqState(height, ls) =>
      logger.info(s"Got ${InitialLiqState(height, ls)}")
      pdCache.setHistoricalPD(ls)
      context.become(initialSync(height, ls.utxoId))
  }

  def initialSync(height: Int, utxoId: String): Receive = {
    case transform: DictionaryTransform =>
      val ls = pdCache.getHistoricalPD.get
      val nextLS = Try {
        client.execute{
          ctx =>
            matchTransform(ls, transform, ctx, utxoId)
        }
      }
      nextLS match {
        case Failure(exception) =>
          logger.error(s"Preserving current state due to application failure on transform $transform")
          logger.error("Reason: ", exception)
          context.become(initialSync(height, utxoId))
        case Success(newLiqState) =>
          context.become(initialSync(transform.blockInfo.height, newLiqState.utxoId))
      }
    case blockMsg: BlockMessage =>
      val relTxs = getRelevantTxs(blockMsg.blockInfo)
      relTxs.foreach(self ! _)
      if(relTxs.nonEmpty)
        logger.info(s"Pre-applied ${relTxs.length} transforms from block ${blockMsg.blockInfo.height}")
    case GetSynced =>
      logger.info("Got initial sync message")
      context.become(postSync(height, utxoId))

  }

  def postSync(height: Int, utxoId: String): Receive = {
    case transform: DictionaryTransform =>
      val ls = pdCache.getHistoricalPD.get
      val nextLS = Try {
        client.execute{
          ctx =>
            matchTransform(ls, transform, ctx, utxoId)
        }
      }
      nextLS match {
        case Failure(exception) =>
          logger.error(s"Preserving current state due to application failure on transform $transform")
          logger.error("Reason: ", exception)
          context.become(postSync(height, utxoId))
        case Success(newLiqState) =>
          context.become(postSync(transform.blockInfo.height, newLiqState.utxoId))
      }
    case blockMsg: BlockMessage =>
      val relTxs = getRelevantTxs(blockMsg.blockInfo)
      relTxs.foreach(self ! _)

      if(relTxs.nonEmpty)
        logger.info(s"Pre-applied ${relTxs.length} transforms from block ${blockMsg.blockInfo.height}")
      sender() ! HandledBlock(blockMsg.blockInfo)

  }

  private def getRelevantTxs(blockInfo: BlockInfo): Seq[DictionaryTransform] = {
    val lpToken = PDHelpers.getLPToken(client)
    blockInfo.txs.foldLeft(Seq.empty[DictionaryTransform]){
      (rel, tx) =>
        if(tx.outputs.head.id != PDHelpers.LP_GENESIS_ID) {
          if (tx.outputs.head.assets.headOption.exists(t => t.id == lpToken)) {
              rel :+ DictionaryTransform(blockInfo, tx, lpToken.toString)
            } else {
              rel
            }
        }else{
          rel // Skip genesis tx as it is not a proper transform
        }
    }
  }

  private def matchTransform(ls: LiquidityState, transform: DictionaryTransform, ctx: BlockchainContext, utxoId: String): LiquidityState = {
    if(utxoId == transform.input.id) {
      val operation = ErgoValue.fromHex(transform.input.spendingProof.get.ext("0")).getValue.asInstanceOf[Byte]

      operation match {
        case LiquidityState.DEP_OP =>
          val nextLS = depositTransform(ls, transform, ctx)
//          if (!minerExists && nextTree.hasMiner) {
//            Globals.mdDB.setDataBoxToken(transform.tx.outputs(1).assets.head.id)
//          }
          pdCache.setHistoricalPD(nextLS)
          nextLS
        case LiquidityState.RED_OP =>
          val nextLS = redemptionTransform(ls, transform, ctx: BlockchainContext)
//          if (minerExists && !nextTree.hasMiner) {
//            Globals.mdDB.delDataBoxToken
//          }
          pdCache.setHistoricalPD(nextLS)
          nextLS
        case LiquidityState.SWAP_OP =>
          val nextLS = swapTransform(ls, transform)
          pdCache.setHistoricalPD(nextLS)
          nextLS
        case LiquidityState.WITHDRAW_OP =>
          val nextLS = withdrawTransform(ls, transform)
          pdCache.setHistoricalPD(nextLS)
          nextLS
      }
    }else{
      logger.warn(s"Skipping out of order transform ${transform} with real utxoId ${utxoId}")
      ls
    }
  }

  private def depositTransform(ls: LiquidityState, transform: DictionaryTransform, ctx: BlockchainContext) = {

    val dict = ls.dictionary
    val nextLiq = ErgoValue.fromHex(transform.output.registers(1)).getValue.asInstanceOf[Long]
    val deltaLPSupply = (PDHelpers.MAX_LIQ - ls.liquidity) - (PDHelpers.MAX_LIQ - nextLiq)
    val kv = ErgoValue.fromHex(transform.input.spendingProof.get.ext("1")).getValue.asInstanceOf[(Coll[Byte], Coll[Byte])]
    val proof = ErgoValue.fromHex(transform.input.spendingProof.get.ext("2")).getValue.asInstanceOf[Coll[Byte]]

    val insertion = dict.insert(kv._1.toArray -> Longs.toByteArray(deltaLPSupply))
    require(insertion.proof.ergoValue.getValue == proof, "Proofs must be equal on deposit transform")
    require(Hex.decode(ls.utxoId) sameElements kv._1.toArray, "Key must be equal on deposit transform")
    require(deltaLPSupply == Longs.fromByteArray(kv._2.toArray), "Value must be equal on deposit transform")
    logger.info(s"Applied transform ${transform.tx.id} for deposit of $deltaLPSupply liquidity")
    ls.copy(numProvisions = ls.numProvisions + 1, liquidity = nextLiq, utxoId = transform.output.id, syncHeight = transform.blockInfo.height)
  }

  private def redemptionTransform(ls: LiquidityState, transform: DictionaryTransform, ctx: BlockchainContext) = {
    val dict = ls.dictionary
    val nextLiq = ErgoValue.fromHex(transform.output.registers(1)).getValue.asInstanceOf[Long]
    val deltaLPSupply = (PDHelpers.MAX_LIQ - ls.liquidity) - (PDHelpers.MAX_LIQ - nextLiq)
    val key = ErgoValue.fromHex(transform.input.spendingProof.get.ext("1")).getValue.asInstanceOf[Coll[Byte]]
    val lookUpProof = ErgoValue.fromHex(transform.input.spendingProof.get.ext("2")).getValue.asInstanceOf[Coll[Byte]]
    val removalProof = ErgoValue.fromHex(transform.input.spendingProof.get.ext("3")).getValue.asInstanceOf[Coll[Byte]]

    val lookUp = dict.lookUp(key.toArray)
    val removal = dict.delete(key.toArray)
    require(lookUp.proof.ergoValue.getValue == lookUpProof, "Proofs must be equal on redemption transform")
    require(removal.proof.ergoValue.getValue == removalProof, "Proofs must be equal on redemption transform")

    logger.info(s"Applied transform ${transform.tx.id} for redemption of ${-deltaLPSupply} liquidity")
    ls.copy(numProvisions = ls.numProvisions - 1, liquidity = nextLiq,
      utxoId = transform.output.id, syncHeight = transform.blockInfo.height)
  }

  private def swapTransform(ls: LiquidityState, transform: DictionaryTransform): LiquidityState = {
    logger.info(s"Applied swap transform ${transform.tx.id}")
    val nextLSFees = ErgoValue.fromHex(transform.output.registers(3)).getValue.asInstanceOf[Coll[Long]].toArray
    ls.copy(lsFeesX = nextLSFees.head, lsFeesY = nextLSFees(1), utxoId = transform.output.id, syncHeight = transform.blockInfo.height)
  }

  private def withdrawTransform(ls: LiquidityState, transform: DictionaryTransform): LiquidityState = {
    logger.info(s"Applied withdraw transform ${transform.tx.id}")
    ls.copy(lsFeesX = 0, lsFeesY = 0, utxoId = transform.output.id, syncHeight = transform.blockInfo.height)
  }

}
