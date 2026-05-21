package transactions

import akka.actor.{Actor, ActorRef}
import akka.pattern.ask
import akka.util.Timeout
import mutations.NotEnoughInputsException
import org.ergoplatform.appkit._
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import play.api.cache.SyncCacheApi
import play.api.libs.concurrent.InjectedActorSupport
import state.messages.MempoolMessages.{RebuildMempoolChains, ResetMempoolState}
import state.messages.RollupMessages
import state.messages.RollupMessages.{GetCurrentRollup, RemoveRollup, RollupInfo}
import state.{DataBoxRetrievalException, LFSMTransformer, NoValidNISPException}
import transactions.SubmissionHandler._
import transactions.TransactionMessages.RollupTxType._
import transactions.TransactionMessages._
import transactions.WalletMessages.{ExcludeInputs, RefreshBoxes, RetrieveInputs, WalletInputs}
import utils.Globals
import work.lithos.mutations.{InputUTXO, Token, UTXO}

import javax.inject.{Inject, Named}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
 * Actor responsible for submitting batched rollup transactions to the node.
 * Receives RollupBatch messages from TransactionProcessor and performs
 * on-chain submission for each stub in priority order.
 */
class SubmissionHandler @Inject()(config: Configuration,
                                  cacheApi: SyncCacheApi,
                                  @Named("sync-handler") syncHandler: ActorRef,
                                  @Named("mempool-view") mempoolView: ActorRef,
                                  @Named("wallet-manager") walletManager: ActorRef)
  extends Actor with InjectedActorSupport {
  implicit val timeout: Timeout = Timeout(30.seconds)
  implicit val ec: ExecutionContext = context.dispatcher

  private val logger: Logger = LoggerFactory.getLogger("SubmissionHandler")
  private val nodeConfig = Globals.getNodeConfig
  private val client = nodeConfig.getClient
  private val wallet = nodeConfig.getNodeWallet
  // Map of pre-allocated UTXOs to be used for fee payments, enabling parallel tx attempts
  private var feeAllocations: Map[String, InputUTXO] = Map.empty
  private var inputsUsed: Seq[InputUTXO] = Seq.empty
  private var batchLock: Boolean = false

  override def receive: Receive = {
    case RollupBatch(stubs) =>
      if (!batchLock) {
        batchLock = true
        val initialTxInfo = InitialTxInfo(stubs.map {
          s =>
            if (s.txType != Payout)
              s.rollupBlockId -> s.fee
            else // Payouts should allocate a little extra, in case they can claim change with tokens
              s.rollupBlockId -> (s.fee + UTXO.MIN_FEE)
        }.toMap)
        logger.info("Creating initial transaction to handle RollupBatch")
        walletManager ! RefreshBoxes
        val initialTx = submitInitialTransaction(stubs, initialTxInfo)
        initialTx match {
          case Failure(_) =>
            logger.warn("Failed to produce an initial transaction from the RollupBatch")
          case Success(txId) =>
            logger.info(s"Successfully sent initial transaction with id $txId")
            logger.info(s"Now attempting ${feeAllocations.size} remaining  transactions")

            // Updates wallet manager to prevent issues with other txs later
            walletManager ! ExcludeInputs(feeAllocations.values.toSeq)
            walletManager ! ExcludeInputs(inputsUsed, dueToUsage = true)

            val remainingStubs = stubs.filter(s => feeAllocations.contains(s.rollupBlockId))
            submitRemainingTxs(remainingStubs)
        }
        batchLock = false
      } else {
        logger.info("Ignored incoming RollupBatch due to submission lock")
      }

  }

  // ─── submission ───────────────────────────────────────────────────────────

  /**
   * Gets rollup transaction function for a given stub
   */
  private def getRollupTransaction(stub: RollupTxStub,
                                   initialTxInfo: Option[InitialTxInfo]): (RollupTxStub, LatestRollup) => Try[String] = {
    stub.txType match {
      case NISPSubmission => sendNISPSubmission(_, _, initialTxInfo)
      case HoldingTransform => sendHoldingTransform(_, _, initialTxInfo)
      case EvalTransform => sendEvalTransform(_, _, initialTxInfo)
      case NISPEvaluation =>
        stub.fpInfo match {
          case Some(minerFraudProofData) =>
            sendFraudProof(_, _, initialTxInfo)
          case None =>
            // Should never happen
            (_, _) => Failure(new IllegalArgumentException("Got raw NISPEvaluation stub from RollupBatch"))
        }

      case Payout => sendPayout(_, _, initialTxInfo)
    }
  }

  /**
   * Get transaction outputs for the initial transaction in the batch
   *
   * @param stub          TxStub associated with this rollup tx
   * @param initialTxInfo Information required for initial transaction
   * @return Fee UTXO for the current transaction and a map of
   *         all rollups (not including the current stub) to the
   *         wallet UTXOs that will be used to pay fees in their transaction
   */
  private def initialTxOutputs(stub: RollupTxStub, initialTxInfo: InitialTxInfo): (UTXO, Map[String, UTXO]) = {
    val feeToPay = initialTxInfo.feesToCreate(stub.rollupBlockId)
    val feesToOutput = initialTxInfo.feesToCreate - stub.rollupBlockId
    val outputs = feesToOutput.map(f => f._1 -> UTXO(wallet.contract, f._2))
    UTXO.feeBox(feeToPay) -> outputs
  }

  /**
   * Load the required amount of UTXOs to pre-create wallet outputs
   *
   * @param initialTxInfo Information required for initial transaction
   * @return Sequence of InputUTXOs used in the initial transaction
   */
  private def initialTxInputs(initialTxInfo: InitialTxInfo): Seq[InputUTXO] = {
    val ergForFees = initialTxInfo.feesToCreate.values.toSeq.sum
    val feeInputs = getWalletInputs(ergForFees, Seq.empty, trackUsed = false)
    if (feeInputs.isEmpty)
      throw new NotEnoughInputsException("WalletManager could not return enough inputs for initial rollup tx")
    else {
      inputsUsed = feeInputs
      feeInputs
    }
    //    // In case we get a single box that is the exact value, but has tokens on it, we should get one more box
    //    // so we can create a change box without error or having to burn tokens.
    //    if (feeInputs.size == 1 && feeInputs.head.value == ergForFees && feeInputs.head.tokens.nonEmpty) {
    //      feeInputs ++ loader.getInputs(UTXO.MIN_FEE)
    //    } else
    //      feeInputs
  }

  /**
   * Retrieves wallet inputs for a rollup tx
   *
   * @param rollupTxStub  TxStub associated with this rollup tx
   * @param initialTxInfo Info for initialTx if it exists
   * @return Seq[InputUTXO] to be used as additional inputs in a tx
   */
  private def rollupWalletInputs(rollupTxStub: RollupTxStub, initialTxInfo: Option[InitialTxInfo]): Seq[InputUTXO] = {
    if (initialTxInfo.isDefined)
      initialTxInputs(initialTxInfo.get)
    else
      Seq(feeAllocations(rollupTxStub.rollupBlockId))
  }

  /**
   * Update the fee map after the initial transaction, so that later transactions can chain the
   * outputs of the initial transaction to pay their own fees.
   *
   * @param signed    Signed initial transaction
   * @param outputMap Output map which is the result of calling initialTxOutputs()
   */
  private def updateFeeMap(signed: SignedTransaction, outputMap: Map[String, UTXO]): Unit = {
    val outToSpend = JavaHelpers.toIndexedSeq(signed.getOutputsToSpend).map(InputUTXO(_))
    feeAllocations = outputMap.keys.zip(outToSpend.slice(2, outputMap.size + 2)).toMap
  }


  private def mkFeeOutputs(stub: RollupTxStub, initialOutputs: Option[(UTXO, Map[String, UTXO])]): Seq[UTXO] = {
    if (initialOutputs.isDefined) {
      Seq(initialOutputs.get._1) ++ initialOutputs.get._2.values.toSeq
    }
    else
      Seq(UTXO.feeBox(stub.fee))
  }

  /**
   * Attempt submission of the initial transaction from the rollup batch
   *
   * @param stubs         Stubs received from the RollupBatch
   * @param initialTxInfo Information required for the initial transaction
   * @return Most recent initial transaction attempt, either holding the successful transaction id
   *         or the exception associated with the attempt
   */
  private def submitInitialTransaction(stubs: Seq[RollupTxStub], initialTxInfo: InitialTxInfo) = {
    val initTx: Try[String] = Failure.apply(new RuntimeException("InitTx never initialized"))

    stubs.foldLeft((initTx, initialTxInfo)) {
      (z, s) =>
        if (z._1.isFailure) {
          val nextInit = attemptTx[RollupTxStub, LatestRollup](getRollupTransaction(s, Some(initialTxInfo)), latestRollupState, s)

          val attemptFailure = (nextInit, z._2.copy(z._2.feesToCreate - s.rollupBlockId))
          val attemptSuccess = (nextInit, z._2)

          nextInit match {
            case Failure(init) if init.getMessage == "InitTx never initialized" =>
              attemptFailure
            case Failure(mal: ErgoClientException) if mal.getMessage.contains("Every input of the transaction should be in UTXO") =>
              syncHandler ! ResetMempoolState(s.rollupBlockId)
              logger.warn(s"Got de-synced mempool state for rollup ${s.rollupBlockId} attempting ${s.txType}")
              attemptFailure
            case Failure(_: NoValidNISPException) =>
              attemptFailure
            case Failure(_: IllegalStateException) =>
              attemptFailure
            case Failure(_: RollupRemovedException) =>
              attemptFailure
            case Failure(_: StubInvalidException) =>
              attemptFailure
            case Failure(_: NotEnoughInputsException) =>
              attemptFailure
            case Failure(ex) =>
              logger.error(s"Got error while submitting initial transaction with rollup ${s.rollupBlockId}", ex)
              attemptFailure
            case Success(_) =>
              attemptSuccess
          }
        } else {
          z
        }
    }._1
  }


  private def submitRemainingTxs(remainingStubs: Seq[RollupTxStub]): Unit = {
    val stubAttempts = remainingStubs.map {
      s =>
        s -> Future {
          attemptTx[RollupTxStub, LatestRollup](getRollupTransaction(s, None), latestRollupState, s)
        }
    }
    stubAttempts.foreach(s => s._2.onComplete {
      case Failure(attemptThreadEx) =>
        logger.error(s"Got unexpected error in tx attempt thread for ${s._1}", attemptThreadEx)
      case Success(txAttempt) =>
        txAttempt match {
          case Failure(mal: ErgoClientException) if mal.getMessage.contains("Every input of the transaction should be in UTXO") =>
            syncHandler ! ResetMempoolState(s._1.rollupBlockId)
            logger.warn(s"Got de-synced mempool state for rollup ${s._1.rollupBlockId} attempting ${s._1.txType}")
          case Failure(ds: ErgoClientException) if ds.getMessage.contains("Double spending") =>
            logger.warn(s"Got double spend for rollup ${s._1.rollupBlockId} attempting ${s._1.txType}")
          case Failure(_: NoValidNISPException) =>
            ()
          case Failure(_: IllegalStateException) =>
            ()
          case Failure(_: RollupRemovedException) =>
            ()
          case Failure(_: StubInvalidException) =>
            ()
          case Failure(exception) =>
            logger.error(s"Got failure for rollup ${s._1.rollupBlockId} attempting ${s._1.txType}", exception)
          case Success(_) =>
            ()
        }
    })
  }


  private def sendNISPSubmission(stub: RollupTxStub,
                                 latestState: LatestRollup,
                                 initialTxInfo: Option[InitialTxInfo]): Try[String] = {
    Try {
      client.execute {
        ctx =>
          checkRollupStubValidity(ctx, stub, latestState)
          val score = LFSMTransformer.getCommitmentsForNISP(client, latestState.NISPTree.startHeight).get
          val nispDB = Globals.nispDB
          val holdingInput = latestState.inputUTXO
          val bestNISP = nispDB.getBestValidNISP(holdingInput.registers(3).getValue.asInstanceOf[Long].toInt, score)
          val initOutputs = initialTxInfo.map(initialTxOutputs(stub, _))
          val feeOutputs = mkFeeOutputs(stub, initOutputs)
          bestNISP match {
            case Some(nisp) =>

              logger.info(s"Got valid NISP for lithos-mined block ${latestState.NISPTree.startHeight} with score ${nisp.score}, heights" +
                s" ${nisp.shares.map(_.getHeight).mkString(", ")} and size ${nisp.serialize.length} bytes")

              val sTx = RollupTransactions.genNISPSubmission(ctx, wallet, holdingInput,
                rollupWalletInputs(stub, initialTxInfo), latestState, feeOutputs, nisp, score)

              if (initOutputs.isDefined)
                updateFeeMap(sTx, initOutputs.get._2)
              val txId = ctx.sendTransaction(sTx).replace("\"", "")
              logger.info(s"Sent transaction ${txId} to submit NISP for rollup ${stub.rollupBlockId}")
              txId
            case None =>
              syncHandler ! RemoveRollup(stub.rollupBlockId, "Unable to submit valid NISP")
              throw new NoValidNISPException(s"Could not produce valid NISP for lithos-mined block ${latestState.NISPTree.startHeight}" +
                s" with id ${stub.rollupBlockId}")
          }
      }
    }
  }

  private def sendHoldingTransform(stub: RollupTxStub,
                                   latestState: LatestRollup,
                                   initialTxInfo: Option[InitialTxInfo]): Try[String] = {
    Try {
      client.execute {
        ctx =>
          checkRollupStubValidity(ctx, stub, latestState)
          val initOutputs = initialTxInfo.map(initialTxOutputs(stub, _))
          val feeOutputs = mkFeeOutputs(stub, initOutputs)
          val sTx = RollupTransactions.genHoldingTransform(ctx, wallet, latestState.inputUTXO,
            rollupWalletInputs(stub, initialTxInfo), feeOutputs)
          if (initOutputs.isDefined)
            updateFeeMap(sTx, initOutputs.get._2)
          val txId = ctx.sendTransaction(sTx).replace("\"", "")
          logger.info(s"Sent transaction ${txId} to transform holding contract for rollup ${stub.rollupBlockId}")
          txId
      }
    }
  }

  private def sendEvalTransform(stub: RollupTxStub,
                                latestState: LatestRollup,
                                initialTxInfo: Option[InitialTxInfo]): Try[String] = {
    Try {
      client.execute {
        ctx =>
          checkRollupStubValidity(ctx, stub, latestState)
          val initOutputs = initialTxInfo.map(initialTxOutputs(stub, _))
          val feeOutputs = mkFeeOutputs(stub, initOutputs)
          val sTx = RollupTransactions.genEvalTransform(ctx, wallet, latestState.inputUTXO,
            rollupWalletInputs(stub, initialTxInfo), feeOutputs)
          if (initOutputs.isDefined)
            updateFeeMap(sTx, initOutputs.get._2)
          val txId = ctx.sendTransaction(sTx).replace("\"", "")
          logger.info(s"Sent transaction ${txId} to transform evaluation contract for rollup ${stub.rollupBlockId}")
          txId
      }
    }
  }

  private def sendFraudProof(stub: RollupTxStub,
                             latestState: LatestRollup,
                             initialTxInfo: Option[InitialTxInfo]): Try[String] = {
    Try("")
  }

  private def sendPayout(stub: RollupTxStub,
                         latestState: LatestRollup,
                         initialTxInfo: Option[InitialTxInfo]): Try[String] = {
    Try {
      client.execute {
        ctx =>
          val initOutputs = initialTxInfo.map(initialTxOutputs(stub, _))
          val feeOutputs = mkFeeOutputs(stub, initOutputs)
          val sTx = RollupTransactions.genPayout(ctx, wallet, latestState.inputUTXO,
            rollupWalletInputs(stub, initialTxInfo), latestState, feeOutputs)
          if (initOutputs.isDefined)
            updateFeeMap(sTx, initOutputs.get._2)
          val txId = ctx.sendTransaction(sTx).replace("\"", "")
          logger.info(s"Sent transaction ${txId} to payout local miner for rollup ${stub.rollupBlockId}")
          txId
      }
    }
  }

  // ─── retry logic ──────────────────────────────────────────────────────────

  /**
   * Attempts to execute txFunc up to MAX_TX_ATTEMPTS times.
   *
   * On each attempt it calls latestState to obtain a fresh InputUTXO, then
   * passes it together with the stub into txFunc. A Failure wrapping an
   * ErgoClientException triggers a sleep of ATTEMPT_INTERVAL milliseconds
   * before the next attempt. Any other exception, or exhaustion of all
   * attempts, causes the last Failure to be returned immediately.
   * A Success is returned as soon as txFunc succeeds.
   */
  private def attemptTx[T <: TxStub, L <: LatestState](
                                                        txFunc: (T, L) => Try[String],
                                                        latestState: T => Try[L],
                                                        stub: T): Try[String] = {
    var attempts = 0
    var lastResult: Try[String] = Failure(new RuntimeException("No attempts made"))

    def expectedFailure(): Unit = {
      attempts += 1
      if (attempts < MAX_TX_ATTEMPTS) Thread.sleep(ATTEMPT_INTERVAL)
    }

    while (attempts < MAX_TX_ATTEMPTS) {
      val retrieveLastState = latestState(stub)
      lastResult = retrieveLastState.map(lastState => txFunc(stub, lastState)).flatten
      lastResult match {
        case _: Success[String] =>
          return lastResult
        case Failure(ds: ErgoClientException) if ds.getMessage.contains("Double spending") =>
          mempoolView ! RebuildMempoolChains
          expectedFailure()
        case Failure(_: ErgoClientException) =>
          expectedFailure()
        case Failure(_: DataBoxRetrievalException) =>
          expectedFailure()
        case Failure(nv: NoValidNISPException) =>
          logger.warn(nv.getMessage)
          return lastResult
        case Failure(illegalState: IllegalStateException) =>
          logger.warn(illegalState.getMessage)
          return lastResult
        case Failure(removed: RollupRemovedException) =>
          logger.warn(removed.getMessage)
          return lastResult
        case Failure(invalidated: StubInvalidException) =>
          logger.warn(invalidated.getMessage)
          return lastResult
        case Failure(_: NotEnoughInputsException) =>
          // Can only happen if this is an initial tx candidate, so we can
          // let real error handling happen there. However, it will not get solved
          // by re-attempts so it's still best to just return out
          return lastResult
        case Failure(ex) =>
          logger.error(s"Got uncommon error when attempting transaction $stub", ex)
          return lastResult
      }
    }
    lastResult
  }

  private def getWalletInputs(amnt: Long, tokens: Seq[Token], trackUsed: Boolean): Seq[InputUTXO] = {
    Await.result((walletManager ? RetrieveInputs(amnt, tokens, trackUsed = trackUsed)).mapTo[WalletInputs], 5.seconds).inputs
  }

  private def checkRollupStubValidity(ctx: BlockchainContext, stub: RollupTxStub, latestRollup: LatestRollup): Unit = {
    if (!stub.validate(ctx.getHeight, latestRollup.NISPTree))
      throw StubInvalidException(s"Invalid ${stub.txType} stub for rollup ${stub.rollupBlockId}")
  }

  private def latestRollupState(rollupTxStub: RollupTxStub) = {
    Try {
      val rollupInfo = Await.result[RollupInfo]((syncHandler ? GetCurrentRollup(rollupTxStub.rollupBlockId)).mapTo[RollupInfo], 5.seconds)
      rollupInfo match {
        case RollupMessages.CurrentRollup(utxoId, nispTree, mempoolState) =>
          if (mempoolState.isDefined) {
            if (!mempoolState.get.toBeRemoved) {
              logger.info(s"Using mempool state for rollup ${rollupTxStub.rollupBlockId}" +
                s" with synced id $utxoId and mempool id ${mempoolState.get.asInput.id}")
              Success(LatestRollup(mempoolState.get.asInput, mempoolState.get.nispTree))
            } else {
              Failure(RollupRemovedException(s"Cannot send transaction for rollup" +
                s" ${rollupTxStub.rollupBlockId} with upcoming removal"))
            }
          } else {
            Success(LatestRollup(InputUTXO(client.execute(_.getBoxesById(utxoId).head)), nispTree))
          }
        case RollupMessages.NoRollupFound() =>
          Failure(new IllegalStateException(s"No existing rollup for blockId ${rollupTxStub.rollupBlockId}"))
      }
    }.flatten
  }
}

object SubmissionHandler {
  /** Maximum number of times attemptTx will retry on an ErgoClientException. */
  final val MAX_TX_ATTEMPTS: Int = 5

  /** Milliseconds to wait between retry attempts in attemptTx. */
  final val ATTEMPT_INTERVAL: Int = 5000

  case class InitialTxInfo(feesToCreate: Map[String, Long])
}
