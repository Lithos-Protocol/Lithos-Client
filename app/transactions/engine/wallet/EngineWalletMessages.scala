package transactions.engine.wallet
import transactions.engine.wallet.EngineWalletState

import work.lithos.mutations.{InputUTXO, Token}

object EngineWalletMessages {

  /** Wraps a selection so it uses the reserved worker and input budget kept for time-sensitive work. */
  private[transactions] case class CriticalWalletRequest(request: Any)

  /**
   * Ownership of one send, bound to the exact transaction it funds.
   *
   * @param operation      intent key, so a rebuild can find its own previous attempt
   * @param signedInputIds every input of the signed transaction, protocol boxes included
   * @param walletInputIds the subset this reservation supplied
   * @param sendFinished   the node call returned or failed; until then nothing may resolve the hold
   */
  private[transactions] final case class EngineHold(operation: String, reservationId: String,
    txId: String, signedInputIds: Set[String], walletInputIds: Set[String],
    sendFinished: Boolean = false, accepted: Boolean = false)

  /** Bind a reservation to a signed transaction. `previousTxId` names the attempt being replaced. */
  private[transactions] case class PinEngineInputs(hold: EngineHold, previousTxId: Option[String] = None)

  /** Undo a pin that never reached the node, restoring `previous` when this was a rebuild. */
  private[transactions] case class CancelEngineInputs(reservationId: String, txId: String,
                                                     previous: Option[EngineHold] = None)

  private[transactions] case class EngineSendFinished(reservationId: String, txId: String, accepted: Boolean)
  private[transactions] case object GetEngineHolds
  private[transactions] case object GetOwnedInputIds
  private[transactions] case class EngineHolds(holds: Vector[EngineHold])

  /** Retire inputs proven consumed and free those proven to have survived. Never guesses. */
  private[transactions] case class ResolveEngineInputs(reservationId: String, txId: String,
    spent: Set[String], free: Set[String])

  // ─── public messages ──────────────────────────────────────────────────────

  /** Triggers a paged, exhaustive fetch of unspent wallet boxes. */
  case object RefreshBoxes

  /**
   * Request UTXOs covering `erg` nanoERG and `tokens`.
   *
   * @param trackUsed      reserve what is selected, so nothing else can be handed the same boxes
   *                       until they are seen spent, released, or the reservation ages out
   * @param single         one box must cover the whole request by itself, rather than a set
   * @param p2pkOnly       draw only from plain P2PK wallet boxes, excluding matured mining rewards
   * @param deadlineMillis past this the request is answered empty rather than queued further
   */
  private[transactions] case class SelectInputs(erg: Long,
                                          tokens: Seq[Token] = Seq.empty,
                                          trackUsed: Boolean = true,
                                          reservationId: String = java.util.UUID.randomUUID().toString,
                                          deadlineMillis: Long = Long.MaxValue,
                                          single: Boolean = false,
                                          p2pkOnly: Boolean = false)

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
  final case class RewardClaimChunk(txId: String, boxes: Int, nanoErgs: Long, outcome: String = "accepted")

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
