package transactions.engine.execution

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import configs.{NodeContext, StateConfig, StratumConfig, TasksConfig}
import lfsm.LFSMPhase.HOLDING
import lfsm.contracts.FraudProofContracts
import lfsm.states.Rollup
import mutations.NotEnoughInputsException
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit._
import org.ergoplatform.sdk.JavaHelpers
import org.slf4j.LoggerFactory
import play.api.Configuration
import state.messages.MempoolMessages.{RebuildMempoolChains, ResetMempoolState}
import state.messages.RollupMessages
import state.messages.RollupMessages.{GetCurrentRollupCritical, GetRollupMetadata, RemoveRollup, RollupInfo}
import state.DataBoxRetrievalException
import transactions.candidate.BlockTxMessages
import transactions.candidate.BlockTxMessages.{BlockTxsReady, CandidateTx, CandidateTxsDropped}
import transactions.engine.execution.RollupExecution._
import transactions.rollups.TransactionMessages.RollupTxType._
import transactions.rollups.TransactionMessages._
import utils.Globals
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future, blocking}
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal


import transactions.rollups._
import transactions.engine.EngineBroadcast
import transactions.engine.wallet.{EngineFunding, FundingAllocation}
/** One engine attempt owns these build allocations; none survives completion of its worker. */
class RollupExecution(nodeContext: NodeContext, walletManager: ActorRef, syncHandler: ActorRef,
                      mempoolView: ActorRef, config: Configuration, dataBoxes: DataBoxSource,
                      rollupNodeApi: node.NodeApi, alive: () => Boolean,
                      worker: ExecutionContext)(implicit ec: ExecutionContext) {
  private implicit val timeout: Timeout = Timeout(30.seconds)
  private val criticalFunding = EngineFunding(walletManager, EngineFunding.AskTimeout, ec, critical = true)
  private val optionalFunding = EngineFunding(walletManager, EngineFunding.AskTimeout, ec)
  private var criticalBatch = false
  private def walletSelector: EngineFunding = if (criticalBatch) criticalFunding else optionalFunding
  private val commitments = new CommitmentTransactions(nodeContext, dataBoxes, alive) {
    override protected def executionNode: node.NodeApi = rollupNodeApi
  }
  private val logger = LoggerFactory.getLogger("RollupExecution")
  private val nodeConfig = nodeContext
  private val stateConfig = new StateConfig(config)
  private val stratumConfig = new StratumConfig(config)
  private val dictionarySyncEnabled = TasksConfig.isEnabled(config, TasksConfig.DictionarySync)
  private val client = nodeContext.getClient
  private val wallet = nodeContext.getNodeWallet
  private var feeAllocations = Map.empty[String, InputUTXO]
  private var feeAllocationReservations = Map.empty[String, FundingAllocation]
  private var initialReservation = Option.empty[FundingAllocation]

  /** A single admitted batch funds its children, then disposes of every unused allocation. */
  def execute(stubs: Seq[RollupTxStub]): Future[Unit] = {
    require(stubs.nonEmpty && stubs.size <= 100, "invalid rollup batch")
    criticalBatch = stubs.exists(s => s.txType == NISPSubmission || s.fpInfo.isDefined)
    Future(runBatch(stubs))(worker).flatMap(identity).andThen { case _ => releaseInitialInputs() }
  }

  /**
   * Fee-less copies for this miner's own block, each bundled with the unconfirmed transactions it
   * chains off.
   *
   * A transform spends the projected rollup tip, which may be an output of a transaction still in
   * the mempool. That parent has to travel with it: a block carrying the child alone is invalid.
   * A chain whose bodies cannot all be fetched is dropped rather than offered incomplete.
   */
  def candidates(stubs: Seq[RollupTxStub], height: Int): Seq[transactions.candidate.CandidateBundle] = {
    require(alive() && stubs.size <= 100, "candidate attempt is obsolete or oversized")
    val built = stubs.flatMap(stub => buildFeeless(stub, height))
    val bodies = ancestorBodies(built.flatMap(_._2).distinct)
    built.flatMap { case (tx, ancestorIds) =>
      val ancestors = ancestorIds.flatMap(bodies.get)
      if (ancestors.size != ancestorIds.size) {
        logger.warn(s"Dropping candidate ${tx.id}: ${ancestorIds.size - ancestors.size} " +
          "unconfirmed ancestor(s) could not be read")
        None
      } else Some(transactions.candidate.CandidateBundle((ancestors :+ tx).toVector,
        ancestorIds.lastOption.map(BlockTxMessages.ChainFromMempool(_)).toSeq ++
          ancestorIds.map(BlockTxMessages.IncludeExisting)))
    }
  }

  /**
   * Bodies for the unconfirmed ancestors a candidate needs, fetched one at a time because a chain is
   * a handful of transactions and the mining path cannot afford a whole-mempool read.
   */
  private def ancestorBodies(ids: Seq[String]): Map[String, CandidateTx] =
    ids.flatMap { id =>
      Try(rollupNodeApi.unconfirmedTransactionById(id).get).toOption.flatten.map { body =>
        val encoded = node.rest.NodeCodecs.encodeTransaction(body).toString
        // The node reports the serialized size it counts towards the block limit, so that is used
        // rather than the encoded length. Execution cost it does not report, and stays unknown.
        id -> CandidateTx(body.id, encoded, CandidateTx.MempoolAncestor,
          body.inputs.map(_.boxId).toSet, body.size.getOrElse(encoded.length))
      }
    }.toMap
  def register(): String = {
    require(alive(), "registration attempt was superseded")
    val view = Globals.syncView
    require(view.canonical.available && view.minerDictionary.available &&
      view.minerDictionaryMetadata.exists(!_.hasMiner) && dataBoxes.getDataBoxToken.isEmpty,
      "registration requires a current unregistered dictionary view")
    val dictionary = Await.result(syncHandler ? state.messages.SyncMessages.GetMinerDictionary, timeout.duration) match {
      case state.messages.SyncMessages.CurrentMinerDictionary(value) => value
      case _ => throw new IllegalStateException("Miner Dictionary became unavailable")
    }
    commitments.sendInitialCommitment(stratumConfig.diff, dictionary, optionalFunding)
  }
  private def runBatch(stubs: Seq[RollupTxStub]): Future[Unit] = {
    require(alive(), "rollup engine attempt was superseded")
    val initialTxInfo = InitialTxInfo(stubs.map { s =>
      if (s.txType != Payout)
        s.rollupBlockId -> s.fee
      else // Payouts should allocate a little extra, in case they can claim change with tokens
        s.rollupBlockId -> (s.fee + UTXO.MIN_FEE)
    }.toMap)
    logger.info("Creating initial transaction to handle RollupBatch")
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
      case Failure(ex) =>
        logger.warn("Failed to produce an initial transaction from the RollupBatch")
        logger.warn(s"Got error: ${ex.getMessage}")
        // Nothing was sent, so the last attempt's inputs are still ours to give back. Leaving them
        // reserved would hold them until the reservation ages out.
        releaseInitialInputs()
        Future.failed(ex)

      case Success(txId) =>
        logger.info(s"Successfully sent initial transaction with id $txId")
        logger.info(s"Now attempting ${feeAllocations.size} remaining  transactions")

        val remainingStubs = stubs.filter(s => feeAllocations.contains(s.rollupBlockId))
        val remaining = submitRemainingTxs(remainingStubs)

        if (stateConfig.autoCommit.getOrElse(true)) {
          logger.info("Auto-commits were enabled, now checking difficulty commitment state")
          // Send auto commitment transaction
          commitments.commitScore(stratumConfig.diff, optionalFunding) match {
            case Success(_) => ()
            case Failure(ex) => logger.error("Got exception during end of submission", ex)
          }
        }

        remaining
    }
  }

  // ─── fee-less builds for this miner's own block ─────────────────────────────

  /**
   * The same transaction the normal path would send, with no fee output.
   *
   * The transform and payout phases recreate their box at the same value, so those balance with no
   * wallet input at all. A NISP submission does not: it has to post a refundable bond, which comes
   * from a wallet box held by the engine for the candidate height so the funded copy cannot
   * select it too. Built against the same mempool-aware state, so anything chaining off an
   * unconfirmed parent stays valid.
   */
  private def buildFeeless(stub: RollupTxStub, blockHeight: Int): Option[(CandidateTx, Seq[String])] = {
    var ancestorIds = Seq.empty[String]
    val built = if (stub.txType == HoldingTransform || stub.txType == EvalTransform) Try {
      val (input, metadata, transformAncestors) = transformInput(stub)
      ancestorIds = transformAncestors
      client.execute { ctx =>
        require(stub.currentPeriod == metadata.currentPeriod &&
          stub.validate(ctx.getHeight, metadata) && stub.validate(blockHeight, metadata),
          "transform is not eligible at both signing and candidate heights")
        val signed = if (stub.txType == HoldingTransform)
          RollupTransactions.genHoldingTransform(ctx, wallet, input, Seq.empty, Seq.empty, blockHeight)
        else RollupTransactions.genEvalTransform(ctx, wallet, input, Seq.empty, Seq.empty)
        candidateTx(signed, if (stub.txType == HoldingTransform) CandidateTx.HoldingTransform else CandidateTx.EvalTransform)
      }
    } else materializedRollupState(stub).flatMap { latest =>
      ancestorIds = latest.ancestorIds
      Try {
        client.execute { ctx =>
          checkCandidateStubValidity(ctx, stub, latest, blockHeight)
          val none = Seq.empty[InputUTXO]
          val noFee = Seq.empty[UTXO]
          stub.txType match {
            case NISPSubmission =>
              val score = commitments.commitmentForNISP(latest.rollup.startHeight).get
              val holdingInput = latest.inputUTXO
              Globals.nispDB.getBestValidNISP(
                RollupTransactions.genesisBlockHeight(holdingInput), score) match {
                case Some(nisp) =>
                  // Reserved before the build and only handed to the candidate once it is signed, so
                  // a build that fails gives the box straight back instead of stranding it.
                  val bond = RollupTransactions.submissionBond(score)
                  val reservation = criticalFunding.reserveCovering(bond)
                  val built = Try {
                    val sTx = RollupTransactions.genNISPSubmission(
                      ctx, wallet, holdingInput, reservation.inputs, latest, noFee, nisp, score)
                    candidateTx(sTx, CandidateTx.NispSubmission)
                  }
                  built match {
                    case Success(tx) =>
                      reservation.holdForCandidate()
                      walletManager ! CandidateLeaseTaken(blockHeight, reservation.reservationId)
                      tx
                    case Failure(ex) =>
                      reservation.release()
                      throw ex
                  }
                case None =>
                  throw new NoValidNISPException(
                    s"no valid NISP for rollup ${stub.rollupBlockId}")
              }

            case HoldingTransform | EvalTransform => throw new IllegalArgumentException("expected a dictionary operation")

            case Payout =>
              // Skipped here and left to the funded copy. A final payout with LIT left over needs
              // an ERG-bearing change output, which a fee-less transaction balancing on the rollup
              // box alone cannot fund. The stub stays queued, so the payout still happens.
              if (RollupTransactions.planPayout(wallet, latest.inputUTXO, latest).needsChangeOutput)
                throw StubInvalidException(
                  s"final payout for rollup ${stub.rollupBlockId} leaves LIT change and cannot be " +
                    "built fee-less")
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
    }
    built match {
      case Success(tx) => Some(tx -> ancestorIds)
      case Failure(ex) =>
        logger.info(s"Skipping [${stub.txType}] for rollup ${stub.rollupBlockId} " +
          s"in the block package: ${ex.getMessage}")
        None
    }
  }

  private def candidateTx(sTx: SignedTransaction, kind: String): CandidateTx =
    CandidateTx(sTx.getId.replace("\"", ""), sTx.toJson(false, false), kind,
      RollupExecution.signedInputIds(sTx), RollupExecution.signedSizeBytes(sTx), sTx.getCost.toLong,
      RollupExecution.signedLeaf(sTx))

  // ─── submission ───────────────────────────────────────────────────────────

  /**
   * Gets rollup transaction function for a given stub
   */
  private def getRollupTransaction(stub: RollupTxStub,
                                   initialTxInfo: Option[InitialTxInfo]): (RollupTxStub, LatestRollup) => Try[String] = {
    stub.txType match {
      case NISPSubmission => sendNISPSubmission(_, _, initialTxInfo)
      case HoldingTransform => (_, _) => Failure(new IllegalArgumentException("structural transforms require metadata"))
      case EvalTransform => (_, _) => Failure(new IllegalArgumentException("structural transforms require metadata"))
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
    // The allocation is two fees wide so the initial transaction can also fund a change output
    val feeToPay = math.min(stub.fee, initialTxInfo.feesToCreate(stub.rollupBlockId))
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
  private[transactions] def initialTxInputs(initialTxInfo: InitialTxInfo, isFPTx: Boolean = false): Seq[InputUTXO] = {
    // Reaching here again means the previous attempt never sent, so give its inputs back before
    // reserving more — five attempts each holding a disjoint set would drain the wallet.
    releaseInitialInputs()

    val ergForFees = initialTxInfo.feesToCreate.values.foldLeft(0L)(Math.addExact)
    // Reserved on selection, not after the send. EmissionsCore draws from the same EngineWalletState,
    // and the gap between selecting and sending spans retries and their sleeps.
    // P2PK-only for a fraud proof, which pays the slashed bond to input 1's own proposition. A
    // matured reward box here is contract-valid and silently relocks the reward for 720 blocks.
    val reservation =
      if (!isFPTx) walletSelector.reserve(ergForFees)
      else criticalFunding.reserveCoveringP2PK(ergForFees)
    val feeInputs = reservation.inputs
    if (feeInputs.isEmpty)
      throw new NotEnoughInputsException("EngineWalletState could not return enough inputs for initial rollup tx")
    else {
      initialReservation = Some(reservation)
      feeInputs
    }
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
    var held = Map.empty[String, FundingAllocation]
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

  /** Try eligible roots in order; once a transaction reached the node, this batch cannot try another. */
  private def submitInitialTransaction(stubs: Seq[RollupTxStub], initial: InitialTxInfo): Try[String] = {
    var result: Try[String] = Failure(new IllegalStateException("no eligible rollup transaction"))
    var funding = initial
    var sent = false
    stubs.iterator.takeWhile(_ => result.isFailure && !sent).foreach { stub =>
      require(alive(), "rollup attempt expired")
      result = if (stub.txType == NISPSubmission && dataBoxes.getDataBoxToken.isEmpty)
        Failure(new DataBoxRetrievalException("NISP submission requires a registered MinerData box"))
      else attemptRollup(stub, Some(funding))
      result match {
        case Failure(_: EngineBroadcast.SubmissionOutcomeException) => sent = true
        case Failure(ex) =>
          releaseInitialInputs()
          funding = funding.copy(feesToCreate = funding.feesToCreate - stub.rollupBlockId)
          logger.warn(s"Rollup ${stub.rollupBlockId} could not fund the batch: ${ex.getMessage}")
          // Nothing about this rollup can change the answer, so stop tracking it here too: the
          // initial-transaction path never reaches `reportAttempt`.
          if (ex.isInstanceOf[CommitmentNotInEffectException]) {
            syncHandler ! RemoveRollup(stub.rollupBlockId, ex.getMessage)
            logger.warn(s"Dropping rollup ${stub.rollupBlockId}: ${ex.getMessage}")
          }
        case Success(_) => ()
      }
    }
    result
  }

  /** At most two children build concurrently against the attempt's immutable fee allocations. */
  private def submitRemainingTxs(remainingStubs: Seq[RollupTxStub]): Future[Unit] = {
    remainingStubs.grouped(2).foldLeft(Future.successful(())) { (previous, group) =>
      previous.flatMap { _ =>
        val attempts = group.map { s =>
          val dispatched = Try(Future(attemptRollup(s, None))(worker))
            .recover { case NonFatal(ex) => Future.failed(ex) }.get
          dispatched.map(reportAttempt(s, _)).recover {
            case NonFatal(ex) => logger.error(s"Got unexpected error in tx attempt thread for $s", ex)
          }
        }
        Future.sequence(attempts).map(_ => ())
      }
    }
  }

  private def reportAttempt(stub: RollupTxStub, txAttempt: Try[String]): Unit =
    txAttempt match {
      case Failure(mal: ErgoClientException) if mal.getMessage.contains("Every input of the transaction should be in UTXO") =>
        syncHandler ! ResetMempoolState(stub.rollupBlockId)
        logger.warn(s"Got de-synced mempool state for rollup ${stub.rollupBlockId} attempting ${stub.txType}")
      case Failure(ds: ErgoClientException) if ds.getMessage.contains("Double spending") =>
        logger.warn(s"Got double spend for rollup ${stub.rollupBlockId} attempting ${stub.txType}")
      // No commitment can ever be in force for this rollup: both its start height and the
      // commitment it would be judged against are fixed, so every retry asks the same question.
      // Stop tracking it rather than re-offering it for the rest of its life.
      case Failure(notInEffect: CommitmentNotInEffectException) =>
        syncHandler ! RemoveRollup(stub.rollupBlockId, notInEffect.getMessage)
        logger.warn(s"Dropping rollup ${stub.rollupBlockId}: ${notInEffect.getMessage}")
      // The rest are terminal for this attempt but not for the rollup, so they say why and stop.
      case Failure(nv: NoValidNISPException) =>
        logger.warn(nv.getMessage)
      case Failure(illegalState: IllegalStateException) =>
        logger.warn(illegalState.getMessage)
      case Failure(removed: RollupRemovedException) =>
        logger.warn(removed.getMessage)
      case Failure(invalidated: StubInvalidException) =>
        logger.warn(invalidated.getMessage)
      case Failure(newGen: NewlyGeneratedRollupException) =>
        logger.warn(newGen.getMessage)
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
          val score = commitments.commitmentForNISP(latestState.rollup.startHeight).get
          val nispDB = Globals.nispDB
          val holdingInput = latestState.inputUTXO
          val bestNISP = nispDB.getBestValidNISP(RollupTransactions.genesisBlockHeight(holdingInput), score)
          val initOutputs = initialTxInfo.map(initialTxOutputs(stub, _))
          val feeOutputs = mkFeeOutputs(stub, initOutputs)
          bestNISP match {
            case Some(nisp) =>

              logger.info(s"Got valid NISP for lithos-mined block ${latestState.rollup.startHeight} with score ${nisp.score}, heights" +
                s" ${nisp.shares.map(_.getHeight).mkString(", ")} and size ${nisp.serialize.length} bytes")

              // A submission funds a refundable bond as well as its fee, and the bond is priced off
              // a score that is only known once a NISP has been chosen. The fee allocation was sized
              // before that, so anything it does not cover is reserved here.
              val base = rollupWalletInputs(stub, initialTxInfo)
              val required = feeOutputs.map(_.value).sum + RollupTransactions.submissionBond(score)
              val supplied = base.foldLeft(0L)(_ + _.value)
              val topUp =
                if (supplied >= required) None
                else Some(walletSelector.reserveCovering(required - supplied))

              val txId = try {
                val sTx = RollupTransactions.genNISPSubmission(ctx, wallet, holdingInput,
                  base ++ topUp.toSeq.flatMap(_.inputs), latestState, feeOutputs, nisp, score)

                if (initOutputs.isDefined)
                  updateFeeMap(sTx, initOutputs.get._2)
                submitSigned(ctx, sTx, initialTxInfo.isDefined, stub.rollupBlockId,
                  latestState.inputUTXO.id.toString, topUp.toSeq)
              } catch {
                case NonFatal(ex) =>
                  // Nothing reached the node on this path: submitSigned owns the lease from the
                  // moment it begins one, and everything before that is local.
                  topUp.foreach(r => Try(r.release()))
                  throw ex
              }
              logger.info(s"Sent transaction ${txId} to submit NISP for rollup ${stub.rollupBlockId}")
              txId
            case None =>
              syncHandler ! RemoveRollup(stub.rollupBlockId, "Unable to submit valid NISP")
              throw new NoValidNISPException(s"Could not produce valid NISP for lithos-mined block ${latestState.rollup.startHeight}" +
                s" with id ${stub.rollupBlockId}")
          }
      }
    }
  }

  /** Transform builders only copy box state; their shared funding path needs no dictionary. */
  private def transformInput(stub: RollupTxStub): (InputUTXO, lfsm.states.RollupMetadata, Seq[String]) = {
    val reply = Await.result((syncHandler ? GetRollupMetadata(stub.rollupBlockId)).mapTo[RollupInfo],
      timeout.duration)
    reply match {
      case RollupMessages.CurrentRollupMetadata(_, _, Some(projected)) if !projected.toBeRemoved =>
        (projected.asInput, projected.metadata, projected.ancestorIds)
      case RollupMessages.CurrentRollupMetadata(id, metadata, None) =>
        (InputUTXO(client.execute(_.getBoxesById(id).head)), metadata, Seq.empty)
      case RollupMessages.RollupUnavailable(reason) => throw new IllegalStateException(reason)
      case _ => throw RollupRemovedException(s"Rollup ${stub.rollupBlockId} has no spendable state")
    }
  }

  /** Both structural transforms use the same batch fee allocations and final input recheck. */
  private def sendTransform(stub: RollupTxStub, initialTxInfo: Option[InitialTxInfo]): Try[String] = Try {
    val (input, metadata, _) = transformInput(stub)
    client.execute { ctx =>
      require(stub.currentPeriod == metadata.currentPeriod && stub.validate(ctx.getHeight, metadata),
        "transform is no longer eligible")
      val initOutputs = initialTxInfo.map(initialTxOutputs(stub, _))
      val fees = mkFeeOutputs(stub, initOutputs)
      val funding = rollupWalletInputs(stub, initialTxInfo)
      val signed = if (stub.txType == HoldingTransform)
        // Broadcast rather than inserted, so the earliest block it can reach is the next one.
        RollupTransactions.genHoldingTransform(ctx, wallet, input, funding, fees, ctx.getHeight + 1)
      else RollupTransactions.genEvalTransform(ctx, wallet, input, funding, fees)
      initOutputs.foreach(outputs => updateFeeMap(signed, outputs._2))
      submitSigned(ctx, signed, initialTxInfo.isDefined, stub.rollupBlockId, input.id.toString)
    }
  }

  private def attemptRollup(stub: RollupTxStub, initialTxInfo: Option[InitialTxInfo]): Try[String] =
    if (stub.txType == HoldingTransform || stub.txType == EvalTransform) sendTransform(stub, initialTxInfo)
    else attemptTx[RollupTxStub, LatestRollup](getRollupTransaction(stub, initialTxInfo), materializedRollupState, stub)

  private def sendFraudProof(stub: RollupTxStub,
                             latestState: LatestRollup,
                             initialTxInfo: Option[InitialTxInfo]): Try[String] = {
    Try {
      client.execute {
        ctx =>
          checkRollupStubValidity(ctx, stub, latestState)

          val initOutputs = initialTxInfo.map(initialTxOutputs(stub, _))
          val feeOutputs = mkFeeOutputs(stub, initOutputs)
          // Rebuilt rather than cached, so the dictionary has to be re-read here too. Only
          // FP_NonMatchingCommitment reads it, and it is the accused's own miner hash that decides
          // whether their MinerData box is needed at all.
          val commitment = CommitmentSources.load(ctx, nodeConfig.getNodeApi, syncHandler,
            Seq(stub.fpInfo.get._1))
          val sTx = RollupTransactions.genFraudProofTransform(ctx, wallet, latestState.inputUTXO,
            rollupWalletInputs(stub, initialTxInfo), latestState, feeOutputs, stub.fpInfo.get._1,
            stub.fpInfo.get._2, commitment, stub.resolvedNisps)
          if (initOutputs.isDefined)
            updateFeeMap(sTx, initOutputs.get._2)
          val txId = submitSigned(ctx, sTx, initialTxInfo.isDefined, stub.rollupBlockId,
            latestState.inputUTXO.id.toString)
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
          val txId = submitSigned(ctx, sTx, initialTxInfo.isDefined, stub.rollupBlockId,
            latestState.inputUTXO.id.toString)
          logger.info(s"Sent transaction ${txId} to payout local miner for rollup ${stub.rollupBlockId}")
          txId
      }
    }
  }

  /** One fresh-state attempt; the engine schedule owns retry delays and expiry. */
  private def attemptTx[T <: TxStub, L <: LatestState](txFunc: (T, L) => Try[String],
                                                      latestState: T => Try[L], stub: T): Try[String] = {
    val result = latestState(stub).flatMap(state => txFunc(stub, state))
    result.failed.foreach {
      case _: ProjectionChangedException => mempoolView ! RebuildMempoolChains
      case ex: ErgoClientException if Option(ex.getMessage).exists(_.contains("Double spending")) =>
        mempoolView ! RebuildMempoolChains
      case _ => ()
    }
    result
  }
  /**
   * End every bond lease taken for one height. Uncertain rather than released, because the block
   * being assembled may have carried the transaction: only an authoritative mempool-aware refresh
   * can say whether the input was spent, and that is what marking it uncertain triggers.
   */
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
                           rollupId: String,
                           expectedRollupInput: String,
                           extraReservations: Seq[FundingAllocation] = Seq.empty): String = {
    val reservation =
      if (usesInitialReservation) initialReservation
      else feeAllocationReservations.get(rollupId)
    if (reservation.isEmpty)
      throw new IllegalStateException(
        s"No wallet reservation owns the fee input for rollup $rollupId")
    // Every lease the transaction spends, so a send that cannot acquire all of them acquires none.
    val allReservations = reservation.toSeq ++ extraReservations

    // Converted BEFORE the node call, so a local conversion failure cannot happen after acceptance.
    // The fee allocations in this list are already held as Known reservations by their own rollups,
    // and the manager refuses a return for a box it is already holding, so what this actually
    // publishes is the change box — which otherwise sat unusable until the ten-minute refresh.
    val change = signableChange(tx)

    // Re-read the projection after signing and immediately before the external send. A mempool
    // revision or confirmed block can land during transaction construction; sending the old input
    // would only create an avoidable double-spend and stale projection retry.
    val current = Await.result[RollupInfo](
      (syncHandler ? GetRollupMetadata(rollupId)).mapTo[RollupInfo], timeout.duration)
    RollupExecution.sendIfCurrentInput(rollupId, expectedRollupInput, current) {
      require(alive(), "rollup engine attempt was superseded")
      try {
        val txId = new transactions.engine.EngineBroadcast(walletManager, rollupNodeApi)(ec)
          .send(tx, allReservations, "rollup:" + rollupId, () => alive()).requireAccepted()
        walletSelector.giveBack(change)
        if (usesInitialReservation) initialReservation = None
        txId
      } catch {
        case NonFatal(ex) =>
          if (usesInitialReservation) initialReservation = None
          throw ex
      }
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
    if (!stub.validate(ctx.getHeight, latestRollup.rollup))
      throw StubInvalidException(s"Invalid ${stub.txType} stub for rollup ${stub.rollupBlockId}")
  }

  /** The same check for work offered to a candidate, which executes one height past the tip. */
  private def checkCandidateStubValidity(ctx: BlockchainContext, stub: RollupTxStub,
                                         latestRollup: LatestRollup, blockHeight: Int): Unit = {
    if (!RollupExecution.eligibleForCandidate(stub, latestRollup.rollup, ctx.getHeight, blockHeight))
      throw StubInvalidException(s"${stub.txType} stub for rollup ${stub.rollupBlockId} is not valid " +
        s"at candidate height $blockHeight")
  }

  private def materializedRollupState(rollupTxStub: RollupTxStub) = {
    Try {
      val rollupInfo = Await.result[RollupInfo](
        (syncHandler ? GetCurrentRollupCritical(rollupTxStub.rollupBlockId)).mapTo[RollupInfo],
        timeout.duration)
      rollupInfo match {
        case RollupMessages.CurrentRollup(utxoId, rollup, mempoolState, _) =>
          if(rollup.phase == HOLDING && rollup.startHeight == client.execute(_.getHeight)){
            Failure(NewlyGeneratedRollupException(s"Cannot submit NISPs to newly generated rollup ${rollup.blockId}"))
          } else if (mempoolState.isDefined) {
            if (!mempoolState.get.toBeRemoved) {
              logger.info(s"Using mempool state for rollup ${rollupTxStub.rollupBlockId}" +
                s" with synced id $utxoId and mempool id ${mempoolState.get.asInput.id}")
              Success(LatestRollup(mempoolState.get.asInput, mempoolState.get.rollup,
                mempoolState.get.ancestorIds))
            } else {
              Failure(RollupRemovedException(s"Cannot send transaction for rollup" +
                s" ${rollupTxStub.rollupBlockId} with upcoming removal"))
            }
          } else {
            Success(LatestRollup(InputUTXO(client.execute(_.getBoxesById(utxoId).head)), rollup))
          }
        case RollupMessages.NoRollupFound() =>
          Failure(new IllegalStateException(s"No existing rollup for blockId ${rollupTxStub.rollupBlockId}"))
        case RollupMessages.RollupUnavailable(reason) =>
          Failure(new IllegalStateException(
            s"Rollup ${rollupTxStub.rollupBlockId} is temporarily unavailable: $reason"))
      }
    }.flatten
  }
}

object RollupExecution {

  /** The fee proposition every `UTXO.feeBox` sits at, derived once rather than per comparison. */
  private val FeeTreeHex: String = Contract.FEE.ergoTreeHex

  /**
   * The boxes a signed transaction spends, read from the transaction itself so candidate admission
   * can find a conflict between two builders without decoding bodies of its own.
   */
  private[transactions] def signedInputIds(sTx: SignedTransaction): Set[String] = {
    import scala.collection.JavaConverters._
    sTx.getInputBoxesIds.asScala.map(_.replace("\"", "")).toSet
  }

  /** Serialized size as the node counts it towards the block limit, not the JSON length. */
  private[transactions] def signedSizeBytes(sTx: SignedTransaction): Int = signedBytes(sTx).length

  /**
   * The merkle leaf a block's transaction tree would carry for this transaction, which is how an
   * inclusion proof is matched back to what was requested.
   */
  private[transactions] def signedLeaf(sTx: SignedTransaction): String =
    Hex.toHexString(scorex.crypto.hash.Blake2b256(signedBytes(sTx)))

  private def signedBytes(sTx: SignedTransaction): Array[Byte] =
    org.ergoplatform.ErgoLikeTransactionSerializer.toBytes(
      sTx.asInstanceOf[org.ergoplatform.appkit.impl.SignedTransactionImpl].getTx)

  /**
   * Whether a stub may go into the candidate for `blockHeight`, built in a context at `tipHeight`.
   * Both are required: work valid at only one of the two cannot be both signed here and accepted
   * there, so it waits a block.
   */
  private[transactions] def eligibleForCandidate(stub: RollupTxStub, rollup: Rollup,
                                            tipHeight: Int, blockHeight: Int): Boolean =
    stub.validate(blockHeight, rollup) && stub.validate(tipHeight, rollup)

  /** The final send gate, kept directly testable so stale state cannot accidentally execute `send`. */
  private[transactions] def sendIfCurrentInput(rollupId: String,
                                          expectedRollupInput: String,
                                          current: RollupInfo)(send: => String): String = {
    val currentInput = current match {
      case RollupMessages.CurrentRollup(_, _, Some(projected), _) if !projected.toBeRemoved =>
        projected.asInput.id.toString
      case RollupMessages.CurrentRollup(utxoId, _, None, _) => utxoId
      case RollupMessages.CurrentRollup(_, _, Some(_), _) =>
        throw ProjectionChangedException(s"Rollup $rollupId is being removed before send")
      case RollupMessages.CurrentRollupMetadata(_, _, Some(projected)) if !projected.toBeRemoved =>
        projected.asInput.id.toString
      case RollupMessages.CurrentRollupMetadata(utxoId, _, None) => utxoId
      case RollupMessages.CurrentRollupMetadata(_, _, Some(_)) =>
        throw ProjectionChangedException(s"Rollup $rollupId is being removed before send")
      case RollupMessages.NoRollupFound() =>
        throw ProjectionChangedException(s"Rollup $rollupId disappeared before send")
      case RollupMessages.RollupUnavailable(reason) =>
        throw ProjectionChangedException(s"Rollup $rollupId became unavailable before send: $reason")
    }
    if (currentInput != expectedRollupInput)
      throw ProjectionChangedException(s"Rollup $rollupId input changed from $expectedRollupInput " +
        s"to $currentInput before send")
    send
  }

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

  /**
   * Self-message: a fee-less candidate build finished off the actor thread.
   *
   * `build` names the attempt rather than the height, because a height can be built for twice —
   * the first package was refused and a replacement is being assembled — and the superseded result
   * must not answer the replacement.
   */
  private[transactions] case class RollupCandidateBuilt(incarnation: java.util.UUID,
                                                build: java.util.UUID, height: Int,
                                                result: Try[Seq[transactions.candidate.CandidateBundle]])

  /**
   * Self-message: a fee-less submission built off the actor thread took a bond input.
   */
  private[transactions] case class CandidateLeaseTaken(blockHeight: Int, reservation: String)

  case class InitialTxInfo(feesToCreate: Map[String, Long])
}
