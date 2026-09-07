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
import transactions.engine.FundingAllocation

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._
import scala.util.{Failure, Success}
import scala.util.control.NonFatal

object EngineBroadcast {
  final case class Funding(id: String, inputIds: Set[String])
  final case class Result(txId: String, outcome: String) {
    def requireAccepted(): String = {
      if (outcome != "accepted") throw new SubmissionOutcomeException(txId, outcome)
      txId
    }
  }
  final class SubmissionOutcomeException(val txId: String, val outcome: String)
    extends RuntimeException(s"Transaction $txId submission outcome is $outcome")
}

/** Pins every funding allocation to the exact signed transaction before the node call. */
class EngineBroadcast(owner: ActorRef, node: NodeApi, requestTimeout: FiniteDuration = 40.seconds)(implicit ec: ExecutionContext) {
  import EngineBroadcast._
  private implicit val timeout: Timeout = Timeout(requestTimeout)

  def send(tx: SignedTransaction, allocations: Seq[FundingAllocation], operation: String,
           alive: () => Boolean): Result =
    sendOwned(tx, allocations.map(a => Funding(a.id, a.inputs.map(_.id.toString).toSet)), operation, alive)

  def sendOwned(tx: SignedTransaction, allocations: Seq[Funding], operation: String,
                alive: () => Boolean): Result = {
    val txId = tx.getId
    var sent = false
    try {
      val json = tx.toJson(false)
      require(json.length <= 1024 * 1024, "transaction exceeds signed body budget")
      val decoded = NodeCodecs.transaction(new com.google.gson.JsonParser().parse(json).getAsJsonObject)
      val inputIds = decoded.inputs.map(_.boxId).toSet
      require(decoded.id == txId && inputIds.size == decoded.inputs.size && inputIds.size <= 150,
        "signed transaction identity or input budget is invalid")
      val walletIds = allocations.flatMap(_.inputIds)
      require(walletIds.distinct.size == walletIds.size && walletIds.toSet.subsetOf(inputIds),
        "signed inputs differ from engine funding allocations")
      val observed = Await.result((owner ? CompleteMempool.Refresh).mapTo[CompleteMempool.Observation], timeout.duration)
      require(observed.fresh && !inputIds.exists(observed.snapshot.get.spent.contains),
        "transaction inputs require a fresh complete unspent observation")
      allocations.foreach { allocation =>
        require(alive(), "engine attempt was superseded")
        val hold = EngineHold(operation, allocation.id, txId, inputIds,
          allocation.inputIds)
        require(Await.result((owner ? PinEngineInputs(hold)).mapTo[Boolean], timeout.duration),
          "engine funding ownership changed before send")
      }
      require(alive() && observed.fresh && CompleteMempool.anchor(node) == observed.snapshot.get.anchor,
        "transaction send observation changed")
      sent = true
      val response = node.sendTransaction(json)
      val accepted = response.toOption.exists(_.replace("\"", "") == txId)
      allocations.foreach(a => owner ! EngineSendFinished(a.id, txId, accepted))
      owner ! RefreshBoxes
      response match {
        case Success(_) if accepted => Result(txId, "accepted")
        case Failure(ex: _root_.node.NodeError.Rejected) =>
          LoggerFactory.getLogger("TransactionEngine").error(s"Got error during broadcast for $txId", ex)
          Result(txId, "rejected")
        case _ => Result(txId, "uncertain")
      }
    } catch {
      case NonFatal(_) if sent =>
        allocations.foreach(a => owner ! EngineSendFinished(a.id, txId, accepted = false))
        Result(txId, "uncertain")
    } finally {
      if (!sent) allocations.foreach { a =>
        owner ! CancelEngineInputs(a.id, txId)
        owner ! ReleaseInputs(a.id)
      }
    }
  }
}
