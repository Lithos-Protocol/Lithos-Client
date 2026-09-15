package transactions.candidate

/** Messages shared by the mining path and transaction sources when preparing candidate bundles. */
object BlockTxMessages {

  /** Signed transaction with input IDs, serialized bytes and execution cost. A carried ancestor's cost is 0 when the node did not report one. */
  case class CandidateTx(id: String, json: String, kind: String, inputIds: Set[String] = Set.empty,
                        sizeBytes: Int = 0, cost: Long = 0L, leaf: String = "")

  /** Declares how a bundle relates to unconfirmed transactions; admission checks its actual inputs. */
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

    /**
     * An unconfirmed transaction carried ahead of a member that spends its outputs. Sized at the
     * serialized size the node reports, falling back to the encoded length, and at the cost the node
     * measured on mempool admission, or 0 when it reported none.
     */
    def ancestor(body: _root_.node.model.NodeTransaction): CandidateTx = {
      val encoded = _root_.node.rest.NodeCodecs.encodeTransaction(body).toString
      CandidateTx(body.id, encoded, MempoolAncestor, body.inputs.map(_.boxId).toSet,
        body.size.getOrElse(encoded.length), body.cost.getOrElse(0L))
    }
  }

  /** Starts source construction for a height and caches the result for collection. */
  case class PrepareBlockTxs(blockHeight: Int, limit: Int)

  /** Collects source offers. Refresh rebuilds mempool-dependent work while preserving height-held wallet funding. */
  case class RequestBlockTxs(blockHeight: Int, limit: Int, refresh: Boolean = false)

  /**
   * A source's answer, in the order the node must apply it — any unconfirmed ancestor before
   * whatever chains off it. An empty sequence is a normal answer.
   */
  case class BlockTxsReady(blockHeight: Int, bundles: Seq[CandidateBundle])

  /** Drops an obsolete height and reconciles any wallet reservations its candidate held. */
  case class CandidateTxsDropped(blockHeight: Int)
}
