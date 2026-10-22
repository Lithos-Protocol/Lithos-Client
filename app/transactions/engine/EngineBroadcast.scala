package transactions.engine

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import node.NodeApi
import node.rest.NodeCodecs
import org.ergoplatform.appkit.SignedTransaction
import org.slf4j.LoggerFactory
import state.synchronization.CompleteMempool
import transactions.candidate.BlockTxMessages.CandidateTx
import transactions.engine.wallet.EngineWalletMessages._

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._
import scala.util.{Failure, Success}
import scala.util.control.NonFatal
import transactions.engine.wallet.{EngineWalletMessages, FundingAllocation}

object EngineBroadcast {
  /** One reservation's wallet inputs, without the hydrated boxes the worker used to build. */
  final case class Funding(reservationId: String, walletInputIds: Set[String])

  final val Accepted = "accepted"
  final val Rejected = "rejected"
  /** The node's answer was lost or contradictory, so the inputs stay owned until reconciliation. */
  final val Uncertain = "uncertain"

  /** `reason` carries the node's own words when it refused, for callers that report them. */
  final case class Result(txId: String, outcome: String, reason: Option[String] = None) {
    def requireAccepted(): String = {
      if (outcome != Accepted) throw new SubmissionOutcomeException(txId, outcome, reason)
      txId
    }
  }
  final class SubmissionOutcomeException(val txId: String, val outcome: String, val reason: Option[String] = None)
    extends RuntimeException(s"Transaction $txId submission outcome is $outcome" + reason.map(": " + _).getOrElse(""))

  /** The two node refusals callers recover from differently. */
  sealed trait Refusal
  /** An input is not in the UTXO set: already spent, or made by a transaction the node lacks. */
  case object InputMissing extends Refusal
  /** An input is already spent by a transaction in the node's mempool. */
  case object DoubleSpend extends Refusal

  /** Which known refusal a failed send was, read from the node's own words. Only a definite refusal counts. */
  def refusal(failure: Throwable): Option[Refusal] = {
    val text = failure match {
      case refused: SubmissionOutcomeException if refused.outcome == Rejected => refused.reason
      case node: org.ergoplatform.appkit.ErgoClientException => Option(node.getMessage)
      case _ => None
    }
    text.collect {
      case words if words.contains("Every input of the transaction should be in UTXO") => InputMissing
      case words if words.contains("Double spending") => DoubleSpend
    }
  }

  /** Signed body accepted for submission. Anything larger is a build defect, not a node limit. */
  private final val MaxSignedBodyBytes = 1024 * 1024

  /** Inputs a single engine-built transaction may carry, well above the wallet's own selection cap. */
  final val MaxSignedInputs = 150
}

/**
 * The send boundary. Pins every funding allocation to the exact signed transaction before the node
 * call, so an ambiguous response leaves the inputs owned rather than free.
 */
class EngineBroadcast(owner: ActorRef, node: NodeApi, requestTimeout: FiniteDuration = 40.seconds)
                     (implicit ec: ExecutionContext) {
  import EngineBroadcast._
  private implicit val timeout: Timeout = Timeout(requestTimeout)

  /** @param observed as for `sendOwned` */
  def send(tx: SignedTransaction, allocations: Seq[FundingAllocation], operation: String,
           alive: () => Boolean, observed: Option[CompleteMempool.Observation] = None): Result =
    sendOwned(tx, allocations.map(allocation =>
      Funding(allocation.reservationId, allocation.inputs.map(_.id.toString).toSet)), operation, alive,
      observed = observed)

  /**
   * @param replaces the finished attempt this transaction supersedes, when rebuilding on the same
   *                 inputs. Pinning then requires that attempt's identity to match, and a failure
   *                 before the node call restores it rather than dropping the ownership record.
   * @param observed a complete observation the caller already took, reused instead of walking the
   *                 mempool twice. It is still checked for freshness, and the chain anchor is
   *                 re-read from the node immediately before the send either way.
   */
  def sendOwned(tx: SignedTransaction, allocations: Seq[Funding], operation: String,
                alive: () => Boolean, replaces: Option[EngineHold] = None,
                observed: Option[CompleteMempool.Observation] = None): Result =
    submit(tx, allocations, operation, alive, _ => replaces, cancelling = false, observed)

  /**
   * Send `tx` to cancel `stale`, every hold of one transaction that is out of the mempool. `tx`
   * spends only boxes those holds own, so at most one of the two can be mined; until one is, the
   * cancellation owns every box the stale holds did, including inputs that are not on chain.
   */
  def cancel(tx: SignedTransaction, stale: Seq[EngineHold], alive: () => Boolean): Result = {
    require(stale.nonEmpty && stale.map(_.txId).distinct.size == 1, "a cancellation replaces one transaction")
    submit(tx, stale.map(hold => Funding(hold.reservationId, hold.walletInputIds)), "cancel:" + stale.head.txId,
      alive, funding => stale.find(_.reservationId == funding.reservationId), cancelling = true, None)
  }

  /** @param previous the attempt each allocation's boxes are bound to now, when replacing one */
  private def submit(tx: SignedTransaction, allocations: Seq[Funding], operation: String,
                     alive: () => Boolean, previous: Funding => Option[EngineHold], cancelling: Boolean,
                     observed: Option[CompleteMempool.Observation]): Result = {
    val txId = CandidateTx.txId(tx)
    // Distinguishes "the node never heard of this" from "the answer was lost", which decides
    // whether the inputs may be released below.
    var crossedSendBoundary = false
    try {
      val signedJson = tx.toJson(false)
      require(signedJson.length <= MaxSignedBodyBytes, "transaction exceeds signed body budget")
      val decoded = NodeCodecs.transaction(new com.google.gson.JsonParser().parse(signedJson).getAsJsonObject)
      val signedInputIds = decoded.inputs.map(_.boxId).toSet
      require(decoded.id == txId && signedInputIds.size == decoded.inputs.size &&
        signedInputIds.size <= MaxSignedInputs, "signed transaction identity or input budget is invalid")
      // Every funded input must appear in the signed body, and no two allocations may claim the
      // same box: otherwise one reservation's release would free another's input. A cancellation
      // is the reverse: it may spend nothing those allocations do not own.
      val fundedInputIds = allocations.flatMap(_.walletInputIds)
      require(fundedInputIds.distinct.size == fundedInputIds.size &&
        (if (cancelling) signedInputIds.nonEmpty && signedInputIds.subsetOf(fundedInputIds.toSet)
        else fundedInputIds.toSet.subsetOf(signedInputIds)),
        "signed inputs differ from engine funding allocations")
      val outputIds = decoded.outputs.map(_.boxId).toSet
      val unspent = observed.getOrElse(CompleteMempool.observe(owner, node, timeout.duration, "transaction send"))
      require(unspent.fresh && !signedInputIds.exists(unspent.snapshot.get.spent.contains),
        "transaction inputs require a fresh complete unspent observation")
      allocations.foreach { allocation =>
        require(alive(), "engine attempt was superseded")
        val replaced = previous(allocation)
        val hold = EngineHold(operation, allocation.reservationId, txId, signedInputIds,
          allocation.walletInputIds, outputIds = outputIds,
          supersedes = if (cancelling) replaced.map(old => old.supersedes + (old.txId -> old.outputIds))
            .getOrElse(Map.empty) else Map.empty)
        require(Await.result((owner ? PinEngineInputs(hold, replaced.map(_.txId), cancelling))
          .mapTo[Boolean], timeout.duration), "engine funding ownership changed before send")
      }
      // Re-read the anchor rather than trusting the earlier one: pinning took several round trips,
      // and a parent change since then invalidates the unspent evidence gathered above.
      require(alive() && unspent.fresh && CompleteMempool.anchor(node) == unspent.snapshot.get.anchor,
        "transaction send observation changed")
      crossedSendBoundary = true
      val response = node.sendTransaction(signedJson)
      val accepted = response.toOption.exists(_.replace("\"", "") == txId)
      allocations.foreach(a => owner ! EngineSendFinished(a.reservationId, txId, accepted))
      owner ! RefreshBoxes
      response match {
        case Success(_) if accepted => Result(txId, Accepted)
        // The node's own answer, not a fault here: usually the chain or mempool moved since the
        // transaction was built, so its reason is the whole story and a stack trace adds nothing.
        // A refused transaction spent nothing, so its inputs go back.
        case Failure(ex: _root_.node.NodeError.Rejected) =>
          LoggerFactory.getLogger("TransactionEngine").warn(s"Node refused $txId ($operation): ${ex.getMessage}")
          allocations.foreach(a => owner ! EngineSendRefused(a.reservationId, txId, previous(a)))
          Result(txId, Rejected, Option(ex.getMessage))
        // A success carrying another transaction id is as ambiguous as no answer at all.
        case Success(_) => Result(txId, Uncertain, Some("node returned another transaction id"))
        case Failure(ex) => Result(txId, Uncertain, Option(ex.getMessage))
      }
    } catch {
      case NonFatal(ex) if crossedSendBoundary =>
        allocations.foreach(a => owner ! EngineSendFinished(a.reservationId, txId, accepted = false))
        Result(txId, Uncertain, Option(ex.getMessage))
    } finally {
      // Safe only because nothing reached the node on this path, so no input can be in flight.
      // A rebuild restores the attempt it was replacing instead of dropping its ownership.
      if (!crossedSendBoundary) allocations.foreach { allocation =>
        owner ! CancelEngineInputs(allocation.reservationId, txId, previous(allocation))
        owner ! ReleaseInputs(allocation.reservationId)
      }
    }
  }
}
