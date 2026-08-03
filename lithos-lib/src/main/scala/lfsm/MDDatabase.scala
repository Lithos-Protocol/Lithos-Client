package lfsm

import lfsm.MDDatabase.{DATA_BOX_TOKEN, MD_DIR}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.sdk.ErgoId
import scorex.crypto.hash.Blake2b256
import scorex.db.LDBFactory
import scorex.utils.Ints

class MDDatabase {

  private val kvstore = LDBFactory.createKvDb(MD_DIR)

  def getAll: Seq[(Array[Byte], Array[Byte])] = kvstore.getAll.toSeq
  def size: Int = getAll.size

  def getDataBoxToken: Option[ErgoId] = kvstore.get(DATA_BOX_TOKEN).map(v => ErgoId.create(Hex.toHexString(v)))
  def setDataBoxToken(tokenId: ErgoId): Boolean = kvstore.insert(DATA_BOX_TOKEN, tokenId.getBytes).isSuccess
  def delDataBoxToken: Boolean = kvstore.remove(Array[Array[Byte]](DATA_BOX_TOKEN)).isSuccess
  def close(): Unit = {
    kvstore.close()
  }

}
object MDDatabase {
  private final val MD_DIR = ".lithos/md"
  private final val DATA_BOX_TOKEN = Blake2b256.hash("DATA_BOX_TOKEN")

}
