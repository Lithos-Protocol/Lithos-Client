package utils

import lfsm.{LFSMPhase, NISPTree}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{Address, BlockchainContext, ErgoProver, ErgoValue}
import org.ergoplatform.restapi.client.{ErgoTransactionInput, ErgoTransactionOutput}
import org.slf4j.{Logger, LoggerFactory}
import play.api.cache.SyncCacheApi
import scorex.utils.Longs
import sigma.ast.ErgoTree
import sigma.data.{AvlTreeFlags, CBigInt}
import sigma.{Coll, SigmaProp}
import work.lithos.mutations.Contract
import work.lithos.plasma.PlasmaParameters
import work.lithos.plasma.collections.PlasmaMap



object NISPTreeCache {
  val logger: Logger = LoggerFactory.getLogger("NISPTreeCache")
  final val TREE_SET = "TREE_SET"
  def cacheNewHolding(ctx:BlockchainContext, output: ErgoTransactionOutput, cache: SyncCacheApi, height: Int): Unit = {
    val holdingBox = Helpers.parseOutput(ctx, output)
    val newTree    = PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.AllOperationsAllowed, PlasmaParameters.default)
    val nispTree   = NISPTree(newTree, 0, BigInt(0), Some(holdingBox.registers(3).getValue.asInstanceOf[Long]),
      holdingBox.value, holdingBox.registers(3).getValue.asInstanceOf[Long].toInt, hasMiner = false, LFSMPhase.HOLDING)

    logger.info(s"Found genesis for NISPTree ${output.getBoxId} in block ${height}")
    cache.set(output.getBoxId, nispTree)
    val treeSet = cache.get[Seq[String]](TREE_SET).get
    cache.set(TREE_SET, treeSet :+ output.getBoxId)
  }
  def cacheExistingHolding(ctx:BlockchainContext, prover: ErgoProver, input: ErgoTransactionInput,
                           output: ErgoTransactionOutput, cache: SyncCacheApi, height: Int): Unit = {
    val holdingBox  = Helpers.parseOutput(ctx, output)
    val optNISPTree = cache.get[NISPTree](input.getBoxId)
    optNISPTree match {
      case Some(oldNISPTree) =>
        val dictionary  = oldNISPTree.tree

        val nextMiners  = holdingBox.registers(1).getValue.asInstanceOf[Int]
        val nextScore   = holdingBox.registers(2).getValue.asInstanceOf[CBigInt].wrappedValue
        val nextPeriod  = holdingBox.registers(3).getValue.asInstanceOf[Long]

        val signer      = ErgoValue.fromHex(input.getSpendingProof.getExtension.get("0")).getValue.asInstanceOf[SigmaProp]
        val keyValue    = ErgoValue.fromHex(input.getSpendingProof.getExtension.get("1")).getValue.asInstanceOf[(Coll[Byte], Coll[Byte])]
        val proof       = ErgoValue.fromHex(input.getSpendingProof.getExtension.get("2")).getValue.asInstanceOf[Coll[Byte]]

        val isMiner     = Helpers.pkHexFromSigmaProp(signer).get == Helpers.pkHexFromBoolean(
          Contract.fromAddress(prover.getAddress).sigmaBoolean.get).get
        val insertion    = dictionary.insert(keyValue._1.toArray -> keyValue._2.toArray)
        require(insertion.proof.ergoValue.getValue == proof, "Proofs must be equal on holding transformation")

        if(isMiner) {
          logger.info(s"Transformed NISPTree ${input.getBoxId} in block $height to" +
            s" output ${output.getBoxId} after submission by local miner")
        }else{
          logger.info(s"Transformed NISPTree ${input.getBoxId} in block $height to output ${output.getBoxId}" +
            s" after submission by miner ${Address.fromErgoTree(ErgoTree.fromProposition(signer), ctx.getNetworkType)}")
        }
        val nextMinerSet = oldNISPTree.minerSet ++ Set(Hex.toHexString(keyValue._1.toArray))
        val nextTree    = oldNISPTree.copy(tree = dictionary, numMiners = nextMiners, totalScore = nextScore,
          currentPeriod = Some(nextPeriod), hasMiner = isMiner || oldNISPTree.hasMiner, minerSet = nextMinerSet)
        cache.remove(input.getBoxId)
        cache.set(output.getBoxId, nextTree)
        val treeSet = cache.get[Seq[String]](TREE_SET).get
        cache.set(TREE_SET, treeSet.filter(_ != input.getBoxId) :+ output.getBoxId)
      case None =>
        logger.info(s"Skipped submission application in block ${height}" +
          s" because NISPTree ${input.getBoxId} had no genesis history")
    }

  }

  def cacheNewEval(ctx:BlockchainContext,input: ErgoTransactionInput,
                   output: ErgoTransactionOutput, cache: SyncCacheApi, height: Int): Unit = {
    val evalBox = Helpers.parseOutput(ctx, output)
    val optNISPTree = cache.get[NISPTree](input.getBoxId)
    optNISPTree match {
      case Some(oldNISPTree) =>
        if(oldNISPTree.hasMiner){
          val nispTree   = oldNISPTree.copy(currentPeriod = Some(evalBox.registers(3).getValue.asInstanceOf[Long]),
            phase = LFSMPhase.EVAL)
          cache.remove(input.getBoxId)
          cache.set(output.getBoxId, nispTree)
          val treeSet = cache.get[Seq[String]](TREE_SET).get
          cache.set(TREE_SET, treeSet.filter(_ != input.getBoxId) :+ output.getBoxId)
          logger.info(s"Transformed NISPTree ${input.getBoxId} in block ${height} from holding" +
            s" contract to ${output.getBoxId} of evaluation contract")
        }else{
          cache.remove(input.getBoxId)
          val treeSet = cache.get[Seq[String]](TREE_SET).get
          cache.set(TREE_SET, treeSet.filter(_ != input.getBoxId))
          logger.info(s"Removed NISPTree ${input.getBoxId} in block ${height} because" +
            s" local miner was not present during holding phase")
        }
      case None =>
        logger.info(s"Skipped holding transformation in block ${height}" +
          s" because NISPTree ${input.getBoxId} had no genesis history")
    }

  }

  def cacheExistingEval(ctx:BlockchainContext,input: ErgoTransactionInput, fpInput: ErgoTransactionInput,
                        output: ErgoTransactionOutput, cache: SyncCacheApi, prover: ErgoProver, height: Int) = {
    val evalBox = Helpers.parseOutput(ctx, output)
    val optNISPTree = cache.get[NISPTree](input.getBoxId)
    optNISPTree match {
      case Some(oldNISPTree) =>
        if(oldNISPTree.hasMiner){
          val dictionary  = oldNISPTree.tree

          val miner       = ErgoValue.fromHex(fpInput.getSpendingProof.getExtension.get("0")).getValue.asInstanceOf[Coll[Byte]]
          val lookProof   = ErgoValue.fromHex(fpInput.getSpendingProof.getExtension.get("1")).getValue.asInstanceOf[Coll[Byte]]
          val delProof    = ErgoValue.fromHex(fpInput.getSpendingProof.getExtension.get("2")).getValue.asInstanceOf[Coll[Byte]]

          val lookUp = dictionary.lookUp(miner.toArray)
          require(lookUp.proof.ergoValue.getValue == lookProof, "Lookup proofs must be equal on eval transformation")
          val delete = dictionary.delete(miner.toArray)
          require(delete.proof.ergoValue.getValue == delProof, "Removal proofs must be equal on eval transformation")
          val minerScore = Longs.fromByteArray(lookUp.response.head.get.slice(0,8))
          val removedMiner =
            Hex.toHexString(miner.toArray) == Hex.toHexString(Contract.fromAddress(prover.getAddress).hashedPropBytes)
          val nextTotalScore = oldNISPTree.totalScore - minerScore
          val nextMinerSet = oldNISPTree.minerSet -- Set(Hex.toHexString(miner.toArray))
          val nispTree = {
            if(removedMiner)
              oldNISPTree.copy(tree = dictionary, hasMiner = false, totalScore = nextTotalScore, minerSet = nextMinerSet)
            else
              oldNISPTree.copy(tree = dictionary, minerSet = nextMinerSet)
          }

          cache.remove(input.getBoxId)
          cache.set(output.getBoxId, nispTree)
          val treeSet = cache.get[Seq[String]](TREE_SET).get
          cache.set(TREE_SET, treeSet.filter(_ != input.getBoxId) :+ output.getBoxId)
          logger.info(s"Transformed NISPTree ${input.getBoxId} in block ${height} to output ${output.getBoxId} after fraud proof application")
        }else{
          cache.remove(input.getBoxId)
          val treeSet = cache.get[Seq[String]](TREE_SET).get
          cache.set(TREE_SET, treeSet.filter(_ != input.getBoxId))
          logger.info(s"Removed NISPTree ${input.getBoxId} in block ${height} because local miner was not found after evaluation")
        }
      case None => logger.info(s"Skipped fraud proof application in block ${height}" +
        s" because NISPTree ${input.getBoxId} had no genesis history")
    }

  }

  def cacheNewPayout(ctx:BlockchainContext,input: ErgoTransactionInput,
                        output: ErgoTransactionOutput, cache: SyncCacheApi, height: Int): Unit = {
    val payoutBox = Helpers.parseOutput(ctx, output)
    val optNISPTree = cache.get[NISPTree](input.getBoxId)
    optNISPTree match {
      case Some(oldNISPTree) =>
        if(oldNISPTree.hasMiner){
          val nispTree   = oldNISPTree.copy(currentPeriod = None, phase = LFSMPhase.PAYOUT)
          cache.remove(input.getBoxId)
          cache.set(output.getBoxId, nispTree)
          val treeSet = cache.get[Seq[String]](TREE_SET).get
          cache.set(TREE_SET, treeSet.filter(_ != input.getBoxId) :+ output.getBoxId)
          logger.info(s"Transformed NISPTree ${input.getBoxId} in block $height from evaluation contract to ${output.getBoxId} of payout contract")
        }else{
          cache.remove(input.getBoxId)
          val treeSet = cache.get[Seq[String]](TREE_SET).get
          cache.set(TREE_SET, treeSet.filter(_ != input.getBoxId))
          logger.info(s"Removed NISPTree ${input.getBoxId} in block $height because local miner was not found after evaluation")

        }
      case None => logger.info(s"Skipped evaluation transformation in block ${height} because" +
        s" NISPTree ${input.getBoxId} had no genesis history")
    }

  }

  def cacheExistingPayout(ctx:BlockchainContext,input: ErgoTransactionInput, output: Option[ErgoTransactionOutput],
                          cache: SyncCacheApi, prover: ErgoProver, height: Int): Unit = {

    val optNISPTree = cache.get[NISPTree](input.getBoxId)
    optNISPTree match {
      case Some(oldNISPTree) =>
        if(oldNISPTree.hasMiner){

          val dictionary  = oldNISPTree.tree

          val miners      = ErgoValue.fromHex(input.getSpendingProof.getExtension.get("0")).getValue.asInstanceOf[Coll[Coll[Byte]]]
          val lookProof   = ErgoValue.fromHex(input.getSpendingProof.getExtension.get("1")).getValue.asInstanceOf[Coll[Byte]]
          val delProof    = ErgoValue.fromHex(input.getSpendingProof.getExtension.get("2")).getValue.asInstanceOf[Coll[Byte]]

          val paidMiner     = miners.toArray.map(_.toArray).exists{
            a => Hex.toHexString(a) == Hex.toHexString(Contract.fromAddress(prover.getAddress).hashedPropBytes)
          }
          if(paidMiner){

            cache.remove(input.getBoxId)
            val treeSet = cache.get[Seq[String]](TREE_SET).get
            cache.set(TREE_SET, treeSet.filter(_ != input.getBoxId))
            logger.info(s"Removed NISPTree ${input.getBoxId} in block ${height} because local miner was paid")
          }else {
            val lookUp = dictionary.lookUp(miners.toArray.map(_.toArray):_*)
            require(lookUp.proof.ergoValue.getValue == lookProof, "Lookup proofs must be equal on payout transformation")
            val delete = dictionary.delete(miners.toArray.map(_.toArray):_*)
            require(delete.proof.ergoValue.getValue == delProof, "Removal proofs must be equal on payout transformation")
            val nispTree = oldNISPTree.copy(tree = dictionary)

            cache.remove(input.getBoxId)
            cache.set(output.get.getBoxId, nispTree)
            val treeSet = cache.get[Seq[String]](TREE_SET).get
            cache.set(TREE_SET, treeSet.filter(_ != input.getBoxId) :+ output.get.getBoxId)
            logger.info(s"Transformed NISPTree ${input.getBoxId} in block ${height} to" +
              s" output ${output.get.getBoxId} after payout application")
          }
        }else{
          cache.remove(input.getBoxId)
          val treeSet = cache.get[Seq[String]](TREE_SET).get
          cache.set(TREE_SET, treeSet.filter(_ != input.getBoxId))
          logger.warn(s"Removed NISPTree ${input.getBoxId} in block ${height} because" +
            s" local miner was not present")
        }
      case None => logger.info(s"Skipped payout application in block $height because" +
        s" NISPTree ${input.getBoxId} had no genesis history")
    }

  }

}
