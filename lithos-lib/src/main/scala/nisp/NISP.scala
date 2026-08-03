package nisp

import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.HeaderWithoutPow
import org.ergoplatform.appkit.ErgoValue
import org.ergoplatform.appkit.scalaapi.scalaByteType
import scorex.utils.Longs
import sigma.data.CHeader
import sigma.pow.Autolykos2PowValidation
import sigma.util.NBitsUtils
import sigma.{Coll, Colls}
import stratum.data.MiningCandidate

/**
 * Non-Interactive Share Proof, represented by a set of 10 high-difficulty shares and the associated value these shares
 * were mined at (score-representation of tau)
 *
 *
 * NISPs are serialized like so:
 *
 * [score: 8bytes][10 SuperShares]
 *
 * Where 1 SuperShare is:
 *
 * [SuperShare: (N: 4bytes)(header: headerSize)(txSize: 2bytes)(numLevels: 1byte)(tx: txSize)(levels: numLevels*33bytes)(y-coord: 32bytes)]
 * @param score Score representation of tau for this NISP
 * @param shares Set of SuperShares (Must equal 10 to be a valid NISP)
 */
case class NISP(score: Long, shares: Seq[SuperShare]) {


  def serialize: Array[Byte] = {
    Longs.toByteArray(score) ++ shares.flatMap(_.serialize)
  }

  def withSuperShare(superShare: SuperShare): NISP = {
    this.copy(shares = this.shares ++ Seq(superShare))
  }

  def ergoValue: ErgoValue[Coll[Byte]] = ErgoValue.of(Colls.fromArray(serialize), scalaByteType)

  /**
   * Returns NISP with only distinct shares, based on header id of each super share
   */
  def distinct: NISP = this.copy(shares = getDistinctShares)

  def getDistinctShares: Seq[SuperShare] = {
    shares.distinct
  }

  def isDistinct = {
    shares.map(s => Hex.toHexString(s.toHeader.id.toArray)).toSet.size == shares.size
  }


}

object NISP {
  def deserialize(bytes: Array[Byte]): NISP = {
    NISP(Longs.fromByteArray(bytes.slice(0, 8)), SuperShare.deserializeMany(bytes.slice(8, bytes.length)))
  }

  def fromErgoValue(ergoValue: ErgoValue[_]): NISP = {
    require(ergoValue.getValue.isInstanceOf[Coll[Byte]], "Cannot deserialize NISP from types other than Coll[Byte]")
    deserialize(ergoValue.getValue.asInstanceOf[Coll[Byte]].toArray)
  }
}
