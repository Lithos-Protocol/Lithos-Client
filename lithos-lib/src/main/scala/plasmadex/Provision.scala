package plasmadex

import org.ergoplatform.appkit.ErgoValue
import org.ergoplatform.appkit.scalaapi.scalaByteType
import scorex.utils.Longs
import sigma.{Coll, Colls}

case class Provision(liquidity: Long) {
  def serialize: Array[Byte] = {
    Longs.toByteArray(liquidity)
  }

  def ergoValue: ErgoValue[Coll[Byte]] = ErgoValue.of(Colls.fromArray(serialize), scalaByteType)
}

object Provision {

  def deserialize(bytes: Array[Byte]): Long = {
    Longs.fromByteArray(bytes)
  }

  def fromErgoValue(ergoValue: ErgoValue[_]): Provision = {
    Provision(deserialize(ergoValue.getValue.asInstanceOf[Coll[Byte]].toArray))
  }
}
