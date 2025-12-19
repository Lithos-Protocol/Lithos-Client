package nisp

import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.JavaHelpers
import org.ergoplatform.{AutolykosSolution, ErgoHeader, HeaderWithoutPow, HeaderWithoutPowSerializer}
import scorex.crypto.hash.Blake2b256
import scorex.utils.{Ints, Shorts}
import sigma.pow.Autolykos2PowValidation
import sigma.serialization.GroupElementSerializer
import stratum.data.MiningCandidate

import java.nio.{ByteBuffer, ByteOrder}

case class SuperShare(headerBytes: Array[Byte], txProof: TransactionProof) {
  lazy val nBytes: Array[Byte] = Ints.toByteArray(getN)

  def getN: Int = {
    Autolykos2PowValidation.calcN(getHeight)
  }
  def serialize: Array[Byte] = {
    nBytes ++ headerBytes ++ Shorts.toByteArray(txProof.tx.length.toShort) ++
      Array(txProof.levels.size.toByte) ++ txProof.serialize
  }

  lazy val size: Int = serialize.length

  override def toString: String = {
    s"SuperShare(${Hex.toHexString(nBytes)}, ${Hex.toHexString(headerBytes)},\n " +
      s"${Hex.toHexString(Shorts.toByteArray(txProof.tx.length.toShort))}, \n " +
      s"${txProof.levels.size.toByte.toHexString}, \n " +
      s"${txProof})"
  }

  def toHeader: ErgoHeader = {
    ErgoHeader.sigmaSerializer.fromBytes(headerBytes)
  }

  def getMerkleRoot: Array[Byte] = {
    headerBytes.slice(65, 97)
  }

  def id: Array[Byte] = toHeader.id.toArray

  def idHex: String = Hex.toHexString(id)

  def getHeight: Int =
    toHeader.height

  override def equals(obj: Any): Boolean = {
    obj match {
      case otr: SuperShare =>
        otr.id sameElements this.id
      case _ =>
        false
    }
  }
}
object SuperShare {
  final val HEADER_SIZE = 220
  final val N_SIZE      = 4
  final val TX_SIZE_SHORT = 2
  final val LEVELS_BYTE = 1
  /**
   * Deserialize SuperShare from given bytes, discarding any unused bytes from end of the given array
   * @param bytes Bytes containing SuperShare
   * @return First SuperShare found within the given bytes
   */
  def deserialize(bytes: Array[Byte]): SuperShare = {
    val nBytes = bytes.slice(0, N_SIZE)
    val header = bytes.slice(N_SIZE, N_SIZE + HEADER_SIZE)
    val txSize = ByteBuffer.wrap(bytes.slice(N_SIZE + HEADER_SIZE, N_SIZE + HEADER_SIZE + TX_SIZE_SHORT))
      .order(ByteOrder.BIG_ENDIAN)
      .asShortBuffer()
      .get()

    val numLevels = bytes(N_SIZE + HEADER_SIZE + TX_SIZE_SHORT)
    val proof = {
          TransactionProof.deserialize(bytes.slice(
            N_SIZE + HEADER_SIZE + TX_SIZE_SHORT + LEVELS_BYTE, N_SIZE + HEADER_SIZE + TX_SIZE_SHORT + LEVELS_BYTE +
              txSize + (numLevels * TransactionProof.LEVEL_SIZE)
          ), txSize, numLevels)
    }
    require(Ints.fromByteArray(nBytes) == Autolykos2PowValidation
      .calcN(ErgoHeader.sigmaSerializer.fromBytes(header).height),
      "Stored N value and calculated N value must be equal")
    SuperShare(header, proof)
  }

  def fromCandidate(nonce: Array[Byte], candidate: MiningCandidate): SuperShare = {
    val preImage = candidate.proof.getString("msgPreimage")
    val jsonArr = candidate.proof.getJSONArray("txProofs")

    val txProofs = for(i <- 0 until jsonArr.length()) yield jsonArr.getJSONObject(i)
    val collTxProof = txProofs.find(t => t.getString("leaf") == candidate.txId)
    require(collTxProof.isDefined,
      s"Could not find correct merkle leaf for collateral txId ${candidate.txId}. \n " +
        s" ${txProofs.map(t => "leaf: " + t.getString("leaf")).mkString(", ")} \n" +
      s"txBytesHash: ${Hex.toHexString(Blake2b256(candidate.txBytes))}"
    )

    val leaf = collTxProof.get.getString("leaf")
    val levelArr = collTxProof.get.getJSONArray("levels")
    val levels = for(i <- 0 until levelArr.toList.size()) yield levelArr.getString(i)
    require(Blake2b256(candidate.txBytes).toArray sameElements Hex.decode(leaf), "Tx bytes did not match merkle leaf")
    val txProof = TransactionProof(candidate.txBytes, levels.map(Hex.decode))
    val headerWithoutPow = HeaderWithoutPowSerializer.fromBytes(Hex.decode(preImage))
    val ge = GroupElementSerializer.fromBytes(Hex.decode(candidate.pk))
    val solution = new AutolykosSolution(ge, ge, nonce, BigInt("0"))
    val header = headerWithoutPow.toHeader(solution, null)

    SuperShare(header.bytes, txProof)
  }

  /**
   * Deserializes a sequence of SuperShares from a given array of bytes, with no left-over bytes after reading
   * @param bytes Bytes containing sequence of SuperShares with no left-over bytes
   * @return Sequence of SuperShares read from bytes
   */
  def deserializeMany(bytes: Array[Byte]): Seq[SuperShare] = {
    var shares = Seq(deserialize(bytes))
    var counter = shares.head.size

    while(counter != bytes.length){
      require(counter < bytes.length, "SuperShares should be evenly read from given bytes")
      val nextShare = deserialize(bytes.slice(counter, bytes.length))
      shares = shares ++ Seq(nextShare)
      counter = counter + nextShare.size
    }
    shares
  }
}
