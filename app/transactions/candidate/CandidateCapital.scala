package transactions.candidate

import mutations.NodeWallet
import transactions.rollups.RollupTransactions
import work.lithos.mutations.{Contract, Token}

/**
 * Candidate-created outputs for one block height, and which of them are still unspent.
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

  /**
   * Marks an output consumed by a later member. Spending one twice is a double spend the node
   * would refuse the whole candidate over, so it is refused here instead.
   */
  def spend(outputId: String, byTxId: String): CandidateCapital = {
    val idx = entries.indexWhere(_.outputId == outputId)
    require(idx >= 0, s"candidate output $outputId is not in the ledger")
    require(entries(idx).spentBy.isEmpty,
      s"candidate output $outputId was already spent by ${entries(idx).spentBy.get}")
    copy(entries = entries.updated(idx, entries(idx).copy(spentBy = Some(byTxId))))
  }

  def unspent: Vector[CapitalEntry] = entries.filter(_.spentBy.isEmpty)

  /** ERG a final holding top-up could still aggregate, before its fee and token residue. */
  def availableErg: Long = unspent.map(_.value).sum

  /** Tokens left on unspent outputs, which cannot enter Holding and go to the miner's wallet. */
  def residualTokens: Seq[Token] =
    RollupTransactions.mergeTokens(unspent.flatMap(_.tokens))
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
