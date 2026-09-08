package transactions.candidate

import work.lithos.mutations.{InputUTXO, Token}

/**
 * Where the ERG on a candidate-created output came from.
 *
 * Only value a spendable output actually holds has an origin. Quoted profit, a fee no output
 * carries, funds already in the miner's wallet, and value already inside Holding have none.
 */
sealed trait CapitalOrigin

object CapitalOrigin {
  /** A batching or order-execution fee this client earned by executing someone else's order. */
  case object ExecutorReward extends CapitalOrigin

  /** Value legally removed from a box that is past its storage-rent deadline. */
  case object StorageRent extends CapitalOrigin

  /** Realized gain on a round trip that both opens and closes inside this block. */
  case object Arbitrage extends CapitalOrigin
}

/**
 * One output an earlier member of a candidate package created, and what became of it.
 *
 * The box travels with the entry because it is not on the chain yet — a later member has to spend
 * it directly rather than read it back. `spentBy` names the member that consumed it, so an entry
 * with none is still available to the final top-up; `parentTxId` names the member that created it,
 * which is what orders the two inside a bundle.
 *
 * Held for one block height by [[CandidateCapital]].
 */
final case class CapitalEntry(origin: CapitalOrigin,
                              box: InputUTXO,
                              parentTxId: String,
                              spentBy: Option[String] = None) {
  def outputId: String = box.id.toString
  def value: Long = box.value
  def tokens: Seq[Token] = box.tokens
}
