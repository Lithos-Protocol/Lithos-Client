package nisp

import org.bouncycastle.util.encoders.Hex

case class TransactionProof(leaf: Array[Byte], levels: Seq[Array[Byte]]) {
  def serialize: Array[Byte] = {
    leaf ++ levels.flatten
  }

  override def toString: String = {
    s"TransactionProof(${Hex.toHexString(leaf)},\n " + levels.map(Hex.toHexString).mkString(",\n ")
  }
}
object TransactionProof {
  final val LEAF_SIZE   = 32
  final val LEVEL_SIZE  = 33
  def deserialize(bytes: Array[Byte], numLevels: Int): TransactionProof = {
    require(numLevels >= 1, "Number of levels for transaction proof must be greater than or equal to 1")
    TransactionProof(bytes.slice(0, LEAF_SIZE), bytes.slice(LEAF_SIZE, LEVEL_SIZE * (1 + numLevels)).grouped(LEVEL_SIZE).toSeq)
  }
}
