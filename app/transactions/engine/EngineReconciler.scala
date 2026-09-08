package transactions.engine

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import configs.NodeContext
import node.NodeApi
import node.rest.{NodeHttpConfig, RestNodeApi}
import state.synchronization.CompleteMempool
import transactions.engine.EngineWalletMessages._

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._
import scala.util.control.NonFatal

/**
 * Resolves outstanding sends for every engine operation, not one kind of transaction: rollup,
 * emission, DEX, consolidation and reward-sweep holds are all reconciled the same way, because what
 * makes an input safe to reuse does not depend on what created it.
 *
 * Every check fails towards keeping ownership. A missing or lagging index never means spent, and
 * absence from the local mempool never means an input is free.
 */
class EngineReconciler(nodeContext: NodeContext, wallet: ActorRef, mempool: ActorRef)
                      (implicit ec: ExecutionContext) {
  private implicit val timeout: Timeout = Timeout(40.seconds)
  protected lazy val node: NodeApi = RestNodeApi(NodeHttpConfig(nodeContext.getNodeUrl,
    Some(nodeContext.getNodeKey), maxResponseBytes = 2 * 1024 * 1024, callTimeoutMs = 10000L))

  private def engineHolds(): Vector[EngineHold] =
    Await.result((wallet ? GetEngineHolds).mapTo[EngineHolds], timeout.duration).holds

  private def observation(): CompleteMempool.Snapshot = {
    val observed = Await.result(
      (mempool ? CompleteMempool.Refresh).mapTo[CompleteMempool.Observation], timeout.duration)
    require(observed.fresh, observed.failure.getOrElse("complete mempool observation expired"))
    observed.snapshot.get
  }

  /**
   * One pass over every hold whose send has finished, bounded by a single walk budget. A hold that
   * cannot be resolved keeps its inputs and is retried on the next pass.
   */
  def reconcile(): Unit = {
    val outstanding = engineHolds().filter(_.sendFinished)
    if (outstanding.isEmpty) return
    val startedAt = System.nanoTime()
    // One observation for the whole pass. Every hold is being judged against the same mempool, and
    // a walk each would put the entire mempool on the latency path once per outstanding send. Each
    // hold still rechecks against a fresh observation before anything is released.
    val observed = observation()
    outstanding.foreach { hold =>
      if (System.nanoTime() - startedAt < CompleteMempool.MaxWalkNanos) {
        try reconcileHold(hold, observed)
        catch { case NonFatal(ex) => org.slf4j.LoggerFactory.getLogger("TransactionEngine")
          .warn(s"Keeping input ownership for ${hold.txId}: ${ex.getMessage}") }
      }
    }
  }

  /** One owned input's status, as three separately checked facts rather than one guess. */
  private case class InputStatus(id: String, confirmedSpent: Boolean, currentlyUnspent: Boolean)

  private def reconcileHold(hold: EngineHold, observed: CompleteMempool.Snapshot): Unit = {
    // Still in the mempool, so it may yet confirm and its inputs stay owned.
    if (observed.ids.contains(hold.txId)) return

    val protocolSpent = hold.signedInputIds.exists(confirmedSpend)
    val inputs = hold.walletInputIds.toVector.map { id =>
      val spent = confirmedSpend(id)
      InputStatus(id, spent,
        currentlyUnspent = !spent && !observed.spent.contains(id) && node.boxById(id).get.exists(_.boxId == id))
    }
    val spentInputs = inputs.collect { case InputStatus(id, true, _) => id }.toSet
    // At least one confirmed consumed input invalidates the old signed transaction, which is what
    // makes its surviving inputs safe to hand out. Local mempool absence alone proves nothing.
    val freeInputs =
      if (protocolSpent || spentInputs.nonEmpty) inputs.collect { case InputStatus(id, false, true) => id }.toSet
      else Set.empty[String]

    val latest = observation()
    require(latest.anchor == observed.anchor && !freeInputs.exists(latest.spent.contains),
      "chain or surviving inputs changed during reconciliation")
    if (spentInputs.nonEmpty || freeInputs.nonEmpty)
      require(Await.result((wallet ? ResolveEngineInputs(hold.reservationId, hold.txId, spentInputs, freeInputs))
        .mapTo[Boolean], timeout.duration), "engine input ownership changed during reconciliation")
  }

  /**
   * Whether the chain has consumed this box: the spending transaction exists, is at a height whose
   * header is still on the current chain, and the box itself is gone. A reorg fails the header
   * check, so a returned transaction cannot retire an input.
   */
  private def confirmedSpend(id: String): Boolean =
    node.indexedBoxById(id).get.flatMap(_.spentTransactionId).exists { txId =>
      node.indexedTransactionById(txId).get.exists { tx =>
        tx.inputs.exists(_.boxId == id) && tx.inclusionHeight > 0 && node.boxById(id).get.isEmpty &&
          node.chainSlice(Some(tx.inclusionHeight - 1), Some(tx.inclusionHeight + 1)).get
            .exists(header => header.id == tx.blockId && header.height == tx.inclusionHeight)
      }
    }
}
