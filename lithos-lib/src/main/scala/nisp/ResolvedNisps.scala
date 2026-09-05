package nisp

import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{ContextVar, ErgoValue}

/** Owns one defensive copy; callers can share it without exposing mutable payload storage. */
final class ResolvedNisp private (val commitment: NispCommitment, private val raw: Array[Byte]) {
  def bytes: Array[Byte] = raw.clone()
  def size: Int = raw.length
  def contextVar: ContextVar = ContextVar.of(NispCommitment.PayloadContextSlot, ErgoValue.of(bytes))
}

object ResolvedNisp {
  def apply(raw: Array[Byte]): ResolvedNisp = {
    val owned = raw.clone()
    new ResolvedNisp(NispCommitment.fromPayload(owned), owned)
  }
}

/** Ephemeral payload ownership shared by one rollup's evaluation and queued fraud work. */
final case class ResolvedNisps(rollupId: String, payloads: Map[String, ResolvedNisp]) {
  def get(miner: Array[Byte]): Option[ResolvedNisp] = payloads.get(Hex.toHexString(miner))
  def retainedBytes: Long = payloads.valuesIterator.map(_.size.toLong).sum
}

final class NispPayloadUnavailable(message: String) extends IllegalStateException(message)
