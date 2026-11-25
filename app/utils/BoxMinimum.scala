package utils

import org.ergoplatform.appkit.BlockchainContext
import work.lithos.mutations.UTXO

import scala.annotation.tailrec

/**
 * The node's size rules for a box: at least `minValuePerByte` nanoERG for each byte of the box as created, and at
 * most 4,096 bytes. A builder that chooses a box's value raises it to its minimum with [[sized]]; a contract floor
 * above that is the builder's to apply.
 */
object BoxMinimum {

  /** The largest box the node accepts, in bytes. */
  final val MaxBoxBytes = 4096

  /** A created box's bytes beyond its candidate: the 32-byte transaction id and up to 2 bytes of output index. */
  final val RefBytes = 34

  /** The node's nanoERG per byte. AppKit caches the parameters, so this reads the node once per data source. */
  def perByte(ctx: BlockchainContext): Long = ctx.getDataSource.getParameters.getMinValuePerByte.toLong

  /** Bytes of `u` once created, at the value it holds. */
  def bytes(ctx: BlockchainContext, u: UTXO): Int = u.toOutBox(ctx).getBytesWithNoRef.length + RefBytes

  /** `u` at `value`, or at the node's minimum for its size when that is more. */
  def sized(ctx: BlockchainContext, u: UTXO, value: Long): UTXO = sized(ctx, u, value, perByte(ctx))

  /**
   * `u` at `value`, or at `rate` nanoERG per byte when that is more. Refused when the box passes [[MaxBoxBytes]]: the
   * node drops a transaction with such an output.
   */
  def sized(ctx: BlockchainContext, u: UTXO, value: Long, rate: Long): UTXO = {
    @tailrec def settle(v: Long, rounds: Int): UTXO = {
      val at = u.setValue(v)
      val size = bytes(ctx, at)
      require(size <= MaxBoxBytes, s"a $size-byte box is over the node's $MaxBoxBytes-byte limit")
      val need = Math.multiplyExact(size.toLong, rate)
      // A larger value can take one more byte, so the box is measured again at the value it is raised to.
      if (need <= v) at else if (rounds == 0) at.setValue(need) else settle(need, rounds - 1)
    }
    settle(value, 2)
  }
}
