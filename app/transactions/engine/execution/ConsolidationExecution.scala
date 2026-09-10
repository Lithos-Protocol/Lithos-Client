package transactions.engine.execution

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import configs.NodeContext
import node.NodeApi
import node.MutationConversions._
import node.model._
import state.synchronization.CompleteMempool
import work.lithos.mutations.{Eip27Adjustment, InputUTXO, MainnetEip27Constants, Token, TxBuilder, UTXO}

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._
import scala.util.Try
import transactions.engine.EngineBroadcast
import transactions.engine.wallet.{EngineFunding, EngineWalletMessages, EngineWalletState, WalletInventory}
import transactions.rent.StorageRent

object ConsolidationExecution {

  /**
   * What one maintenance tick observed. `oldestExcluded` covers boxes consolidation may not touch
   * (token-bearing, reserved, unconfirmed), so an ineligible box drifting towards storage rent stays
   * visible instead of silently ageing.
   */
  final case class Status(total: Long, target: Int, eligible: Long, oldestEligible: Option[Int],
                          blocksUntilRent: Option[Long], oldestExcluded: Option[Int],
                          targetUnreachable: Boolean, outcome: String)

  private[engine] final case class Selection(boxes: Vector[NodeBox], status: Status)

  /**
   * How one batch's value and tokens are laid out across its outputs.
   *
   * Tokens are summed per id and cut into groups of at most [[MaxTokensPerBox]], one output each,
   * with a single plain output when the batch holds none. Re-emission tokens are left out entirely:
   * the build burns them and pays the proxy instead, so they are never carried and their obligation
   * is not available to spend.
   *
   * Fails rather than trimming. Dropping a token group to fit the value would leave those tokens
   * unaccounted for and the transaction would not balance, so a batch that cannot fund one minimum
   * output per group is cancelled and its boxes are picked up by a later pass.
   */
  private[engine] def outputPlan(inputs: Seq[InputUTXO], fee: Long,
                                    obligation: Long): Seq[(Seq[Token], Long)] = {
    val carried = inputs.flatMap(_.tokens)
      .filterNot(t => obligation > 0L && t.id.toString == MainnetEip27Constants.TokenId)
    // Ordered by id so the same batch always produces the same transaction.
    val merged: Seq[Token] = carried.groupBy(_.id.toString).toSeq.sortBy(_._1).map {
      case (_, held) => Token(held.head.id, held.foldLeft(0L)((n, t) => Math.addExact(n, t.amount)))
    }
    val groups: Seq[Seq[Token]] =
      if (merged.isEmpty) Vector(Seq.empty[Token]) else merged.grouped(MaxTokensPerBox).toVector
    val valueIn = inputs.foldLeft(0L)((sum, input) => Math.addExact(sum, input.value))
    val spendable = Math.subtractExact(Math.subtractExact(valueIn, fee), obligation)
    require(spendable >= Math.multiplyExact(groups.size.toLong, UTXO.MIN_CHANGE),
      s"consolidation of ${groups.size} output(s) cannot cover fee, obligation and minimum values")
    // The first output takes the remainder so the rest sit exactly at the minimum.
    val values = Math.subtractExact(spendable,
      Math.multiplyExact(groups.size.toLong - 1L, UTXO.MIN_CHANGE)) +:
      Seq.fill(groups.size - 1)(UTXO.MIN_CHANGE)
    groups.zip(values)
  }


  final val OutcomeEligible = "eligible"
  final val OutcomeNoWork = "no-work"
  final val OutcomeDisabled = "disabled"
  final val OutcomeNotObserved = "not-observed"
  final val OutcomeDeferred = "deferred"
  /** A previous consolidation still owns inputs, so starting another would chain unconfirmed spends. */
  final val OutcomeAwaitingReconciliation = "awaiting-reconciliation"

  /** Ceiling on one consolidation's signed size, well inside any supported node's block limit. */
  private final val MaxSignedBytes = 98304

  /**
   * Distinct token ids one consolidated output may carry.
   *
   * Deliberately far below the protocol's own ceiling. Boxes with very large token sets have caused
   * trouble before, around 255, so this leaves an order of magnitude of headroom. A batch holding
   * more than this many distinct ids is split across several outputs rather than refused.
   */
  private final val MaxTokensPerBox = 50

  /**
   * Count the whole wallet but retain only the oldest input-budget worth of candidates, so a
   * fragmented wallet costs a bounded amount of memory rather than a sorted copy of itself.
   *
   * Eligible means confirmed, on-chain, signable, register-free and unowned. Token boxes are
   * included: emission pays LIT in amounts that leave dust behind, and those boxes are most of what
   * a fragmented wallet accumulates. Registers still exclude a box, because they carry meaning this
   * path would destroy.
   */
  private[engine] def select(api: NodeApi, trees: Set[String], excluded: Set[String],
                             height: Int, target: Int, minInputs: Int = 2,
                             limits: configs.WalletConfig = configs.WalletConfig.Default,
                             transactions: Int = 1): Selection = {
    require(target > 0, "consolidation target must be positive")
    require(minInputs >= 2, "consolidation needs at least two inputs to remove a box")
    require(transactions >= 1, "a pass builds at least one consolidation")
    // Enough boxes for every transaction this pass may build; `execute` cuts them into batches.
    val ceiling = Math.multiplyExact(limits.maxInputs.toLong, transactions.toLong)
    var total, eligible = 0L
    var oldestExcluded = Option.empty[Int]
    var oldestEligible = Vector.empty[NodeBox]
    var paging = Paging(0, limits.pageSize)
    var exhausted = false
    val startedAt = System.nanoTime()
    while (!exhausted) {
      require(System.nanoTime() - startedAt < limits.inventoryTimeoutMs * 1000000L,
        "consolidation inventory deadline exceeded")
      val page = api.walletUnspentBoxes(ConfirmationRange.IncludeMempool, paging).get
      require(page.size <= paging.limit, "oversized consolidation inventory page")
      page.foreach { entry =>
        val box = entry.box
        total = Math.addExact(total, 1L)
        val safe = entry.onchain && !entry.spent && entry.confirmationsNum.exists(_ > 0) &&
          trees.contains(box.ergoTree) &&
          !excluded.contains(box.boxId) && box.creationHeight <= height
        if (safe) {
          eligible = Math.addExact(eligible, 1L)
          // Box id breaks height ties so the same wallet always yields the same selection.
          oldestEligible = (oldestEligible :+ box)
            .sortBy(candidate => (candidate.creationHeight, candidate.boxId))
            .take(ceiling.toInt)
        } else oldestExcluded = Some(oldestExcluded.fold(box.creationHeight)(math.min(_, box.creationHeight)))
      }
      exhausted = page.size < paging.limit
      paging = paging.next
    }
    // Merging n inputs into one output removes n - 1 boxes, so overshooting the target by one input
    // is what lands exactly on it.
    // N transactions leave N outputs behind, so landing on the target needs N more inputs than one
    // transaction would.
    val wanted = math.min(ceiling, math.max(0L, total - target + transactions.toLong)).toInt
    val oldestHeight = oldestEligible.headOption.map(_.creationHeight)
    val blocksUntilRent = oldestHeight.map(created => math.max(0L,
      created.toLong + StorageRent.StoragePeriod - height))
    // Even consolidating every eligible box leaves one output behind, so the excluded boxes alone
    // can put the target out of reach.
    val unreachable = total > target && total - math.max(0L, eligible - 1) > target
    // A pass below the floor pays a fee to remove fewer boxes than it is worth, so it is no work
    // rather than a small win.
    val worthwhile = total > target && wanted >= minInputs
    Selection(if (worthwhile) oldestEligible.take(wanted) else Vector.empty,
      Status(total, target, eligible, oldestHeight, blocksUntilRent, oldestExcluded,
        unreachable, if (worthwhile) OutcomeEligible else OutcomeNoWork))
  }
}

/**
 * Optional wallet maintenance. It merges the oldest safe ERG-only boxes towards a configured total
 * UTXO count using the ordinary selection, signing, broadcast and reconciliation path, and never
 * runs while higher-priority work is queued.
 */
private[engine] class ConsolidationExecution(node: NodeContext, api: NodeApi, owner: ActorRef,
                                             limits: configs.WalletConfig = configs.WalletConfig.Default)
                                            (implicit ec: ExecutionContext) {
  import ConsolidationExecution._
  import EngineWalletMessages._

  private val logger: org.slf4j.Logger =
    org.slf4j.LoggerFactory.getLogger("ConsolidationExecution")
  private implicit val timeout: Timeout = Timeout(40.seconds)

  private def observation(): CompleteMempool.Observation = {
    val observed = Await.result(
      (owner ? CompleteMempool.Refresh).mapTo[CompleteMempool.Observation], timeout.duration)
    require(observed.fresh, "consolidation requires a fresh complete mempool")
    observed
  }

  /**
   * Build, sign and send one batch. Every bound is checked per transaction rather than per pass,
   * because each is submitted on its own and a batch that breaches one must not stop the others.
   */
  private def sendOne(ctx: org.ergoplatform.appkit.BlockchainContext,
                      batch: Vector[NodeBox], alive: () => Boolean): String = {
    val inputs = batch.map { box =>
      val input = box.toInputUTXO(ctx)
      require(input.id.toString == box.boxId && input.bytes.length <= limits.maxInputBytes,
        "consolidation input identity or byte budget is invalid")
      input
    }
    val fee = transactions.rollups.TransactionMessages.RollupTxStub.ROLLUP_FEE
    val obligation = Eip27Adjustment.obligation(inputs, node.getNetwork)
    val plan = outputPlan(inputs, fee, obligation)
    val merged = plan.flatMap(_._1)
    val outputs = plan.map {
      case (tokens, value) => UTXO(node.getNodeWallet.contract, value, tokens)
    }
    val allocation = EngineFunding(owner, 10.seconds, ec).reserveKnown(inputs)
    try {
      val unsigned = TxBuilder(ctx).setInputs(inputs: _*)
        .setOutputs((outputs :+ UTXO.feeBox(fee)): _*)
        .buildTx(0, node.getNodeWallet.p2pk)
      require(alive(), "consolidation attempt expired")
      val signed = node.getNodeWallet.sign(unsigned)
      require(signed.getCost > 0 && signed.getCost <= ctx.getDataSource.getParameters.getMaxBlockCost,
        "consolidation exceeds transaction cost budget")
      val signedBytes = org.ergoplatform.ErgoLikeTransactionSerializer.toBytes(
        signed.asInstanceOf[org.ergoplatform.appkit.impl.SignedTransactionImpl].getTx).length
      require(signedBytes <= math.min(MaxSignedBytes, ctx.getDataSource.getParameters.getMaxBlockSize),
        "consolidation exceeds transaction byte budget")
      val result = new EngineBroadcast(owner, api).send(signed, Seq(allocation), "consolidation", alive)
      logger.info(s"Sent transaction ${result.txId} to consolidate ${inputs.size} wallet box(es) " +
        s"into ${outputs.size} holding ${merged.size} token id(s): ${result.outcome}")
      result.outcome
      // Release only clears a reservation that never crossed the send boundary; a broadcast
      // transaction's inputs stay owned until reconciliation resolves them.
    } finally allocation.release()
  }

  def execute(target: Int, alive: () => Boolean, minInputs: Int = 2, transactions: Int = 1): Status = {
    // One consolidation at a time. Chaining a second onto an unconfirmed first would build a run of
    // unconfirmed spends that a single rejection invalidates end to end.
    val holds = Await.result((owner ? GetEngineHolds).mapTo[EngineHolds], timeout.duration)
    if (holds.holds.exists(_.operation == "consolidation"))
      return Status(0, target, 0, None, None, None, false, OutcomeAwaitingReconciliation)

    val observed = observation()
    val ownedInputIds = Await.result((owner ? GetOwnedInputIds).mapTo[Set[String]], timeout.duration)
    node.getClient.execute { ctx =>
      val plan = select(api, node.getNodeWallet.signableTrees,
        ownedInputIds ++ observed.snapshot.get.spent, ctx.getHeight, target, minInputs, limits,
        transactions)
      // The scan pages the whole wallet, so recheck that neither the parent nor the mempool moved
      // while it ran before committing to the boxes it chose.
      val latest = observation()
      require(latest.snapshot.get.anchor == observed.snapshot.get.anchor &&
        latest.snapshot.get.ids == observed.snapshot.get.ids && alive(),
        "consolidation observation changed")
      // Disjoint batches, each its own transaction. They are deliberately not chained: a rejection
      // then costs only its own batch rather than invalidating everything behind it. A trailing
      // batch of one box is dropped, since merging one box removes nothing.
      val cut = plan.boxes.grouped(limits.maxInputs).filter(_.size >= 2).toVector
      // Every batch's inputs stay owned until its send resolves, so the pass cannot ask for more
      // than optional work is allowed to hold at once. Capping is right rather than failing: the
      // batches that fit are still worth sending, and the rest are picked up next pass.
      val headroom = EngineWalletState.MAX_OPTIONAL_INPUTS - ownedInputIds.size
      val affordable = cut.foldLeft((Vector.empty[Vector[NodeBox]], 0)) {
        case ((kept, held), batch) =>
          if (held + batch.size <= headroom) (kept :+ batch, held + batch.size) else (kept, held)
      }._1
      if (affordable.size < cut.size)
        logger.info(s"Consolidation capped at ${affordable.size} of ${cut.size} transaction(s): " +
          s"optional work may hold ${EngineWalletState.MAX_OPTIONAL_INPUTS} inputs and " +
          s"${ownedInputIds.size} are already owned")
      if (affordable.isEmpty) plan.status.copy(outcome = OutcomeNoWork)
      else {
        // Per batch, so one that cannot be built or sent does not discard the ones already away.
        val outcomes = affordable.map(batch => Try(sendOne(ctx, batch, alive)))
        outcomes.foreach(_.failed.foreach(ex =>
          logger.warn(s"One consolidation transaction failed: ${ex.getMessage}")))
        val sent = outcomes.flatMap(_.toOption)
        logger.info(s"Consolidation sent ${sent.size} of ${affordable.size} transaction(s) merging " +
          s"${affordable.map(_.size).sum} box(es)" +
          (if (sent.nonEmpty) s": ${sent.mkString(", ")}" else ""))
        // The pass is only as good as its transactions, so a send that did not simply succeed is
        // the outcome worth surfacing.
        val outcome = sent.find(_ != OutcomeEligible).orElse(sent.headOption)
          .getOrElse(OutcomeAwaitingReconciliation)
        plan.status.copy(outcome = outcome)
      }
    }
  }
}
