package transactions.wallet

import work.lithos.mutations.{InputUTXO, Token}

/** Test seam for the one production wallet-selection facade, [[WalletSelector]]. */
trait WalletSource {

  /** Reserve inputs covering the requested ERG and tokens, or throw when the wallet cannot cover it. */
  def reserve(value: Long, tokens: Seq[Token] = Seq.empty): WalletReservation

  /** Reserve one input that can cover the ERG request by itself. May be a matured mining reward. */
  def reserveCovering(value: Long): WalletReservation

  /**
   * The same, restricted to plain P2PK wallet boxes.
   */
  def reserveCoveringP2PK(value: Long): WalletReservation

  /**
   * Hold exact signable outputs produced inside a transaction chain, including before the node can
   * report them. The returned handle must travel with the later spend which consumes those boxes.
   */
  def reserveKnown(inputs: Seq[InputUTXO]): WalletReservation

  /** Make accepted transaction change available to a later chained transaction. */
  def giveBack(boxes: Seq[InputUTXO]): Unit
}
