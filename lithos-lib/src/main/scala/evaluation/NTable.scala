package evaluation

import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{ErgoType, ErgoValue}
import scorex.crypto.hash.{Blake2b256, Digest32}
import scorex.utils.Ints
import sigma.{Coll, Colls}
import sigma.pow.Autolykos2PowValidation
import sigma.pow.Autolykos2PowValidation.NIncreasementHeightMax

/**
 * Describes generation of N Table used to verify correct N value for given height
 */
object NTable {
  lazy val N_Table = generateNTable

  /**
   * Generates mapping of heights to N values
   *
   * Only saves first height which maps to N value not seen before in the table
   */
  private def generateNTable: Array[(Int, Int)] = {
    (0 to NIncreasementHeightMax + 1).foldLeft(Array.empty[(Int, Int)]){
      (a: Array[(Int, Int)], b: Int) =>
        val n = Autolykos2PowValidation.calcN(b)
        if(!a.exists(p => p._2 == n)){
          a :+ (b -> n)
        }else{
          a
        }
    }
  }

  def ergoValue: ErgoValue[Coll[(Int, Int)]] = {
    ErgoValue.of(Colls.fromArray(N_Table), ErgoType.pairType(ErgoType.integerType(), ErgoType.integerType()))
  }

  def serialized: Array[Byte] = {
    N_Table.foldLeft(Array.empty[Byte]){
      (a: Array[Byte], b: (Int, Int)) =>
        a ++ (Ints.toByteArray(b._1) ++ Ints.toByteArray(b._2))
    }
  }

  /**
   * Find the associated N value for a given height, without having to calculate N
   *
   * NOTE: Should be faster than calling `Autolykos2PowValidation.calcN()`
   * @param height Height to find N value for
   */
  def lookUp(height: Int): Int = {
    N_Table.find(hn => height >= hn._1).get._2
  }

  /**
   * Hash of serialized NTable
   *
   * For correctly generated NTable, hash is: `5c40f60e9dabf1272597810085d9827d41c2db44e3a12b7f07f38d90868e410d`
   */
  def hash: Digest32 = {
    Blake2b256.hash(serialized)
  }

  /**
   * Hexadecimal representation of NTable hash
   * @return For correctly generated NTable, hash is `5c40f60e9dabf1272597810085d9827d41c2db44e3a12b7f07f38d90868e410d`
   */
  override def toString: String = {
    Hex.toHexString(hash)
  }
}
