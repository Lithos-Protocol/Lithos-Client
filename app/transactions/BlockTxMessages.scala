package transactions

/**
 * The protocol between the mining path and the transaction actors for filling this miner's own block.
 * Kept apart from [[transactions.rollups.TransactionMessages]] and the emission messages because
 * several unrelated
 * sources answer the same request and none should know about the others' stub types.
 *
 * Everything the client builds is broadcast to the mempool as normal fee-paying transactions, since
 * a single miner rarely finds the block their own transaction needs. When this miner *is* building a
 * candidate it also inserts fee-less copies, which spend the same boxes so the node drops the
 * mempool versions while packing: same work, same beneficiary, one of them lands. Only the pool's
 * own transactions are displaced this way; other people's are chained onto instead.
 */
object BlockTxMessages {

  /**
   * A signed transaction offered for inclusion in this miner's own block. `json` is what goes to
   * `/mining/candidateWithTxsAndPk`; `kind` is for logging, so a rejected candidate names which
   * builder produced the offending transaction.
   */
  case class CandidateTx(id: String, json: String, kind: String)

  object CandidateTx {
    final val NispSubmission = "nisp-submission"
    final val FraudProof = "fraud-proof"
    final val HoldingTransform = "holding-transform"
    final val EvalTransform = "eval-transform"
    final val Payout = "payout"
    final val Activate = "activate"
    final val Clear = "clear"
    final val MempoolAncestor = "mempool-ancestor"
  }

  /**
   * Mining → a transaction source: what have you got for the block at `blockHeight`? `limit` is a
   * hard ceiling, kept small because the rest of the block should carry fee-paying transactions.
   */
  case class RequestBlockTxs(blockHeight: Int, limit: Int)

  /**
   * A source's answer, in the order the node must apply it — any unconfirmed ancestor before
   * whatever chains off it. An empty sequence is a normal answer.
   */
  case class BlockTxsReady(blockHeight: Int, txs: Seq[CandidateTx])
}
