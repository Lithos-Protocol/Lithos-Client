package node.model

case class NodeBox(boxId: String,
                   transactionId: String,
                   value: Long,
                   index: Int,
                   creationHeight: Int,
                   ergoTree: String,
                   assets: Seq[NodeAsset] = Seq.empty[NodeAsset],
                   additionalRegisters: NodeRegisters = NodeRegisters.empty)

case class IndexedBox(box: NodeBox,
                      address: String,
                      inclusionHeight: Int,
                      globalIndex: Long,
                      spentTransactionId: Option[String] = None,
                      spendingHeight: Option[Int] = None,
                      spendingProof: Option[NodeSpendingProof] = None) {

  def boxId: String = box.boxId

  def value: Long = box.value

  def ergoTree: String = box.ergoTree

  def assets: Seq[NodeAsset] = box.assets

  def isSpent: Boolean = spentTransactionId.isDefined
}

case class SerializedNodeBox(boxId: String, bytes: String)

case class BoxesBinaryProof(boxes: Seq[SerializedNodeBox], proof: String, digest: String)
