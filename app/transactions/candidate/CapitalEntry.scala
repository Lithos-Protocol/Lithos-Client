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
}

/**
 * One output an earlier member of a candidate package created.
 *
 * The box travels with the entry because it is not on the chain yet — the top-up has to spend it
 * directly rather than read it back. `parentTxId` names the member that created it, which is what
 * the package's revenue is credited to.
 *
 * Held for one block height by [[CandidateCapital]].
 */
final case class CapitalEntry(origin: CapitalOrigin,
                              box: InputUTXO,
                              parentTxId: String) {
  def outputId: String = box.id.toString
  def value: Long = box.value
  def tokens: Seq[Token] = box.tokens
}
