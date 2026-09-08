package transactions.engine.execution

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import configs.NodeContext
import node.NodeApi
import node.MutationConversions._
import node.model._
import state.synchronization.CompleteMempool
import work.lithos.mutations.{TxBuilder, UTXO}
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._
import transactions.engine.EngineBroadcast
import transactions.engine.wallet.{EngineFunding, EngineWalletMessages, WalletInventory}

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
   * Count the whole wallet but retain only the oldest input-budget worth of candidates, so a
   * fragmented wallet costs a bounded amount of memory rather than a sorted copy of itself.
   *
   * Eligible means confirmed, on-chain, signable, ERG-only, register-free and unowned. Token boxes
   * are excluded outright: merging them would need per-token preservation this path does not do.
   */
  private[engine] def select(api: NodeApi, trees: Set[String], excluded: Set[String],
                             height: Int, target: Int): Selection = {
    require(target > 0, "consolidation target must be positive")
    var total, eligible = 0L
    var oldestExcluded = Option.empty[Int]
    var oldestEligible = Vector.empty[NodeBox]
    var paging = Paging(0, WalletInventory.PageSize)
    var exhausted = false
    val startedAt = System.nanoTime()
    while (!exhausted) {
      require(System.nanoTime() - startedAt < WalletInventory.MaxWalkNanos,
        "consolidation inventory deadline exceeded")
      val page = api.walletUnspentBoxes(ConfirmationRange.IncludeMempool, paging).get
      require(page.size <= paging.limit, "oversized consolidation inventory page")
      page.foreach { entry =>
        val box = entry.box
        total = Math.addExact(total, 1L)
        val safe = entry.onchain && !entry.spent && entry.confirmationsNum.exists(_ > 0) &&
          trees.contains(box.ergoTree) && box.assets.isEmpty && box.additionalRegisters.ordered.isEmpty &&
          !excluded.contains(box.boxId) && box.creationHeight <= height
        if (safe) {
          eligible = Math.addExact(eligible, 1L)
          // Box id breaks height ties so the same wallet always yields the same selection.
          oldestEligible = (oldestEligible :+ box)
            .sortBy(candidate => (candidate.creationHeight, candidate.boxId))
            .take(WalletInventory.MaxInputs)
        } else oldestExcluded = Some(oldestExcluded.fold(box.creationHeight)(math.min(_, box.creationHeight)))
      }
      exhausted = page.size < paging.limit
      paging = paging.next
    }
    // Merging n inputs into one output removes n - 1 boxes, so overshooting the target by one input
    // is what lands exactly on it.
    val wanted = math.min(WalletInventory.MaxInputs.toLong, math.max(0L, total - target + 1)).toInt
    val oldestHeight = oldestEligible.headOption.map(_.creationHeight)
    val blocksUntilRent = oldestHeight.map(created => math.max(0L,
      created.toLong + org.ergoplatform.wallet.protocol.Constants.StoragePeriod - height))
    // Even consolidating every eligible box leaves one output behind, so the excluded boxes alone
    // can put the target out of reach.
    val unreachable = total > target && total - math.max(0L, eligible - 1) > target
    Selection(if (total > target) oldestEligible.take(wanted) else Vector.empty,
      Status(total, target, eligible, oldestHeight, blocksUntilRent, oldestExcluded,
        unreachable, OutcomeEligible))
  }
}

/**
 * Optional wallet maintenance. It merges the oldest safe ERG-only boxes towards a configured total
 * UTXO count using the ordinary selection, signing, broadcast and reconciliation path, and never
 * runs while higher-priority work is queued.
 */
private[engine] class ConsolidationExecution(node: NodeContext, api: NodeApi, owner: ActorRef)
                                            (implicit ec: ExecutionContext) {
  import ConsolidationExecution._
  import EngineWalletMessages._
  private implicit val timeout: Timeout = Timeout(40.seconds)

  private def observation(): CompleteMempool.Observation = {
    val observed = Await.result(
      (owner ? CompleteMempool.Refresh).mapTo[CompleteMempool.Observation], timeout.duration)
    require(observed.fresh, "consolidation requires a fresh complete mempool")
    observed
  }

  def execute(target: Int, alive: () => Boolean): Status = {
    // One consolidation at a time. Chaining a second onto an unconfirmed first would build a run of
    // unconfirmed spends that a single rejection invalidates end to end.
    val holds = Await.result((owner ? GetEngineHolds).mapTo[EngineHolds], timeout.duration)
    if (holds.holds.exists(_.operation == "consolidation"))
      return Status(0, target, 0, None, None, None, false, OutcomeAwaitingReconciliation)

    val observed = observation()
    val ownedInputIds = Await.result((owner ? GetOwnedInputIds).mapTo[Set[String]], timeout.duration)
    node.getClient.execute { ctx =>
      val plan = select(api, node.getNodeWallet.signableTrees,
        ownedInputIds ++ observed.snapshot.get.spent, ctx.getHeight, target)
      // The scan pages the whole wallet, so recheck that neither the parent nor the mempool moved
      // while it ran before committing to the boxes it chose.
      val latest = observation()
      require(latest.snapshot.get.anchor == observed.snapshot.get.anchor &&
        latest.snapshot.get.ids == observed.snapshot.get.ids && alive(),
        "consolidation observation changed")
      if (plan.boxes.size < 2) plan.status.copy(outcome = OutcomeNoWork)
      else {
        val inputs = plan.boxes.map { box =>
          val input = box.toInputUTXO(ctx)
          require(input.id.toString == box.boxId && input.bytes.length <= WalletInventory.MaxInputBytes,
            "consolidation input identity or byte budget is invalid")
          input
        }
        val fee = transactions.rollups.TransactionMessages.RollupTxStub.ROLLUP_FEE
        val outputValue = inputs.foldLeft(0L)((sum, input) => Math.addExact(sum, input.value)) - fee
        require(outputValue >= UTXO.MIN_CHANGE, "consolidation cannot cover fee and minimum output")
        val allocation = EngineFunding(owner, 10.seconds, ec).reserveKnown(inputs)
        try {
          val unsigned = TxBuilder(ctx).setInputs(inputs: _*)
            .setOutputs(UTXO(node.getNodeWallet.contract, outputValue), UTXO.feeBox(fee))
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
          plan.status.copy(outcome = result.outcome)
          // Release only clears a reservation that never crossed the send boundary; a broadcast
          // transaction's inputs stay owned until reconciliation resolves them.
        } finally allocation.release()
      }
    }
  }
}
