package state

import akka.actor.{ActorRef, Cancellable}
import cache.{MDCache, RollupCache}
import evaluation.Evaluator
import lfsm.LFSMPhase.{EVAL, HOLDING, PAYOUT}
import lfsm.contracts.{DictionaryContracts, FraudProofContracts, RollupContracts}
import lfsm.LFSMHelpers
import lfsm.states.NISPTree
import mutations.{BoxLoader, NodeWallet, NotEnoughInputsException}
import nisp.NISPDatabase
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.ErgoTreePredef
import org.ergoplatform.appkit._
import org.slf4j.{Logger, LoggerFactory}
import play.api.cache.SyncCacheApi
import scorex.utils.Longs
import sigma.{AvlTree, Coll, Colls}
import sigma.data.CBigInt
import state.messages.MempoolMessages.MempoolRollupState
import state.messages.RollupMessages.RemoveRollup
import stratum.CollateralRetriever
import utils.Helpers.{compileEval, compileHolding, compilePayout, evalContract, holdingContract, payoutContract}
import utils.{Globals, Helpers}
import work.lithos.mutations.{Contract, InputUTXO, Token, TxBuilder, UTXO}

import scala.util.{Failure, Success, Try}

object LFSMTransformer {
  private val logger: Logger = LoggerFactory.getLogger("LFSMTransformer")
  private final val DATA_BOX_BUFFER = (LFSMHelpers.NISP_WINDOW + 50).toInt
  // Simple lock to make sure sync transforms are never made concurrently
  private final var TRANSFORM_LOCK: Boolean = false
  private def makeMinerDataBox(ctx: BlockchainContext, minerContract: Contract, addToken: ErgoId, score: Long) = {
    val contract =  Helpers.dataBoxContract(ctx)
    val commit = ctx.getHeight + DATA_BOX_BUFFER -> score
    logger.info(s"Making new data box for miner with id $addToken, and commit $commit")
    val utxo = UTXO(contract, Parameters.MinFee, Seq(Token(addToken, 1L)),
      registers = Seq(
        ErgoValue.ofColl(Colls.fromArray(Array(commit)), ErgoType.pairType(ErgoType.integerType(), ErgoType.longType())),
        ErgoValue.ofColl(Colls.fromArray(minerContract.hashedPropBytes), ErgoType.byteType())
      ))
    utxo
  }

  def addToMD(client: ErgoClient, cache: SyncCacheApi, prover: NodeWallet, diff: String): String = {
    logger.info("Attempting to add self to miner dictionary")
    client.execute {
      ctx =>
        val inputs = new BoxLoader(ctx).loadBoxes.getInputs(Parameters.MinFee*2)
        val tau = LFSMHelpers.parseDiffValueForStratum(diff)
        val score = LFSMHelpers.convertTauOrScore(tau.get).toLong
        val minerTree = MDCache(cache).getMD.get

        val proverContract = prover.contract
        val insertionTree = minerTree.dictionary.copy()
        insertionTree.prover.generateProof()
        val dictInput = InputUTXO(ctx.getBoxesById(minerTree.utxoId).head)
        insertionTree.prover.generateProof()
        val insertion = insertionTree.insert(proverContract.hashedPropBytes -> dictInput.id.getBytes)

        val ctxDictInput = dictInput
          .withCtxVar(ContextVar.of(0.toByte, 0.toByte))
          .withCtxVar(ContextVar.of(1.toByte, proverContract.sigmaBoolean.get))
          .withCtxVar(ContextVar.of(
            2.toByte,
            ErgoValue.pairOf(
              ErgoValue.ofColl(Colls.fromArray(proverContract.hashedPropBytes), ErgoType.byteType()),
              ErgoValue.ofColl(Colls.fromArray(dictInput.id.getBytes), ErgoType.byteType())
            )))
          .withCtxVar(ContextVar.of(3.toByte, insertion.proof.ergoValue))
        val dictOutput = dictInput.toUTXO.copy(registers = Seq(insertionTree.ergoValue))
        val output = makeMinerDataBox(ctx, proverContract, dictInput.id, score)
        val feeOutput = UTXO(Contract(ErgoTreePredef.feeProposition(720)), Parameters.MinFee)
        val uTx = TxBuilder(ctx)
          .setInputs((Seq(ctxDictInput) ++ inputs): _*)
          .setOutputs(dictOutput, output, feeOutput)
          .buildTx(0, prover.p2pk)

        val sTx = prover.sign(uTx)
        ctx.sendTransaction(sTx)
    }
  }

  def onSync(client: ErgoClient, cache: SyncCacheApi, prover: NodeWallet, diff: String,
             nispTrees: Seq[(String, NISPTree)], syncHandler: ActorRef, autoCommits: Boolean): Unit = {

    def useCorrectState(syncState: (String, NISPTree), mempoolMap: Map[String, MempoolRollupState]) = {
      if(mempoolMap.contains(syncState._1))
        syncState._1 -> mempoolMap(syncState._1).nispTree
      else
        syncState
    }

    if(!TRANSFORM_LOCK) {

      TRANSFORM_LOCK = true
      Try {
        Thread.sleep(1000)
        logger.info(s"Creating LFSM transforms for ${nispTrees.size} synced rollups")

        client.execute {
          ctx =>
            val boxLoader = new BoxLoader(ctx).loadBoxes
            val rollupCache = new RollupCache(cache)
            val memStates = nispTrees.flatMap {
              n =>
                rollupCache.getMempoolState(n._2.blockId).map{
                  s =>
                    n._1 -> s
                }
            }.toMap
            val statesToRemove = memStates.filter(_._2.toBeRemoved)
            logger.info(s"Using mempool states for ${memStates.size - statesToRemove.size}" +
              s" (with ${statesToRemove.size} states removed)")
            val updatedTrees = nispTrees.map(useCorrectState(_, memStates))
            val transformableTrees = updatedTrees.filterNot(u => statesToRemove.contains(u._1))

            val holdingTrees = transformableTrees.filter(h => h._2.phase == HOLDING)
            val evalTrees = nispTrees.filter(h => h._2.phase == EVAL)
            val updatedEvalTrees = transformableTrees.filter(h => h._2.phase == EVAL)
            val payoutTrees = transformableTrees.filter(h => h._2.phase == PAYOUT)

            checkHoldingTransforms(ctx, holdingTrees, prover, boxLoader, memStates)
            checkEvalTransforms(ctx, updatedEvalTrees, prover, boxLoader, memStates)
            attemptPayouts(ctx, payoutTrees, prover, boxLoader, memStates)
            attemptHoldingSubmissions(ctx, holdingTrees, prover, diff, boxLoader, cache, syncHandler, memStates)

            // We do not use mempool states for evaluation
            attemptEvaluation(ctx, evalTrees, prover, boxLoader, cache)

            val collatRetriever = new CollateralRetriever(client, prover)
            collatRetriever.checkCollateral(boxLoader)
            if (autoCommits)
              checkAutoCommits(ctx, diff, prover, boxLoader)
        }
      }.recoverWith{
        case e =>
          logger.error("Got top-level transformer error", e)
          Failure(e)
      }
      TRANSFORM_LOCK = false
    } else {
      logger.info("Skipped onSync due to transform lock")
    }
  }


  private def checkAutoCommits(ctx: BlockchainContext, diff: String, prover: NodeWallet, boxLoader: BoxLoader) = {
    Try{
      val dataNFT = Globals.mdDB.getDataBoxToken
      dataNFT match {
        case Some(nft) =>
          logger.info(s"Found saved DataBox token ${nft}")

          val tau = LFSMHelpers.parseDiffValueForStratum(diff)
          val score = LFSMHelpers.convertTauOrScore(tau.get)
          val dataContract = Helpers.dataBoxContract(ctx)
          val dataBox = LFSMHelpers.getLocalDataBox(ctx, nft, dataContract)
          if (dataBox.isFailure) {
            logger.warn(s"Could not find data box with saved id ${nft} on blockchain")
          } else {
            val dataInput = dataBox.get
            val commits = dataInput.registers.head.getValue.asInstanceOf[Coll[(Int, Long)]]
            val currentCommit = commits(0)
            // commit 0 is currently in effect
            if(ctx.getHeight - currentCommit._1 >= LFSMHelpers.NISP_WINDOW) {
              if (currentCommit._2 != score) {
                logger.info(s"Config has diff ${score} but currentCommit is ${currentCommit._2}")
                val newCommit = ctx.getHeight + DATA_BOX_BUFFER -> score.toLong
                val nextCommits = commits
                  .updated(0, newCommit)
                  .append(Colls.fromArray(Array(currentCommit)))
                logger.info(s"Next commitment set: ${newCommit}")
                val otherInputs = boxLoader.getInputs(Parameters.MinFee)
                val nextDataBox = dataInput.toUTXO.copy(
                  registers = dataInput.registers.updated(0, ErgoValue.ofColl(
                    nextCommits,
                    ErgoType.pairType(ErgoType.integerType(), ErgoType.longType())
                  ))
                )
                val proverContract = prover.contract
                val fee = UTXO(Contract(ErgoTreePredef.feeProposition(720)), Parameters.MinFee)
                val inputWithCtx = dataInput
                  .withCtxVar(0.toByte, ErgoValue.of(0.toByte))
                  .withCtxVar(ContextVar.of(1.toByte, proverContract.sigmaBoolean.get))

                val txB = TxBuilder(ctx)
                val uTx = txB
                  .setInputs((Seq(inputWithCtx) ++ otherInputs):_*)
                  .setOutputs(nextDataBox, fee)
                  .buildTx(0, prover.p2pk)

                val sTx = prover.sign(uTx)
                val txId = ctx.sendTransaction(sTx)
                logger.info(s"Sent transaction ${txId} to submit commitment ${newCommit} for data box ${nft}")
              } else {
                logger.info("No commitment change needed")
              }
            }else{
              logger.info(s"Not changing commits until next commit takes effect " +
                s"(height: ${ctx.getHeight}, commit: ${currentCommit})")
            }
          }
        case None =>
          logger.info("Cannot auto-commit due to no saved data box token")
      }
    }.recoverWith{
      case t: Throwable =>
        logger.error("Got error while attempting autoCommits", t)
        Failure(t)
    }


  }

  private def checkHoldingTransforms(ctx: BlockchainContext, holdingTrees: Seq[(String, NISPTree)],
                                     prover: NodeWallet, loader: BoxLoader, mempoolMap: Map[String, MempoolRollupState]): Unit = {
    val transformable = holdingTrees.filter(h => ctx.getHeight - h._2.currentPeriod.get >= LFSMHelpers.HOLDING_PERIOD)
    val transforms = transformable.map(t => Try(transformHolding(ctx, t, prover, loader, mempoolMap.get(t._1))))
    if(transforms.exists(_.isSuccess)) {
      logger.info(s"Transformed ${transforms.count(_.isSuccess)} holding utxos successfully")
    }
    if(transforms.exists(_.isFailure)) {
      logger.info(s"Failed to transform ${transforms.count(_.isFailure)} holding utxos")
      transforms.filter(_.isFailure).foreach {
        t =>
          t.failed.get match {
            case ds: ErgoClientException if ds.getMessage.contains("Double spending attempt") =>
              logger.warn("Skipped holding transformation due to double spend")
            case inp: NotEnoughInputsException =>
              logger.warn("Skipped holding transformation due to failure to find inputs")
            case e =>
              logger.error("Found error while transforming holdings", e)
          }

      }
    }
  }

  private def checkEvalTransforms(ctx: BlockchainContext, evalTrees: Seq[(String, NISPTree)],
                                  prover: NodeWallet, loader: BoxLoader, mempoolMap: Map[String, MempoolRollupState]): Unit = {
    val transformable = evalTrees.filter(h => ctx.getHeight - h._2.currentPeriod.get >= LFSMHelpers.EVAL_PERIOD)
    val transforms = transformable.map(t => Try(transformEval(ctx, t, prover, loader, mempoolMap.get(t._1))))
    if(transforms.exists(_.isSuccess)) {
      logger.info(s"Transformed ${transforms.count(_.isSuccess)} eval utxos successfully")
    }
    if(transforms.exists(_.isFailure)) {
      logger.info(s"Failed to transform ${transforms.count(_.isFailure)} eval utxos")
      transforms.filter(_.isFailure).foreach {
        t =>
          t.failed.get match {
            case ds: ErgoClientException if ds.getMessage.contains("Double spending attempt") =>
              logger.warn("Skipped eval transformation due to double spend")
            case inp: NotEnoughInputsException =>
              logger.warn("Skipped eval transformation due to failure to find inputs")
            case e =>
              logger.error("Found error while transforming evals", e)
          }
      }
    }
  }

  private def attemptEvaluation(ctx: BlockchainContext, evalTrees: Seq[(String, NISPTree)],
                                 prover: NodeWallet, loader: BoxLoader, cache: SyncCacheApi): Unit = {
    val transformable = evalTrees.filter(h => ctx.getHeight - h._2.currentPeriod.get < LFSMHelpers.EVAL_PERIOD)
    val unchecked = transformable.filter(!_._2.evaluated)
    val evaluations = unchecked.map(t => Try(evaluateSubmissions(ctx, t, prover, loader, cache)))
    if(evaluations.exists(_.isSuccess)) {
      logger.info(s"Evaluated ${evaluations.count(_.isSuccess)} eval utxos successfully")
    }
    if(evaluations.exists(_.isFailure)) {
      logger.info(s"Failed to evaluate ${evaluations.count(_.isFailure)} eval utxos")
      evaluations.filter(_.isFailure).foreach {
        t =>
          t.failed.get match {
            case ds: ErgoClientException if ds.getMessage.contains("Double spending attempt") =>
              logger.warn("Skipped evaluation due to double spend")
            case inp: NotEnoughInputsException =>
              logger.warn("Skipped evaluation due to failure to find inputs")
            case e =>
              logger.error("Found error while performing evaluations", e)
          }
      }
    }
  }

  private def attemptPayouts(ctx: BlockchainContext, payoutTrees: Seq[(String, NISPTree)],
                             prover: NodeWallet, loader: BoxLoader, mempoolMap: Map[String, MempoolRollupState]): Unit = {
    val transforms = payoutTrees.map(t => Try(payoutERG(ctx, t, prover, loader, mempoolMap.get(t._2.blockId))))
    if(transforms.exists(_.isSuccess)) {
      logger.info(s"Paid out ${transforms.count(_.isSuccess)} payout utxos successfully")
    }
    if(transforms.exists(_.isFailure)) {
      logger.info(s"Failed to pay out ${transforms.count(_.isFailure)} payout utxos")
      transforms.filter(_.isFailure).foreach {
        t =>
          t.failed.get match {
            case ds: ErgoClientException if ds.getMessage.contains("Double spending attempt") =>
              logger.warn("Skipped payout due to double spend")
            case inp: NotEnoughInputsException =>
              logger.warn("Skipped payout due to failure to find inputs")
            case e =>
              logger.error("Found error while attempting payouts", e)
          }
      }
    }
  }

  private def attemptHoldingSubmissions(ctx: BlockchainContext, holdingTrees: Seq[(String, NISPTree)],
                                        prover: NodeWallet, diff: String, loader: BoxLoader, cache: SyncCacheApi,
                                        syncHandler: ActorRef, mempoolMap: Map[String, MempoolRollupState]): Unit = {
    val transformable = holdingTrees.filter{
      h =>
        ctx.getHeight - h._2.currentPeriod.get < LFSMHelpers.HOLDING_PERIOD && !h._2.hasMiner
    }
    val transforms = transformable.map(t => Try(submitNISPs(ctx, t, prover, diff, loader, cache, syncHandler, mempoolMap.get(t._1))))
    if(transforms.exists(_.isSuccess)) {
      logger.info(s"Submitted NISPs to ${transforms.count(_.isSuccess)} holding utxos successfully")
    }
    if(transforms.exists(_.isFailure)) {
      logger.info(s"Failed to submit ${transforms.count(_.isFailure)} NISPs")
      transforms.filter(_.isFailure).foreach {
        t =>
          t.failed.get match {
            case f: NoValidNISPException =>
              logger.warn(f.getMessage)
            case ds: ErgoClientException if ds.getMessage.contains("Double spending attempt") =>
              logger.warn("Skipped NISP submission due to double spend")
            case ia: IllegalArgumentException if ia.getMessage.contains("lastHeight is undefined") =>
              logger.warn("Skipped NISP submission as no super shares have been found")
            case inp: NotEnoughInputsException =>
              logger.warn("Skipped NISP submission due to failure to find inputs")
            case e =>
              logger.error("Found error while submitting NISPs", e)
          }

      }
    }
  }

  private def transformHolding(ctx: BlockchainContext, holding: (String, NISPTree),
                               prover: NodeWallet, loader: BoxLoader, memState: Option[MempoolRollupState]) = {

    if(memState.isDefined)
      logger.info(s"Using mempool state for rollup ${holding._2.blockId}")
    val holdingInput = getRollupInput(ctx, holding._1, memState)
    val eval = evalContract(ctx)

    val otherInputs = loader.getInputs(Parameters.MinFee)
    val output = UTXO(eval, holdingInput.value, holdingInput.tokens,
      registers = Seq(
        holdingInput.registers.head,
        holdingInput.registers(1),
        holdingInput.registers(2),
        ErgoValue.of(ctx.getHeight.toLong),
        holdingInput.registers(3)
      ))
    val feeOutput = UTXO(Contract(ErgoTreePredef.feeProposition(720)), Parameters.MinFee)
    val uTx = TxBuilder(ctx)
      .setInputs((Seq(holdingInput) ++ otherInputs):_*)
      .setOutputs(output, feeOutput)
      .buildTx(0, prover.p2pk)
    val sTx = prover.sign(uTx)
    ctx.sendTransaction(sTx)
  }

  private def transformEval(ctx: BlockchainContext, eval: (String, NISPTree),
                            prover: NodeWallet, loader: BoxLoader, memState: Option[MempoolRollupState]): Unit = {
    if(memState.isDefined)
      logger.info(s"Using mempool state for rollup ${eval._2.blockId}")
    val evalInput = getRollupInput(ctx, eval._1, memState)
    val payout = payoutContract(ctx)

    val otherInputs = loader.getInputs(Parameters.MinFee)
    val output = UTXO(payout, evalInput.value, evalInput.tokens,
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
      .buildTx(0, prover.p2pk)
    val sTx = prover.sign(uTx)
    val txId = ctx.sendTransaction(sTx)
    logger.info(s"Sent transaction ${txId} to transform eval contract")
  }

  private def evaluateSubmissions(ctx: BlockchainContext, eval: (String, NISPTree),
                                  prover: NodeWallet, loader: BoxLoader, cache: SyncCacheApi): Unit = {
    logger.info(s"Evaluating NISP submissions for NISPTree ${eval._1}")
    val fpControl = LFSMHelpers.getFPControlBox(ctx)
    val evalBox = InputUTXO(ctx.getBoxesById(eval._1).head)
    // Sort miners randomly for more unique fp transactions, helps to prevent competing fraud proof checks
    // TODO: Optimize here by preventing redundant evaluations
    val currentMiners = eval._2.minerSet.toSeq.map(Hex.decode).sortBy(_ => Math.random())
    val evaluator = Evaluator(ctx, prover, evalBox, eval._2, currentMiners, fpControl, loader,
      FraudProofContracts.getFraudProofContracts(ctx))
    val attemptEval = Try(evaluator.evaluate)
    attemptEval match {
      case Failure(e) =>
        e match {
          case inp: NotEnoughInputsException =>
            logger.warn("Skipped evaluation due to lack of input utxos")
          case _ =>
            logger.error("Got error while attempting evaluation", e)
        }
      case Success(txs) =>
        if(txs.nonEmpty){
          logger.info(s"Got fraud proof transactions for NISPTree ${eval._1}")
          val txIds = for(t <- txs) yield ctx.sendTransaction(t)
          txIds.foreach(id => logger.info(s"Sent fraud proof transaction ${id} as part of evaluation"))
        }else{
          logger.info(s"Found no fraud in NISPTree ${eval._1}")
          cache.set(eval._1, eval._2.copy(evaluated = true))
        }
    }
  }
  def getCommitedScore(ctx: BlockchainContext, diff: String, reason: String): Try[Long] = {
    Try {
      val dataBoxNFT = Globals.mdDB.getDataBoxToken
      val score = LFSMHelpers.convertTauOrScore(BigInt(LFSMHelpers.parseDiffValueForStratum(diff).get))
      dataBoxNFT match {
        case Some(nft) =>
          val dataBox = LFSMHelpers.getLocalDataBox(ctx, nft, Helpers.dataBoxContract(ctx))
          dataBox match {
            case Failure(exception) =>
              logger.error(s"Got error while retrieving data box from blockchain. Defaulting to config diff value $diff")
              logger.error("NISPs submitted or mined in this manner may be considered fraudulent")
              logger.error("error: ", exception)
              score.toLong
            case Success(dataInput) =>
              val commits = dataInput.registers.head.getValue.asInstanceOf[Coll[(Int, Long)]]
              if (commits.size == 1) {
                logger.info(s"Using new diff commitment ${commits(0)} for $reason")
                commits(0)._2
              } else {
                if (ctx.getHeight - commits(0)._1 >= LFSMHelpers.NISP_WINDOW) {
                  logger.info(s"Using latest commitment ${commits(0)} for $reason")
                  commits(0)._2
                } else {
                  logger.info(s"Using old commitment ${commits(1)} for $reason")
                  commits(1)._2
                }
              }
          }
        case None =>
          logger.warn(s"No stored data box, forcing config diff value $diff and score $score")
          logger.warn("NISPs submitted or mined in this manner may be considered fraudulent")
          score.toLong
      }
    }
  }
  private def submitNISPs(ctx: BlockchainContext, holdTree: (String, NISPTree), prover: NodeWallet,
                          diff: String, loader: BoxLoader, cache: SyncCacheApi, syncHandler: ActorRef,
                          memState: Option[MempoolRollupState]): Unit = {
    if(memState.isDefined)
      logger.info(s"Using mempool state for rollup ${holdTree._2.blockId}")
    val holdingInput = getRollupInput(ctx, holdTree._1, memState)

    val nispDB = Globals.nispDB

    val commitedScore = getCommitedScore(ctx, diff, "NISP submission").get
    val bestNISP = nispDB.getBestValidNISP(holdingInput.registers(3).getValue.asInstanceOf[Long].toInt, commitedScore)


    bestNISP match {
      case Some(nisp) =>
        val holding = holdingContract(ctx)

        val otherInputs = loader.getInputs(Parameters.MinFee)
        val tree = holdTree._2.dictionary
        val copiedTree = tree.copy()
        copiedTree.prover.generateProof() // Reset proof for copied tree, proofs will be incorrect if this is not done!

        logger.info(s"Got valid NISP with score ${nisp.score}, heights" +
          s" ${nisp.shares.map(_.getHeight).mkString(", ")} and size ${nisp.serialize.length} bytes")

        val insert = copiedTree.insert(
          prover.contract.hashedPropBytes -> nisp.serialize)

        val inputWithContext = holdingInput.setCtxVars(
          ContextVar.of(0.toByte, ErgoValue.of(prover.contract.sigmaBoolean.get)),
          ContextVar.of(
            1.toByte,
            ErgoValue.pairOf(
              ErgoValue.ofColl(Colls.fromArray(prover.contract.hashedPropBytes), ErgoType.byteType()),
              nisp.ergoValue)
          ),
          ContextVar.of(2.toByte, insert.proof.ergoValue)
        )

        val lastMiners  = holdingInput.registers(1).getValue.asInstanceOf[Int]
        val lastScore   = holdingInput.registers(2).getValue.asInstanceOf[CBigInt].wrappedValue

        val output = UTXO(holding, holdingInput.value, holdingInput.tokens,
          registers = Seq(
            copiedTree.ergoValue,
            ErgoValue.of(lastMiners + 1),
            ErgoValue.of((commitedScore + BigInt(lastScore)).bigInteger),
            holdingInput.registers(3)
          ))

        val feeOutput = UTXO(Contract(ErgoTreePredef.feeProposition(720)), Parameters.MinFee)
        val uTx = TxBuilder(ctx)
          .setInputs((Seq(inputWithContext) ++ otherInputs): _*)
          .setOutputs(output, feeOutput)
          .buildTx(0, prover.p2pk)
        val sTx = prover.sign(uTx)
        val txId = ctx.sendTransaction(sTx)
        logger.info(s"Sent transaction ${txId} to submit NISP for NISPTree ${holdTree._2.blockId}")
      case None =>

        syncHandler ! RemoveRollup(holdTree._2.blockId, "Unable to submit valid NISP")
        throw new NoValidNISPException(s"Could not produce valid NISP for lithos-mined block ${holdTree._2.startHeight}" +
        s" with id ${holdTree._2.blockId}")
    }

  }

  private def payoutERG(ctx: BlockchainContext, payments: (String, NISPTree),
                        prover: NodeWallet, loader: BoxLoader, memState: Option[MempoolRollupState]): Unit = {
    logger.info(s"Paying out ERG for NISPTree ${payments._1}")
    if(memState.isDefined)
      logger.info(s"Using mempool state for rollup ${payments._2.blockId}")
    val payInput = getRollupInput(ctx, payments._1, memState)
    val payout = payoutContract(ctx)

    val otherInputs = loader.getInputs(Parameters.MinFee)
    val tree = payments._2.dictionary
    val copiedTree = tree.copy()
    copiedTree.prover.generateProof() // Reset proof for copied tree

    val lookUp = copiedTree.lookUp(prover.contract.hashedPropBytes)
    val delete = copiedTree.delete(prover.contract.hashedPropBytes)
    val score = Longs.fromByteArray(lookUp.response.head.ergoValue.getValue.toArray.slice(0, 8))
    val totalScore   = payInput.registers(2).getValue.asInstanceOf[CBigInt].wrappedValue
    val totalReward  = payInput.registers(3).getValue.asInstanceOf[Long]
    val totalTokens = payInput.tokens.headOption
    // The same is not true for totalScore, which stays constant during payouts, but is decreased during
    // evals
    val amountToPay = LFSMHelpers.paymentFromScore(score, totalScore, totalReward)
    val amountTokens = LFSMHelpers.paymentFromScore(score, totalScore, totalTokens.map(_.amount).getOrElse(0L))
    val inputWithContext = payInput.setCtxVars(
      ContextVar.of(0.toByte,
        ErgoValue.ofArray(Array(Colls.fromArray(prover.contract.hashedPropBytes)),
        ErgoType.collType(ErgoType.byteType()))),
      ContextVar.of(1.toByte, lookUp.proof.ergoValue),
      ContextVar.of(2.toByte, delete.proof.ergoValue)
    )
    if(amountToPay < payInput.value && payInput.value - amountToPay > 1000000L) {
      val nextTokens = {
        if(totalTokens.isDefined) {
          if(totalTokens.get.amount == amountTokens)
            Seq.empty[Token]
          else
            Seq(totalTokens.get - amountTokens)
        }else {
          Seq.empty[Token]
        }
      }
      val tokensOutputted = {
        if(totalTokens.isDefined) {
          Seq(Token(LFSMHelpers.LIT_ID, amountTokens))
        }else {
          Seq.empty[Token]
        }
      }
      val output = UTXO(payout, payInput.value - amountToPay, nextTokens,
        registers = Seq(
          copiedTree.ergoValue,
          payInput.registers(1),
          payInput.registers(2),
          payInput.registers(3)
        ))
      val minerOutput = UTXO(prover.contract, amountToPay, tokensOutputted)
      val feeOutput = UTXO(Contract(ErgoTreePredef.feeProposition(720)), Parameters.MinFee)
      val uTx = TxBuilder(ctx)
        .setInputs((Seq(inputWithContext) ++ otherInputs): _*)
        .setOutputs(output, minerOutput, feeOutput)
        .buildTx(0, prover.p2pk)
      val sTx = prover.sign(uTx)
      val txId = ctx.sendTransaction(sTx)
      logger.info(s"Sent transaction ${txId} to pay local miner")
    }else{

      val tokensOutputted = {
        if(totalTokens.isDefined) {
          Seq(Token(LFSMHelpers.LIT_ID, amountTokens))
        }else {
          Seq.empty[Token]
        }
      }
      val minerOutput = UTXO(prover.contract, amountToPay, tokensOutputted)
      val feeOutput = UTXO(Contract(ErgoTreePredef.feeProposition(720)), Parameters.MinFee)
      val uTx = TxBuilder(ctx)
        .setInputs((Seq(inputWithContext) ++ otherInputs): _*)
        .setOutputs(minerOutput, feeOutput)
        .buildTx(0, prover.p2pk)
      val sTx = prover.sign(uTx)
      val txId = ctx.sendTransaction(sTx)
      logger.info(s"Sent transaction ${txId} to pay local miner")
    }
  }

  private def getRollupInput(ctx: BlockchainContext, utxoId: String, memState: Option[MempoolRollupState]) = {
    if(memState.isDefined)
      memState.get.asInput
    else
      InputUTXO(ctx.getBoxesById(utxoId).head)
  }
}
