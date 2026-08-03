package nisp

import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.sdk.JavaHelpers
import org.ergoplatform.{AutolykosSolution, ErgoHeader, HeaderWithoutPow, HeaderWithoutPowSerializer}
import scorex.crypto.hash.Blake2b256
import scorex.utils.{Ints, Shorts}
import sigma.GroupElement
import sigma.crypto.{BigIntegers, Platform}
import sigma.crypto.Platform.Ecp
import sigma.data.CHeader
import sigma.pow.Autolykos2PowValidation
import sigma.serialization.GroupElementSerializer
import stratum.CollateralData
import stratum.data.MiningCandidate

import java.nio.{ByteBuffer, ByteOrder}

case class SuperShare(headerBytes: Array[Byte], txProof: TransactionProof, levels: Seq[Array[Byte]]) {
  lazy val nBytes: Array[Byte] = Ints.toByteArray(getN)
  val yCoord: Array[Byte] = Platform.getYCoord(toHeader.powSolution.pk).value.getEncoded

  def getN: Int = {
    Autolykos2PowValidation.calcN(getHeight)
  }
  def serialize: Array[Byte] = {
    nBytes ++ headerBytes ++ Shorts.toByteArray(txProof.size.toShort) ++
      Array(levels.length.toByte) ++ txProof.serialize ++ levels.flatten ++ yCoord
  }

  lazy val size: Int = serialize.length

  override def toString: String = {
    s"SuperShare(${Hex.toHexString(nBytes)}, ${Hex.toHexString(headerBytes)},\n " +
      s"${Hex.toHexString(Shorts.toByteArray(txProof.tx.length.toShort))}, \n " +
      s"${levels.size.toByte.toHexString}, \n " +
      s"${txProof}, \n" +
      s"${levels.map(Hex.toHexString).mkString("\n")}, \n" +
      s"${Hex.toHexString(yCoord)})"
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

  /**
   * Get AutolykosV2 hit for this super share
   * @return BigInt representing hit value for AutolykosV2 of this header
   */
  def powHit: BigInt = Autolykos2PowValidation.hitForVersion2(new CHeader(toHeader))

  /**
   * Replaces GroupElement bytes of minerPK with given bytes
   * NOTE: Does not perform validity checks on given GroupElement bytes, so
   * calling toHeader may fail on the copy
   */
  def withGroupElement(bytes: Array[Byte]): SuperShare = {
    val headerSize = toHeader.bytes.length
    this.copy(headerBytes = headerBytes.patch(headerSize - 41, bytes, 33))
  }

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
  final val N_SIZE      = 4
  final val TX_PROOF_SIZE_SHORT = 2
  final val LEVELS_BYTE = 1
  final val Y_COORD_SIZE = 32
  final val LEVEL_SIZE  = 33
  /**
   * Deserialize SuperShare from given bytes, discarding any unused bytes from end of the given array
   *
   * NOTE: Not safe to use on unverified super-shares
   * @param bytes Bytes containing SuperShare
   * @return First SuperShare found within the given bytes
   */
  def deserialize(bytes: Array[Byte]): SuperShare = {
    val nBytes = bytes.slice(0, N_SIZE)
    val unparsedHeader = bytes.slice(N_SIZE, bytes.length)
    //println(s"bytes length: ${bytes.length} unparsed: ${unparsedHeader.length}")
    val header = ErgoHeader.sigmaSerializer.fromBytes(unparsedHeader)
    val headerSize = header.bytes.length
    val txProofSize = ByteBuffer.wrap(bytes.slice(N_SIZE + headerSize, N_SIZE + headerSize + TX_PROOF_SIZE_SHORT))
      .order(ByteOrder.BIG_ENDIAN)
      .asShortBuffer()
      .get()

    val numLevels = bytes(N_SIZE + headerSize + TX_PROOF_SIZE_SHORT)
    val proof = {
      TransactionProof.deserialize(bytes.slice(
        N_SIZE + headerSize + TX_PROOF_SIZE_SHORT + LEVELS_BYTE,
        N_SIZE + headerSize + TX_PROOF_SIZE_SHORT + LEVELS_BYTE + txProofSize
      ))
    }
    val totalLevels = numLevels.toInt * LEVEL_SIZE
    val levels = bytes.slice(
      N_SIZE + headerSize + TX_PROOF_SIZE_SHORT + LEVELS_BYTE + txProofSize,
      N_SIZE + headerSize + TX_PROOF_SIZE_SHORT + LEVELS_BYTE + txProofSize + totalLevels
    ).grouped(LEVEL_SIZE).toSeq
    val yCoord = bytes.slice(
      N_SIZE + headerSize + TX_PROOF_SIZE_SHORT + LEVELS_BYTE + txProofSize + totalLevels,
      N_SIZE + headerSize + TX_PROOF_SIZE_SHORT + LEVELS_BYTE + txProofSize + totalLevels + Y_COORD_SIZE
    )
    require(Ints.fromByteArray(nBytes) == Autolykos2PowValidation
      .calcN(header.height),
      "Stored N value and calculated N value must be equal")
    require(BigIntegers.fromUnsignedByteArray(yCoord) == Platform.getYCoord(header.powSolution.pk).value.toBigInteger,
      "Stored Y coord and calculated Y coord must be equal")

    SuperShare(header.bytes, proof, levels)
  }

  def fromCandidate(nonce: Array[Byte], candidate: MiningCandidate, collateralData: CollateralData): SuperShare = {
    val preImage = candidate.proof.getString("msgPreimage")
    val jsonArr = candidate.proof.getJSONArray("txProofs")

    val txProofs = for(i <- 0 until jsonArr.length()) yield jsonArr.getJSONObject(i)
    val collTxProof = txProofs.find(t => t.getString("leaf") == collateralData.txId)
    require(collTxProof.isDefined,
      s"Could not find correct merkle leaf for collateral txId ${collateralData.txId}. \n " +
        s" ${txProofs.map(t => "leaf: " + t.getString("leaf")).mkString(", ")} \n" +
      s"txBytesHash: ${Hex.toHexString(Blake2b256(collateralData.txBytes))}" +
        s"candidate: ${candidate.height} : ${Hex.toHexString(Blake2b256(candidate.collateralData.txBytes))}"
    )

    val leaf = collTxProof.get.getString("leaf")
    val levelArr = collTxProof.get.getJSONArray("levels")
    val levels = for(i <- 0 until levelArr.toList.size()) yield levelArr.getString(i)
    require(Blake2b256(collateralData.txBytes).toArray sameElements Hex.decode(leaf), "Tx bytes did not match merkle leaf")
    val txProof = TransactionProof(collateralData.txBytes, collateralData.collateralBoxBytes)
    val headerWithoutPow = HeaderWithoutPowSerializer.fromBytes(Hex.decode(preImage))
    val ge = GroupElementSerializer.fromBytes(Hex.decode(candidate.pk))
    val solution = new AutolykosSolution(ge, ge, nonce, BigInt("0"))
    val header = headerWithoutPow.toHeader(solution, null)
    val decodedLevels = levels.map(Hex.decode)
    SuperShare(header.bytes, txProof, decodedLevels.toSeq)
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
