package nisp

import org.bouncycastle.util.encoders.Hex
import scorex.crypto.hash.Blake2b256
import scorex.utils.Longs

/** The rollup leaf commits to the exact submitted bytes, including malformed serialization. */
final case class NispCommitment(score: Long, hash: String) {
  require(hash.length == 64 && hash.forall(c => (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')),
    "NISP hash must be 32 bytes of lowercase hexadecimal")

  def bytes: Array[Byte] = Longs.toByteArray(score) ++ Hex.decode(hash)

  def matchesPayload(payload: Array[Byte]): Boolean =
    payload.length >= 8 && Longs.fromByteArray(payload.take(8)) == score &&
      Hex.toHexString(Blake2b256.hash(payload)) == hash
}

object NispCommitment {
  final val EntrySize = 40
  /** Input 1, alongside miner/lookup/removal (0-2) and optional proof-specific evidence (3). */
  final val PayloadContextSlot: Byte = 4.toByte

  def decode(entry: Array[Byte]): NispCommitment = {
    require(entry.length == EntrySize, s"Expected a 40-byte NISP commitment, got ${entry.length}")
    NispCommitment(Longs.fromByteArray(entry.take(8)), Hex.toHexString(entry.drop(8)))
  }

  def fromPayload(payload: Array[Byte]): NispCommitment = {
    require(payload.length >= 8, "NISP payload is too short to contain its score")
    NispCommitment(Longs.fromByteArray(payload.take(8)), Hex.toHexString(Blake2b256.hash(payload)))
  }
}
