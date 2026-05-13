package actors


import akka.actor.Actor
import cache.RollupCache
import com.google.inject.assistedinject.Assisted
import lfsm.states.NISPTree
import lfsm.{LFSMHelpers, LFSMPhase}
import mutations.NodeWallet
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{Address, BlockchainContext, ErgoProver, ErgoValue}
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import play.api.cache.SyncCacheApi
import scorex.utils.Longs
import sigma.ast.ErgoTree
import sigma.data.CBigInt
import sigma.{Coll, SigmaProp}
import state.messages.MempoolMessages.{MempoolChain, MempoolRollupState, MempoolTransform}
import state.messages.{DictionaryMessages, MempoolMessages}
import state.messages.RollupMessages._
import state.messages.SyncMessages.Transform
import utils.{Globals, Helpers, PayoutRecord}
import work.lithos.mutations.{Contract, InputUTXO}

import javax.inject.Inject

class RollupSynchronizer @Inject()(config: Configuration, @Assisted rollupBlockId: String, @Assisted ctx: BlockchainContext, @Assisted prover: NodeWallet, cacheApi: SyncCacheApi) extends Actor {
  val logger: Logger = LoggerFactory.getLogger("RollupSynchronizer-" + rollupBlockId.slice(0, 12))
  val treeCache: RollupCache = RollupCache(cacheApi)


  val relErgoTrees: Seq[String] = Helpers.rollupErgoTrees(ctx)

  override def receive: Receive = {
    case Genesis(tree, _) =>
      logger.info(s"Generated rollup $rollupBlockId with utxo ${tree.utxoId}")
      treeCache.addToTreeCache(tree.utxoId, tree)
      context.become(postInit(tree.utxoId))
  }

  def postInit(utxoId: String): Receive = {
    case transform: RollupTransform =>
      val treeOpt = treeCache.get(utxoId)
      if(treeOpt.isDefined){
        val nextTree = matchTransform(treeOpt.get, transform)
        context.become(postInit(nextTree.utxoId))
      }else{
        // This should be impossible, but in case it does happen we should
        // be loud about it, as ensuring the cached rollup state matches its corresponding
        // actor is critical
        logger.error(s"Lost tracking for rollup $rollupBlockId with bad utxoId $utxoId")
        throw new IllegalStateException(s"Rollup $rollupBlockId has no matching cache state")
      }

    case mempoolChain: MempoolChain =>
      val treeOpt = treeCache.get(utxoId)
      if(treeOpt.isDefined) {
        val tree = treeOpt.get
        val nextTree = mempoolChain.transforms.foldLeft(tree) {
          (a, m) =>
            if (a.utxoId == m.input.id) {
              matchTransform(a, m)
            } else
              a
        }
        val asInput = mempoolChain.transforms.last.output.toInput(ctx)
        if (mempoolChain.transforms.last.output.id == nextTree.utxoId) {
          //logger.info(s"Completed mempool chain ${mempoolChain}")
          treeCache.setMempoolState(rollupBlockId, MempoolRollupState(asInput, nextTree))
        } else {
          // If transformations did not reach the end of the mempool sequence, then
          // tracking was likely stopped in the middle, meaning this rollup will be removed in
          // one the upcoming transforms.
          logger.info(s"Rollup will be removed in upcoming mempool chain ${mempoolChain}")
          treeCache.setMempoolState(rollupBlockId, MempoolRollupState(asInput, nextTree, toBeRemoved = true))
        }
      }else {
        // This should be impossible, but in case it does happen we should
        // be loud about it, as ensuring the cached rollup state matches its corresponding
        // actor is critical
        logger.error(s"Lost tracking for rollup $rollupBlockId with bad utxoId $utxoId")
        throw new IllegalStateException(s"Rollup $rollupBlockId has no matching cache state")
      }
  }

  private def matchTransform(tree: NISPTree, transform: Transform): NISPTree = {
    val phase = tree.phase
    val isMempool = transform.isInstanceOf[MempoolTransform]
    phase match {
      case LFSMPhase.HOLDING =>
        transform.output.ergoTree match {
          case hContract if hContract == relErgoTrees.head =>
            submissionTransform(tree, transform, isMempool)
          case eContract if eContract == relErgoTrees(1) =>
            holdingTransform(tree, transform, isMempool)
        }
      case LFSMPhase.EVAL =>
        transform.output.ergoTree match {
          case eContract if eContract == relErgoTrees(1) =>
            fraudProofTransform(tree, transform, isMempool)
          case pContract if pContract == relErgoTrees(2) =>
            evalTransform(tree, transform, isMempool)
        }
      case LFSMPhase.PAYOUT =>
        transform match {
          case mempool: MempoolTransform =>
            mempoolPayoutTransform(tree, mempool)
          case rollup: RollupTransform =>
           rollupPayoutTransform(tree, rollup)
        }

    }
  }

  private def submissionTransform(tree: NISPTree, transform: Transform, isMempool: Boolean): NISPTree = {

    val holdingBox = transform.output.toInput(ctx)
    val dict = {
      if(!isMempool) {
        tree.dictionary
      } else {
        val tmpDict = tree.dictionary.copy()
        tmpDict.prover.generateProof()
        tmpDict
      }
    }

    val nextMiners = holdingBox.registers(1).getValue.asInstanceOf[Int]
    val nextScore = holdingBox.registers(2).getValue.asInstanceOf[CBigInt].wrappedValue
    val nextPeriod = holdingBox.registers(3).getValue.asInstanceOf[Long]

    val signer = ErgoValue.fromHex(transform.input.spendingProof.get.ext("0")).getValue.asInstanceOf[SigmaProp]
    val keyValue = ErgoValue.fromHex(transform.input.spendingProof.get.ext("1")).getValue.asInstanceOf[(Coll[Byte], Coll[Byte])]
    val proof = ErgoValue.fromHex(transform.input.spendingProof.get.ext("2")).getValue.asInstanceOf[Coll[Byte]]

    val isMiner = Helpers.pkHexFromSigmaProp(signer).get == Helpers.pkHexFromBoolean(
      prover.contract.sigmaBoolean.get).get
    val insertion = dict.insert(keyValue._1.toArray -> keyValue._2.toArray)
    require(insertion.proof.ergoValue.getValue == proof, "Proofs must be equal on submission transform")

    val nextMinerSet = tree.minerSet ++ Set(Hex.toHexString(keyValue._1.toArray))
    val nextTree = tree.copy(dictionary = dict, numMiners = nextMiners, totalScore = nextScore,
      currentPeriod = Some(nextPeriod), hasMiner = isMiner || tree.hasMiner, minerSet = nextMinerSet,
      utxoId = transform.output.id)
    if(!isMempool)
      treeCache.updateTreeCache(transform.input.id, nextTree.utxoId, nextTree)

    if (isMiner) {
      logger.info(s"Applied submission transform ${transform.tx.id} with $transform for local miner")
    } else {
      logger.info(s"Applied submission transform ${transform.tx.id} with $transform for miner" +
        s" ${Address.fromErgoTree(ErgoTree.fromProposition(signer), ctx.getNetworkType)}")
    }
    nextTree

  }

  private def holdingTransform(tree: NISPTree, transform: Transform, isMempool: Boolean): NISPTree = {

    val evalBox = transform.output.toInput(ctx)
    if (tree.hasMiner) {
      val nispTree = tree.copy(currentPeriod = Some(evalBox.registers(3).getValue.asInstanceOf[Long]),
        phase = LFSMPhase.EVAL, utxoId = transform.output.id)
      if(!isMempool) {
        treeCache.updateTreeCache(transform.input.id, nispTree.utxoId, nispTree)
      }
      logger.info(s"Applied holding transform ${transform.tx.id} with $transform")
      nispTree
    } else {
      stopSync("No local miner in holding transform", tree, isMempool)
    }
  }

  private def fraudProofTransform(tree: NISPTree, transform: Transform, isMempool: Boolean): NISPTree = {

    val fpInput = transform.tx.inputs(1)

    if (tree.hasMiner) {
      val dict = {
        if(!isMempool) {
          tree.dictionary
        } else {
          val tmpDict = tree.dictionary.copy()
          tmpDict.prover.generateProof()
          tmpDict
        }
      }

      val miner = ErgoValue.fromHex(fpInput.spendingProof.get.ext("0")).getValue.asInstanceOf[Coll[Byte]]
      val lookProof = ErgoValue.fromHex(fpInput.spendingProof.get.ext("1")).getValue.asInstanceOf[Coll[Byte]]
      val delProof = ErgoValue.fromHex(fpInput.spendingProof.get.ext("2")).getValue.asInstanceOf[Coll[Byte]]

      val lookUp = dict.lookUp(miner.toArray)
      require(lookUp.proof.ergoValue.getValue == lookProof, "Lookup proofs must be equal on fraud proof transform")
      val delete = dict.delete(miner.toArray)
      require(delete.proof.ergoValue.getValue == delProof, "Removal proofs must be equal on fraud proof transform")
      val minerScore = Longs.fromByteArray(lookUp.response.head.get.slice(0, 8))
      val removedMiner =
        Hex.toHexString(miner.toArray) == prover.contract.hashedPropBytesHex
      val nextTotalScore = tree.totalScore - minerScore
      val nextMinerSet = tree.minerSet -- Set(Hex.toHexString(miner.toArray))
      val nispTree = {
        if (removedMiner)
          tree.copy(dictionary = dict, hasMiner = false, totalScore = nextTotalScore, minerSet = nextMinerSet,
            utxoId = transform.output.id)
        else
          tree.copy(dictionary = dict, minerSet = nextMinerSet, totalScore = nextTotalScore,
            utxoId = transform.output.id)
      }
      if(!isMempool)
        treeCache.updateTreeCache(transform.input.id, nispTree.utxoId, nispTree)
      logger.info(s"Applied fraud proof transform ${transform.tx.id} with $transform")
      nispTree
    } else {
      stopSync("No local miner in fraud proof transform", tree, isMempool)
    }
  }

  private def evalTransform(tree: NISPTree, transform: Transform, isMempool: Boolean): NISPTree = {
    if (tree.hasMiner) {
      val nispTree = tree.copy(currentPeriod = None, phase = LFSMPhase.PAYOUT, utxoId = transform.output.id)
      if(!isMempool)
        treeCache.updateTreeCache(transform.input.id, nispTree.utxoId, nispTree)
      logger.info(s"Applied evaluation transform ${transform.tx.id} with $transform")
      nispTree
    } else {
      stopSync("No local miner in evaluation transform", tree, isMempool)
    }
  }

  private def rollupPayoutTransform(tree: NISPTree, transform: RollupTransform): NISPTree = {
    if (tree.hasMiner) {
      val dict = tree.dictionary
      val input = transform.input
      val miners = ErgoValue.fromHex(input.spendingProof.get.ext("0")).getValue.asInstanceOf[Coll[Coll[Byte]]]
      val lookProof = ErgoValue.fromHex(input.spendingProof.get.ext("1")).getValue.asInstanceOf[Coll[Byte]]
      val delProof = ErgoValue.fromHex(input.spendingProof.get.ext("2")).getValue.asInstanceOf[Coll[Byte]]

      val paidMiner = miners.toArray.map(_.toArray).exists {
        a => Hex.toHexString(a) == prover.contract.hashedPropBytesHex
      }
      if (paidMiner) {
        val payoutOutput = transform.tx.outputs
          .find(_.ergoTree == prover.contract.ergoTreeHex)
          .get.toUTXO(ctx)
        val payoutRecord = PayoutRecord(transform.tx.id, payoutOutput.value, LFSMHelpers.scoreFromPayment(payoutOutput.value, tree.totalScore, tree.totalReward),
          input.id, rollupBlockId, tree.startHeight, transform.blockInfo.height)
        val payoutSet = cacheApi.getOrElseUpdate[Seq[PayoutRecord]](Globals.TRACKED_PAYOUTS)(Seq.empty[PayoutRecord])
        cacheApi.set(Globals.TRACKED_PAYOUTS, payoutSet ++ Seq(payoutRecord))
        stopSync("Local miner was paid", tree, isMempool = false)
      } else {
        val lookUp = dict.lookUp(miners.toArray.map(_.toArray): _*)
        require(lookUp.proof.ergoValue.getValue == lookProof, "Lookup proofs must be equal on payout transformation")
        val delete = dict.delete(miners.toArray.map(_.toArray): _*)
        require(delete.proof.ergoValue.getValue == delProof, "Removal proofs must be equal on payout transformation")

        if (transform.output.ergoTree == Helpers.payoutContract(ctx).ergoTreeHex) {
          val nispTree = tree.copy(dictionary = dict, utxoId = transform.output.id)

          treeCache.updateTreeCache(transform.input.id, nispTree.utxoId, nispTree)
          logger.info(s"Applied payout transform ${transform.tx.id} with $transform")
          nispTree
        } else {
          logger.warn("Removing tree with miner before miner was paid")
          stopSync("No payout contract output in transform", tree, isMempool = false)
        }
      }
    } else {
      stopSync("No local miner in payout transform", tree, isMempool = false)
    }
  }

  private def mempoolPayoutTransform(tree: NISPTree, transform: MempoolTransform): NISPTree = {
    if (tree.hasMiner) {
      val dict = tree.dictionary.copy()
      dict.prover.generateProof()
      val input = transform.input
      val miners = ErgoValue.fromHex(input.spendingProof.get.ext("0")).getValue.asInstanceOf[Coll[Coll[Byte]]]
      val lookProof = ErgoValue.fromHex(input.spendingProof.get.ext("1")).getValue.asInstanceOf[Coll[Byte]]
      val delProof = ErgoValue.fromHex(input.spendingProof.get.ext("2")).getValue.asInstanceOf[Coll[Byte]]

      val paidMiner = miners.toArray.map(_.toArray).exists {
        a => Hex.toHexString(a) == prover.contract.hashedPropBytesHex
      }
      if (paidMiner) {
        stopSync("Local miner was paid", tree, isMempool = true)
      } else {
        val lookUp = dict.lookUp(miners.toArray.map(_.toArray): _*)
        require(lookUp.proof.ergoValue.getValue == lookProof, "Lookup proofs must be equal on payout transformation")
        val delete = dict.delete(miners.toArray.map(_.toArray): _*)
        require(delete.proof.ergoValue.getValue == delProof, "Removal proofs must be equal on payout transformation")

        if (transform.output.ergoTree == Helpers.payoutContract(ctx).ergoTreeHex) {
          val nispTree = tree.copy(dictionary = dict, utxoId = transform.output.id)

          logger.info(s"Applied payout transform ${transform.tx.id} with $transform")
          nispTree
        } else {
          logger.warn("Removing tree with miner before miner was paid")
          stopSync("No payout contract output in transform", tree, isMempool = true)
        }
      }
    } else {
      stopSync("No local miner in payout transform", tree, isMempool = true)
    }
  }

  private def stopSync(reason: String, tree: NISPTree, isMempool: Boolean): NISPTree = {
    if(!isMempool) {
      logger.info("Stopped sync due to: " + reason)
      context.stop(self)
      treeCache.remove(tree.utxoId)
      sender() ! StopTracking(tree.utxoId, rollupBlockId)
    }
    // Return same tree after stopping sync, does not matter because either message processing stops
    // or it is a mempool transform who's state is impermanent`
    tree
  }
}

object RollupSynchronizer {

  trait RollupSyncFactory {
    def apply(rollupBlockId: String, ctx: BlockchainContext, prover: NodeWallet): Actor
  }
}