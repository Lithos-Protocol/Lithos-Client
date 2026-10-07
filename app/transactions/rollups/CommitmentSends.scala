package transactions.rollups

import node.NodeApi
import state.messages.SyncView

import java.util.concurrent.atomic.AtomicReference
import scala.util.{Failure, Success}

/**
 * The registration or commitment change this client sent last, until it confirms and this client has
 * synced past it. Until then the dictionary and the data box read as they did before it, so the
 * auto-commit loop and the API both check here before sending another.
 */
class CommitmentSends {
  private val last = new AtomicReference[Option[CommitmentSends.Sent]](None)

  def record(sent: CommitmentSends.Sent): Unit = last.set(Some(sent))

  /**
   * The last send and what it still waits for, or None once it confirmed and was synced, or left the
   * mempool without confirming. A node that cannot answer keeps it outstanding.
   */
  def outstanding(nodeApi: NodeApi, view: SyncView): Option[CommitmentSends.Outstanding] = {
    val current = last.get()
    current.flatMap { sent =>
      val status = for {
        pending <- nodeApi.unconfirmedTransactionById(sent.txId).map(_.isDefined)
        included <- if (pending) Success(None) else nodeApi.indexedTransactionById(sent.txId).map(_.map(_.inclusionHeight))
      } yield (pending, included)
      status match {
        case Success((true, _)) => Some(CommitmentSends.Outstanding(sent, None, s"transaction ${sent.txId} to confirm"))
        case Success((false, included)) if CommitmentSends.awaitingSync(included, view.cursor.map(_.height)) =>
          Some(CommitmentSends.Outstanding(sent, included,
            s"synchronization to reach height ${included.get}, where ${sent.txId} confirmed"))
        case Success(_) =>
          last.compareAndSet(current, None)
          None
        case Failure(ex) =>
          Some(CommitmentSends.Outstanding(sent, None, s"the node to report on transaction ${sent.txId}: ${ex.getMessage}"))
      }
    }
  }
}

object CommitmentSends {
  /** One per process, so a send from the engine is seen by every reader. */
  val Shared: CommitmentSends = new CommitmentSends

  final val Registration = "registration"
  final val Change = "change"

  /** A sent transaction, the kind it was, and the commitment it declares. */
  final case class Sent(txId: String, kind: String, score: Long, declaredHeight: Int)

  /** A send still holding the next one back: confirmed at `includedAt` if known, waiting on `reason`. */
  final case class Outstanding(sent: Sent, includedAt: Option[Int], reason: String)

  /**
   * Whether a transaction confirmed at `includedAt` is still ahead of this client's synced height, so
   * its effect is not yet visible locally. A transaction the node never confirmed holds nothing back.
   */
  def awaitingSync(includedAt: Option[Int], syncedTo: Option[Int]): Boolean =
    includedAt.exists(height => syncedTo.forall(_ < height))
}
