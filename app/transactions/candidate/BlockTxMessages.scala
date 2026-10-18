package transactions.candidate

/** Messages shared by the mining path and transaction sources when preparing candidate bundles. */
object BlockTxMessages {

  /**
   * Signed transaction with input IDs, serialized bytes and execution cost. A carried ancestor's
   * cost is 0 when the node did not report one. `leaf` is the digest a block's transaction tree
   * carries for this transaction, which an inclusion proof is matched against.
   */
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
    final val Genesis = "genesis"
    final val NispSubmission = "nisp-submission"
    final val FraudProof = "fraud-proof"
    final val HoldingTransform = "holding-transform"
    final val EvalTransform = "eval-transform"
    final val Payout = "payout"
    final val Activate = "activate"
    final val Clear = "clear"
    final val MempoolAncestor = "mempool-ancestor"
    /** Prefix of an upkeep successor's kind; the job's name follows, as `upkeep:<job>`. */
    final val Upkeep = "upkeep"

    /**
     * An unconfirmed transaction carried ahead of a member that spends its outputs. Sized at the
     * serialized size the node reports, falling back to the encoded length, and at the cost the node
     * measured on mempool admission, or 0 when it reported none.
     */
    def ancestor(body: _root_.node.model.NodeTransaction): CandidateTx = {
      val encoded = _root_.node.rest.NodeCodecs.encodeTransaction(body).toString
      CandidateTx(body.id, encoded, MempoolAncestor, body.inputs.map(_.boxId).toSet,
        body.size.getOrElse(encoded.length), body.cost.getOrElse(0L), body.id)
    }

    /**
     * The most context variables on one input that the node rebuilds in the order the JSON lists
     * them. Its decoder builds a Scala map, which keeps insertion order up to four entries and hashes
     * past that, landing on the order this client's own map signed with.
     */
    private final val JsonOrderedVariables = 4

    /**
     * The JSON a candidate carries for a transaction this client signed.
     *
     * The id and every signature cover each input's context variables in signed order. An input with
     * two to four of them is rebuilt in the order this JSON lists, so JSON listing another order is a
     * different transaction to the node and is refused here, loudly.
     */
    def signedJson(tx: org.ergoplatform.appkit.SignedTransaction): String = {
      import scala.collection.JavaConverters._
      val json = tx.toJson(false, false)
      val signed = tx.asInstanceOf[org.ergoplatform.appkit.impl.SignedTransactionImpl].getTx.inputs
        .map(_.spendingProof.extension.values.keys.map(_.toString).toVector).toVector
      val ordered = signed.map(keys => keys.size >= 2 && keys.size <= JsonOrderedVariables)
      // Most transactions have no input whose order the node takes from the JSON, so skip the parse
      if (!ordered.contains(true)) return json
      val inputs = new com.google.gson.JsonParser().parse(json).getAsJsonObject.getAsJsonArray("inputs")
      val listed = (0 until inputs.size).map { i =>
        Option(inputs.get(i).getAsJsonObject.getAsJsonObject("spendingProof"))
          .flatMap(proof => Option(proof.getAsJsonObject("extension")))
          .map(_.keySet().asScala.toVector).getOrElse(Vector.empty[String])
      }.toVector
      require(listed.size == signed.size && signed.indices.forall(i => !ordered(i) || signed(i) == listed(i)),
        s"transaction ${tx.getId} was signed with context variables " +
        s"${signed.map(_.mkString("(", ",", ")")).mkString(" ")} but its JSON lists " +
        s"${listed.map(_.mkString("(", ",", ")")).mkString(" ")}")
      json
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
