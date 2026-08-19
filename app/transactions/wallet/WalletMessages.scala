package transactions.wallet

import work.lithos.mutations.{InputUTXO, Token}

object WalletMessages {

  // ─── public messages ──────────────────────────────────────────────────────

  /** Triggers a paged, exhaustive fetch of unspent wallet boxes. */
  case object RefreshBoxes

  /**
   * Request a set of UTXOs covering erg nanoERG and the specified tokens.
   * If trackUsed is true (default) the selected UTXOs are reserved, so nothing else can be handed
   * them until they are seen spent, released, or the reservation ages out.
   */
  private[transactions] case class RetrieveInputs(erg: Long,
                                            tokens: Seq[Token],
                                            trackUsed: Boolean = true,
                                            reservationId: String = java.util.UUID.randomUUID().toString,
                                            deadlineMillis: Long = Long.MaxValue)

  /**
   * Retrieve singular input which covers value of the entire requested ERG amount
   * @param erg Amount to cover in one input
   * @param trackUsed Record input as used after reply
   */
  private[transactions] case class RetrieveCoveringInput(erg: Long,
                                                   trackUsed: Boolean = true,
                                                   reservationId: String = java.util.UUID.randomUUID().toString,
                                                   deadlineMillis: Long = Long.MaxValue)

  /**
   * Reserve exact signable outputs whose parent transaction has been built but may not be visible to
   * the node yet. This keeps intermediate change owned by its transaction chain across wallet
   * refreshes; unlike an ordinary selection, absence from a complete refresh is expected.
   */
  private[transactions] case class ReserveKnownInputs(inputs: Seq[InputUTXO],
                                                       reservationId: String,
                                                       deadlineMillis: Long)

  /**
   * Hand change outputs from a just-built transaction back to the pool so the next transaction in a
   * chained run can spend them, instead of waiting out the refresh interval. Boxes outside the
   * prover's signable set are ignored.
   */
  private[transactions] case class ReturnInputs(inputs: Seq[InputUTXO])

  /**
   * Un-reserve a selection known never to have crossed the external-send boundary, such as a local
   * build/sign failure. A send failure is ambiguous and must use MarkReservationUncertain instead.
   */
  private[transactions] case class ReleaseInputs(reservationId: String)

  /** Node acceptance is known: retire the reserved parents and publish only real signable outputs. */
  private[transactions] case class CommitReservation(reservationId: String,
                                               outputs: Seq[InputUTXO] = Seq.empty)

  /**
   * Request permission to cross the external-send boundary. The manager must acknowledge this
   * transition before the caller contacts the node; a lease which expired or was replaced cannot
   * be used to send an old transaction.
   */
  private[transactions] case class BeginReservationSubmission(reservationId: String,
                                                        expectedInputIds: Set[String] = Set.empty,
                                                        deadlineMillis: Long = Long.MaxValue)

  /** Identity-bound acknowledgement for BeginReservationSubmission. */
  private[transactions] case class ReservationSubmissionStarted(reservationId: String, accepted: Boolean)

  /**
   * Cancel an exact lease before any node call was made. This is the only transition which may
   * retire an acknowledged Submitting lease without treating the send outcome as ambiguous.
   */
  private[transactions] case class CancelReservationSubmission(reservationId: String,
                                                                expectedInputIds: Set[String])

  /** Identity-bound acknowledgement for CancelReservationSubmission. */
  private[transactions] case class ReservationSubmissionCancelled(reservationId: String,
                                                                    accepted: Boolean)

  /** Send outcome is ambiguous; never age this reservation out without a complete node refresh. */
  private[transactions] case class MarkReservationUncertain(reservationId: String)

  /** Reply sent back to the requester with the selected UTXOs and matching lease identity. */
  private[transactions] case class WalletInputs(inputs: Seq[InputUTXO], reservationId: String = "")

  /**
   * Sweep tick: release reservations that have gone stale, meaning the box was never seen spent and
   * nobody released it. Never clears the set wholesale — a reservation that is merely young is a box
   * sitting in an unconfirmed transaction, and handing it out again is a double spend.
   */
  case object ResetUsedInputs

  // ─── exceptions ───────────────────────────────────────────────────────────

  class InsufficientWalletFundsException(msg: String) extends RuntimeException(msg)
}
