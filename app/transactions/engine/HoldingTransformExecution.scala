package transactions.engine

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import configs.NodeContext
import lfsm.LFSMPhase.HOLDING
import node.{NodeApi, NodeError}
import node.rest.{NodeCodecs, NodeHttpConfig, RestNodeApi}
import state.messages.RollupMessages._
import state.synchronization.CompleteMempool
import transactions.engine.TransactionEngine._
import transactions.rollups.RollupTransactions
import transactions.engine.EngineWalletMessages._
import transactions.engine.EngineFunding
import work.lithos.mutations.{InputUTXO, UTXO}

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._
import scala.util.{Failure, Success}
import scala.util.control.NonFatal

/** Blocking execution of the first typed intent. Only the engine worker calls this class.
  * EngineWalletState holds compact send ownership until fresh, per-input evidence resolves it.
  */
class HoldingTransformExecution(nodeContext: NodeContext, wallet: ActorRef, sync: ActorRef,
                                mempool: ActorRef)(implicit ec: ExecutionContext) {
  private implicit val timeout: Timeout = Timeout(40.seconds)
  protected lazy val node: NodeApi = RestNodeApi(NodeHttpConfig(nodeContext.getNodeUrl,
    Some(nodeContext.getNodeKey), maxResponseBytes = 2 * 1024 * 1024, callTimeoutMs = 10000L))
  private val selector = EngineFunding(wallet, 5.seconds, ec)

  private def holds(): Vector[EngineHold] =
    Await.result((wallet ? GetEngineHolds).mapTo[EngineHolds], timeout.duration).holds

  private def observation(): CompleteMempool.Snapshot = {
    val observed = Await.result((mempool ? CompleteMempool.Refresh).mapTo[CompleteMempool.Observation], timeout.duration)
    require(observed.fresh, observed.failure.getOrElse("complete mempool observation expired"))
    observed.snapshot.get
  }

  private def current(intent: HoldingTransform): Option[(String, lfsm.states.Rollup)] =
    Await.result((sync ? GetCurrentRollupCritical(intent.blockId)).mapTo[RollupInfo], timeout.duration) match {
      case CurrentRollup(_, _, Some(projected), _) if projected.toBeRemoved => None
      case CurrentRollup(_, _, Some(projected), _) => Some(projected.asInput.id.toString -> projected.rollup)
      case CurrentRollup(id, rollup, None, _) => Some(id -> rollup)
      case NoRollupFound() => None
      case RollupUnavailable(reason) => throw new IllegalStateException(reason)
    }

  def execute(intent: HoldingTransform, alive: () => Boolean): Outcome = {
    require(alive(), "engine attempt was superseded")
    val owned = holds()
    val previous = owned.find(_.operation == intent.key)
    if (previous.exists(!_.sendFinished))
      return Uncertain(intent.key, previous.get.txId, "previous send has not completed")
    if (previous.isEmpty && owned.size >= 64) return Deferred(intent.key, "active ownership capacity exhausted")
    val observed = observation()
    previous.filter(h => observed.ids.contains(h.txId)).foreach { hold =>
      return Accepted(intent.key, hold.txId)
    }
    val state = current(intent)
    if (state.isEmpty || state.exists { case (_, r) => r.phase != HOLDING || !r.currentPeriod.contains(intent.period) })
      return Completed(intent.key)
    val (protocolInput, _) = state.get
    if (previous.exists(!_.signedInputIds.contains(protocolInput)))
      return Uncertain(intent.key, previous.get.txId, "previous input ownership awaits reconciliation")
    val height = node.info().get.fullHeight.getOrElse(throw new IllegalStateException("node has no full height"))
    if (height - intent.period < lfsm.LFSMHelpers.HOLDING_PERIOD)
      return Deferred(intent.key, "holding period has not elapsed")
    if (observed.spent.contains(protocolInput)) return Deferred(intent.key, "rollup input is already spent in the mempool")
    require(CompleteMempool.anchor(node) == observed.anchor, "chain changed before preparation")
    if (previous.exists(_.walletInputIds.exists(id => observed.spent.contains(id) || node.boxById(id).get.isEmpty)))
      return Uncertain(intent.key, previous.get.txId, "owned fee input is not currently unspent")
    sendOwned(intent, protocolInput, observed, previous, alive)
  }

  private def sendOwned(intent: HoldingTransform, protocolInput: String,
                        observed: CompleteMempool.Snapshot, previous: Option[EngineHold],
                        alive: () => Boolean): Outcome = {
    val reservation = if (previous.isEmpty) Some(selector.reserveCoveringP2PK(intent.fee + UTXO.MIN_FEE)) else None
    val reservationId = previous.map(_.reservationId).getOrElse(reservation.get.id)
    var knownId: Option[String] = None
    var crossedSend = false
    try {
      val tx = build(intent, protocolInput, reservation.map(_.inputs), previous.map(_.walletInputIds))
      knownId = Some(tx.getId)
      val json = tx.toJson(false)
      require(json.length <= 1024 * 1024, "signed transaction body exceeds engine budget")
      val decoded = NodeCodecs.transaction(new com.google.gson.JsonParser().parse(json).getAsJsonObject)
      val walletIds = previous.map(_.walletInputIds).getOrElse(reservation.get.inputs.map(_.id.toString).toSet)
      require(decoded.inputs.map(_.boxId).toSet == walletIds + protocolInput, "signed inputs differ from owned inputs")
      val fresh = observation()
      require(fresh.anchor == observed.anchor && current(intent).exists(_._1 == protocolInput),
        "chain or rollup input changed during preparation")
      require(!decoded.inputs.exists(i => fresh.spent.contains(i.boxId)), "signed input is now mempool-spent")
      require(alive(), "engine attempt was superseded before send")
      val hold = EngineHold(intent.key, reservationId, tx.getId, walletIds + protocolInput, walletIds)
      val pinned = Await.result((wallet ? PinEngineInputs(hold, previous.map(_.txId))).mapTo[Boolean], timeout.duration)
      require(pinned, "wallet no longer owns the exact engine inputs")
      require(alive() && CompleteMempool.anchor(node) == fresh.anchor &&
        System.nanoTime() - fresh.observedAt < CompleteMempool.MaxAgeNanos, "send observation is obsolete")
      crossedSend = true
      val result = node.sendTransaction(json)
      val accepted = result.toOption.exists(_.replace("\"", "") == tx.getId)
      wallet ! EngineSendFinished(reservationId, tx.getId, accepted)
      result match {
        case Success(_) if accepted => Accepted(intent.key, tx.getId)
        case Failure(ex: NodeError.Rejected) => Rejected(intent.key, ex.getMessage, Some(tx.getId))
        case Failure(ex) => Uncertain(intent.key, tx.getId, ex.getMessage)
        case Success(_) => Uncertain(intent.key, tx.getId, "node returned another transaction id")
      }
    } catch {
      case NonFatal(ex) if crossedSend =>
        wallet ! EngineSendFinished(reservationId, knownId.get, accepted = false)
        Uncertain(intent.key, knownId.get, ex.getMessage)
      case NonFatal(ex) => Deferred(intent.key, ex.getMessage)
    } finally {
      if (!crossedSend) {
        knownId.foreach(id => wallet ! CancelEngineInputs(reservationId, id, previous))
        reservation.foreach(_.release())
      }
    }
  }

  /** The signing boundary is kept separate from admission and external-send ownership. */
  protected def build(intent: HoldingTransform, protocolInput: String,
                      selected: Option[Seq[InputUTXO]], retained: Option[Set[String]]): org.ergoplatform.appkit.SignedTransaction =
    nodeContext.getClient.execute { ctx =>
      val input = InputUTXO(ctx.getBoxesById(protocolInput).head)
      val inputs = selected.getOrElse(retained.get.toVector.sorted.map(id => InputUTXO(ctx.getBoxesById(id).head)))
      RollupTransactions.genHoldingTransform(ctx, nodeContext.getNodeWallet, input,
        inputs, Seq(UTXO.feeBox(intent.fee)))
    }

  /** Missing or lagging index data never means spent. Each surviving input is considered separately. */
  def reconcile(): Unit = {
    val owned = holds().filter(_.sendFinished)
    if (owned.isEmpty) return
    val started = System.nanoTime()
    owned.foreach { hold =>
      if (System.nanoTime() - started < CompleteMempool.MaxWalkNanos) {
        try reconcileHold(hold)
        catch { case NonFatal(ex) => org.slf4j.LoggerFactory.getLogger("TransactionEngine")
          .warn(s"Keeping input ownership for ${hold.txId}: ${ex.getMessage}") }
      }
    }
  }

  private def reconcileHold(hold: EngineHold): Unit = {
    val observed = observation()
    if (observed.ids.contains(hold.txId)) return
    val protocolSpent = hold.signedInputIds.exists(confirmedSpend)
    val states = hold.walletInputIds.toVector.map { id =>
      val spent = confirmedSpend(id)
      val free = !spent && !observed.spent.contains(id) && node.boxById(id).get.exists(_.boxId == id)
      (id, spent, free)
    }
    val spentInputs = states.collect { case (id, true, _) => id }.toSet
    // At least one confirmed consumed input invalidates the old signed transaction. Local
    // mempool absence by itself cannot make its other inputs safe for a different request.
    val freeInputs = if (protocolSpent || spentInputs.nonEmpty) states.collect { case (id, false, true) => id }.toSet else Set.empty[String]
    val latest = observation()
    require(latest.anchor == observed.anchor && !freeInputs.exists(latest.spent.contains),
      "chain or surviving inputs changed during reconciliation")
    if (spentInputs.nonEmpty || freeInputs.nonEmpty)
      require(Await.result((wallet ? ResolveEngineInputs(hold.reservationId, hold.txId, spentInputs, freeInputs))
        .mapTo[Boolean], timeout.duration), "engine input ownership changed during reconciliation")
  }

  private def confirmedSpend(id: String): Boolean =
    node.indexedBoxById(id).get.flatMap(_.spentTransactionId).exists { txId =>
      node.indexedTransactionById(txId).get.exists { tx =>
        tx.inputs.exists(_.boxId == id) && tx.inclusionHeight > 0 && node.boxById(id).get.isEmpty &&
          node.chainSlice(Some(tx.inclusionHeight - 1), Some(tx.inclusionHeight + 1)).get
            .exists(header => header.id == tx.blockId && header.height == tx.inclusionHeight)
      }
    }
}
