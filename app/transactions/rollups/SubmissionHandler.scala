package transactions.rollups

import akka.actor.{Actor, ActorRef}
import akka.pattern.ask
import akka.util.Timeout
import configs.{NodeContext, StateConfig, StratumConfig, TasksConfig}
import lfsm.contracts.FraudProofContracts
import mutations.NotEnoughInputsException
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit._
import org.ergoplatform.sdk.JavaHelpers
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import play.api.cache.SyncCacheApi
import play.api.libs.concurrent.InjectedActorSupport
import state.messages.MempoolMessages.{RebuildMempoolChains, ResetMempoolState}
import state.messages.RollupMessages
import state.messages.RollupMessages.{GetCurrentRollup, RemoveRollup, RollupInfo}
import state.DataBoxRetrievalException
import transactions.BlockTxMessages.{BlockTxsReady, CandidateTx}
import transactions.rollups.SubmissionHandler._
import transactions.rollups.TransactionMessages.RollupTxType._
import transactions.rollups.TransactionMessages._
import transactions.wallet.{WalletReservation, WalletSelector}
import utils.Globals
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

import javax.inject.{Inject, Named}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future, blocking}
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal

/**
 * Actor responsible for submitting batched rollup transactions to the node.
 * Receives RollupBatch messages from TransactionProcessor and performs
 * on-chain submission for each stub in priority order.
 */
class SubmissionHandler @Inject()(config: Configuration, nodeContext: NodeContext,
                                  cacheApi: SyncCacheApi,
                                  dataBoxes: DataBoxSource,
                                  @Named("sync-handler") syncHandler: ActorRef,
                                  @Named("mempool-view") mempoolView: ActorRef,
                                  @Named("wallet-manager") walletManager: ActorRef)
  extends Actor with InjectedActorSupport {
  implicit val timeout: Timeout = Timeout(30.seconds)
  implicit val ec: ExecutionContext = context.dispatcher

  private val walletSelector = WalletSelector(walletManager, 5.seconds, ec)
  private val commitments = new CommitmentTransactions(nodeContext, dataBoxes)

  private val logger: Logger = LoggerFactory.getLogger("SubmissionHandler")
  private val nodeConfig: NodeContext = nodeContext
  private val stateConfig = new StateConfig(config)
  private val stratumConfig = new StratumConfig(config)
  /**
   * Whether MDSyncTask runs, read once and tolerantly.
   *
   * It only decides which of three warnings to print when no data box exists. Constructing a whole
   * `TasksConfig` per batch to get it read nine keys with `get`, so a config missing the
   * `lithos-tasks` block threw out of `runBatch` and took every stub in the batch with it — a log
   * line costing the miner its NISP submissions.
   */
  private val dictionarySyncEnabled: Boolean =
    TasksConfig.isEnabled(config, TasksConfig.DictionarySync)
  private val client = nodeConfig.getClient
  private val wallet = nodeConfig.getNodeWallet
  // Map of pre-allocated UTXOs to be used for fee payments, enabling parallel tx attempts
  private var feeAllocations: Map[String, InputUTXO] = Map.empty
  /** Exact actor-owned holds for feeAllocations; one follows each allocation into its spend. */
  private var feeAllocationReservations: Map[String, WalletReservation] = Map.empty
  private var initialReservation: Option[WalletReservation] = None
  private var batchLock: Boolean = false

  override def receive: Receive = {
    // Off the mailbox. A batch is up to five attemptTx retries with five-second sleeps per stub,
    // plus selection asks, signing and node round trips — tens of seconds. This actor also answers
    // BuildBlockTxs, which is a miner assembling a block against a 20-second budget, so running the
    // batch inline silently cost every block its inserted transactions.
    //
    // It also makes batchLock mean something. Inline, nothing else could be processed while the
    // batch ran, so the lock guarded nothing — while submitRemainingTxs' Futures went on reading
    // feeAllocations and inputsUsed after receive returned, where a second batch could overwrite
    // both underneath them.
    case RollupBatch(stubs) =>
      if (batchLock)
        // Unacknowledged on purpose: the sender keeps these stubs and offers them again next tick.
        logger.info("Ignored incoming RollupBatch due to submission lock")
      else {
        batchLock = true
        // Acknowledged on acceptance rather than on completion. A batch that fails part way has
        // still consumed its stubs — the failures are per-stub and retried inside — whereas one that
        // was never started must not lose them.
        sender() ! BatchAccepted(stubs)
        // flatMap, so the lock is held until the parallel attempts finish too. Releasing when the
        // initial transaction is done would let the next batch overwrite feeAllocations while they
        // are still reading it.
        Future(runBatch(stubs)).flatMap(identity).onComplete {
          case Success(_) => self ! BatchFinished
          case Failure(ex) =>
            logger.error("RollupBatch failed outside of transaction handling", ex)
            self ! BatchFinished
        }
      }

    case BatchFinished =>
      releaseFeeAllocations()
      batchLock = false

    // A block is being assembled: build the same work fee-less and hand it back without
    // sending. The stubs stay queued so the funded copies still reach the mempool.
    case BuildBlockTxs(blockHeight, stubs) =>
      val replyTo = sender()
      Future {
        stubs.flatMap(buildFeeless)
      }.onComplete {
        case Success(built) =>
          if (built.nonEmpty)
            logger.info(s"Offering ${built.size} fee-less transaction(s) for block $blockHeight: " +
              built.map(_.kind).mkString(", "))
          replyTo ! BlockTxsReady(blockHeight, built)
        case Failure(ex) =>
          logger.warn(s"No rollup transactions for block $blockHeight: ${ex.getMessage}")
          replyTo ! BlockTxsReady(blockHeight, Seq.empty[CandidateTx])
      }
  }

  /**
   * One batch, start to finish. Runs in a single Future, so `feeAllocations` and `inputsUsed` are
   * touched by one thread at a time for as long as `batchLock` is held — including by the parallel
   * Futures `submitRemainingTxs` spawns, which are created after those fields are written.
   */
  private def runBatch(stubs: Seq[RollupTxStub]): Future[Unit] = {
    val initialTxInfo = InitialTxInfo(stubs.map { s =>
      if (s.txType != Payout)
        s.rollupBlockId -> s.fee
      else // Payouts should allocate a little extra, in case they can claim change with tokens
        s.rollupBlockId -> (s.fee + UTXO.MIN_FEE)
    }.toMap)
    logger.info("Creating initial transaction to handle RollupBatch")
    // No RefreshBoxes here: it is fire-and-forget, so its BoxesRefreshed lands behind the ask
    // below and the selection would use the old set anyway. Freshness comes from the refresh
    // ticker and from ReturnInputs handing change back.
    val stubsReordered = {
      if (stubs.exists(_.fpInfo.isDefined)) {
        // Avoid letting fp stubs be the initial tx, since max inputs on them is 2
        val nonFPIndex = stubs.indexWhere(_.fpInfo.isEmpty)
        if (nonFPIndex != -1)
          stubs(nonFPIndex) +: stubs.filter(_.rollupBlockId != stubs(nonFPIndex).rollupBlockId)
        else // No choice, only fp txs exist
          stubs
      } else
        stubs
    }

    submitInitialTransaction(stubsReordered, initialTxInfo) match {
      case Failure(_) =>
        logger.warn("Failed to produce an initial transaction from the RollupBatch")
        // Nothing was sent, so the last attempt's inputs are still ours to give back. Leaving them
        // reserved would hold them until the reservation ages out.
        releaseInitialInputs()
        Future.successful(())

      case Success(txId) =>
        logger.info(s"Successfully sent initial transaction with id $txId")
        logger.info(s"Now attempting ${feeAllocations.size} remaining  transactions")

        val remainingStubs = stubs.filter(s => feeAllocations.contains(s.rollupBlockId))
        val remaining = submitRemainingTxs(remainingStubs)

        if (stateConfig.autoCommit.getOrElse(true)) {
          logger.info("Auto-commits were enabled, now checking difficulty commitment state")
          // Send auto commitment transaction
          commitments.commitScore(stratumConfig.diff, walletSelector) match {
            case Success(_) => ()
            case Failure(ex) => logger.error("Got exception during end of submission", ex)
          }
        }

        remaining
    }
  }

  // ─── fee-less builds for this miner's own block ─────────────────────────────

  /**
   * The same transaction the normal path would send, with no fee output and no wallet input.
   *
   * Every rollup phase recreates its box at the same value, so these balance with nothing added.
   * That also means they touch no wallet UTXO and cannot contend with the fee allocations the
   * funded path depends on. Built against the same mempool-aware state, so anything chaining off an
   * unconfirmed parent stays valid.
   */
  private def buildFeeless(stub: RollupTxStub): Option[CandidateTx] =
    latestRollupState(stub).flatMap { latest =>
      Try {
        client.execute { ctx =>
          checkRollupStubValidity(ctx, stub, latest)
          val none = Seq.empty[InputUTXO]
          val noFee = Seq.empty[UTXO]
          stub.txType match {
            case NISPSubmission =>
              val score = commitments.commitmentForNISP(latest.NISPTree.startHeight).get
              val holdingInput = latest.inputUTXO
              Globals.nispDB.getBestValidNISP(
                holdingInput.registers(3).getValue.asInstanceOf[Long].toInt, score) match {
                case Some(nisp) =>
                  val sTx = RollupTransactions.genNISPSubmission(
                    ctx, wallet, holdingInput, none, latest, noFee, nisp, score)
                  candidateTx(sTx, CandidateTx.NispSubmission)
                case None =>
                  throw new NoValidNISPException(
                    s"no valid NISP for rollup ${stub.rollupBlockId}")
              }

            case HoldingTransform =>
              candidateTx(
                RollupTransactions.genHoldingTransform(ctx, wallet, latest.inputUTXO, none, noFee),
                CandidateTx.HoldingTransform)

            case EvalTransform =>
              candidateTx(
                RollupTransactions.genEvalTransform(ctx, wallet, latest.inputUTXO, none, noFee),
                CandidateTx.EvalTransform)

            case Payout =>
              candidateTx(
                RollupTransactions.genPayout(ctx, wallet, latest.inputUTXO, none, latest, noFee),
                CandidateTx.Payout)

            case NISPEvaluation =>
              // The funded path works; this one does not yet. A fraud proof's context variables
              // ride on a wallet input, so unlike the phases above it cannot balance on the rollup
              // box alone. Explicit rather than falling through, because fraud proofs are the
              // highest-priority thing this method should return once that is solved.
              throw new IllegalStateException("fraud proofs cannot yet be built without wallet inputs")
          }
        }
      }
    } match {
      case Success(tx) => Some(tx)
      case Failure(ex) =>
        logger.info(s"Skipping [${stub.txType}] for rollup ${stub.rollupBlockId} " +
          s"in the block package: ${ex.getMessage}")
        None
    }

  private def candidateTx(sTx: SignedTransaction, kind: String): CandidateTx =
    CandidateTx(sTx.getId.replace("\"", ""), sTx.toJson(false, false), kind)

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
          case Some(_) =>
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
  private def initialTxInputs(initialTxInfo: InitialTxInfo, isFPTx: Boolean = false): Seq[InputUTXO] = {
    // Reaching here again means the previous attempt never sent, so give its inputs back before
    // reserving more — five attempts each holding a disjoint set would drain the wallet.
    releaseInitialInputs()

    val ergForFees = initialTxInfo.feesToCreate.values.toSeq.sum
    // Reserved on selection, not after the send. EmissionHandler draws from the same WalletManager,
    // and the gap between selecting and sending spans retries and their sleeps.
    val reservation =
      if (!isFPTx) walletSelector.reserve(ergForFees)
      else walletSelector.reserveCovering(ergForFees)
    val feeInputs = reservation.inputs
    if (feeInputs.isEmpty)
      throw new NotEnoughInputsException("WalletManager could not return enough inputs for initial rollup tx")
    else {
      initialReservation = Some(reservation)
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
      initialTxInputs(initialTxInfo.get, rollupTxStub.fpInfo.isDefined)
    else
      Seq(feeAllocations(rollupTxStub.rollupBlockId))
  }

  /**
   * Update the fee map after the initial transaction, so that later transactions can chain the
   * outputs of the initial transaction to pay their own fees.
   *
   * The wallet outputs are found by locating the FEE BOX and taking what follows, rather than by a
   * fixed index. `mkFeeOutputs` always emits `feeBox +: walletOuts` as one run at the end, but the
   * number of outputs in front of it is per-builder: one for the rollup phases and the fee-less
   * fraud proof, but TWO for a Payout that pays a miner as well as recreating its box. A constant
   * offset handed the first rollup the fee box — unsignable, so its transaction failed every attempt
   * — and shifted every other rollup onto a neighbour's box, which can be worth less than its fee.
   *
   * @param signed    Signed initial transaction
   * @param outputMap Output map which is the result of calling initialTxOutputs()
   */
  private def updateFeeMap(signed: SignedTransaction, outputMap: Map[String, UTXO]): Unit = {
    val outToSpend = JavaHelpers.toIndexedSeq(signed.getOutputsToSpend).map(InputUTXO(_))
    val allocations = walletOutputsAfterFee(outToSpend, outputMap.size)
    if (allocations.isEmpty && outputMap.nonEmpty)
      // Cannot happen on a funded path: mkFeeOutputs put a fee box there. Refuse to guess rather
      // than map rollups onto arbitrary outputs — an empty map leaves the stubs queued for a retry.
      logger.error(s"Initial transaction ${signed.getId} carries no fee output, so its wallet " +
        "outputs cannot be located. No fee allocations made; the batch's remaining stubs will retry")
    val mapped = outputMap.keys.zip(allocations).toSeq
    releaseFeeAllocations()
    var held = Map.empty[String, WalletReservation]
    try {
      mapped.foreach { case (rollupId, allocation) =>
        held += rollupId -> walletSelector.reserveKnown(Seq(allocation))
      }
      feeAllocations = mapped.toMap
      feeAllocationReservations = held
    } catch {
      case NonFatal(ex) =>
        held.values.foreach(_.release())
        feeAllocations = Map.empty
        feeAllocationReservations = Map.empty
        throw ex
    }
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
    // We do not participate in rollup transactions if there is no data box created.
    if(dataBoxes.getDataBoxToken.isEmpty){
      logger.warn("No saved data box token was found")
      val isEnabled = dictionarySyncEnabled
      if(isEnabled && !Globals.getMDSyncState){
        logger.warn("Please wait for MDSyncTask to complete MinerDictionary synchronization")
        Failure(new DataBoxRetrievalException("Could not find a stored data box"))
      }else if(isEnabled && Globals.getMDSyncState){
        logger.warn("MDSyncTask has completed synchronization. Please wait for a data box to be created.")
        logger.warn("If this message persists, there may be an issue with sending the MinerDictionary transaction.")
        Failure(new DataBoxRetrievalException("Could not find a stored data box"))
      }else{
        logger.warn("Please enable MDSyncTask in your config to create or synchronize to your data box.")
        Failure(new DataBoxRetrievalException("Could not find a stored data box"))
      }
    }else {
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
  }


  /**
   * The rest of the batch, in parallel. The returned Future completes only when every attempt has,
   * which is what keeps `batchLock` covering the fields these read.
   */
  private def submitRemainingTxs(remainingStubs: Seq[RollupTxStub]): Future[Unit] = {
    val attempts: Seq[Future[Unit]] = remainingStubs.map { s =>
      Future(attemptTx[RollupTxStub, LatestRollup](getRollupTransaction(s, None), latestRollupState, s))
        .map(reportAttempt(s, _))
        .recover {
          case attemptThreadEx =>
            logger.error(s"Got unexpected error in tx attempt thread for $s", attemptThreadEx)
        }
    }
    Future.sequence(attempts).map(_ => ())
  }

  private def reportAttempt(stub: RollupTxStub, txAttempt: Try[String]): Unit =
    txAttempt match {
      case Failure(mal: ErgoClientException) if mal.getMessage.contains("Every input of the transaction should be in UTXO") =>
        syncHandler ! ResetMempoolState(stub.rollupBlockId)
        logger.warn(s"Got de-synced mempool state for rollup ${stub.rollupBlockId} attempting ${stub.txType}")
      case Failure(ds: ErgoClientException) if ds.getMessage.contains("Double spending") =>
        logger.warn(s"Got double spend for rollup ${stub.rollupBlockId} attempting ${stub.txType}")
      case Failure(_: NoValidNISPException) =>
        ()
      case Failure(_: IllegalStateException) =>
        ()
      case Failure(_: RollupRemovedException) =>
        ()
      case Failure(_: StubInvalidException) =>
        ()
      case Failure(exception) =>
        logger.error(s"Got failure for rollup ${stub.rollupBlockId} attempting ${stub.txType}", exception)
      case Success(_) =>
        ()
    }


  private def sendNISPSubmission(stub: RollupTxStub,
                                 latestState: LatestRollup,
                                 initialTxInfo: Option[InitialTxInfo]): Try[String] = {
    Try {
      client.execute {
        ctx =>
          checkRollupStubValidity(ctx, stub, latestState)
          val score = commitments.commitmentForNISP(latestState.NISPTree.startHeight).get
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
              val txId = submitSigned(ctx, sTx, initialTxInfo.isDefined, stub.rollupBlockId)
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
          val txId = submitSigned(ctx, sTx, initialTxInfo.isDefined, stub.rollupBlockId)
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
          val txId = submitSigned(ctx, sTx, initialTxInfo.isDefined, stub.rollupBlockId)
          logger.info(s"Sent transaction ${txId} to transform evaluation contract for rollup ${stub.rollupBlockId}")
          txId
      }
    }
  }

  private def sendFraudProof(stub: RollupTxStub,
                             latestState: LatestRollup,
                             initialTxInfo: Option[InitialTxInfo]): Try[String] = {
    Try {
      client.execute {
        ctx =>
          checkRollupStubValidity(ctx, stub, latestState)

          val initOutputs = initialTxInfo.map(initialTxOutputs(stub, _))
          val feeOutputs = mkFeeOutputs(stub, initOutputs)
          val sTx = RollupTransactions.genFraudProofTransform(ctx, wallet, latestState.inputUTXO,
            rollupWalletInputs(stub, initialTxInfo), latestState, feeOutputs, stub.fpInfo.get._1,
            stub.fpInfo.get._2)
          if (initOutputs.isDefined)
            updateFeeMap(sTx, initOutputs.get._2)
          val txId = submitSigned(ctx, sTx, initialTxInfo.isDefined, stub.rollupBlockId)
          logger.info(s"Sent transaction ${txId} to submit fraud proof for miner ${Hex.toHexString(stub.fpInfo.get._1)}" +
            s" for rollup ${stub.rollupBlockId}")
          txId
      }
    }
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
          val txId = submitSigned(ctx, sTx, initialTxInfo.isDefined, stub.rollupBlockId)
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

    // `blocking` matters: these run as parallel Futures on the same fork-join pool as WalletManager,
    // and callers Await on that mailbox. Without it, enough concurrent retries sleeping starve the
    // pool and those Awaits time out, which surfaces as transactions failing for no visible reason.
    def expectedFailure(): Unit = {
      attempts += 1
      if (attempts < MAX_TX_ATTEMPTS) blocking(Thread.sleep(ATTEMPT_INTERVAL))
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

  /** Hand back inputs reserved for an initial transaction that was never sent. */
  private def releaseInitialInputs(): Unit =
    {
      initialReservation.foreach(_.release())
      initialReservation = None
      // If an initial send never became known-successful, none of its allocation outputs is used by
      // a child transaction. Releasing these exact holds is safe even when the send result was
      // ambiguous: a real output, if the node accepted it, is simply an ordinary unspent wallet box.
      releaseFeeAllocations()
    }

  /** Release only allocations whose child was definitely never submitted; terminal handles no-op. */
  private def releaseFeeAllocations(): Unit = {
    feeAllocationReservations.values.foreach(_.release())
    feeAllocationReservations = Map.empty
    feeAllocations = Map.empty
  }

  private def submitSigned(ctx: BlockchainContext,
                           tx: SignedTransaction,
                           usesInitialReservation: Boolean,
                           rollupId: String): String = {
    val reservation =
      if (usesInitialReservation) initialReservation
      else feeAllocationReservations.get(rollupId)
    if (reservation.isEmpty)
      throw new IllegalStateException(
        s"No wallet reservation owns the fee input for rollup $rollupId")

    // Converted BEFORE the node call, so a local conversion failure cannot happen after acceptance.
    // The fee allocations in this list are already held as Known reservations by their own rollups,
    // and the manager refuses a return for a box it is already holding, so what this actually
    // publishes is the change box — which otherwise sat unusable until the ten-minute refresh.
    val change = signableChange(tx)

    reservation.foreach(_.beginSubmission())
    try {
      val txId = ctx.sendTransaction(tx).replace("\"", "")
      reservation.foreach(_.commit(change))
      if (usesInitialReservation) initialReservation = None
      txId
    } catch {
      case NonFatal(ex) =>
        reservation.foreach(_.uncertain())
        if (usesInitialReservation) initialReservation = None
        throw ex
    }
  }

  /**
   * The transaction's own outputs this client can spend.
   *
   * Empty rather than throwing if the conversion fails: publishing change is an optimisation, and
   * failing the send over it would turn a landed transaction into a reported failure.
   */
  private def signableChange(tx: SignedTransaction): Seq[InputUTXO] =
    Try(JavaHelpers.toIndexedSeq(tx.getOutputsToSpend).map(InputUTXO(_))
      .filter(box => wallet.signableTrees.contains(box.contract.ergoTreeHex)))
      .getOrElse {
        logger.warn(s"Could not read the outputs of ${tx.getId} to return its change; " +
          "it will come back on the next wallet refresh")
        Seq.empty[InputUTXO]
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

  /** The fee proposition every `UTXO.feeBox` sits at, derived once rather than per comparison. */
  private val FeeTreeHex: String = Contract.FEE_720.ergoTreeHex

  /**
   * The initial transaction's pre-created wallet outputs: the `count` outputs following the fee box.
   *
   * Located by finding the fee box rather than by a fixed index, because the number of outputs in
   * FRONT of it is per-builder. `mkFeeOutputs` always emits `feeBox +: walletOuts` as one run, and
   * every builder appends that run after its own outputs — but a Payout that pays a miner as well as
   * recreating its box puts TWO there where the others put one. A constant offset handed the first
   * rollup the fee-proposition box, which this client cannot sign for, and shifted every other rollup
   * onto a neighbour's box, which can be worth less than its own fee.
   *
   * Empty when there is no fee box, which the caller treats as "make no allocations" rather than
   * guessing.
   */
  private[transactions] def walletOutputsAfterFee(outputs: Seq[InputUTXO], count: Int): Seq[InputUTXO] = {
    val feeIdx = outputs.indexWhere(_.contract.ergoTreeHex == FeeTreeHex)
    if (feeIdx < 0) Seq.empty[InputUTXO]
    else outputs.slice(feeIdx + 1, feeIdx + 1 + count)
  }

  /** Self-message: the batch and every attempt in it are done, so the lock can come off. */
  private case object BatchFinished

  /** Maximum number of times attemptTx will retry on an ErgoClientException. */
  final val MAX_TX_ATTEMPTS: Int = 5

  /** Milliseconds to wait between retry attempts in attemptTx. */
  final val ATTEMPT_INTERVAL: Int = 5000

  case class InitialTxInfo(feesToCreate: Map[String, Long])
}
