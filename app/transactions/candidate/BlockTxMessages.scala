package transactions.candidate

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
   *
   * `inputIds` is carried rather than decoded during selection: the builder already holds the signed
   * transaction, and admission has to reject two sources spending the same box before the package
   * reaches the node, which would otherwise refuse the whole candidate.
   *
   * `sizeBytes` and `cost` are measured where the builder holds the signed transaction. An
   * unconfirmed ancestor arrives as a node body instead: it reports the serialized size the node
   * charges to the block, but no execution cost, so its cost reads as zero. The package deliberately
   * claims only part of the block so that unknown stays covered.
   */
  case class CandidateTx(id: String, json: String, kind: String, inputIds: Set[String] = Set.empty,
                        sizeBytes: Int = 0, cost: Long = 0L, leaf: String = "")

  /**
   * How a bundle means to interact with what is already unconfirmed. These describe interactions,
   * not kinds: a bundle may declare several or none, and one built only from confirmed inputs
   * declares nothing.
   *
   * A declaration is a request, never authority. Admission derives conflicts and dependency order
   * from the actual inputs regardless of what is claimed here.
   */
  sealed trait MempoolInteraction

  /** Include an existing unconfirmed transaction, and its unique ancestor closure, unchanged. */
  final case class IncludeExisting(txId: String) extends MempoolInteraction

  /**
   * Spend an output of `parentTxId`, which is still unconfirmed. The parent and every ancestor it
   * needs must appear in the same bundle, before the child: a block carrying the child alone is
   * invalid.
   */
  final case class ChainFromMempool(parentTxId: String) extends MempoolInteraction

  /**
   * Replace competing unconfirmed spends. Their descendants become invalid with them, and their
   * displaced fees are the package's to account for.
   */
  final case class Supersede(conflicting: Set[String]) extends MempoolInteraction

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
   * Mining → a transaction source: start building for `blockHeight` now, and hold the result.
   *
   * Sent as soon as the height is known, alongside the genesis build rather than after it, so the
   * signing and node reads a source needs are already done when the package is asked for. Without
   * it every source builds inside the collection deadline and a slow one costs the block its extras.
   *
   * Preparing is not a promise that the work will be asked for: a height that never publishes
   * genesis is told to drop it, exactly as a rejected one is.
   */
  case class PrepareBlockTxs(blockHeight: Int, limit: Int)

  /**
   * Mining → a transaction source: what have you got for the block at `blockHeight`? `limit` is a
   * hard ceiling, kept small because the rest of the block should carry fee-paying transactions.
   *
   * Answered from what [[PrepareBlockTxs]] already built where possible; a source that was not asked
   * to prepare, or has not finished, builds here instead.
   */
  case class RequestBlockTxs(blockHeight: Int, limit: Int)

  /**
   * A source's answer, in the order the node must apply it — any unconfirmed ancestor before
   * whatever chains off it. An empty sequence is a normal answer.
   */
  case class BlockTxsReady(blockHeight: Int, bundles: Seq[CandidateBundle])

  /**
   * Mining → every transaction source: nothing offered for `blockHeight` can land any more, because
   * the node refused the package or the chain moved past it.
   *
   * A source that reserved a wallet box for one of those transactions has no other way to learn
   * this. The transaction was never broadcast, so no send outcome resolves the box, and the block
   * may still have taken it — which is why this is a signal to reconcile rather than to release.
   */
  case class CandidateTxsDropped(blockHeight: Int)
}
