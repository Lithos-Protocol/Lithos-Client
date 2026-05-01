package plasmadex

import org.ergoplatform.appkit.ErgoValue
import scorex.utils.Longs
import sigma.Coll

case class Provision(liquidity: Long) {
  def serialize: Array[Byte] = {
    Longs.toByteArray(liquidity)
  }

  def ergoValue: ErgoValue[Coll[Byte]] = ErgoValue.of(serialize)
}

object Provision {

  def deserialize(bytes: Array[Byte]): Long = {
    Longs.fromByteArray(bytes)
  }

  def fromErgoValue(ergoValue: ErgoValue[_]): Provision = {
    Provision(deserialize(ergoValue.getValue.asInstanceOf[Coll[Byte]].toArray))
  }
}
