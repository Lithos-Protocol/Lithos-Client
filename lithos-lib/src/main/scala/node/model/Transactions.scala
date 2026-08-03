package node.model

case class NodeSpendingProof(proofBytes: String, extension: Map[String, String])

object NodeSpendingProof {
  val empty: NodeSpendingProof = NodeSpendingProof("", Map.empty)
}

case class NodeInput(boxId: String, spendingProof: NodeSpendingProof)

case class NodeDataInput(boxId: String)

case class NodeTransaction(id: String,
                           inputs: Seq[NodeInput],
                           dataInputs: Seq[NodeDataInput],
                           outputs: Seq[NodeBox],
                           size: Option[Int] = None)

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
