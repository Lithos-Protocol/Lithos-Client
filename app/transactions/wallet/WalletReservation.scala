package transactions.wallet

import work.lithos.mutations.InputUTXO

import java.util.concurrent.atomic.AtomicInteger
import scala.util.control.NonFatal

/**
 * One uniquely-owned wallet reservation.
 *
 * Releasing by reservation id prevents an old caller from clearing a newer reservation of the same
 * box after a TTL/reselection cycle. Accepted and ambiguous sends are different terminal messages:
 * accepted parents are atomically replaced with their real outputs, while ambiguous parents remain
 * withheld until a complete mempool-aware refresh resolves them.
 */
final class WalletReservation private[wallet](val id: String,
                                              val inputs: Seq[InputUTXO],
                                              selector: WalletSelector) {
  // 0 selected, 1 submitting, 2 uncertain, 3 resolved, 4 candidate-held.
  private val state = new AtomicInteger(0)

  def release(): Unit =
    if (state.compareAndSet(0, 3)) selector.releaseReservation(id)

  /**
   * Withhold this lease for a transaction handed to a block candidate rather than to the node.
   *
   * Taken before the signed transaction leaves this actor, because from that moment the input may
   * end up spent by a block and must not be selectable again. Nothing releases it: the only exit is
   * [[uncertain]] once the height is over, followed by an authoritative refresh.
   */
  def holdForCandidate(): Unit = {
    if (!state.compareAndSet(0, 4))
      throw new ReservationExpiredException(s"Wallet reservation $id cannot be held for a candidate")
    if (!selector.holdReservationForCandidate(id)) {
      state.set(3)
      throw new ReservationExpiredException(
        s"Wallet reservation $id expired before it could be held for a candidate")
    }
  }

  /**
   * Acquire an identity-bound send permit before calling the node.
   *
   * This is deliberately acknowledged rather than fire-and-forget. If the lease expired or was
   * replaced while a transaction was being built, the old caller must stop before broadcasting.
   * A lost acknowledgement still means no node call was made. The exact lease is therefore
   * cancelled through the manager rather than left Uncertain indefinitely, and the caller receives
   * the failure instead of sending without a live lease.
   */
  def beginSubmission(): Unit = {
    if (!state.compareAndSet(0, 1))
      throw new ReservationExpiredException(s"Wallet reservation $id is not available for submission")
    try {
      if (!selector.beginReservationSubmission(id, inputIds))
        throw new ReservationExpiredException(s"Wallet reservation $id expired before submission")
    } catch {
      case NonFatal(ex) =>
        if (state.compareAndSet(1, 3)) {
          try cancelManagerLease()
          catch { case NonFatal(cancelEx) => ex.addSuppressed(cancelEx) }
        }
        throw ex
    }
  }

  /**
   * Retire an acknowledged send permit when the caller can prove it has not contacted the node.
   * This is used when a later lease in the same transaction cannot acquire its own permit.
   */
  def cancelSubmission(): Unit = {
    if (!state.compareAndSet(1, 3))
      throw new ReservationExpiredException(
        s"Wallet reservation $id is not awaiting a local submission")
    cancelManagerLease()
  }

  def commit(outputs: Seq[InputUTXO] = Seq.empty): Unit = {
    val previous = state.getAndSet(3)
    if (previous == 1 || previous == 2) selector.commitReservation(id, outputs)
    else if (previous != 3)
      throw new ReservationExpiredException(
        s"Wallet reservation $id was committed before submission")
  }

  def uncertain(): Unit = {
    val transitioned =
      state.compareAndSet(1, 2) || state.compareAndSet(0, 2) || state.compareAndSet(4, 2)
    if (transitioned) selector.markReservationUncertain(id)
  }

  private def inputIds: Set[String] = inputs.map(_.id.toString).toSet

  private def cancelManagerLease(): Unit =
    if (!selector.cancelReservationSubmission(id, inputIds))
      throw new ReservationExpiredException(
        s"WalletManager could not cancel reservation $id before submission")
}

/**
 * A lease that is no longer usable: expired, replaced, or already past the state being asked for.
 *
 * Its own type because it is a TIMING condition, not a defect. It happens when a build took longer
 * than the lease's deadline, or when a refresh replaced the lease underneath a caller — both of
 * which a caller resolves by re-reading and trying again. Raised as a bare `IllegalStateException`
 * it reached the API's catch-all and was reported as a 500, which tells a caller to stop.
 */
class ReservationExpiredException(msg: String) extends RuntimeException(msg)

object WalletReservation {

  /**
   * Acquire every lease needed by one transaction as a group. If any acquisition fails, leases
   * already acknowledged are manager-cancelled and the untouched suffix is released; no node call
   * has occurred at this point.
   */
  /** Widened for the collateral-market API, which joins the queue from outside the package. */
  def beginAll(reservations: Seq[WalletReservation]): Unit = {
    var begun = Vector.empty[WalletReservation]
    var index = 0
    try {
      while (index < reservations.size) {
        val reservation = reservations(index)
        reservation.beginSubmission()
        begun :+= reservation
        index += 1
      }
    } catch {
      case NonFatal(ex) =>
        begun.reverse.foreach { reservation =>
          try reservation.cancelSubmission()
          catch { case NonFatal(cleanupEx) => ex.addSuppressed(cleanupEx) }
        }
        // `index` is the reservation whose begin failed. It has already cancelled itself when it
        // crossed into the local Submitting state; release is a no-op there and clears only the
        // untouched Selected suffix.
        reservations.drop(index).foreach { reservation =>
          try reservation.release()
          catch { case NonFatal(cleanupEx) => ex.addSuppressed(cleanupEx) }
        }
        throw ex
    }
  }
}
