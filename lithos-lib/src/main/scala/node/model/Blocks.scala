package node.model

case class NodePowSolution(pk: String, w: String, n: String, d: String)

case class NodeHeader(id: String,
                      parentId: String,
                      height: Int,
                      timestamp: Long,
                      version: Int,
                      nBits: Long,
                      difficulty: BigInt,
                      adProofsRoot: String,
                      stateRoot: String,
                      transactionsRoot: String,
                      extensionHash: String,
                      powSolutions: NodePowSolution,
                      votes: String,
                      size: Option[Int] = None,
                      extensionId: Option[String] = None,
                      transactionsId: Option[String] = None,
                      adProofsId: Option[String] = None)

case class NodeExtensionField(key: String, value: String)

case class NodeExtension(headerId: String, digest: String, fields: Seq[NodeExtensionField])

case class NodeAdProofs(headerId: String, proofBytes: String, digest: String, size: Int)

case class NodeBlockTransactions(headerId: String, transactions: Seq[NodeTransaction], size: Int)

case class NodeBlock(header: NodeHeader,
                     blockTransactions: NodeBlockTransactions,
                     extension: NodeExtension,
                     adProofs: Option[NodeAdProofs],
                     size: Int) {

  def id: String = header.id

  def height: Int = header.height

  def transactions: Seq[NodeTransaction] = blockTransactions.transactions
}

case class IndexedBlock(header: NodeHeader,
                        blockTransactions: Seq[IndexedTransaction],
                        extension: NodeExtension,
                        adProofs: Option[NodeAdProofs],
                        size: Int)

case class MerkleLevel(digest: String, side: Int) {
  def encoded: String = f"${side & 0xff}%02x" + digest
}

object MerkleLevel {
  def fromEncoded(hex: String): MerkleLevel =
    if (hex.length < 2) MerkleLevel(hex, 0)
    else MerkleLevel(hex.substring(2), Integer.parseInt(hex.substring(0, 2), 16))
}

case class NodeMerkleProof(leaf: String, levels: Seq[MerkleLevel])

case class PopowHeader(header: NodeHeader, interlinks: Seq[String])

case class NipopowProof(m: Int,
                        k: Int,
                        prefix: Seq[PopowHeader],
                        suffixHead: PopowHeader,
                        suffixTail: Seq[NodeHeader])

case class ChainSlice(headers: Seq[NodeHeader])
