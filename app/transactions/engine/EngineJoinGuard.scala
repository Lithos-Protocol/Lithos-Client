package transactions.engine

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import node.NodeApi
import org.ergoplatform.appkit.SignedTransaction
import state.synchronization.CompleteMempool
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._

private[transactions] object EngineJoinGuard {
  /** Ask the engine which lender keys local attempts already hold. */
  case object Keys
  /** Claim a key before any funding is selected, so two local attempts cannot both pass the scan. */
  final case class Acquire(key: String, lease: String)
  /** Bind a claimed key to the exact transaction and funding about to be sent. */
  final case class Pin(key: String, lease: String, txId: String, funding: Set[String])
  /** Drop a claim that never reached a send. A pinned key survives this. */
  final case class Cancel(key: String, lease: String)

  final case class Ownership(lease: String, txId: Option[String] = None, funding: Set[String] = Set.empty)
}

/**
 * Keeps one emission join from reusing a lender key another join already took. A duplicate key
 * produces a queue position that can only be cleared or slashed, so every check here fails closed:
 * an incomplete scan means "ineligible", never "free".
 */
private[transactions] class EngineJoinGuard(owner: ActorRef, api: NodeApi)(implicit ec: ExecutionContext) {
  import EngineJoinGuard._
  private implicit val timeout: Timeout = Timeout(40.seconds)

  /** Keys claimed by unconfirmed protocol boxes plus those held by local in-flight attempts. */
  def exclusions(): Set[String] = {
    val observed = Await.result(
      (owner ? CompleteMempool.Refresh).mapTo[CompleteMempool.Observation], timeout.duration)
    require(observed.fresh && CompleteMempool.anchor(api) == observed.snapshot.get.anchor,
      "join eligibility requires a fresh complete mempool")
    observed.snapshot.get.lenderKeys ++ Await.result((owner ? Keys).mapTo[Set[String]], timeout.duration)
  }

  /**
   * Run `buildAndSend` while holding `key`. The claim is taken before funding so a second attempt
   * sees the key as taken immediately, rather than after the first reaches the mempool.
   */
  def withKey[A](key: String)(buildAndSend: String => A): A = {
    require(!exclusions().contains(key), "lender key is already owned")
    val lease = java.util.UUID.randomUUID().toString
    try {
      require(Await.result((owner ? Acquire(key, lease)).mapTo[Boolean], timeout.duration),
        "lender key ownership changed")
      buildAndSend(lease)
    } finally owner ! Cancel(key, lease)
  }

  /** Recheck the key against a fresh mempool, pin it to this transaction, then broadcast. */
  def send(key: String, lease: String, tx: SignedTransaction, allocations: Seq[FundingAllocation],
           operation: String, alive: () => Boolean): EngineBroadcast.Result = {
    val observed = Await.result(
      (owner ? CompleteMempool.Refresh).mapTo[CompleteMempool.Observation], timeout.duration)
    require(observed.fresh && !observed.snapshot.get.lenderKeys.contains(key) &&
      CompleteMempool.anchor(api) == observed.snapshot.get.anchor,
      "lender key eligibility changed before send")
    require(allocations.nonEmpty &&
      Await.result((owner ? Pin(key, lease, tx.getId, allocations.map(_.reservationId).toSet))
        .mapTo[Boolean], timeout.duration), "lender key pin was refused")
    new EngineBroadcast(owner, api).send(tx, allocations, operation, alive)
  }
}
