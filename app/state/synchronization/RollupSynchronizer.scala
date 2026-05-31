package state.synchronization

import akka.actor.Actor
import cache.RollupCache
import com.google.inject.assistedinject.Assisted
import lfsm.states.NISPTree
import lfsm.{LFSMHelpers, LFSMPhase}
import mutations.NodeWallet
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{Address, BlockchainContext, ErgoValue}
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import play.api.cache.SyncCacheApi
import scorex.utils.Longs
import sigma.ast.ErgoTree
import sigma.data.CBigInt
import sigma.{Coll, SigmaProp}
import state.messages.MempoolMessages.{MempoolChain, MempoolRollupState, MempoolTransform}
import state.messages.RollupMessages._
import state.messages.SyncMessages.Transform
import utils.{Globals, Helpers, PayoutRecord}

import javax.inject.Inject

class RollupSynchronizer @Inject()(config: Configuration, @Assisted rollupBlockId: String, @Assisted ctx: BlockchainContext, @Assisted prover: NodeWallet, cacheApi: SyncCacheApi) extends Actor {
  val logger: Logger = LoggerFactory.getLogger("RollupSynchronizer-" + rollupBlockId.slice(0, 12))
  private val treeCache: RollupCache = RollupCache(cacheApi)


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
        val oldMemState = treeCache.getMempoolState(rollupBlockId)

        if(oldMemState.isEmpty || (oldMemState.isDefined && oldMemState.get.asInput.id.toString != mempoolChain.endId)) {
          val nextTree = mempoolChain.transforms.foldLeft(tree) {
            (a, m) =>
              if (a.utxoId == m.input.id) {
                matchTransform(a, m, mempoolChain.transforms.size == 1)
              } else
                a
          }
          val asInput = mempoolChain.transforms.last.output.toInput(ctx)
          if (mempoolChain.transforms.last.output.id == nextTree.utxoId) {
            if(mempoolChain.transforms.size > 1)
              logger.info(s"Applied $mempoolChain with initial transform" +
                s" ${mempoolChain.transforms.head.tx.id}")
            treeCache.setMempoolState(rollupBlockId, MempoolRollupState(asInput, nextTree))
          } else {
            // If transformations did not reach the end of the mempool sequence, then
            // tracking was likely stopped in the middle, meaning this rollup will be removed in
            // one the upcoming transforms.
            logger.info(s"Rollup will be removed in upcoming mempool chain ${mempoolChain}")
            treeCache.setMempoolState(rollupBlockId, MempoolRollupState(asInput, nextTree, toBeRemoved = true))
          }
        }
      }else {
        // This should be impossible, but in case it does happen we should
        // be loud about it, as ensuring the cached rollup state matches its corresponding
        // actor is critical
        logger.error(s"Lost tracking for rollup $rollupBlockId with bad utxoId $utxoId")
        throw new IllegalStateException(s"Rollup $rollupBlockId has no matching cache state")
      }
    case _: UpdateEvaluation =>
      val treeOpt = treeCache.get(utxoId)
      if(treeOpt.isDefined){
        updateEvaluationState(utxoId, treeOpt.get)
      }else{
        // This should be impossible, but in case it does happen we should
        // be loud about it, as ensuring the cached rollup state matches its corresponding
        // actor is critical
        logger.error(s"Lost tracking for rollup $rollupBlockId with bad utxoId $utxoId")
        throw new IllegalStateException(s"Rollup $rollupBlockId has no matching cache state")
      }
  }

  /**
   * Helper method to only log for certain transforms
   * @param msg Msg to log
   * @param shouldLog whether to transform or not
   */
  private def log(msg: String, shouldLog: Boolean): Unit = {
    if(shouldLog)
      logger.info(msg)
  }
  private def matchTransform(tree: NISPTree, transform: Transform, shouldLog: Boolean = true): NISPTree = {
    val phase = tree.phase
    val isMempool = transform.isInstanceOf[MempoolTransform]
    phase match {
      case LFSMPhase.HOLDING =>
        transform.output.ergoTree match {
          case hContract if hContract == relErgoTrees.head =>
            submissionTransform(tree, transform, isMempool, shouldLog)
          case eContract if eContract == relErgoTrees(1) =>
            holdingTransform(tree, transform, isMempool, shouldLog)
        }
      case LFSMPhase.EVAL =>
        transform.output.ergoTree match {
          case eContract if eContract == relErgoTrees(1) =>
            fraudProofTransform(tree, transform, isMempool, shouldLog)
          case pContract if pContract == relErgoTrees(2) =>
            evalTransform(tree, transform, isMempool, shouldLog)
        }
      case LFSMPhase.PAYOUT =>
        transform match {
          case mempool: MempoolTransform =>
            mempoolPayoutTransform(tree, mempool, shouldLog)
          case rollup: RollupTransform =>
           rollupPayoutTransform(tree, rollup)
        }

    }
  }

  private def submissionTransform(tree: NISPTree, transform: Transform, isMempool: Boolean, shouldLog: Boolean): NISPTree = {

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
      log(s"Applied submission transform ${transform.tx.id} with $transform for local miner", shouldLog)
    } else {
      log(s"Applied submission transform ${transform.tx.id} with $transform for miner" +
        s" ${Address.fromErgoTree(ErgoTree.fromProposition(signer), ctx.getNetworkType)}", shouldLog)
    }
    nextTree

  }

  private def holdingTransform(tree: NISPTree, transform: Transform, isMempool: Boolean, shouldLog: Boolean): NISPTree = {

    val evalBox = transform.output.toInput(ctx)
    if (tree.hasMiner) {
      val nispTree = tree.copy(currentPeriod = Some(evalBox.registers(3).getValue.asInstanceOf[Long]),
        phase = LFSMPhase.EVAL, utxoId = transform.output.id)
      if(!isMempool) {
        treeCache.updateTreeCache(transform.input.id, nispTree.utxoId, nispTree)
      }
      log(s"Applied holding transform ${transform.tx.id} with $transform", shouldLog)
      nispTree
    } else {
      stopSync("No local miner in holding transform", tree, isMempool)
    }
  }

  private def fraudProofTransform(tree: NISPTree, transform: Transform, isMempool: Boolean, shouldLog: Boolean): NISPTree = {

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
      log(s"Applied fraud proof transform ${transform.tx.id} with $transform", shouldLog)
      nispTree
    } else {
      stopSync("No local miner in fraud proof transform", tree, isMempool)
    }
  }

  private def evalTransform(tree: NISPTree, transform: Transform, isMempool: Boolean, shouldLog: Boolean): NISPTree = {
    if (tree.hasMiner) {
      val nispTree = tree.copy(currentPeriod = None, phase = LFSMPhase.PAYOUT, utxoId = transform.output.id)
      if(!isMempool)
        treeCache.updateTreeCache(transform.input.id, nispTree.utxoId, nispTree)
      log(s"Applied evaluation transform ${transform.tx.id} with $transform", shouldLog)
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
          logger.warn(s"Got early removal in payout transform ${transform.tx.id} with $transform")
          stopSync("No payout contract output in transform", tree, isMempool = false)
        }
      }
    } else {
      stopSync("No local miner in payout transform", tree, isMempool = false)
    }
  }

  private def mempoolPayoutTransform(tree: NISPTree, transform: MempoolTransform, shouldLog: Boolean): NISPTree = {
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

          log(s"Applied payout transform ${transform.tx.id} with $transform", shouldLog)
          nispTree
        } else {
          log(s"Got early removal in payout transform ${transform.tx.id} with $transform", shouldLog)
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

  /**
   * Safely update evaluation state
   * @param utxoId utxoId of current rollup
   * @param tree current NISPTree state of rollup
   */
  private def updateEvaluationState(utxoId: String, tree: NISPTree): Unit = {
    // UTXO id is not changing, as evaluation state is purely local.
    // However, we must use this method to update eval state, because changing state
    // outside of this actor could cause memory leaks if the utxoId used is no longer
    // associated with the current state of this rollup.
    treeCache.updateTreeCache(utxoId, utxoId, tree.copy(evaluated = true))
  }
}

object RollupSynchronizer {

  trait RollupSyncFactory {
    def apply(rollupBlockId: String, ctx: BlockchainContext, prover: NodeWallet): Actor
  }
}