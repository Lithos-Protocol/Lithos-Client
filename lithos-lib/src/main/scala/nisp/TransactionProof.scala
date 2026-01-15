package nisp

import lfsm.LFSMHelpers
import org.bouncycastle.util.encoders.Hex
import scorex.crypto.hash.{Blake2b256, Digest32}
import scorex.utils.Shorts

import java.nio.{ByteBuffer, ByteOrder}

/**
 * Proof of valid collateral transaction
 * @param tx Unsigned transaction bytes
 * @param collateralBox Bytes of collateral box which is first input of transaction
 */
case class TransactionProof(tx: Array[Byte], collateralBox: Array[Byte]) {
  def serialize: Array[Byte] = {
    Shorts.toByteArray(tx.length.toShort) ++ tx ++ collateralBox
  }

  override def toString: String = {
    s"TransactionProof(${Hex.toHexString(tx)},\n " + Hex.toHexString(collateralBox) + ")"
  }

  def txId: Digest32 = Blake2b256(tx)
  def txIdHex: String = Hex.toHexString(txId)

  def leafHash = Blake2b256(0.toByte +: txId)

  def size: Int = serialize.length
}
object TransactionProof {

  // Do not use on unvalidated tx proofs
  def deserialize(bytes: Array[Byte]): TransactionProof = {
    val txSize = ByteBuffer.wrap(bytes.slice(0, 2))
      .order(ByteOrder.BIG_ENDIAN)
      .asShortBuffer()
      .get()
    require(txSize > LFSMHelpers.TX_SIZE_MIN, s"Encoded txSize must be greater than ${LFSMHelpers.TX_SIZE_MIN} bytes")
    val tx = bytes.slice(2, 2 + txSize)
    val collateralBox = bytes.slice(2 + txSize, bytes.length)
    require(bytes.length >= LFSMHelpers.TX_PROOF_MIN, s"Encoded txProof must be greater than ${LFSMHelpers.TX_PROOF_MIN} bytes")
    TransactionProof(tx, collateralBox)
  }
}
