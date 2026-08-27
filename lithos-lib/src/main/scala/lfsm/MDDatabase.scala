package lfsm

import lfsm.MDDatabase.{DATA_BOX_TOKEN, MD_DIR}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.sdk.ErgoId
import scorex.crypto.hash.Blake2b256
import storage.{KeyValueStore, LevelDbKeyValueStore}

import java.nio.file.Paths

class MDDatabase(private val kvstore: KeyValueStore) {

  def this() = this(LevelDbKeyValueStore.openOrThrow(Paths.get(MD_DIR)))

  def getAll: Seq[(Array[Byte], Array[Byte])] =
    KeyValueStore.orThrow(kvstore.scanPrefix(Array.emptyByteArray))

  def size: Int = getAll.size

  def getDataBoxToken: Option[ErgoId] =
    KeyValueStore.orThrow(kvstore.get(DATA_BOX_TOKEN)).map(value => ErgoId.create(Hex.toHexString(value)))

  def setDataBoxToken(tokenId: ErgoId): Boolean =
    kvstore.put(DATA_BOX_TOKEN, tokenId.getBytes).isRight

  def delDataBoxToken: Boolean =
    kvstore.delete(DATA_BOX_TOKEN).isRight

  def close(): Unit = KeyValueStore.orThrow(kvstore.close())
}

object MDDatabase {
  private final val MD_DIR = ".lithos/md"
  private final val DATA_BOX_TOKEN = Blake2b256.hash("DATA_BOX_TOKEN")
}
