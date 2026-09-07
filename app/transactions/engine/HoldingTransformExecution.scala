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
import work.lithos.mutations.{InputUTXO, UTXO}

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._
import scala.util.{Failure, Success}
import scala.util.control.NonFatal

/**
 * Builds and sends holding transforms, and owns the engine's reconciliation pass.
 *
 * Only the engine worker calls this; every method blocks. [[reconcile]] covers every outstanding
 * engine hold, not just holding transforms, because input ownership is resolved the same way
 * whichever operation created it.
 */
class HoldingTransformExecution(nodeContext: NodeContext, wallet: ActorRef, sync: ActorRef,
                                mempool: ActorRef)(implicit ec: ExecutionContext) {
  /** Outstanding engine holds before new optional work is refused rather than queued behind them. */
  private final val MaxConcurrentHolds = 64
  private final val MaxSignedBodyBytes = 1024 * 1024

  private implicit val timeout: Timeout = Timeout(40.seconds)
  protected lazy val node: NodeApi = RestNodeApi(NodeHttpConfig(nodeContext.getNodeUrl,
    Some(nodeContext.getNodeKey), maxResponseBytes = 2 * 1024 * 1024, callTimeoutMs = 10000L))
  private val selector = EngineFunding(wallet, 5.seconds, ec)

  /** Active ownership records held by the engine, keyed by the operation that created them. */
  private def engineHolds(): Vector[EngineHold] =
    Await.result((wallet ? GetEngineHolds).mapTo[EngineHolds], timeout.duration).holds

  private def observation(): CompleteMempool.Snapshot = {
    val observed = Await.result(
      (mempool ? CompleteMempool.Refresh).mapTo[CompleteMempool.Observation], timeout.duration)
    require(observed.fresh, observed.failure.getOrElse("complete mempool observation expired"))
    observed.snapshot.get
  }

  /**
   * The rollup box this transform must spend and its state, preferring the projected tip so a
   * chain of unconfirmed transforms builds on the newest one. None means there is nothing to spend.
   */
  private def currentRollup(intent: HoldingTransform): Option[(String, lfsm.states.Rollup)] =
    Await.result((sync ? GetCurrentRollupCritical(intent.blockId)).mapTo[RollupInfo], timeout.duration) match {
      case CurrentRollup(_, _, Some(projected), _) if projected.toBeRemoved => None
      case CurrentRollup(_, _, Some(projected), _) => Some(projected.asInput.id.toString -> projected.rollup)
      case CurrentRollup(id, rollup, None, _) => Some(id -> rollup)
      case NoRollupFound() => None
      case RollupUnavailable(reason) => throw new IllegalStateException(reason)
    }

  /**
   * Decide whether this transform still needs sending, then send it. A previous attempt's inputs are
   * reused rather than reselected, so a rebuild replaces that transaction instead of competing with it.
   */
  def execute(intent: HoldingTransform, alive: () => Boolean): Outcome = {
    require(alive(), "engine attempt was superseded")
    val owned = engineHolds()
    val previous = owned.find(_.operation == intent.key)
    if (previous.exists(!_.sendFinished))
      return Uncertain(intent.key, previous.get.txId, "previous send has not completed")
    if (previous.isEmpty && owned.size >= MaxConcurrentHolds)
      return Deferred(intent.key, "active ownership capacity exhausted")

    val observed = observation()
    // The earlier attempt is in the mempool, so it is doing the job and must not be duplicated.
    previous.filter(hold => observed.ids.contains(hold.txId)).foreach { hold =>
      return Accepted(intent.key, hold.txId)
    }

    // The contract state, not a stored receipt, decides whether this work is already done.
    val rollup = currentRollup(intent)
    if (rollup.isEmpty || rollup.exists { case (_, state) =>
      state.phase != HOLDING || !state.currentPeriod.contains(intent.period) })
      return Completed(intent.key)
    val (protocolInputId, _) = rollup.get

    if (previous.exists(!_.signedInputIds.contains(protocolInputId)))
      return Uncertain(intent.key, previous.get.txId, "previous input ownership awaits reconciliation")
    val height = node.info().get.fullHeight.getOrElse(
      throw new IllegalStateException("node has no full height"))
    if (height - intent.period < lfsm.LFSMHelpers.HOLDING_PERIOD)
      return Deferred(intent.key, "holding period has not elapsed")
    if (observed.spent.contains(protocolInputId))
      return Deferred(intent.key, "rollup input is already spent in the mempool")
    require(CompleteMempool.anchor(node) == observed.anchor, "chain changed before preparation")
    if (previous.exists(_.walletInputIds.exists(id =>
      observed.spent.contains(id) || node.boxById(id).get.isEmpty)))
      return Uncertain(intent.key, previous.get.txId, "owned fee input is not currently unspent")

    sendOwned(intent, protocolInputId, observed, previous, alive)
  }

  private def sendOwned(intent: HoldingTransform, protocolInputId: String,
                        observed: CompleteMempool.Snapshot, previous: Option[EngineHold],
                        alive: () => Boolean): Outcome = {
    val reservation = if (previous.isEmpty) Some(selector.reserveCoveringP2PK(intent.fee + UTXO.MIN_FEE)) else None
    val reservationId = previous.map(_.reservationId).getOrElse(reservation.get.reservationId)
    var signedTxId: Option[String] = None
    // Distinguishes "never reached the node" from "the answer was lost", which decides whether the
    // inputs may be released in the finally block.
    var crossedSendBoundary = false
    try {
      val tx = build(intent, protocolInputId, reservation.map(_.inputs), previous.map(_.walletInputIds))
      signedTxId = Some(tx.getId)
      val signedJson = tx.toJson(false)
      require(signedJson.length <= MaxSignedBodyBytes, "signed transaction body exceeds engine budget")
      val decoded = NodeCodecs.transaction(new com.google.gson.JsonParser().parse(signedJson).getAsJsonObject)
      val walletInputIds = previous.map(_.walletInputIds)
        .getOrElse(reservation.get.inputs.map(_.id.toString).toSet)
      require(decoded.inputs.map(_.boxId).toSet == walletInputIds + protocolInputId,
        "signed inputs differ from owned inputs")

      // Building took node round trips and a signature. Recheck that neither the parent nor the
      // rollup tip moved, or this transaction is already spending something that no longer exists.
      val fresh = observation()
      require(fresh.anchor == observed.anchor && currentRollup(intent).exists(_._1 == protocolInputId),
        "chain or rollup input changed during preparation")
      require(!decoded.inputs.exists(input => fresh.spent.contains(input.boxId)),
        "signed input is now mempool-spent")
      require(alive(), "engine attempt was superseded before send")

      val hold = EngineHold(intent.key, reservationId, tx.getId, walletInputIds + protocolInputId, walletInputIds)
      val pinned = Await.result(
        (wallet ? PinEngineInputs(hold, previous.map(_.txId))).mapTo[Boolean], timeout.duration)
      require(pinned, "wallet no longer owns the exact engine inputs")
      require(alive() && CompleteMempool.anchor(node) == fresh.anchor &&
        System.nanoTime() - fresh.observedAt < CompleteMempool.MaxAgeNanos, "send observation is obsolete")

      crossedSendBoundary = true
      val response = node.sendTransaction(signedJson)
      val accepted = response.toOption.exists(_.replace("\"", "") == tx.getId)
      wallet ! EngineSendFinished(reservationId, tx.getId, accepted)
      response match {
        case Success(_) if accepted => Accepted(intent.key, tx.getId)
        case Failure(ex: NodeError.Rejected) => Rejected(intent.key, ex.getMessage, Some(tx.getId))
        case Failure(ex) => Uncertain(intent.key, tx.getId, ex.getMessage)
        // A success naming another transaction is as ambiguous as no answer at all.
        case Success(_) => Uncertain(intent.key, tx.getId, "node returned another transaction id")
      }
    } catch {
      case NonFatal(ex) if crossedSendBoundary =>
        wallet ! EngineSendFinished(reservationId, signedTxId.get, accepted = false)
        Uncertain(intent.key, signedTxId.get, ex.getMessage)
      case NonFatal(ex) => Deferred(intent.key, ex.getMessage)
    } finally {
      // Safe only because nothing reached the node on this path.
      if (!crossedSendBoundary) {
        signedTxId.foreach(txId => wallet ! CancelEngineInputs(reservationId, txId, previous))
        reservation.foreach(_.release())
      }
    }
  }

  /** The signing boundary, kept separate from admission and from send ownership. */
  protected def build(intent: HoldingTransform, protocolInputId: String,
                      selected: Option[Seq[InputUTXO]],
                      retained: Option[Set[String]]): org.ergoplatform.appkit.SignedTransaction =
    nodeContext.getClient.execute { ctx =>
      val rollupInput = InputUTXO(ctx.getBoxesById(protocolInputId).head)
      val feeInputs = selected.getOrElse(retained.get.toVector.sorted.map(id => InputUTXO(ctx.getBoxesById(id).head)))
      RollupTransactions.genHoldingTransform(ctx, nodeContext.getNodeWallet, rollupInput,
        feeInputs, Seq(UTXO.feeBox(intent.fee)))
    }

  /**
   * Resolve outstanding sends against the chain and mempool. Bounded by one walk budget, and any
   * hold whose evidence is incomplete keeps its inputs: a missing or lagging index never means spent.
   */
  def reconcile(): Unit = {
    val outstanding = engineHolds().filter(_.sendFinished)
    if (outstanding.isEmpty) return
    val startedAt = System.nanoTime()
    outstanding.foreach { hold =>
      if (System.nanoTime() - startedAt < CompleteMempool.MaxWalkNanos) {
        try reconcileHold(hold)
        catch { case NonFatal(ex) => org.slf4j.LoggerFactory.getLogger("TransactionEngine")
          .warn(s"Keeping input ownership for ${hold.txId}: ${ex.getMessage}") }
      }
    }
  }

  /** One owned input's status, as three separately checked facts rather than one guess. */
  private case class InputStatus(id: String, confirmedSpent: Boolean, currentlyUnspent: Boolean)

  private def reconcileHold(hold: EngineHold): Unit = {
    val observed = observation()
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
