package transactions.engine

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import configs.NodeContext
import lfsm.LFSMPhase.HOLDING
import node.NodeApi
import node.rest.{NodeCodecs, NodeHttpConfig, RestNodeApi}
import state.messages.RollupMessages._
import state.synchronization.CompleteMempool
import transactions.engine.TransactionEngine._
import transactions.rollups.RollupTransactions
import transactions.engine.EngineWalletMessages._
import work.lithos.mutations.{InputUTXO, UTXO}

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._
import scala.util.control.NonFatal

/**
 * Builds and sends holding transforms.
 *
 * Only the engine worker calls this; every method blocks. Whether the work is still needed is
 * decided from contract state rather than a stored record, so a restart or a lost response both
 * resolve by rereading the chain. A previous attempt's inputs are reused rather than reselected,
 * which makes a rebuild replace that transaction instead of competing with it.
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

  private def engineHolds(): Vector[EngineHold] =
    Await.result((wallet ? GetEngineHolds).mapTo[EngineHolds], timeout.duration).holds

  private def observe(): CompleteMempool.Observation = {
    val observed = Await.result(
      (mempool ? CompleteMempool.Refresh).mapTo[CompleteMempool.Observation], timeout.duration)
    require(observed.fresh, observed.failure.getOrElse("complete mempool observation expired"))
    observed
  }

  private def observation(): CompleteMempool.Snapshot = observe().snapshot.get

  /**
   * The rollup box this transform must spend and its state, preferring the projected tip so a
   * chain of unconfirmed transforms builds on the newest one. None means there is nothing to spend.
   *
   * Asks for metadata only: a holding transform copies box state forward without touching the
   * authenticated dictionary, so loading one to answer this would be work no output depends on.
   */
  private def currentRollup(intent: HoldingTransform): Option[(String, lfsm.states.RollupStateView)] =
    Await.result((sync ? GetRollupMetadata(intent.blockId)).mapTo[RollupInfo], timeout.duration) match {
      case CurrentRollupMetadata(_, _, Some(projected)) if projected.toBeRemoved => None
      case CurrentRollupMetadata(_, _, Some(projected)) =>
        Some(projected.asInput.id.toString -> projected.metadata)
      case CurrentRollupMetadata(id, metadata, None) => Some(id -> metadata)
      case NoRollupFound() => None
      case RollupUnavailable(reason) => throw new IllegalStateException(reason)
      case other => throw new IllegalStateException(s"unexpected rollup reply: $other")
    }

  /** Decide whether this transform still needs sending, then send it. */
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

  /**
   * Build, recheck the rollup tip, then hand the signed transaction to the shared send boundary.
   * That boundary owns pinning, the node call and the cancel-on-failure path; everything before it
   * here is what makes this transform specifically still worth sending.
   */
  private def sendOwned(intent: HoldingTransform, protocolInputId: String,
                        observed: CompleteMempool.Snapshot, previous: Option[EngineHold],
                        alive: () => Boolean): Outcome = {
    val reservation = if (previous.isEmpty) Some(selector.reserveCoveringP2PK(intent.fee + UTXO.MIN_FEE)) else None
    val reservationId = previous.map(_.reservationId).getOrElse(reservation.get.reservationId)
    var broadcastStarted = false
    try {
      val tx = build(intent, protocolInputId, reservation.map(_.inputs), previous.map(_.walletInputIds))
      val signedJson = tx.toJson(false)
      require(signedJson.length <= MaxSignedBodyBytes, "signed transaction body exceeds engine budget")
      val decoded = NodeCodecs.transaction(new com.google.gson.JsonParser().parse(signedJson).getAsJsonObject)
      val walletInputIds = previous.map(_.walletInputIds)
        .getOrElse(reservation.get.inputs.map(_.id.toString).toSet)
      require(decoded.inputs.map(_.boxId).toSet == walletInputIds + protocolInputId,
        "signed inputs differ from owned inputs")

      // Building took node round trips and a signature. Recheck that neither the parent nor the
      // rollup tip moved, or this transaction is already spending something that no longer exists.
      val fresh = observe()
      require(fresh.snapshot.get.anchor == observed.anchor &&
        currentRollup(intent).exists(_._1 == protocolInputId),
        "chain or rollup input changed during preparation")
      require(alive(), "engine attempt was superseded before send")

      broadcastStarted = true
      // The observation just taken is handed on rather than walked again; the send boundary still
      // checks its freshness and re-reads the anchor before the node call.
      val result = new EngineBroadcast(wallet, node).sendOwned(tx,
        Seq(EngineBroadcast.Funding(reservationId, walletInputIds)), intent.key, alive,
        previous, Some(fresh))
      result.outcome match {
        case EngineBroadcast.Accepted => Accepted(intent.key, result.txId)
        case EngineBroadcast.Rejected =>
          Rejected(intent.key, result.reason.getOrElse("node rejected the transaction"), Some(result.txId))
        case _ => Uncertain(intent.key, result.txId, result.reason.getOrElse("node response was inconclusive"))
      }
    } catch {
      // Only pre-send failures reach here; the send boundary converts an ambiguous node call into an
      // Uncertain result rather than throwing, and releases nothing it may have sent.
      case NonFatal(ex) => Deferred(intent.key, ex.getMessage)
    } finally {
      if (!broadcastStarted) reservation.foreach(_.release())
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
}
