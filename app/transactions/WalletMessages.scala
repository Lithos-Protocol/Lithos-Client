package transactions

import work.lithos.mutations.{InputUTXO, Token}

object WalletMessages {

  // ─── public messages ──────────────────────────────────────────────────────

  /** Triggers a fresh fetch of up to WalletManager.MAX_WALLET_BOXES unspent wallet boxes. */
  case object RefreshBoxes

  /**
   * Request a set of UTXOs covering erg nanoERG and the specified tokens.
   * If trackUsed is true (default) the selected UTXOs are recorded in
   * usedInputs so they cannot be handed out again until ResetUsedInputs.
   */
  case class RetrieveInputs(erg: Long, tokens: Seq[Token], trackUsed: Boolean = true)

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

  /** Clears usedInputs, allowing previously-used UTXOs to be re-selected. */
  case object ResetUsedInputs

  // ─── exceptions ───────────────────────────────────────────────────────────

  class InsufficientWalletFundsException(msg: String) extends RuntimeException(msg)
}
