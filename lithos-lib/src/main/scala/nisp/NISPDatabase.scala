package nisp

import lfsm.LFSMHelpers
import nisp.NISPDatabase.{CURRENT_HEIGHT, LAST_HEIGHT, NISP_DIR}
import org.iq80.leveldb.Options
import scorex.crypto.hash.Blake2b256
import scorex.db.{LDBFactory, LDBKVStore}
import scorex.utils.Ints

import java.io.File

class NISPDatabase {
  private val db = LDBFactory.factory.open(new File(NISP_DIR), new Options())
  private val kvstore = new LDBKVStore(db)

  def getAll: Seq[(Array[Byte], Array[Byte])] = kvstore.getAll.toSeq
  def size: Int = getAll.size
  /**
   * Adds SuperShare to create new NISP at given height, or adds the share to an existing NISP
   * @param height Height of the SuperShare
   * @param score Score associated with the share, only used in new insertions
   * @param shareBytes Bytes of SuperShare to add to database
   * @return Whether all operations returned successfully
   */
  def addNISP(height: Int, score: Long, shareBytes: Array[Byte]): Boolean = {
    addNISP(height, score, SuperShare.deserialize(shareBytes))
  }

  /**
   * Adds SuperShare to create new NISP at given height, or adds the share to an existing NISP
   * @param height Height of the SuperShare
   * @param score Score associated with the share, only used in new insertions
   * @param share Share to add to database
   * @return Whether all operations returned successfully
   */
  def addNISP(height: Int, score: Long, share: SuperShare): Boolean = {
    val hKey = Ints.toByteArray(height)

    def heightsUpdated: Boolean = {
      val storedLastHeight = lastHeight
      val lastHeightUpdated = storedLastHeight match {
        case Some(value) => true
        case None => updateLastHeight(hKey, storedLastHeight)
      }
      val storedCurrHeight = currentHeight
      val currentHeightUpdated = storedCurrHeight match {
        case Some(curr) =>
          if(Ints.fromByteArray(curr) < height) {
            updateCurrentHeight(hKey, storedCurrHeight)
          }else{
            true
          }
        case None => updateCurrentHeight(hKey, storedCurrHeight)
      }
      currentHeightUpdated && lastHeightUpdated
    }

    kvstore.get(hKey) match {
      case Some(bytes) =>
        val nextNISP = NISP.deserialize(bytes).withSuperShare(share)
        kvstore.remove(Seq(hKey)).isSuccess && kvstore.insert(hKey, nextNISP.serialize).isSuccess &&
          heightsUpdated
      case None =>
        val nisp = NISP(score, Seq(share))
        kvstore.insert(hKey, nisp.serialize).isSuccess && heightsUpdated
    }
  }

  /**
   * Remove NISPs from the db until the given height (exclusive)
   * @param height Threshold height such that all NISPs under this height are removed
   */
  def removeUntil(height: Int): Boolean = {
    val currHeight = currentHeight
    if(currHeight.isEmpty || height > Ints.fromByteArray(currHeight.get)) {
      throw new Exception(s"Cannot remove NISPs until height ${height} due to currHeight" +
        s" ${currHeight} being too small or undefined")
    }else{
      lastHeight match {
        case Some(last) =>
          var removals = true
          while(Ints.fromByteArray(lastHeight.get) < height){
            removals = removals && removeLastNISP
          }
          removals
        case None =>
          throw new Exception("Cannot remove NISPs because lastHeight is undefined")
      }

    }
  }
  def removeLastNISP: Boolean = {
    lastHeight match {
      case Some(bytes) =>
        val removal = kvstore.remove(Seq(bytes)).isSuccess
        val nextHeight = getNextHeight(bytes)
        nextHeight match {
          case Some(nextHeightBytes) => updateLastHeight(nextHeightBytes, Some(bytes)) && removal
          case None =>
            kvstore.remove(Seq(LAST_HEIGHT)).isSuccess && kvstore.remove(Seq(CURRENT_HEIGHT)).isSuccess && removal
        }

      case None =>
        throw new Exception("Failed to remove last NISP because lastHeight was not defined")
    }
  }

  /**
   * Gets next height with nisp starting from given height
   */
  def getNextHeight(start: Array[Byte]): Option[Array[Byte]] = {
    currentHeight match {
      case Some(curr) =>
        val hVal = Ints.fromByteArray(start)
        var heightCounter = hVal + 1
        var nextNISP = kvstore.get(Ints.toByteArray(heightCounter))
        while (nextNISP.isEmpty && heightCounter <= Ints.fromByteArray(curr)) {
          heightCounter = heightCounter + 1
          nextNISP = kvstore.get(Ints.toByteArray(heightCounter))
        }
        if(nextNISP.isEmpty)
          return None
        Some(Ints.toByteArray(heightCounter))
      case None =>
        throw new Exception("Can't get next height when current height is undefined. Is this database empty?")
    }
  }

  def lastHeight: Option[Array[Byte]] = {
    kvstore.get(LAST_HEIGHT)
  }

  def currentHeight: Option[Array[Byte]] = {
    kvstore.get(CURRENT_HEIGHT)
  }

  def getNISPBytes(height: Int): Option[Array[Byte]] = {
    kvstore.get(Ints.toByteArray(height))
  }

  def getNISP(height: Int): Option[NISP] = {
    getNISPBytes(height).map(NISP.deserialize)
  }

  /**
   * Gets the best valid NISP before a given height and above a given score. If a NISP with 10 super-shares cannot be
   * made, `None` is returned.
   * @param height Height that all super-shares must be under. Super-shares must be above (height - NISP_PERIOD)
   * @param score Score that all super-shares must be above.
   * @return `Some(NISP)` with 10 super-shares below the given height and above the given score, or `None`
   */
  def getBestValidNISP(height: Int, score: Long): Option[NISP] = {
    require(lastHeight.isDefined, "Cannot search for NISPs when lastHeight is undefined")
    val minHeight = height - LFSMHelpers.NISP_PERIOD.toInt
    val start = Math.max(minHeight, lastHeight.map(Ints.fromByteArray).get)
    val nisps = for(i <- start to height) yield getNISP(i)
    val validNISPs = nisps.filter(n => n.isDefined && n.get.score >= score).map(_.get)
    val bestNISP = validNISPs.foldLeft(Option.empty[NISP]){
      (z: Option[NISP], x: NISP) =>
        if(z.isEmpty) {
          Some(x)
        }else if(z.get.shares.size >= 10){
          z
        }else{
          Some(z.get.copy(score = score, shares = z.get.shares ++ x.shares))
        }
    }
    if(bestNISP.isEmpty || bestNISP.get.shares.size < 10){
      None
    }else{
      Some(bestNISP.get.copy(shares = bestNISP.get.shares.take(10)))
    }
  }

  def updateLastHeight(newLastHeight: Array[Byte], storedLastHeight: Option[Array[Byte]]): Boolean = {
    storedLastHeight match {
      case Some(h) =>
        kvstore.remove(Seq(LAST_HEIGHT)).isSuccess && kvstore.insert(LAST_HEIGHT, newLastHeight).isSuccess
      case None =>
        kvstore.insert(LAST_HEIGHT, newLastHeight).isSuccess
    }
  }

  def updateCurrentHeight(newCurrHeight: Array[Byte], storedCurrHeight: Option[Array[Byte]]): Boolean = {
    storedCurrHeight match {
      case Some(h) =>
          kvstore.remove(Seq(CURRENT_HEIGHT)).isSuccess && kvstore.insert(CURRENT_HEIGHT, newCurrHeight).isSuccess
      case None =>
        kvstore.insert(CURRENT_HEIGHT, newCurrHeight).isSuccess
    }
  }

}
object NISPDatabase {
  final val NISP_DIR = ".lithos/nisp"
  final val LAST_HEIGHT = Blake2b256.hash("LAST_HEIGHT")
  final val CURRENT_HEIGHT = Blake2b256.hash("CURRENT_HEIGHT")
}
