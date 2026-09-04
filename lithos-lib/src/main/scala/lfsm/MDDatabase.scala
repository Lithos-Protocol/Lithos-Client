package lfsm

import lfsm.MDDatabase.{DATA_BOX_TOKEN, MD_DIR, SCHEMA_VERSION, Schema}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.sdk.ErgoId
import scorex.crypto.hash.Blake2b256
import storage.KeyValueStore

import java.nio.file.Paths

class MDDatabase(private val kvstore: KeyValueStore) {

  def this() = this(KeyValueStore.openOrThrow(KeyValueStore.DefaultBackend, Paths.get(MD_DIR)))

  def getAll: Seq[(Array[Byte], Array[Byte])] =
    KeyValueStore.orThrow(kvstore.scanPrefix(Array.emptyByteArray))

  def size: Int = getAll.size

  def getDataBoxToken: Option[ErgoId] =
    KeyValueStore.orThrow(kvstore.get(DATA_BOX_TOKEN)).map(value => ErgoId.create(Hex.toHexString(value)))

  def setDataBoxToken(tokenId: ErgoId): Boolean =
    kvstore.put(DATA_BOX_TOKEN, tokenId.getBytes).isRight

  def delDataBoxToken: Boolean =
    kvstore.delete(DATA_BOX_TOKEN).isRight

  def getSchemaVersion: Option[Int] =
    KeyValueStore.orThrow(kvstore.get(SCHEMA_VERSION))
      .filter(_.length == 4)
      .map(bytes => scorex.utils.Ints.fromByteArray(bytes))

  def setSchemaVersion(version: Int): Boolean =
    kvstore.put(SCHEMA_VERSION, scorex.utils.Ints.toByteArray(version)).isRight

  /** Every key, so a version this build cannot read leaves nothing behind. */
  def clearAll(): Boolean =
    getAll.map(entry => kvstore.delete(entry._1).isRight).forall(identity)

  /**
   * Clears the store and stamps it when the stored schema is below this build's or absent, and
   * stamps only if the clear completed. Nothing kept here is authoritative, so dropping it costs
   * only the republish that refills it.
   *
   * @return whether anything was cleared
   */
  def migrate(): Boolean = {
    if (getSchemaVersion.exists(_ >= Schema)) false
    else if (!clearAll()) false
    else {
      setSchemaVersion(Schema)
      true
    }
  }

  def close(): Unit = KeyValueStore.orThrow(kvstore.close())
}

object MDDatabase {
  private final val MD_DIR = ".lithos/md"
  private final val DATA_BOX_TOKEN = Blake2b256.hash("DATA_BOX_TOKEN")
  private final val SCHEMA_VERSION = Blake2b256.hash("SCHEMA_VERSION")

  /** Bump whenever a key's meaning or encoding changes. Anything below it is discarded, not read. */
  final val Schema: Int = 1
}
