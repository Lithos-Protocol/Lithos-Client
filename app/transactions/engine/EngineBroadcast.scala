package transactions.engine

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import node.NodeApi
import node.rest.NodeCodecs
import org.ergoplatform.appkit.SignedTransaction
import org.slf4j.LoggerFactory
import state.synchronization.CompleteMempool
import transactions.engine.EngineWalletMessages._

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._
import scala.util.{Failure, Success}
import scala.util.control.NonFatal

object EngineBroadcast {
  /** One reservation's wallet inputs, without the hydrated boxes the worker used to build. */
  final case class Funding(reservationId: String, walletInputIds: Set[String])

  final val Accepted = "accepted"
  final val Rejected = "rejected"
  /** The node's answer was lost or contradictory, so the inputs stay owned until reconciliation. */
  final val Uncertain = "uncertain"

  final case class Result(txId: String, outcome: String) {
    def requireAccepted(): String = {
      if (outcome != Accepted) throw new SubmissionOutcomeException(txId, outcome)
      txId
    }
  }
  final class SubmissionOutcomeException(val txId: String, val outcome: String)
    extends RuntimeException(s"Transaction $txId submission outcome is $outcome")

  /** Signed body accepted for submission. Anything larger is a build defect, not a node limit. */
  private final val MaxSignedBodyBytes = 1024 * 1024

  /** Inputs a single engine-built transaction may carry, well above the wallet's own selection cap. */
  private final val MaxSignedInputs = 150
}

/**
 * The send boundary. Pins every funding allocation to the exact signed transaction before the node
 * call, so an ambiguous response leaves the inputs owned rather than free.
 */
class EngineBroadcast(owner: ActorRef, node: NodeApi, requestTimeout: FiniteDuration = 40.seconds)
                     (implicit ec: ExecutionContext) {
  import EngineBroadcast._
  private implicit val timeout: Timeout = Timeout(requestTimeout)

  def send(tx: SignedTransaction, allocations: Seq[FundingAllocation], operation: String,
           alive: () => Boolean): Result =
    sendOwned(tx, allocations.map(allocation =>
      Funding(allocation.reservationId, allocation.inputs.map(_.id.toString).toSet)), operation, alive)

  def sendOwned(tx: SignedTransaction, allocations: Seq[Funding], operation: String,
                alive: () => Boolean): Result = {
    val txId = tx.getId
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
      // same box: otherwise one reservation's release would free another's input.
      val fundedInputIds = allocations.flatMap(_.walletInputIds)
      require(fundedInputIds.distinct.size == fundedInputIds.size &&
        fundedInputIds.toSet.subsetOf(signedInputIds),
        "signed inputs differ from engine funding allocations")
      val observed = Await.result(
        (owner ? CompleteMempool.Refresh).mapTo[CompleteMempool.Observation], timeout.duration)
      require(observed.fresh && !signedInputIds.exists(observed.snapshot.get.spent.contains),
        "transaction inputs require a fresh complete unspent observation")
      allocations.foreach { allocation =>
        require(alive(), "engine attempt was superseded")
        val hold = EngineHold(operation, allocation.reservationId, txId, signedInputIds,
          allocation.walletInputIds)
        require(Await.result((owner ? PinEngineInputs(hold)).mapTo[Boolean], timeout.duration),
          "engine funding ownership changed before send")
      }
      // Re-read the anchor rather than trusting the earlier one: pinning took several round trips,
      // and a parent change since then invalidates the unspent evidence gathered above.
      require(alive() && observed.fresh && CompleteMempool.anchor(node) == observed.snapshot.get.anchor,
        "transaction send observation changed")
      crossedSendBoundary = true
      val response = node.sendTransaction(signedJson)
      val accepted = response.toOption.exists(_.replace("\"", "") == txId)
      allocations.foreach(a => owner ! EngineSendFinished(a.reservationId, txId, accepted))
      owner ! RefreshBoxes
      response match {
        case Success(_) if accepted => Result(txId, Accepted)
        case Failure(ex: _root_.node.NodeError.Rejected) =>
          LoggerFactory.getLogger("TransactionEngine").error(s"Got error during broadcast for $txId", ex)
          Result(txId, Rejected)
        // A success carrying another transaction id is as ambiguous as no answer at all.
        case _ => Result(txId, Uncertain)
      }
    } catch {
      case NonFatal(_) if crossedSendBoundary =>
        allocations.foreach(a => owner ! EngineSendFinished(a.reservationId, txId, accepted = false))
        Result(txId, Uncertain)
    } finally {
      // Safe only because nothing reached the node on this path, so no input can be in flight.
      if (!crossedSendBoundary) allocations.foreach { allocation =>
        owner ! CancelEngineInputs(allocation.reservationId, txId)
        owner ! ReleaseInputs(allocation.reservationId)
      }
    }
  }
}
