package transactions.candidate

import mutations.NodeWallet
import work.lithos.mutations.Contract

/**
 * Candidate-created outputs for one block height, which a final holding top-up may aggregate.
 *
 * Rebuilt per height and never persisted. A package that does not reach a block leaves nothing to
 * carry forward, and value that survives a reorg is found by ordinary wallet observation rather
 * than credited to some later height's candidate.
 */
final case class CandidateCapital(height: Int, entries: Vector[CapitalEntry] = Vector.empty) {

  /** Records a new output. The same output id cannot be credited twice. */
  def credit(entry: CapitalEntry): CandidateCapital = {
    require(!entries.exists(_.outputId == entry.outputId),
      s"candidate output ${entry.outputId} is already in the ledger")
    require(entry.value >= 0L, "a candidate output cannot hold negative value")
    copy(entries = entries :+ entry)
  }

  /** ERG a final holding top-up could still aggregate, before its fee and token residue. */
  def availableErg: Long = entries.map(_.value).sum
}

object CandidateCapital {

  /**
   * The script an intermediate candidate output carries until a later member spends it.
   *
   * `TrueProp` costs no proof bytes and no signing. Anyone could spend such an output, but only
   * once its parent is back on the chain, and a reorg does not put it there: the node keeps the
   * returned transaction to itself and it pays no fee. The miner's own P2PK is still the default,
   * so an intermediate stays recoverable whatever puts its parent back.
   */
  def collectionContract(wallet: NodeWallet, useTrueProp: Boolean): Contract =
    if (useTrueProp) Contract.SIGMA_TRUE else wallet.contract
}
