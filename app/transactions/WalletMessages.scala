package transactions

import work.lithos.mutations.{InputUTXO, Token}

object WalletMessages {

  // ─── public messages ──────────────────────────────────────────────────────

  /** Triggers a fresh fetch of up to WalletManager.MAX_WALLET_BOXES unspent wallet boxes. */
  case object RefreshBoxes

  /**
   * Request a set of UTXOs covering erg nanoERG and the specified tokens.
   * If trackUsed is true (default) the selected UTXOs are reserved, so nothing else can be handed
   * them until they are seen spent, released, or the reservation ages out.
   */
  case class RetrieveInputs(erg: Long, tokens: Seq[Token], trackUsed: Boolean = true)

  /**
   * Retrieve singular input which covers value of the entire requested ERG amount
   * @param erg Amount to cover in one input
   * @param trackUsed Record input as used after reply
   */
  case class RetrieveCoveringInput(erg: Long, trackUsed: Boolean = true)

  /**
   * Hand change outputs from a just-built transaction back to the pool so the next transaction in a
   * chained run can spend them, instead of waiting out the refresh interval. Boxes outside the
   * prover's signable set are ignored.
   */
  case class ReturnInputs(inputs: Seq[InputUTXO])

  /**
   * Un-reserve inputs that were taken but never spent, because the build or the send failed.
   * Without this they stay reserved until their reservation ages out, which is far longer.
   */
  case class ReleaseInputs(inputs: Seq[InputUTXO])

  /** Reply sent back to the requester with the selected UTXOs. */
  case class WalletInputs(inputs: Seq[InputUTXO])

  /**
   * Sent to wallet manager to indicate Inputs to exclude from retrieval.
   *
   * @param inputsExcluded Inputs to exclude from retrieval
   * @param dueToUsage If these inputs are being excluded because they have already been used.
   *                   If this is true, the inputs will be added in usedInputs. If false,
   *                   they will replace the current externalExclusions.
   */
  case class ExcludeInputs(inputsExcluded: Seq[InputUTXO], dueToUsage: Boolean = false)

  /**
   * Sweep tick: release reservations that have gone stale, meaning the box was never seen spent and
   * nobody released it. Never clears the set wholesale — a reservation that is merely young is a box
   * sitting in an unconfirmed transaction, and handing it out again is a double spend.
   */
  case object ResetUsedInputs

  // ─── exceptions ───────────────────────────────────────────────────────────

  class InsufficientWalletFundsException(msg: String) extends RuntimeException(msg)
}
