package nisp

import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.JavaHelpers
import org.ergoplatform.{AutolykosSolution, ErgoHeader, HeaderWithoutPow, HeaderWithoutPowSerializer}
import scorex.utils.Ints
import sigma.pow.Autolykos2PowValidation
import sigma.serialization.GroupElementSerializer
import stratum.data.MiningCandidate

case class SuperShare(headerBytes: Array[Byte], txProof: Option[TransactionProof] = None) {
  def serialize: Array[Byte] = {
    nBytes ++ headerBytes ++ Seq((if(txProof.isDefined) txProof.get.levels.size else 0).toByte) ++ txProof.map(_.serialize).toSeq.flatten
  }

  def size: Int = serialize.length

  override def toString: String = {
    s"SuperShare(${Hex.toHexString(nBytes)}, ${Hex.toHexString(headerBytes)},\n " +
      s"${(if(txProof.isDefined) txProof.get.levels.size else 0).toByte}, \n " +
      s"${txProof})"
  }

  def getHeader: ErgoHeader = {
    ErgoHeader.sigmaSerializer.fromBytes(headerBytes)
  }

  def getHeight: Int =
    getHeader.height

  def getN: Int = {
    Autolykos2PowValidation.calcN(getHeight)
  }

  lazy val nBytes: Array[Byte] = Ints.toByteArray(getN)

}
object SuperShare {
  final val HEADER_SIZE = 220
  final val N_SIZE      = 4
  /**
   * Deserialize SuperShare from given bytes, discarding any unused bytes from end of the given array
   * @param bytes Bytes containing SuperShare
   * @return First SuperShare found within the given bytes
   */
  def deserialize(bytes: Array[Byte]): SuperShare = {
    val nBytes = bytes.slice(0, N_SIZE)
    val header = bytes.slice(N_SIZE, N_SIZE + HEADER_SIZE)
    val proof = {
      if(bytes(N_SIZE + HEADER_SIZE) != 0.toByte) {
        Some(
          TransactionProof.deserialize(bytes.slice(
            N_SIZE + HEADER_SIZE + 1, N_SIZE + HEADER_SIZE + 1 +
              TransactionProof.LEAF_SIZE + (bytes(N_SIZE + HEADER_SIZE).toInt * TransactionProof.LEVEL_SIZE)
          ), bytes(N_SIZE + HEADER_SIZE).toInt)
        )
      }else{
        None
      }
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
      s"Could not find correct merkle leaf for collateral txId ${candidate.txId}")
    val leaf = collTxProof.get.getString("leaf")
    val levelArr = collTxProof.get.getJSONArray("levels")
    val levels = for(i <- 0 until levelArr.toList.size()) yield levelArr.getString(i)
    val txProof = TransactionProof(Hex.decode(leaf), levels.map(Hex.decode))
    val headerWithoutPow = HeaderWithoutPowSerializer.fromBytes(Hex.decode(preImage))
    val ge = GroupElementSerializer.fromBytes(Hex.decode(candidate.pk))
    val solution = new AutolykosSolution(ge, ge, nonce, BigInt("0"))
    val header = headerWithoutPow.toHeader(solution, null)

    SuperShare(header.bytes, Some(txProof))
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
