package nisp

import org.bouncycastle.util.encoders.Hex
import scorex.crypto.hash.{Blake2b256, Digest32}

case class TransactionProof(tx: Array[Byte], levels: Seq[Array[Byte]]) {
  def serialize: Array[Byte] = {
    tx ++ levels.flatten
  }

  override def toString: String = {
    s"TransactionProof(${Hex.toHexString(tx)},\n " + levels.map(Hex.toHexString).mkString(",\n ")
  }

  def txId: Digest32 = Blake2b256(tx)
  def txIdHex: String = Hex.toHexString(txId)

  def leafHash = Blake2b256(0.toByte +: txId)
}
object TransactionProof {
  final val LEVEL_SIZE  = 33
  // Do not use on unvalidated tx proofs
  def deserialize(bytes: Array[Byte], txSize: Short, numLevels: Byte): TransactionProof = {
    require(numLevels >= 1, "Number of levels for transaction proof must be greater than or equal to 1")
    val tx = bytes.slice(0, txSize)
    val levels = bytes.slice(txSize, txSize + (LEVEL_SIZE * numLevels)).grouped(LEVEL_SIZE).toSeq
    TransactionProof(tx, levels)
  }
}
