package node.model

case class NodeSpendingProof(proofBytes: String, extension: Map[String, String])

object NodeSpendingProof {
  val empty: NodeSpendingProof = NodeSpendingProof("", Map.empty)
}

case class NodeInput(boxId: String, spendingProof: NodeSpendingProof)

case class NodeDataInput(boxId: String)

/**
 * @param size serialized bytes, when the node reports them
 * @param cost validation cost the node measured when the transaction entered its mempool; absent when
 *             the node does not report it or never measured it
 */
case class NodeTransaction(id: String,
                           inputs: Seq[NodeInput],
                           dataInputs: Seq[NodeDataInput],
                           outputs: Seq[NodeBox],
                           size: Option[Int] = None,
                           cost: Option[Long] = None)

case class IndexedTransaction(id: String,
                              inputs: Seq[IndexedBox],
                              dataInputs: Seq[NodeDataInput],
                              outputs: Seq[IndexedBox],
                              inclusionHeight: Int,
                              numConfirmations: Int,
                              blockId: String,
                              timestamp: Long,
                              index: Int,
                              globalIndex: Long,
                              size: Int)

case class UnconfirmedTransaction(transaction: NodeTransaction,
                                  size: Int,
                                  spendingProofsSize: Option[Int] = None,
                                  createdAt: Option[Long] = None)

case class FeeHistogramBin(nTxns: Int, totalFee: Long)

case class FeeHistogram(bins: Seq[FeeHistogramBin])

case class PoolHistogram(waiting: FeeHistogram, mined: FeeHistogram)
