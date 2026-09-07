package transactions.engine

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import configs.NodeContext
import node.NodeApi
import node.MutationConversions._
import node.model._
import state.synchronization.CompleteMempool
import work.lithos.mutations.{InputUTXO, TxBuilder, UTXO}
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._
import scala.collection.JavaConverters._

object ConsolidationExecution {
  final case class Status(total: Long, target: Int, eligible: Long, oldestEligible: Option[Int],
                          blocksUntilRent: Option[Long], oldestExcluded: Option[Int],
                          targetUnreachable: Boolean, outcome: String)
  private[engine] final case class Selection(boxes: Vector[NodeBox], status: Status)

  /** Retains only the oldest shared input budget while counting the entire wallet. */
  private[engine] def select(api: NodeApi, trees: Set[String], excluded: Set[String],
                            height: Int, target: Int): Selection = {
    require(target > 0, "consolidation target must be positive")
    var total, eligible = 0L
    var oldestExcluded = Option.empty[Int]
    var selected = Vector.empty[NodeBox]
    var paging = Paging(0, WalletInventory.PageSize)
    var done = false
    val started = System.nanoTime()
    while (!done) {
      require(System.nanoTime() - started < WalletInventory.MaxWalkNanos, "consolidation inventory deadline exceeded")
      val page = api.walletUnspentBoxes(ConfirmationRange.IncludeMempool, paging).get
      require(page.size <= paging.limit, "oversized consolidation inventory page")
      page.foreach { entry =>
        val b = entry.box
        total = Math.addExact(total, 1L)
        val safe = entry.onchain && !entry.spent && entry.confirmationsNum.exists(_ > 0) &&
          trees.contains(b.ergoTree) && b.assets.isEmpty && b.additionalRegisters.ordered.isEmpty &&
          !excluded.contains(b.boxId) && b.creationHeight <= height
        if (safe) {
          eligible = Math.addExact(eligible, 1L)
          selected = (selected :+ b).sortBy(x => (x.creationHeight, x.boxId)).take(WalletInventory.MaxInputs)
        } else oldestExcluded = Some(oldestExcluded.fold(b.creationHeight)(math.min(_, b.creationHeight)))
      }
      done = page.size < paging.limit
      paging = paging.next
    }
    val count = math.min(WalletInventory.MaxInputs.toLong, math.max(0L, total - target + 1)).toInt
    val oldest = selected.headOption.map(_.creationHeight)
    val rent = oldest.map(h => math.max(0L,
      h.toLong + org.ergoplatform.wallet.protocol.Constants.StoragePeriod - height))
    Selection(if (total > target) selected.take(count) else Vector.empty,
      Status(total, target, eligible, oldest, rent, oldestExcluded,
        total > target && total - math.max(0L, eligible - 1) > target, "eligible"))
  }
}

/** A maintenance attempt owns ordinary inputs through the same broadcast and reconciliation path. */
private[engine] class ConsolidationExecution(node: NodeContext, api: NodeApi, owner: ActorRef)
                                               (implicit ec: ExecutionContext) {
  import ConsolidationExecution._
  import EngineWalletMessages._
  private implicit val timeout: Timeout = Timeout(40.seconds)
  private def observation(): CompleteMempool.Observation = {
    val result = Await.result((owner ? CompleteMempool.Refresh).mapTo[CompleteMempool.Observation], timeout.duration)
    require(result.fresh, "consolidation requires a fresh complete mempool")
    result
  }

  def execute(target: Int, alive: () => Boolean): Status = {
    val holds = Await.result((owner ? GetEngineHolds).mapTo[EngineHolds], timeout.duration)
    if (holds.holds.exists(_.operation == "consolidation"))
      return Status(0, target, 0, None, None, None, false, "awaiting-reconciliation")
    val observed = observation()
    val owned = Await.result((owner ? GetOwnedInputIds).mapTo[Set[String]], timeout.duration)
    node.getClient.execute { ctx =>
      val plan = select(api, node.getNodeWallet.signableTrees,
        owned ++ observed.snapshot.get.spent, ctx.getHeight, target)
      val latest = observation()
      require(latest.snapshot.get.anchor == observed.snapshot.get.anchor &&
        latest.snapshot.get.ids == observed.snapshot.get.ids && alive(), "consolidation observation changed")
      if (plan.boxes.size < 2) plan.status.copy(outcome = "no-work")
      else {
        val inputs = plan.boxes.map { b =>
          val input = b.toInputUTXO(ctx)
          require(input.id.toString == b.boxId && input.bytes.length <= WalletInventory.MaxInputBytes,
            "consolidation input identity or byte budget is invalid")
          input
        }
        val fee = transactions.rollups.TransactionMessages.RollupTxStub.ROLLUP_FEE
        val value = inputs.foldLeft(0L)((n, b) => Math.addExact(n, b.value)) - fee
        require(value >= UTXO.MIN_CHANGE, "consolidation cannot cover fee and minimum output")
        val allocation = EngineFunding(owner, 10.seconds, ec).reserveKnown(inputs)
        try {
          val unsigned = TxBuilder(ctx).setInputs(inputs: _*)
            .setOutputs(UTXO(node.getNodeWallet.contract, value), UTXO.feeBox(fee))
            .buildTx(0, node.getNodeWallet.p2pk)
          require(alive(), "consolidation attempt expired")
          val signed = node.getNodeWallet.sign(unsigned)
          require(signed.getCost > 0 && signed.getCost <= ctx.getDataSource.getParameters.getMaxBlockCost,
            "consolidation exceeds transaction cost budget")
          val bytes = org.ergoplatform.ErgoLikeTransactionSerializer.toBytes(
            signed.asInstanceOf[org.ergoplatform.appkit.impl.SignedTransactionImpl].getTx).length
          require(bytes <= math.min(98304, ctx.getDataSource.getParameters.getMaxBlockSize),
            "consolidation exceeds transaction byte budget")
          val result = new EngineBroadcast(owner, api).send(signed, Seq(allocation), "consolidation", alive)
          plan.status.copy(outcome = result.outcome)
        } finally allocation.release()
      }
    }
  }
}
