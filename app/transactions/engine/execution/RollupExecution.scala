package transactions.engine.execution

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import configs.NodeContext
import lfsm.LFSMPhase
import lfsm.contracts.FraudProofContracts
import lfsm.states.{Rollup, RollupInfoState}
import mutations.NotEnoughInputsException
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit._
import org.ergoplatform.sdk.JavaHelpers
import org.slf4j.LoggerFactory
import play.api.Configuration
import state.messages.MempoolMessages.{MempoolRollupMetadata, RebuildMempoolChains, ResetMempoolState}
import state.messages.RollupMessages
import state.messages.RollupMessages.{GetCurrentRollupCritical, GetRollupMetadata, RemoveRollup, RollupInfo}
import state.DataBoxRetrievalException
import transactions.candidate.{BlockTxMessages, CandidateBundle}
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
import transactions.engine.wallet.EngineWalletMessages.{GetPinnedInputs, PinnedInputs, UnpinInput}
/** One engine attempt owns these build allocations; none survives completion of its worker. */
class RollupExecution(nodeContext: NodeContext, walletManager: ActorRef, syncHandler: ActorRef,
                      mempoolView: ActorRef, config: Configuration, dataBoxes: DataBoxSource,
                      rollupNodeApi: node.NodeApi, alive: () => Boolean,
                      worker: ExecutionContext,
                      discardStubs: (String, String) => Unit = (_, _) => (),
                      fraudSubmitted: (RollupTxStub, String) => Unit = (_, _) => ())(implicit ec: ExecutionContext) {
  private implicit val timeout: Timeout = Timeout(30.seconds)
  private val criticalFunding = EngineFunding(walletManager, EngineFunding.askTimeout(config), ec, critical = true)
  private val optionalFunding = EngineFunding(walletManager, EngineFunding.askTimeout(config), ec)
  private var criticalBatch = false
  private def walletSelector: EngineFunding = if (criticalBatch) criticalFunding else optionalFunding
  private val commitments = new CommitmentTransactions(nodeContext, dataBoxes, alive) {
    override protected def executionNode: node.NodeApi = rollupNodeApi
  }
  private val logger = LoggerFactory.getLogger("RollupExecution")
  private val nodeConfig = nodeContext
  private val client = nodeContext.getClient
  private val wallet = nodeContext.getNodeWallet
  private val maxAncestorTxs = configs.RollupSourceConfig(config).maxAncestorTxs
  private val pinnedInputs = configs.CandidateConfig(config).pinnedInputs
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
   * chains off, built in the order given until `limit` transactions are in hand.
   *
   * A spend of the projected rollup tip may take an output of a transaction still in the mempool.
   * That parent has to travel with it: a block carrying the child alone is invalid. A chain whose
   * bodies cannot all be fetched is dropped rather than offered incomplete.
   *
   * Transactions funded from one pinned box form a chain, each spending the change of the one
   * before, and share one bundle so the package takes the whole chain or none of it.
   *
   * @param held submissions this height already built, by rollup. One still spending the current
   *             tip is reused, so a refresh does not reserve a second bond box for it.
   */
  def candidates(stubs: Seq[RollupTxStub], height: Int, limit: Int,
                 held: Map[String, CandidateBundle] = Map.empty): CandidateBuild = {
    require(alive() && stubs.size <= 100, "candidate attempt is obsolete or oversized")
    var bodies = Map.empty[String, CandidateTx]
    var taken = Set.empty[String]
    var bundles = Vector.empty[CandidateBundle]
    var built = Vector.empty[RollupTxStub]
    var chainSlots = Map.empty[Int, Int]
    val pins = new PinnedFunding
    stubs.iterator.takeWhile(_ => taken.size < limit && alive()).foreach { stub =>
      val bundle = buildFeeless(stub, height, held, pins).flatMap {
        case Left(reused) => Some(reused -> None)
        case Right(work) =>
          bodies ++= ancestorBodies(work.ancestorIds.filterNot(bodies.contains))
          val ancestors = work.ancestorIds.flatMap(bodies.get)
          if (ancestors.size != work.ancestorIds.size) {
            logger.warn(s"Dropping candidate ${work.tx.id}: ${work.ancestorIds.size - ancestors.size} " +
              "unconfirmed ancestor(s) could not be read")
            // Its change is queued for the next link, which would spend an output the block never carries
            work.chain.foreach(pins.close)
            None
          } else {
            val fresh = CandidateBundle((ancestors ++ work.leading :+ work.tx).toVector,
              work.ancestorIds.lastOption.map(BlockTxMessages.ChainFromMempool(_)).toSeq ++
                work.ancestorIds.map(BlockTxMessages.IncludeExisting) ++
                Some(work.supersedes).filter(_.nonEmpty).map(BlockTxMessages.Supersede))
            if (work.tx.kind == CandidateTx.NispSubmission)
              walletManager ! CandidateSubmissionHeld(height, stub.rollupBlockId, fresh)
            Some(fresh -> work.chain)
          }
      }
      bundle match {
        // A link past the limit would take its whole chain with it at admission
        case Some((b, Some(chain))) if chainSlots.contains(chain) && (taken ++ b.members.map(_.id)).size > limit =>
          pins.close(chain)
        case Some((b, chain)) =>
          chain.flatMap(chainSlots.get) match {
            case Some(slot) => bundles = bundles.updated(slot, RollupExecution.extendChain(bundles(slot), b))
            case None =>
              chain.foreach(c => chainSlots += c -> bundles.size)
              bundles :+= b
          }
          taken ++= b.members.map(_.id)
          built :+= stub
        case None => ()
      }
    }
    CandidateBuild(bundles, built)
  }

  /**
   * Bodies for the unconfirmed ancestors a candidate needs, fetched one at a time because a chain is
   * a handful of transactions and the mining path cannot afford a whole-mempool read.
   */
  private def ancestorBodies(ids: Seq[String]): Map[String, CandidateTx] =
    ids.flatMap { id =>
      Try(rollupNodeApi.unconfirmedTransactionById(id).get).toOption.flatten.map { body =>
        id -> CandidateTx.ancestor(body)
      }
    }.toMap

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
        submitRemainingTxs(remainingStubs)
    }
  }

  // ─── fee-less builds for this miner's own block ─────────────────────────────

  /**
   * The same transaction the normal path would send, with no fee output, or a submission this
   * height already built (`Left`).
   *
   * The transform and payout phases recreate their box at the same value, so those balance with no
   * wallet input at all. A NISP submission does not: it has to post a refundable bond, which comes
   * from a wallet box held by the engine for the candidate height so the funded copy cannot
   * select it too. Built against the same mempool-aware state, so anything chaining off an
   * unconfirmed parent stays valid.
   */
  private def buildFeeless(stub: RollupTxStub, blockHeight: Int, held: Map[String, CandidateBundle],
                           pins: PinnedFunding)
  : Option[Either[CandidateBundle, Feeless]] = {
    var ancestorIds = Seq.empty[String]
    var superseding = Option.empty[Superseding]
    var chain = Option.empty[Int]
    val built: Try[Either[CandidateBundle, CandidateTx]] = if (stub.txType == NISPEvaluation) {
      // A fraud proof's context variables ride on a wallet input, which in a block of this miner's
      // own is a pinned box or the change of one. Refused before any state is read when none is
      // free, so it costs the stubs behind it nothing.
      if (stub.fpInfo.isEmpty) Failure(new IllegalArgumentException("evaluation stubs go to the evaluator"))
      else pins.take() match {
        case None => Failure(new IllegalStateException(
          "no pinned wallet input is free for a fraud proof (stratum.candidate.pinnedInputs)"))
        case Some(funding) =>
          dependentState(stub, blockHeight).flatMap { case (latest, rebuilt) =>
            ancestorIds = latest.ancestorIds
            superseding = rebuilt
            fraudProofCandidate(stub, blockHeight, funding.box, latest)
          } match {
            case Success((tx, change)) =>
              chain = Some(funding.chain)
              change.foreach(pins.extend(funding, _))
              Success(Right(tx))
            case Failure(ex) =>
              pins.giveBack(funding)
              Failure(ex)
          }
      }
    } else if (stub.txType == HoldingTransform || stub.txType == EvalTransform) Try {
      val (input, metadata, transformAncestors) = transformInput(stub)
      ancestorIds = transformAncestors
      withinAncestorCap(ancestorIds)
      client.execute { ctx =>
        require(stub.currentPeriod == metadata.currentPeriod && stub.validate(blockHeight, metadata),
          s"transform is not eligible at candidate height $blockHeight")
        val signed = if (stub.txType == HoldingTransform)
          RollupTransactions.genHoldingTransform(ctx, wallet, input, Seq.empty, Seq.empty, blockHeight)
        else RollupTransactions.genEvalTransform(ctx, wallet, input, Seq.empty, Seq.empty, blockHeight)
        Right(candidateTx(signed,
          if (stub.txType == HoldingTransform) CandidateTx.HoldingTransform else CandidateTx.EvalTransform))
      }
    } else Try(if (stub.txType == NISPSubmission) submissionShortcut(stub, held) else None).flatMap {
      case Some(reused) => Success(Left(reused))
      case None =>
        val state =
          if (stub.txType == Payout) dependentState(stub, blockHeight)
          else materializedRollupState(stub).map(_ -> Option.empty[Superseding])
        state.flatMap { case (latest, rebuilt) =>
          ancestorIds = latest.ancestorIds
          superseding = rebuilt
          Try {
            withinAncestorCap(ancestorIds)
            client.execute { ctx =>
              checkCandidateStubValidity(stub, latest, blockHeight)
              val none = Seq.empty[InputUTXO]
              val noFee = Seq.empty[UTXO]
              Right(stub.txType match {
                case NISPSubmission =>
                  val score = commitments.commitmentForNISP(latest.rollup.startHeight).get
                  val holdingInput = latest.inputUTXO
                  Globals.nispDB.getBestValidNISP(
                    RollupTransactions.genesisBlockHeight(holdingInput), score) match {
                    case Some(nisp) =>
                      // Reserved before the build and only handed to the candidate once it is signed, so
                      // a build that fails gives the box straight back instead of stranding it. A minimum
                      // box over the bond, since there is no fee output to fold smaller change into, and
                      // confirmed, since the bundle carries no parent for it.
                      val bond = RollupTransactions.submissionBond(score)
                      val reservation = criticalFunding.reserveCoveringConfirmed(bond + UTXO.MIN_CHANGE)
                      val built = Try {
                        val sTx = RollupTransactions.genNISPSubmission(
                          ctx, wallet, holdingInput, reservation.inputs, latest, noFee, nisp, score, blockHeight)
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

                case NISPEvaluation => throw new IllegalArgumentException("fraud proofs are built above")
              })
            }
          }
        }
    }
    built match {
      case Success(Left(reused)) => Some(Left(reused))
      case Success(Right(tx)) => Some(Right(Feeless(tx, ancestorIds, superseding.map(_.tx).toSeq,
        superseding.map(_.replaced).toSet, chain)))
      case Failure(ex) =>
        logger.info(s"Skipping [${stub.txType}] for rollup ${stub.rollupBlockId} " +
          s"in the block package: ${ex.getMessage}")
        None
    }
  }

  /**
   * A fraud proof for this miner's own block, spending a pinned box or its change and paying no fee.
   * That box is input 1, so the slashed bond pays to this wallet and the box returns whole as change,
   * which is handed back for the next funded transaction. Signed at the tip: a proof is valid from
   * its period's start until its end, so validity in the block implies validity at the tip.
   */
  private def fraudProofCandidate(stub: RollupTxStub, blockHeight: Int, funding: InputUTXO,
                                  latest: LatestRollup): Try[(CandidateTx, Option[InputUTXO])] = Try {
    withinAncestorCap(latest.ancestorIds)
    client.execute { ctx =>
      checkCandidateStubValidity(stub, latest, blockHeight)
      val (miner, proof) = stub.fpInfo.get
      val commitment = CommitmentSources.load(ctx, nodeConfig.getNodeApi, syncHandler, Seq(miner))
      val signed = RollupTransactions.genFraudProofTransform(ctx, wallet, latest.inputUTXO, Seq(funding),
        latest, Seq.empty, miner, proof, commitment, stub.resolvedNisps)
      candidateTx(signed, CandidateTx.FraudProof) -> RollupExecution.changeOf(signed, funding)
    }
  }

  /**
   * The state a candidate payout or fraud proof builds on.
   *
   * When the rollup's only unconfirmed spend is the transform into this phase, and nothing in the
   * mempool spends any of that transform's outputs, the transform is rebuilt fee-less and the
   * candidate chains off the rebuild instead of carrying it. A transform is pool work anyone may do,
   * so replacing it costs its sender nothing but a fee they get back. With anything built on it, that
   * is someone's work, and the chain is carried as it stands.
   */
  private def dependentState(stub: RollupTxStub, blockHeight: Int): Try[(LatestRollup, Option[Superseding])] =
    currentRollup(stub).flatMap { current =>
      latestOf(stub, current).map { latest =>
        current.mempoolState.filter(_ => latest.ancestorIds.size == 1).flatMap { projected =>
          Try(rebuildTransform(stub, current, projected, blockHeight)).recover { case NonFatal(ex) =>
            logger.info(s"Carrying the pending transform of rollup ${stub.rollupBlockId} rather than " +
              s"replacing it: ${ex.getMessage}")
            None
          }.get
        }.map { case (rebuilt, onRebuild) => onRebuild -> Some(rebuilt) }.getOrElse(latest -> None)
      }
    }

  /**
   * The pending transform rebuilt fee-less on the confirmed box, and the state after it. None when
   * the pending spend is not a transform, or when anything in the mempool spends one of its outputs.
   */
  private def rebuildTransform(stub: RollupTxStub, current: RollupMessages.CurrentRollup,
                               projected: state.messages.MempoolMessages.MempoolRollupState,
                               blockHeight: Int): Option[(Superseding, LatestRollup)] = {
    val confirmed = current.rollup
    val pending = projected.ancestorIds.head
    def outputsSpent: Boolean = rollupNodeApi.unconfirmedTransactionById(pending).get
      .getOrElse(throw new IllegalStateException(s"pending transform $pending left the mempool"))
      .outputs.exists(out => rollupNodeApi.unconfirmedInputByBoxId(out.boxId).get.nonEmpty)
    RollupExecution.replaceableTransform(confirmed.phase, projected.rollup.phase, projected.ancestorIds.size,
      t => RollupTxStub(stub.rollupBlockId, confirmed.currentPeriod, t).validate(blockHeight, confirmed),
      outputsSpent).map { t =>
      client.execute { ctx =>
        val input = RollupExecution.unspentBox(ctx, current.utxoId)
        val signed = if (t == HoldingTransform)
          RollupTransactions.genHoldingTransform(ctx, wallet, input, Seq.empty, Seq.empty, blockHeight)
        else RollupTransactions.genEvalTransform(ctx, wallet, input, Seq.empty, Seq.empty, blockHeight)
        val box = InputUTXO(signed.getOutputsToSpend.get(0))
        // The holding transform starts the evaluation period at the block it lands in, so the rebuild
        // writes a different period than the one pending
        val state = if (t == HoldingTransform) RollupInfoState.evaluation(blockHeight.toLong,
          confirmed.state.genesisBlockHeight, confirmed.state.totalBond) else projected.rollup.state
        val kind = if (t == HoldingTransform) CandidateTx.HoldingTransform else CandidateTx.EvalTransform
        Superseding(candidateTx(signed, kind), pending) ->
          LatestRollup(box, projected.rollup.copy(state = state, utxoId = box.id.toString))
      }
    }
  }

  /**
   * The wallet inputs one candidate build may spend for transactions that need one: pinned boxes,
   * then the change each funded transaction returns. A fee-less transaction returns its funding whole
   * as change, so each pin starts a chain that later transactions in the same block extend. Pins are
   * read from the wallet only when first needed and checked against the confirmed UTXO set as each
   * is taken; one that is gone was spent by a block, and is reported so the wallet replaces it.
   */
  private final class PinnedFunding {
    private lazy val ids: Iterator[String] =
      if (pinnedInputs <= 0) Iterator.empty
      else Try(Await.result((walletManager ? GetPinnedInputs)(PinnedAskTimeout).mapTo[PinnedInputs],
        PinnedAskTimeout.duration).boxIds).getOrElse(Seq.empty[String]).iterator
    private var returned = List.empty[Funding]
    private var chained = Vector.empty[Funding]
    private var chains = 0
    private var closed = Set.empty[Int]

    def take(): Option[Funding] = returned match {
      case head :: tail =>
        returned = tail
        Some(head)
      case Nil => freshPin().map { box =>
        chains += 1
        Funding(box, chains - 1)
      }.orElse(chained.headOption.map { next =>
        chained = chained.tail
        next
      })
    }

    private def freshPin(): Option[InputUTXO] = {
      import _root_.node.MutationConversions._
      var found = Option.empty[InputUTXO]
      while (found.isEmpty && ids.hasNext) {
        val id = ids.next()
        // Only a definite absence unpins; a failed read leaves the pin for the next build
        Try(rollupNodeApi.boxById(id).get.map(box => client.execute(ctx => box.toInputUTXO(ctx)))) match {
          case Success(Some(input)) => found = Some(input)
          case Success(None) => walletManager ! UnpinInput(id)
          case Failure(ex) => logger.warn(s"Could not read pinned input $id: ${ex.getMessage}")
        }
      }
      found
    }

    /** Funding whose transaction failed to build is free for the next one. */
    def giveBack(funding: Funding): Unit = if (!closed(funding.chain)) returned = funding :: returned

    /** The change a funded transaction returned, for the next transaction on the same chain. */
    def extend(funding: Funding, change: InputUTXO): Unit =
      if (!closed(funding.chain)) chained :+= Funding(change, funding.chain)

    /**
     * End a chain whose last link will not be in the package. Whatever it queued spends that link's
     * output, so it goes too.
     */
    def close(chain: Int): Unit = {
      closed += chain
      chained = chained.filterNot(_.chain == chain)
      returned = returned.filterNot(_.chain == chain)
    }
  }

  /** A box funding a candidate transaction, and the chain of transactions it belongs to. */
  private final case class Funding(box: InputUTXO, chain: Int)

  /** A pending transform rebuilt fee-less, and the id of the mempool transaction it replaces. */
  private final case class Superseding(tx: CandidateTx, replaced: String)

  /**
   * One stub's fee-less work: the mempool transactions to carry ahead of it, this client's own
   * transactions that go between them and it, and the pinned chain it extends, if any.
   */
  private final case class Feeless(tx: CandidateTx, ancestorIds: Seq[String], leading: Seq[CandidateTx],
                                   supersedes: Set[String], chain: Option[Int])

  /** Refused before anything is signed or reserved, so a skipped submission holds no bond box. */
  private def withinAncestorCap(ancestors: Seq[String]): Unit =
    if (ancestors.size > maxAncestorTxs)
      throw StubInvalidException(s"needs ${ancestors.size} unconfirmed ancestor(s), more than " +
        s"maxAncestorTxs $maxAncestorTxs; left to the mempool until they confirm")

  /**
   * What a submission can settle before its dictionary is materialized: a copy this height already
   * built that still spends the current tip, or refusal when this miner holds no NISP for the
   * rollup, which is true of most of them. None means build it.
   */
  private def submissionShortcut(stub: RollupTxStub, held: Map[String, CandidateBundle]): Option[CandidateBundle] = {
    val (input, metadata, _) = currentInput(stub, lapsing = None)
    held.get(stub.rollupBlockId).filter(_.members.last.inputIds.contains(input.id.toString)).orElse {
      val score = commitments.commitmentForNISP(metadata.startHeight).get
      if (Globals.nispDB.getBestValidNISP(metadata.state.genesisBlockHeight.toInt, score).isEmpty)
        throw new NoValidNISPException(s"no valid NISP for rollup ${stub.rollupBlockId}")
      None
    }
  }

  private def candidateTx(sTx: SignedTransaction, kind: String): CandidateTx =
    CandidateTx(sTx.getId.replace("\"", ""), _root_.transactions.candidate.BlockTxMessages.CandidateTx.signedJson(sTx), kind,
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
            discardStubs(stub.rollupBlockId, ex.getMessage)
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
        discardStubs(stub.rollupBlockId, notInEffect.getMessage)
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
      // The chain or the mempool moved under the attempt; the next tick reads it again
      case Failure(changed: StateChangedException) =>
        logger.warn(s"[${stub.txType}] for rollup ${stub.rollupBlockId}: ${changed.getMessage}")
      case Failure(refused: EngineBroadcast.SubmissionOutcomeException) =>
        logger.warn(s"[${stub.txType}] for rollup ${stub.rollupBlockId} not sent: ${refused.getMessage}")
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
              val shortfall = RollupExecution.submissionShortfall(base, feeOutputs.map(_.value).sum,
                RollupTransactions.submissionBond(score))
              val topUp = if (shortfall == 0L) None else Some(walletSelector.reserveCovering(shortfall))

              val txId = try {
                val sTx = RollupTransactions.genNISPSubmission(ctx, wallet, holdingInput,
                  base ++ topUp.toSeq.flatMap(_.inputs), latestState, feeOutputs, nisp, score,
                  RollupExecution.pooledHeight(ctx))

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
              // Stubs already queued for it would otherwise be retried against a rollup no longer tracked
              syncHandler ! RemoveRollup(stub.rollupBlockId, "Unable to submit valid NISP")
              discardStubs(stub.rollupBlockId, "Unable to submit valid NISP")
              throw new NoValidNISPException(s"Could not produce valid NISP for lithos-mined block ${latestState.rollup.startHeight}" +
                s" with id ${stub.rollupBlockId}")
          }
      }
    }
  }

  /** The box a transform spends; builders only copy its state, so no dictionary is needed. */
  private def transformInput(stub: RollupTxStub): (InputUTXO, lfsm.states.RollupMetadata, Seq[String]) =
    currentInput(stub, lapsingPhase(stub))

  /**
   * The box the next spend of this rollup takes, its state, and the unconfirmed chain behind it,
   * resolved by [[RollupExecution.spendTarget]].
   */
  private def currentInput(stub: RollupTxStub, lapsing: Option[LFSMPhase])
  : (InputUTXO, lfsm.states.RollupMetadata, Seq[String]) = {
    val reply = Await.result((syncHandler ? GetRollupMetadata(stub.rollupBlockId)).mapTo[RollupInfo],
      timeout.duration)
    RollupExecution.spendTarget(reply, lapsing) match {
      case Some(Right(projected)) => (projected.asInput, projected.metadata, projected.ancestorIds)
      case Some(Left(id)) =>
        val metadata = reply.asInstanceOf[RollupMessages.CurrentRollupMetadata].metadata
        (client.execute(RollupExecution.unspentBox(_, id)), metadata, Seq.empty)
      case None => reply match {
        case RollupMessages.RollupUnavailable(reason) => throw new IllegalStateException(reason)
        case _ => throw RollupRemovedException(s"Rollup ${stub.rollupBlockId} has no spendable state")
      }
    }
  }

  /** Both structural transforms use the same batch fee allocations and final input recheck. */
  private def sendTransform(stub: RollupTxStub, initialTxInfo: Option[InitialTxInfo]): Try[String] = Try {
    val (input, metadata, _) = transformInput(stub)
    client.execute { ctx =>
      val height = RollupExecution.pooledHeight(ctx)
      require(stub.currentPeriod == metadata.currentPeriod && stub.validate(height, metadata),
        "transform is no longer eligible")
      val initOutputs = initialTxInfo.map(initialTxOutputs(stub, _))
      val fees = mkFeeOutputs(stub, initOutputs)
      val funding = rollupWalletInputs(stub, initialTxInfo)
      val signed = if (stub.txType == HoldingTransform)
        RollupTransactions.genHoldingTransform(ctx, wallet, input, funding, fees, height)
      else RollupTransactions.genEvalTransform(ctx, wallet, input, funding, fees, height)
      initOutputs.foreach(outputs => updateFeeMap(signed, outputs._2))
      submitSigned(ctx, signed, initialTxInfo.isDefined, stub.rollupBlockId, input.id.toString,
        lapsing = lapsingPhase(stub))
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
          fraudSubmitted(stub, txId)
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
          // The state read may predate the transform into payout, for instance when the node dropped
          // the pending transform from its mempool; signing a payout against an evaluation box only
          // fails inside the interpreter
          checkRollupStubValidity(ctx, stub, latestState)
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
                           extraReservations: Seq[FundingAllocation] = Seq.empty,
                           lapsing: Option[LFSMPhase] = None): String = {
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
    RollupExecution.sendIfCurrentInput(rollupId, expectedRollupInput, current, lapsing) {
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

  /** Validity for the mempool, which checks a pooled transaction against the block after the tip. */
  private def checkRollupStubValidity(ctx: BlockchainContext, stub: RollupTxStub, latestRollup: LatestRollup): Unit = {
    if (!stub.validate(RollupExecution.pooledHeight(ctx), latestRollup.rollup))
      throw StubInvalidException(s"Invalid ${stub.txType} stub for rollup ${stub.rollupBlockId}")
  }

  /** The same check for work offered to a candidate, at the height that candidate is mined at. */
  private def checkCandidateStubValidity(stub: RollupTxStub, latestRollup: LatestRollup, blockHeight: Int): Unit = {
    if (!RollupExecution.eligibleForCandidate(stub, latestRollup.rollup, blockHeight))
      throw StubInvalidException(s"${stub.txType} stub for rollup ${stub.rollupBlockId} is not valid " +
        s"at candidate height $blockHeight")
  }

  /** The phase a transform stub moves its rollup out of. */
  private def lapsingPhase(stub: RollupTxStub): Option[LFSMPhase] = stub.txType match {
    case HoldingTransform => Some(LFSMPhase.HOLDING)
    case EvalTransform => Some(LFSMPhase.EVAL)
    case _ => None
  }

  private def materializedRollupState(rollupTxStub: RollupTxStub): Try[LatestRollup] =
    currentRollup(rollupTxStub).flatMap(latestOf(rollupTxStub, _))

  /** The rollup's confirmed state with its dictionary, and its mempool projection if there is one. */
  private def currentRollup(rollupTxStub: RollupTxStub): Try[RollupMessages.CurrentRollup] =
    Try {
      Await.result[RollupInfo](
        (syncHandler ? GetCurrentRollupCritical(rollupTxStub.rollupBlockId)).mapTo[RollupInfo],
        timeout.duration) match {
        case current: RollupMessages.CurrentRollup => Success(current)
        case RollupMessages.NoRollupFound() =>
          Failure(new IllegalStateException(s"No existing rollup for blockId ${rollupTxStub.rollupBlockId}"))
        case RollupMessages.RollupUnavailable(reason) =>
          Failure(new IllegalStateException(
            s"Rollup ${rollupTxStub.rollupBlockId} is temporarily unavailable: $reason"))
        case other =>
          Failure(new IllegalStateException(s"Unexpected rollup state reply ${other.getClass.getSimpleName}"))
      }
    }.flatten

  /** The box the next spend takes: the mempool tip when there is one, else the confirmed box. */
  private def latestOf(rollupTxStub: RollupTxStub, current: RollupMessages.CurrentRollup): Try[LatestRollup] =
    current.mempoolState match {
      case Some(projected) if !projected.toBeRemoved =>
        logger.info(s"Using mempool state for rollup ${rollupTxStub.rollupBlockId}" +
          s" with synced id ${current.utxoId} and mempool id ${projected.asInput.id}")
        Success(LatestRollup(projected.asInput, projected.rollup, projected.ancestorIds))
      case Some(_) =>
        Failure(RollupRemovedException(s"Cannot send transaction for rollup" +
          s" ${rollupTxStub.rollupBlockId} with upcoming removal"))
      case None =>
        Try(LatestRollup(client.execute(RollupExecution.unspentBox(_, current.utxoId)), current.rollup))
    }
}

object RollupExecution {

  /** The wallet is the engine's own mailbox, so the pin list answers at once or not at all. */
  private val PinnedAskTimeout: Timeout = Timeout(5.seconds)

  /**
   * A rollup box the synchronized state names as unspent, read from the node. A 404 means a block
   * spent it after that state was read, which is reported in one line rather than the node's body.
   */
  private[transactions] def unspentBox(ctx: BlockchainContext, boxId: String): InputUTXO =
    try InputUTXO(ctx.getBoxesById(boxId).head)
    catch {
      case gone: ErgoClientException if Option(gone.getMessage).exists(_.contains("404")) =>
        throw StateChangedException(s"box $boxId is no longer unspent at the node; a block moved " +
          "after this rollup's state was read")
    }

  /**
   * The transform a candidate may rebuild in place of the pending one, if any. Only the transform
   * into the projected phase qualifies, only as the rollup's sole unconfirmed spend, only if it is
   * valid in this block, and only while nothing in the mempool spends any of its outputs.
   * `outputsSpent` is asked last, since it reads the node.
   */
  private[transactions] def replaceableTransform(confirmed: LFSMPhase, projected: LFSMPhase,
                                                 pendingSpends: Int, eligible: RollupTxType => Boolean,
                                                 outputsSpent: => Boolean): Option[RollupTxType] = {
    val transform = (confirmed, projected) match {
      case (LFSMPhase.HOLDING, LFSMPhase.EVAL) => Some(HoldingTransform)
      case (LFSMPhase.EVAL, LFSMPhase.PAYOUT) => Some(EvalTransform)
      case _ => None
    }
    transform.filter(t => pendingSpends == 1 && eligible(t) && !outputsSpent)
  }

  /**
   * A pinned chain's bundle with the next funded transaction appended. That transaction spends the
   * change of one already in the bundle, so it goes after it; members already present are not repeated.
   */
  private[transactions] def extendChain(chain: CandidateBundle, next: CandidateBundle): CandidateBundle = {
    val present = chain.members.map(_.id).toSet
    CandidateBundle(chain.members ++ next.members.filterNot(m => present.contains(m.id)),
      (chain.interactions ++ next.interactions).distinct, chain.capital ++ next.capital)
  }

  /**
   * The output returning a funded transaction's wallet input whole: same script, same value, no
   * tokens. The last such output, since appkit appends change after the planned outputs, and a
   * fraud proof's reward pays the same script ahead of it.
   */
  private[transactions] def changeOf(signed: SignedTransaction, funding: InputUTXO): Option[InputUTXO] =
    JavaHelpers.toIndexedSeq(signed.getOutputsToSpend).map(InputUTXO(_)).filter { out =>
      out.contract.ergoTreeHex == funding.contract.ergoTreeHex && out.value == funding.value && out.tokens.isEmpty
    }.lastOption

  /**
   * What a funded submission still has to reserve beyond `base`: its fee outputs and bond, plus a
   * minimum box when a wallet input carries tokens, since those leave in a change box of their own.
   * The selection behind `base` left that room above the fees only, and the bond can use it up.
   */
  private[transactions] def submissionShortfall(base: Seq[InputUTXO], fees: Long, bond: Long): Long = {
    val required = fees + bond + (if (base.exists(_.tokens.nonEmpty)) UTXO.MIN_CHANGE else 0L)
    math.max(0L, required - base.foldLeft(0L)(_ + _.value))
  }

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
   *
   * A block's transaction tree is built over transaction ids, not over the signed bytes: the id is
   * the hash of the message to sign, so hashing the serialized form — proofs included — would
   * produce a digest no returned proof can ever carry.
   */
  private[transactions] def signedLeaf(sTx: SignedTransaction): String =
    sTx.getId.replace("\"", "")

  private def signedBytes(sTx: SignedTransaction): Array[Byte] =
    org.ergoplatform.ErgoLikeTransactionSerializer.toBytes(
      sTx.asInstanceOf[org.ergoplatform.appkit.impl.SignedTransactionImpl].getTx)

  /**
   * Whether a stub may go into the candidate for `blockHeight`. Every builder signs at that height
   * too, so it is the only height asked about.
   */
  private[transactions] def eligibleForCandidate(stub: RollupTxStub, rollup: Rollup, blockHeight: Int): Boolean =
    stub.validate(blockHeight, rollup)

  /** The height a transaction sent now is validated at: the node checks pooled ones against the next block. */
  private[transactions] def pooledHeight(ctx: BlockchainContext): Int = ctx.getHeight + 1

  /**
   * Which box the next spend takes from a metadata reply: the confirmed one (`Left`) or the
   * projected mempool tip (`Right`). None when the rollup has no spendable state.
   *
   * `lapsing` is the phase a transform moves the rollup out of. An unconfirmed chain still in that
   * phase is submissions or fraud proofs, which the contract accepts only before the period ends
   * while the transform lands only after it, so the transform takes the confirmed box instead.
   */
  private[transactions] def spendTarget(reply: RollupInfo, lapsing: Option[LFSMPhase])
  : Option[Either[String, MempoolRollupMetadata]] = reply match {
    case RollupMessages.CurrentRollupMetadata(utxoId, _, Some(projected))
      if !projected.toBeRemoved && projected.ancestorIds.nonEmpty &&
        lapsing.contains(projected.metadata.phase) => Some(Left(utxoId))
    case RollupMessages.CurrentRollupMetadata(_, _, Some(projected)) if !projected.toBeRemoved =>
      Some(Right(projected))
    case RollupMessages.CurrentRollupMetadata(utxoId, _, None) => Some(Left(utxoId))
    case _ => None
  }

  /** The final send gate, kept directly testable so stale state cannot accidentally execute `send`. */
  private[transactions] def sendIfCurrentInput(rollupId: String,
                                          expectedRollupInput: String,
                                          current: RollupInfo,
                                          lapsing: Option[LFSMPhase] = None)(send: => String): String = {
    val currentInput = current match {
      case RollupMessages.CurrentRollup(_, _, Some(projected), _) if !projected.toBeRemoved =>
        projected.asInput.id.toString
      case RollupMessages.CurrentRollup(utxoId, _, None, _) => utxoId
      case RollupMessages.CurrentRollup(_, _, Some(_), _) =>
        throw ProjectionChangedException(s"Rollup $rollupId is being removed before send")
      case metadata: RollupMessages.CurrentRollupMetadata if spendTarget(metadata, lapsing).nonEmpty =>
        spendTarget(metadata, lapsing).get.fold(identity, _.asInput.id.toString)
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

  /** Self-message: the finished bundle for a fee-less submission, kept so that height's refreshes reuse it. */
  private[transactions] case class CandidateSubmissionHeld(blockHeight: Int, rollupId: String,
                                                           bundle: CandidateBundle)

  case class InitialTxInfo(feesToCreate: Map[String, Long])

  /** One candidate build: the bundles for the package, and the stubs they were built from. */
  final case class CandidateBuild(bundles: Seq[CandidateBundle], built: Seq[RollupTxStub])
}
