package transactions.batching.tracking

import node.model.NodeHeader
import state.synchronization.CanonicalBlockSource

/** A state kept from blocks: the block it is at, the oldest it can undo to, and itself as of an earlier block. */
trait BlockKept[S] {
  def height: Int
  def headerId: String
  def base: Int
  /** The block id this state holds for `at`, when `at` is its tip or a retained block or that block's parent. */
  def idAt(at: Int): Option[String]
  def rolledBackTo(at: Int): Option[S]
}

object BlockFollow {

  /**
   * `state` moved to `headerId` at `height` by undoing blocks back to the newest one the canonical chain shares
   * with it, then applying each canonical block after it with `apply`. None when they share no retained block or
   * the target is more than `maxCatchUp` blocks ahead. Fails when the node's chain does not have `headerId` at
   * `height`, or a read fails.
   */
  def follow[S <: BlockKept[S]](source: CanonicalBlockSource, state: S, height: Int, headerId: String,
                                maxCatchUp: Int)(apply: (S, NodeHeader) => S): Option[S] =
    if (height - state.height > maxCatchUp || height < state.base) None
    else {
      // The tip's own height first, which is all a block extending it needs; the retained window only on a fork
      def shared(from: Int): Option[(Seq[NodeHeader], Int)] = {
        val headers = source.headerSlice(from, height).get
        if (!headers.lastOption.exists(_.id == headerId))
          throw new IllegalStateException(s"the node's chain does not have block $headerId at height $height")
        headers.filter(h => h.height <= state.height && state.idAt(h.height).contains(h.id))
          .lastOption.map(ancestor => headers -> ancestor.height)
      }
      val near = math.min(state.height, height)
      shared(near).orElse(if (state.base < near) shared(state.base) else None).map { case (headers, ancestor) =>
        headers.filter(_.height > ancestor).foldLeft(state.rolledBackTo(ancestor).get)(apply)
      }
    }
}
