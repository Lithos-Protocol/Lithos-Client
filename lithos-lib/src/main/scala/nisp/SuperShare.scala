package nisp

import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.JavaHelpers
import org.ergoplatform.{AutolykosSolution, HeaderWithoutPow, HeaderWithoutPowSerializer}
import stratum.data.MiningCandidate

case class SuperShare(nonce: Array[Byte], msgPreImg: Array[Byte], txProof: Option[TransactionProof] = None) {
  def serialize: Array[Byte] = {
    nonce ++ msgPreImg ++ Seq((if(txProof.isDefined) txProof.get.levels.size else 0).toByte) ++ txProof.map(_.serialize).toSeq.flatten
  }

  def size: Int = serialize.length

  override def toString: String = {
    s"SuperShare(${Hex.toHexString(nonce)}, ${Hex.toHexString(msgPreImg)},\n " +
      s"${(if(txProof.isDefined) txProof.get.levels.size else 0).toByte}, \n " +
      s"${txProof})"
  }

  def getHeaderWithoutPoW: HeaderWithoutPow = {
    HeaderWithoutPowSerializer.fromBytes(msgPreImg)
  }

  def getHeight: Int =
    getHeaderWithoutPoW.height
}
object SuperShare {
  final val HEADER_SIZE = 179
  final val NONCE_SIZE = 8
  /**
   * Deserialize SuperShare from given bytes, discarding any unused bytes from end of the given array
   * @param bytes Bytes containing SuperShare
   * @return First SuperShare found within the given bytes
   */
  def deserialize(bytes: Array[Byte]): SuperShare = {
    val nonce = bytes.slice(0, NONCE_SIZE)
    val preImg = bytes.slice(NONCE_SIZE, NONCE_SIZE + HEADER_SIZE)
    val proof = {
      if(bytes(HEADER_SIZE + NONCE_SIZE) != 0.toByte) {
        Some(
          TransactionProof.deserialize(bytes.slice(
            NONCE_SIZE + HEADER_SIZE + 1, NONCE_SIZE + HEADER_SIZE + 1 +
              TransactionProof.LEAF_SIZE + (bytes(HEADER_SIZE + NONCE_SIZE).toInt * TransactionProof.LEVEL_SIZE)
          ), bytes(HEADER_SIZE + NONCE_SIZE).toInt)
        )
      }else{
        None
      }
    }
    SuperShare(nonce, preImg, proof)
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

    SuperShare(nonce, Hex.decode(preImage), Some(txProof))
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
