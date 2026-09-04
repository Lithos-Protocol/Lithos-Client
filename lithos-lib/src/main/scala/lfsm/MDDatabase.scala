package lfsm

import lfsm.MDDatabase.{DATA_BOX_TOKEN, MD_DIR, REGISTRATION_EXPIRY}
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

  /**
   * The height this miner's own registration expires at, written when the registration is built.
   *
   * Kept here rather than read back out of the dictionary, because the only consumer wants it on a
   * timer and materializing a dictionary to answer that is a cold reconstruction.
   */
  def getRegistrationExpiry: Option[Long] =
    KeyValueStore.orThrow(kvstore.get(REGISTRATION_EXPIRY))
      .filter(_.length == 8)
      .map(bytes => scorex.utils.Longs.fromByteArray(bytes))

  def setRegistrationExpiry(height: Long): Boolean =
    kvstore.put(REGISTRATION_EXPIRY, scorex.utils.Longs.toByteArray(height)).isRight

  def delRegistrationExpiry: Boolean =
    kvstore.delete(REGISTRATION_EXPIRY).isRight

  def close(): Unit = KeyValueStore.orThrow(kvstore.close())
}

object MDDatabase {
  private final val MD_DIR = ".lithos/md"
  private final val DATA_BOX_TOKEN = Blake2b256.hash("DATA_BOX_TOKEN")
  private final val REGISTRATION_EXPIRY = Blake2b256.hash("REGISTRATION_EXPIRY")
}
