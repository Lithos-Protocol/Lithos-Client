package transactions.engine

import work.lithos.mutations.InputUTXO
import java.util.concurrent.atomic.AtomicInteger
import scala.util.control.NonFatal

/** Worker-local hydration for an engine-owned allocation; broadcasts use compact exact-input holds. */
final class FundingAllocation private[engine](val id: String, val inputs: Seq[InputUTXO], funding: EngineFunding) {
  // Selected, resolved, candidate-held, and awaiting candidate reconciliation.
  private val state = new AtomicInteger(0)

  def release(): Unit =
    if (state.compareAndSet(0, 1)) funding.releaseReservation(id)

  def holdForCandidate(): Unit = {
    if (!state.compareAndSet(0, 2))
      throw new FundingExpiredException(s"Funding allocation $id cannot enter a candidate")
    val held = try funding.holdReservationForCandidate(id)
    catch {
      case NonFatal(ex) =>
        uncertain()
        throw ex
    }
    if (!held) {
      state.set(1)
      throw new FundingExpiredException(s"Funding allocation $id expired before candidate publication")
    }
  }

  def uncertain(): Unit =
    if (state.compareAndSet(2, 3) || state.compareAndSet(0, 3)) funding.markReservationUncertain(id)
}

class FundingExpiredException(message: String) extends RuntimeException(message)
