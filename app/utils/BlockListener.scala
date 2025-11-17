package utils

import lfsm.LFSMHelpers
import lfsm.rollup.RollupContracts
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.ErgoTreePredef
import org.ergoplatform.appkit.{BlockchainContext, ContextVar, ErgoClient, ErgoProver, ErgoType, ErgoValue, ExplorerAndPoolUnspentBoxesLoader, JavaHelpers, Parameters}
import org.ergoplatform.restapi.client.FullBlock
import org.slf4j.{Logger, LoggerFactory}
import play.api.cache.SyncCacheApi
import scorex.utils.Longs
import sigma.data.CBigInt
import sigma.{Coll, Colls}
import utils.Helpers.{evalContract, holdingContract, payoutContract}
import utils.LFSMPhase.{EVAL, HOLDING, PAYOUT}
import work.lithos.mutations.{Contract, InputUTXO, TxBuilder, UTXO}

import scala.collection.JavaConverters
import scala.util.Try

object BlockListener {
  private val logger: Logger = LoggerFactory.getLogger("BlockListener")

  def onSync(client: ErgoClient, cache: SyncCacheApi, prover: ErgoProver, diff: String) = {
    logger.info("Starting LFSM state updates")
    val treeSet   = cache.get[Seq[String]](NISPTreeCache.TREE_SET).get
    val nispTrees = for(t <- treeSet) yield t -> cache.get[NISPTree](t).get

    logger.info(s"Found ${nispTrees.size} NISPTrees")
    client.execute{
      ctx =>
        val holdingTrees = nispTrees.filter(h => h._2.phase == HOLDING)
        val evalTrees    = nispTrees.filter(h => h._2.phase == EVAL)
        val payoutTrees  = nispTrees.filter(h => h._2.phase == PAYOUT)
        checkHoldingTransforms(ctx, holdingTrees, prover)
        checkEvalTransforms(ctx, evalTrees, prover)
        attemptPayouts(ctx, payoutTrees, prover)
        attemptHoldingSubmissions(ctx, holdingTrees, prover, diff)
    }
  }
  // TODO: Add disable for transforms
  def checkHoldingTransforms(ctx: BlockchainContext, holdingTrees: Seq[(String, NISPTree)], prover: ErgoProver): Unit = {
    val transformable = holdingTrees.filter(h => ctx.getHeight - h._2.currentPeriod.get >= LFSMHelpers.HOLDING_PERIOD)
    val transforms = transformable.map(t => Try(transformHolding(ctx, t, prover)))
    logger.info(s"Transformed ${transforms.count(_.isSuccess)} holding utxos successfully")
    logger.info(s"Failed to transform ${transforms.count(_.isFailure)} holding utxos")
    transforms.filter(_.isFailure).foreach{
      t =>
        logger.error("Found error while transforming holdings", t.failed.get)
    }
  }

  // TODO: Add disable for transforms
  def checkEvalTransforms(ctx: BlockchainContext, evalTrees: Seq[(String, NISPTree)], prover: ErgoProver): Unit = {
    val transformable = evalTrees.filter(h => ctx.getHeight - h._2.currentPeriod.get >= LFSMHelpers.EVAL_PERIOD)
    val transforms = transformable.map(t => Try(transformEval(ctx, t, prover)))
    logger.info(s"Transformed ${transforms.count(_.isSuccess)} eval utxos successfully")
    logger.info(s"Failed to transform ${transforms.count(_.isFailure)} eval utxos")
    transforms.filter(_.isFailure).foreach{
      t =>
        logger.error("Found error while transforming evals", t.failed.get)
    }
  }

  def attemptPayouts(ctx: BlockchainContext, payoutTrees: Seq[(String, NISPTree)], prover: ErgoProver): Unit = {
    val transforms = payoutTrees.map(t => Try(payoutERG(ctx, t, prover)))
    logger.info(s"Paid out ${transforms.count(_.isSuccess)} payout utxos successfully")
    logger.info(s"Failed to pay out ${transforms.count(_.isFailure)} payout utxos")
    transforms.filter(_.isFailure).foreach{
      t =>
        logger.error("Found error while attempting payouts", t.failed.get)
    }
  }
  // TODO Change back after week 1 so NISP existence is required to submit
  def attemptHoldingSubmissions(ctx: BlockchainContext, holdingTrees: Seq[(String, NISPTree)],
                                prover: ErgoProver, diff: String): Unit = {
    val transformable = holdingTrees.filter{
      h =>
        ctx.getHeight - h._2.currentPeriod.get < LFSMHelpers.HOLDING_PERIOD && !h._2.hasMiner
    }
    val transforms = transformable.map(t => Try(submitNISPs(ctx, t, prover, diff)))
    logger.info(s"Submitted (Fake)NISPs to ${transforms.count(_.isSuccess)} holding utxos successfully")
    logger.info(s"Failed to submit ${transforms.count(_.isFailure)} NISPs")
    transforms.filter(_.isFailure).foreach{
      t =>
        logger.error("Found error while submitting NISPs", t.failed.get)
    }
  }

  private def transformHolding(ctx: BlockchainContext, holding: (String, NISPTree), prover: ErgoProver) = {
    val holdingInput = InputUTXO(ctx.getBoxesById(holding._1).head)
    val eval = evalContract(ctx)
    val boxes = ctx.getDataSource.getUnspentWalletBoxes
    val otherInputs = loadBoxes(Parameters.MinFee, JavaHelpers.toIndexedSeq(boxes).map(InputUTXO(_)))
    val output = UTXO(eval, holdingInput.value,
      registers = Seq(
        holdingInput.registers.head,
        holdingInput.registers(1),
        holdingInput.registers(2),
        ErgoValue.of(ctx.getHeight.toLong)
      ))
    val feeOutput = UTXO(Contract(ErgoTreePredef.feeProposition(720)), Parameters.MinFee)
    val uTx = TxBuilder(ctx)
      .setInputs((Seq(holdingInput) ++ otherInputs):_*)
      .setOutputs(output, feeOutput)
      .buildTx(0, prover.getAddress)
    val sTx = prover.sign(uTx)
    ctx.sendTransaction(sTx)
  }

  def transformEval(ctx: BlockchainContext, eval: (String, NISPTree), prover: ErgoProver) = {
    val evalInput = InputUTXO(ctx.getBoxesById(eval._1).head)
    val payout = payoutContract(ctx)
    val boxes = ctx.getDataSource.getUnspentWalletBoxes
    val otherInputs = loadBoxes(Parameters.MinFee, JavaHelpers.toIndexedSeq(boxes).map(InputUTXO(_)))
    val output = UTXO(payout, evalInput.value,
      registers = Seq(
        evalInput.registers.head,
        evalInput.registers(1),
        evalInput.registers(2),
        ErgoValue.of(evalInput.value)
      ))
    val feeOutput = UTXO(Contract(ErgoTreePredef.feeProposition(720)), Parameters.MinFee)
    val uTx = TxBuilder(ctx)
      .setInputs((Seq(evalInput) ++ otherInputs):_*)
      .setOutputs(output, feeOutput)
      .buildTx(0, prover.getAddress)
    val sTx = prover.sign(uTx)
    val txId = ctx.sendTransaction(sTx)
    logger.info(s"Sent transaction ${txId} to transform eval contract")
  }
  // TODO Change this back to real NISP submission after week 1
  def submitNISPs(ctx: BlockchainContext, holdTree: (String, NISPTree), prover: ErgoProver, diff: String) = {
    logger.info(s"Submitting (Fake) NISP to NISPTree ${holdTree._1}")
    val holdingInput = InputUTXO(ctx.getBoxesById(holdTree._1).head)

    val holding = holdingContract(ctx)
    val boxes = ctx.getDataSource.getUnspentWalletBoxes
    val otherInputs = loadBoxes(Parameters.MinFee, JavaHelpers.toIndexedSeq(boxes).map(InputUTXO(_)))
    val tree = holdTree._2.tree
    val score = LFSMHelpers.convertTauOrScore(BigInt(LFSMHelpers.parseDiffValueForStratum(diff).get))
    // TODO Change back to real NISP concatenation
    val insert = tree.insert(
      Contract.fromAddress(prover.getAddress).hashedPropBytes -> (Longs.toByteArray(score.toLong) ++ Array(0.toByte)))




    val inputWithContext = holdingInput.setCtxVars(
      ContextVar.of(0.toByte, ErgoValue.of(Contract.fromAddress(prover.getAddress).sigmaBoolean.get)),
      ContextVar.of(
        1.toByte,
        ErgoValue.pairOf(
          ErgoValue.ofColl(Colls.fromArray(Contract.fromAddress(prover.getAddress).hashedPropBytes), ErgoType.byteType()),
          ErgoValue.ofColl(Colls.fromArray((Longs.toByteArray(score.toLong) ++ Array(0.toByte))), ErgoType.byteType())
        )
      ),
      ContextVar.of(2.toByte, insert.proof.ergoValue)
    )
    val lastMiners  = holdingInput.registers(1).getValue.asInstanceOf[Int]
    val lastScore   = holdingInput.registers(2).getValue.asInstanceOf[CBigInt].wrappedValue

    val output = UTXO(holding, holdingInput.value,
      registers = Seq(
        tree.ergoValue,
        ErgoValue.of(lastMiners + 1),
        ErgoValue.of((score + BigInt(lastScore)).bigInteger),
        holdingInput.registers(3)
      ))

    val feeOutput = UTXO(Contract(ErgoTreePredef.feeProposition(720)), Parameters.MinFee)
    val uTx = TxBuilder(ctx)
      .setInputs((Seq(inputWithContext) ++ otherInputs): _*)
      .setOutputs(output, feeOutput)
      .buildTx(0, prover.getAddress)
    val sTx = prover.sign(uTx)
    val txId = ctx.sendTransaction(sTx)
    logger.info(s"Sent transaction ${txId} to submit NISP")
  }

  def payoutERG(ctx: BlockchainContext, payments: (String, NISPTree), prover: ErgoProver) = {
    logger.info(s"Paying out ERG for NISPTree ${payments._1}")
    val payInput = InputUTXO(ctx.getBoxesById(payments._1).head)
    val payout = payoutContract(ctx)
    val boxes = ctx.getDataSource.getUnspentWalletBoxes
    val otherInputs = loadBoxes(Parameters.MinFee, JavaHelpers.toIndexedSeq(boxes).map(InputUTXO(_)))
    val tree = payments._2.tree
    val lookUp = tree.lookUp(Contract.fromAddress(prover.getAddress).hashedPropBytes)
    val delete = tree.delete(Contract.fromAddress(prover.getAddress).hashedPropBytes)

    val score = Longs.fromByteArray(lookUp.response.head.ergoValue.getValue.toArray.slice(0, 8))
    val amountToPay = LFSMHelpers.paymentFromScore(score, payments._2.totalScore, payments._2.totalReward)
    val inputWithContext = payInput.setCtxVars(
      ContextVar.of(0.toByte,
        ErgoValue.ofArray(Array(Colls.fromArray(Contract.fromAddress(prover.getAddress).hashedPropBytes)),
        ErgoType.collType(ErgoType.byteType()))),
      ContextVar.of(1.toByte, lookUp.proof.ergoValue),
      ContextVar.of(2.toByte, delete.proof.ergoValue)
    )
    if(amountToPay < payInput.value && payInput.value - amountToPay > 1000000L) {
      val output = UTXO(payout, payInput.value - amountToPay,
        registers = Seq(
          tree.ergoValue,
          payInput.registers(1),
          payInput.registers(2),
          payInput.registers(3)
        ))
      val minerOutput = UTXO(Contract.fromAddress(prover.getAddress), amountToPay)
      val feeOutput = UTXO(Contract(ErgoTreePredef.feeProposition(720)), Parameters.MinFee)
      val uTx = TxBuilder(ctx)
        .setInputs((Seq(inputWithContext) ++ otherInputs): _*)
        .setOutputs(output, minerOutput, feeOutput)
        .buildTx(0, prover.getAddress)
      val sTx = prover.sign(uTx)
      val txId = ctx.sendTransaction(sTx)
      logger.info(s"Sent transaction ${txId} to pay local miner")
    }else{
      val minerOutput = UTXO(Contract.fromAddress(prover.getAddress), amountToPay)
      val feeOutput = UTXO(Contract(ErgoTreePredef.feeProposition(720)), Parameters.MinFee)
      val uTx = TxBuilder(ctx)
        .setInputs((Seq(inputWithContext) ++ otherInputs): _*)
        .setOutputs(minerOutput, feeOutput)
        .buildTx(0, prover.getAddress)
      val sTx = prover.sign(uTx)
      val txId = ctx.sendTransaction(sTx)
      logger.info(s"Sent transaction ${txId} to pay local miner")
    }
  }



  // TODO: Better box loading algo this is ugly
  def loadBoxes(value: Long, boxes: Seq[InputUTXO]): Seq[InputUTXO] = {
    var initVal = 0L
    var counter = 0
    var initSeq = Seq.empty[InputUTXO]
    val sortedBoxes = boxes.sortBy(_.value).reverse
    while(initVal < value){
      initSeq = initSeq :+ sortedBoxes(counter)
      initVal = initVal + sortedBoxes(counter).value
      counter = counter + 1
    }
    initSeq
  }

}
