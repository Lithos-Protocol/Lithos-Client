package utils

import akka.Done
import lfsm.LFSMHelpers
import lfsm.rollup.RollupContracts
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{Address, BlockchainContext, ErgoClient, ErgoProver, ErgoValue}
import org.ergoplatform.restapi.client.{ErgoTransactionInput, ErgoTransactionOutput, FullBlock}
import org.slf4j.{Logger, LoggerFactory}
import play.api.cache.{AsyncCacheApi, SyncCacheApi}
import sigma.ast.ErgoTree
import sigma.{Coll, Colls, SigmaProp}
import sigma.data.{AvlTreeFlags, CBigInt}
import state.{LFSMPhase, NISPTree}
import utils.Helpers.holdingContract
import work.lithos.mutations.Contract
import work.lithos.plasma.PlasmaParameters
import work.lithos.plasma.collections.PlasmaMap

import java.math.BigInteger
import scala.collection.JavaConverters
import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.util.{Failure, Success}



object NISPTreeCache {
  val logger: Logger = LoggerFactory.getLogger("NISPTreeCache")
  final val TREE_SET = "TREE_SET"
  def cacheNewHolding(ctx:BlockchainContext, output: ErgoTransactionOutput, cache: SyncCacheApi): Unit = {
    val holdingBox = Helpers.parseOutput(ctx, output)
    val newTree    = PlasmaMap[Array[Byte], Array[Byte]](AvlTreeFlags.AllOperationsAllowed, PlasmaParameters.default)
    val nispTree   = NISPTree(newTree, 0, BigInt(0), Some(holdingBox.registers(3).getValue.asInstanceOf[Long]),
      holdingBox.value, output.getCreationHeight, hasMiner = false, LFSMPhase.HOLDING)

    logger.info(s"Found new holding utxo ${output.getBoxId} at height ${output.getCreationHeight}")
    cache.set(output.getBoxId, nispTree)
    val treeSet = cache.get[Seq[String]](TREE_SET).get
    cache.set(TREE_SET, treeSet :+ output.getBoxId)
  }
  def cacheExistingHolding(ctx:BlockchainContext, prover: ErgoProver, input: ErgoTransactionInput,
                           output: ErgoTransactionOutput, cache: SyncCacheApi): Unit = {
    val holdingBox  = Helpers.parseOutput(ctx, output)
    val oldNISPTree = cache.get[NISPTree](input.getBoxId).get
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
    logger.info(s"Found existing holding utxo ${output.getBoxId}")
    if(isMiner) {
      logger.info(s"Holding utxo ${output.getBoxId} contains local miner!")
    }else{
      logger.info(s"Holding utxo ${output.getBoxId} contains miner" +
        s" ${Address.fromErgoTree(ErgoTree.fromProposition(signer), ctx.getNetworkType)}")
    }
    val nextTree    = oldNISPTree.copy(tree = dictionary, numMiners = nextMiners, totalScore = nextScore,
      currentPeriod = Some(nextPeriod), hasMiner = isMiner)
    cache.remove(input.getBoxId)
    cache.set(output.getBoxId, nextTree)
    val treeSet = cache.get[Seq[String]](TREE_SET).get
    cache.set(TREE_SET, treeSet.filter(_ != input.getBoxId) :+ output.getBoxId)
  }

  def cacheNewEval(ctx:BlockchainContext,input: ErgoTransactionInput,
                   output: ErgoTransactionOutput, cache: SyncCacheApi): Unit = {
    val evalBox = Helpers.parseOutput(ctx, output)
    val oldNISPTree = cache.get[NISPTree](input.getBoxId).get
    if(oldNISPTree.hasMiner){
      val nispTree   = oldNISPTree.copy(currentPeriod = Some(evalBox.registers(3).getValue.asInstanceOf[Long]),
        phase = LFSMPhase.EVAL)
      cache.remove(input.getBoxId)
      cache.set(output.getBoxId, nispTree)
      val treeSet = cache.get[Seq[String]](TREE_SET).get
      cache.set(TREE_SET, treeSet.filter(_ != input.getBoxId) :+ output.getBoxId)
      logger.info(s"Transformed NISPTree ${input.getBoxId} of holding contract to ${output.getBoxId} of evaluation contract")
    }else{
      cache.remove(input.getBoxId)
      val treeSet = cache.get[Seq[String]](TREE_SET).get
      cache.set(TREE_SET, treeSet.filter(_ != input.getBoxId))
      logger.warn(s"Removed NISPTree ${input.getBoxId} because local miner was not present during holding phase")
    }
  }

//  def cacheExistingEval(ctx:BlockchainContext,input: ErgoTransactionInput,
//                        output: ErgoTransactionOutput, cache: SyncCacheApi) = {
//    val evalBox = Helpers.parseOutput(ctx, output)
//    val optNISPTree = cache.get[NISPTree](input.getBoxId)
//    optNISPTree match {
//      case Some(oldNISPTree) =>
//        if(oldNISPTree.hasMiner){
//          val nispTree   = oldNISPTree.copy()
//          cache.remove(input.getBoxId)
//          cache.set(evalBox.id.toString, nispTree)
//        }else{
//          cache.remove(input.getBoxId)
//        }
//      case None => logger.warn(s"Skipping Eval Output ${evalBox.id.toString} in block ${output.getCreationHeight}" +
//        s" due to no local miner presence")
//    }
//
//  }

  def cacheNewPayout(ctx:BlockchainContext,input: ErgoTransactionInput,
                        output: ErgoTransactionOutput, cache: SyncCacheApi): Unit = {
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
          logger.info(s"Transformed NISPTree ${input.getBoxId} of evaluation contract to ${output.getBoxId} of payout contract")
        }else{
          cache.remove(input.getBoxId)
          val treeSet = cache.get[Seq[String]](TREE_SET).get
          cache.set(TREE_SET, treeSet.filter(_ != input.getBoxId))
          logger.warn(s"Removed NISPTree ${input.getBoxId} because local miner was not found after evaluation")

        }
      case None => logger.warn(s"Skipping Payout Output ${payoutBox.id.toString} in block ${output.getCreationHeight}" +
        s" due to no local miner presence")
    }

  }

  def cacheExistingPayout(ctx:BlockchainContext,input: ErgoTransactionInput,
                     output: Option[ErgoTransactionOutput], cache: SyncCacheApi, prover: ErgoProver): Unit = {

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
            logger.info(s"Removing NISPTree for block ${oldNISPTree.startHeight} because miner was paid")
            cache.remove(input.getBoxId)
            val treeSet = cache.get[Seq[String]](TREE_SET).get
            cache.set(TREE_SET, treeSet.filter(_ != input.getBoxId))

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
            logger.info(s"Transformed NISPTree ${input.getBoxId} to ${output.get.getBoxId} after payment tx")
          }
        }else{
          cache.remove(input.getBoxId)
          val treeSet = cache.get[Seq[String]](TREE_SET).get
          cache.set(TREE_SET, treeSet.filter(_ != input.getBoxId))
          logger.warn(s"Removed NISPTree ${input.getBoxId} because local miner was not found during payouts (?)")
        }
      case None => logger.warn(s"Skipping NISPTree ${input.getBoxId}" +
        s" due to no local miner presence")
    }

  }

}
