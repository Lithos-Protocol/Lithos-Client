package transactions.engine.wallet

import work.lithos.mutations.InputUTXO
import java.util.concurrent.atomic.AtomicReference
import scala.util.control.NonFatal

private[engine] object FundingAllocation {
  /** Where an allocation's wallet inputs stand, from the worker's point of view. */
  sealed trait State
  /** Reserved and still spendable by this build. */
  case object Selected extends State
  /** Handed back to the wallet; nothing was sent. */
  case object Released extends State
  /** Withheld for a transaction offered into this miner's own block candidate. */
  case object HeldForCandidate extends State
  /** Ownership retained until a fresh observation says whether the inputs were consumed. */
  case object Uncertain extends State
}

/**
 * Worker-local handle for one reserved set of wallet inputs. It carries the hydrated boxes for the
 * build; the engine's own ownership record stays compact and is keyed by [[reservationId]].
 */
final class FundingAllocation private[engine](val reservationId: String,
                                              val inputs: Seq[InputUTXO],
                                              funding: EngineFunding) {
  import FundingAllocation._

  private val state = new AtomicReference[State](Selected)

  /** Return the inputs to the wallet. Only safe when nothing crossed the send boundary. */
  def release(): Unit =
    if (state.compareAndSet(Selected, Released)) funding.releaseReservation(reservationId)

  /**
   * Withhold the inputs for a candidate transaction, which is never broadcast and so has no send
   * outcome to resolve it. A refused or failed hold leaves the allocation uncertain, not free.
   */
  def holdForCandidate(): Unit = {
    if (!state.compareAndSet(Selected, HeldForCandidate))
      throw new FundingExpiredException(s"Funding allocation $reservationId cannot enter a candidate")
    val held = try funding.holdReservationForCandidate(reservationId)
    catch {
      case NonFatal(ex) =>
        uncertain()
        throw ex
    }
    if (!held) {
      state.set(Released)
      throw new FundingExpiredException(s"Funding allocation $reservationId expired before candidate publication")
    }
  }

  /** Withhold the inputs until reconciliation, because the send outcome is unknown. */
  def uncertain(): Unit =
    if (state.compareAndSet(HeldForCandidate, Uncertain) || state.compareAndSet(Selected, Uncertain))
      funding.markReservationUncertain(reservationId)
}

class FundingExpiredException(message: String) extends RuntimeException(message)
