package support

import node.model.{NodeAsset, NodeBox, NodeRegisters}
import node.rest.NodeCodecs
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.impl.InputBoxImpl
import org.ergoplatform.restapi.client.ErgoTransactionOutput

/** Node-shaped fixtures use the identity committed by their serialized box contents. */
object CanonicalNodeBox {
  def apply(boxId: String, transactionId: String, value: Long, index: Int, creationHeight: Int,
            ergoTree: String, assets: Seq[NodeAsset] = Seq.empty,
            additionalRegisters: NodeRegisters = NodeRegisters.empty): NodeBox = {
    val box = NodeBox(boxId, transactionId, value, index, creationHeight, ergoTree, assets, additionalRegisters)
    val wire = new com.google.gson.Gson().fromJson(NodeCodecs.encodeBox(box), classOf[ErgoTransactionOutput])
    box.copy(boxId = Hex.toHexString(new InputBoxImpl(wire).getErgoBox.id))
  }
}
