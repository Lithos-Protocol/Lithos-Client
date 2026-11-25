package transactions.batching.sigmafi.zc

import node.model.NodeBox
import org.ergoplatform.appkit.BlockchainContext
import utils.BoxMinimum
import work.lithos.mutations.UTXO

/**
 * The size rules for the ZC boxes this client makes: the node's ([[BoxMinimum]]), with `ZcBoxes.MinBox` as a floor. ZC
 * contracts take `next.value >= …`, so a builder raises a box to its own minimum instead of leaving it at `MinBox`,
 * which covers only boxes up to 2,777 bytes.
 */
object ZcSizing {

  /** The largest box the node accepts, in bytes. */
  final val MaxBoxBytes: Int = BoxMinimum.MaxBoxBytes

  /** Mainnet's nanoERG per byte, for pre-filters that run without a node context. Builders read the node's own. */
  final val DefaultPerByte = 360L

  /**
   * The most distinct collateral tokens a bond may carry. A liquidated bond pulled into a Dutch vault, whose tree is
   * 2.2 KB, passes 4,096 bytes at about 44, and its shares could then never be partly claimed.
   */
  final val MaxCollateralTokens = 30

  /** An offer continuation's R6: a 32-byte policy hash and the 32-byte predecessor id, with its type and length. */
  private final val OfferRestR6Bytes = 66

  def perByte(ctx: BlockchainContext): Long = BoxMinimum.perByte(ctx)

  def bytes(ctx: BlockchainContext, u: UTXO): Int = BoxMinimum.bytes(ctx, u)

  /** `u` at `value`, raised to `MinBox` and to the node's minimum for its size; refused past [[MaxBoxBytes]]. */
  def sized(ctx: BlockchainContext, u: UTXO, value: Long): UTXO =
    BoxMinimum.sized(ctx, u, math.max(value, ZcBoxes.MinBox))

  /** The least `u` may hold. */
  def least(ctx: BlockchainContext, u: UTXO): Long = sized(ctx, u, ZcBoxes.MinBox).value

  /** Why the node would refuse `u` at the value it holds, or None. */
  def refusal(ctx: BlockchainContext, u: UTXO): Option[String] = {
    val size = bytes(ctx, u)
    val need = math.max(ZcBoxes.MinBox, Math.multiplyExact(size.toLong, perByte(ctx)))
    if (size > MaxBoxBytes) Some(s"$size bytes, over the node's $MaxBoxBytes-byte limit")
    else if (u.value < need) Some(s"${u.value} nanoERG, under the $need its $size bytes need")
    else None
  }

  /** Serialized bytes of a box read from the node, counted from its JSON fields. */
  def bytes(box: NodeBox): Int =
    vlq(box.value) + box.ergoTree.length / 2 + vlq(box.creationHeight.toLong) + 1 +
      box.assets.map(a => 32 + vlq(a.amount)).sum + 1 +
      box.additionalRegisters.values.values.map(_.length / 2).sum + BoxMinimum.RefBytes

  /**
   * The least an offer's continuation may hold, at [[DefaultPerByte]]: the offer's own box with R6 grown to its
   * predecessor form. Its value and remaining capital only shrink, so this never undercounts.
   */
  def offerRestLeast(box: NodeBox): Long = {
    val r6 = box.additionalRegisters.get(6).fold(0)(_.length / 2)
    math.max(ZcBoxes.MinBox, (bytes(box) - r6 + OfferRestR6Bytes).toLong * DefaultPerByte)
  }

  private def vlq(n: Long): Int = {
    var rest = n >>> 7
    var size = 1
    while (rest != 0L) { rest >>>= 7; size += 1 }
    size
  }
}
