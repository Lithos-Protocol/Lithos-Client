package actors

import akka.actor.Actor
import cache.MDCache
import configs.NodeConfig
import lfsm.LFSMHelpers
import lfsm.states.MinerTree
import mutations.NodeWallet
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{BlockchainContext, ErgoClient, ErgoProver, ErgoValue}
import org.slf4j.{Logger, LoggerFactory}
import play.api.cache.SyncCacheApi
import play.api.libs.concurrent.InjectedActorSupport
import sigma.ast.ErgoTree
import sigma.{Coll, SigmaProp}
import state.messages.DictionaryMessages.{DictionaryTransform, InitialState}
import state.messages.SyncMessages.{GetSynced, HandledBlock}
import state.messages.{BlockInfo, BlockMessage}
import utils.Globals
import work.lithos.mutations.Contract

import javax.inject.Inject
import scala.util.{Failure, Success, Try}

class MDSynchronizer @Inject()(cacheApi: SyncCacheApi) extends Actor with InjectedActorSupport {
  val logger: Logger = LoggerFactory.getLogger("MDSynchronizer")
  val nodeConfig: NodeConfig  = Globals.getNodeConfig
  val client: ErgoClient      = nodeConfig.getClient
  val prover: NodeWallet      = nodeConfig.getNodeWallet
  val mdCache: MDCache = MDCache(cacheApi)


  override def receive: Receive = {
    case InitialState(height, md) =>
      logger.info(s"Got ${InitialState(height, md)}")
      mdCache.setMD(md)
      context.become(initialSync(height, md.utxoId))
  }

  def initialSync(height: Int, utxoId: String): Receive = {
    case transform: DictionaryTransform =>
      val tree = mdCache.getMD.get
      val nextTree = Try {
        client.execute{
          ctx =>
            matchTransform(tree, transform, ctx, utxoId)
        }
      }
      nextTree match {
        case Failure(exception) =>
          logger.error(s"Preserving current state due to application failure on transform $transform")
          logger.error("Reason: ", exception)
          context.become(initialSync(height, utxoId))
        case Success(newMinerTree) =>
          context.become(initialSync(transform.blockInfo.height, newMinerTree.utxoId))
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
      val tree = mdCache.getMD.get
      val nextTree = Try {
        client.execute{
          ctx =>
            matchTransform(tree, transform, ctx, utxoId)
        }
      }
      nextTree match {
        case Failure(exception) =>
          logger.error(s"Preserving current state due to application failure on transform $transform")
          logger.error("Reason: ", exception)
          context.become(postSync(height, utxoId))
        case Success(newMinerTree) =>
          context.become(postSync(transform.blockInfo.height, newMinerTree.utxoId))
      }
    case blockMsg: BlockMessage =>
      val relTxs = getRelevantTxs(blockMsg.blockInfo)
      relTxs.foreach(self ! _)

      if(relTxs.nonEmpty)
        logger.info(s"Pre-applied ${relTxs.length} transforms from block ${blockMsg.blockInfo.height}")
      sender() ! HandledBlock(blockMsg.blockInfo)

  }

  private def getRelevantTxs(blockInfo: BlockInfo): Seq[DictionaryTransform] = {
    val mdToken = LFSMHelpers.getMDToken(client)
    blockInfo.txs.foldLeft(Seq.empty[DictionaryTransform]){
      (rel, tx) =>
        if(tx.outputs.head.id != LFSMHelpers.MD_GENESIS_ID) {
          if (tx.outputs.head.assets.headOption.exists(t => t.id == mdToken)) {
            rel :+ DictionaryTransform(blockInfo, tx, mdToken.toString)
          } else {
            rel
          }
        }else{
          rel // Skip genesis tx as it is not a proper transform
        }
    }
  }

  private def matchTransform(tree: MinerTree, transform: DictionaryTransform, ctx: BlockchainContext, utxoId: String): MinerTree = {
    if(utxoId == transform.input.id) {
      val operation = ErgoValue.fromHex(transform.input.spendingProof.get.ext("0")).getValue.asInstanceOf[Byte]
      val minerExists = tree.hasMiner
      operation match {
        case MinerTree.ADD_MINER_OP =>
          val nextTree = addMinerTransform(tree, transform, ctx: BlockchainContext)
          if (!minerExists && nextTree.hasMiner) {
            Globals.mdDB.setDataBoxToken(transform.tx.outputs(1).assets.head.id)
          }
          mdCache.setMD(nextTree)
          nextTree
        case MinerTree.REMOVE_MINER_OP =>
          val nextTree = removeMinerTransform(tree, transform, ctx: BlockchainContext)
          if (minerExists && !nextTree.hasMiner) {
            Globals.mdDB.delDataBoxToken
          }
          mdCache.setMD(nextTree)
          nextTree
      }
    }else{
      logger.warn(s"Skipping out of order transform ${transform} with real utxoId ${utxoId}")
      tree
    }
  }

  private def addMinerTransform(tree: MinerTree, transform: DictionaryTransform, ctx: BlockchainContext) = {
      val nextDataBox = transform.tx.outputs(1)
      val dataBoxOptions = ErgoValue.fromHex(nextDataBox.registers(1)).getValue.asInstanceOf[Coll[Byte]].toArray
      val proverContract = prover.contract.hashedPropBytes
      val dataBoxMiner = dataBoxOptions.slice(0, 32)
      val isLocalMiner = dataBoxMiner sameElements proverContract
      val dict = tree.dictionary
      val nextToken = nextDataBox.assets.head
      val sigmaProp = ErgoValue.fromHex(transform.input.spendingProof.get.ext("1")).getValue.asInstanceOf[SigmaProp]
      val kv = ErgoValue.fromHex(transform.input.spendingProof.get.ext("2")).getValue.asInstanceOf[(Coll[Byte], Coll[Byte])]
      val proof = ErgoValue.fromHex(transform.input.spendingProof.get.ext("3")).getValue.asInstanceOf[Coll[Byte]]
      val miner = new Contract(ErgoTree.fromProposition(sigmaProp))
      val insertion = dict.insert(kv._1.toArray -> kv._2.toArray)
      require(insertion.proof.ergoValue.getValue == proof, "Proofs must be equal on add miner transform")
      require(miner.hashedPropBytes sameElements kv._1.toArray, "Key and signer must be equal on add miner transform")


      if (isLocalMiner) {
        logger.info(s"Applied transform ${transform.tx.id} for addition of local miner ${miner.address(ctx)}")
      } else {
        logger.info(s"Applied transform ${transform.tx.id} for addition of miner ${miner.address(ctx)}")
      }
      tree.copy(numMiners = tree.numMiners + 1, minerMap = tree.minerMap + (Hex.toHexString(kv._1.toArray) -> (miner.address(ctx), nextToken.id)),
        hasMiner = isLocalMiner, utxoId = transform.output.id, savedHeight = transform.blockInfo.height)
  }

  private def removeMinerTransform(tree: MinerTree, transform: DictionaryTransform, ctx: BlockchainContext) = {
    val dataBox = transform.tx.inputs(1)

    val proverContract = prover.contract.hashedPropBytes


    val dict = tree.dictionary

    val sigmaProp = ErgoValue.fromHex(dataBox.spendingProof.get.ext("1")).getValue.asInstanceOf[SigmaProp]
    val lookupProof = ErgoValue.fromHex(dataBox.spendingProof.get.ext("2")).getValue.asInstanceOf[Coll[Byte]]
    val removalProof = ErgoValue.fromHex(dataBox.spendingProof.get.ext("3")).getValue.asInstanceOf[Coll[Byte]]
    val miner = new Contract(ErgoTree.fromProposition(sigmaProp))
    val lookUp = dict.lookUp(miner.hashedPropBytes)
    val removal = dict.delete(miner.hashedPropBytes)


    require(lookUp.proof.ergoValue.getValue == lookupProof, "Lookup proofs must be equal on remove miner transform")
    require(removal.proof.ergoValue.getValue == removalProof, "Removal proofs must be equal on remove miner transform")
    val isLocalMiner = miner.hashedPropBytes sameElements proverContract
    if(isLocalMiner){
      logger.info(s"Applied transform ${transform.tx.id} for removal of" +
        s" local miner ${miner.address(ctx)} with data box ${Hex.toHexString(lookUp.response.head.get)}")
    }else{
      logger.info(s"Applied transform ${transform.tx.id} for removal of" +
        s" miner ${miner.address(ctx)} with data box ${Hex.toHexString(lookUp.response.head.get)}")
    }
    tree.copy(numMiners = tree.numMiners-1, minerMap = tree.minerMap - (Hex.toHexString(miner.hashedPropBytes)),
      hasMiner = tree.hasMiner && !isLocalMiner, utxoId = transform.output.id, savedHeight = transform.blockInfo.height)
  }

}
