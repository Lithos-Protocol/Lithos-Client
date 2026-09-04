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
   * The same single-box request, restricted to plain P2PK wallet boxes.
   */
  private[transactions] case class RetrieveCoveringP2PKInput(erg: Long,
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

  /**
   * Withhold a selection for a transaction offered into this miner's own block candidate.
   *
   * Such a transaction is never broadcast, so no send outcome and no TTL can resolve it. The hold
   * lasts until the height it was built for is over, at which point the caller marks it uncertain
   * and an authoritative refresh decides whether the block took the input.
   */
  private[transactions] case class HoldReservationForCandidate(reservationId: String)

  /** Identity-bound acknowledgement for HoldReservationForCandidate. */
  private[transactions] case class ReservationHeldForCandidate(reservationId: String, accepted: Boolean)

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

  // ─── reward-box sweep ─────────────────────────────────────────────────────

  /** Ask how many coinbase reward boxes this wallet holds, locked and unlocked. */
  case object GetUnlockedRewards

  /** Reply to [[GetUnlockedRewards]]. Amounts are nanoERG as of the last refresh's height. */
  final case class RewardSummary(lockedBoxes: Int,
                                 unlockedBoxes: Int,
                                 lockedNanoErgs: Long,
                                 unlockedNanoErgs: Long,
                                 blocksUntilFirstUnlock: Option[Int])

  /**
   * Ask how much ERG is selectable right now — unreserved wallet boxes plus matured reward boxes.
   * A dry-run figure: nothing is reserved by asking.
   */
  case object GetSpendableBalance

  /** Reply to [[GetSpendableBalance]]. */
  final case class SpendableBalance(nanoErgs: Long)

  /**
   * Sweep every unlocked coinbase box to the wallet's primary address (EIP-3 index 0). Runs off the
   * actor in batches; each accepted batch retires its inputs exactly like an external send.
   */
  case object ClaimUnlockedRewards

  /** One broadcast batch of a sweep. */
  final case class RewardClaimChunk(txId: String, boxes: Int, nanoErgs: Long)

  /**
   * Reply to [[ClaimUnlockedRewards]] on success. Boxes are never dropped for being small — the
   * sweep exists to consolidate them — so an empty result means another sweep held the leases.
   */
  final case class RewardsClaimed(chunks: Seq[RewardClaimChunk])

  /** Reply to [[ClaimUnlockedRewards]] when the sweep could not run at all. */
  final case class RewardClaimFailed(reason: String)

  // ─── exceptions ───────────────────────────────────────────────────────────

  class InsufficientWalletFundsException(msg: String) extends RuntimeException(msg)
}
