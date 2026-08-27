package state.synchronization

import node.NodeApi
import node.model.NodeHeader
import state.messages.{BlockInfo, NodeSync}

import scala.util.{Failure, Success, Try}

/** Reads only blocks named by the node's canonical header chain. */
final class CanonicalBlockSource(nodeApi: NodeApi) {

  private val logger = org.slf4j.LoggerFactory.getLogger("CanonicalBlockSource")

  /**
   * Highest available height to read from. Indexed height is needed for input verification,
   * so it takes priority.
   */
  def tipHeight: Try[Int] =
    nodeApi.info().flatMap { info =>
      info.fullHeight match {
        case None => Failure(new IllegalStateException("Node did not report a full-block height"))
        case Some(full) => nodeApi.indexedHeight().map(index => math.min(full, index.indexedHeight))
      }
    }

  def headerAt(height: Int): Try[NodeHeader] =
    headerSlice(height, height).flatMap {
      case Seq(header) => Success(header)
      case Seq() => Failure(new NoSuchElementException(s"No canonical header at height $height"))
      case many => Failure(new IllegalStateException(
        s"Canonical chain slice returned ${many.size} headers at height $height"))
    }

  /**
   * Returns an inclusive, contiguous, parent-linked canonical header range.
   *
   * The node treats `fromHeight` as exclusive, so the request starts one height earlier.
   */
  def headerSlice(fromHeight: Int, toHeight: Int): Try[Seq[NodeHeader]] =
    if (toHeight < fromHeight) Success(Seq.empty)
    else nodeApi.chainSlice(Some(math.max(fromHeight - 1, 0)), Some(toHeight)).flatMap { headers =>
      val ordered = headers.filter(h => h.height >= fromHeight && h.height <= toHeight).sortBy(_.height)
      // Count and positions are separate checks.
      val expected = toHeight - fromHeight + 1
      val positioned = ordered.zipWithIndex.forall { case (h, i) => h.height == fromHeight + i }
      val linked = ordered.sliding(2).forall {
        case Seq(previous, next) => next.parentId == previous.id
        case _ => true
      }
      if (ordered.size != expected) Failure(new IllegalStateException(
        s"Canonical chain slice $fromHeight-$toHeight returned ${ordered.size} of $expected header(s): " +
          ordered.map(_.height).mkString(",")))
      else if (!positioned) Failure(new IllegalStateException(
        s"Canonical chain slice $fromHeight-$toHeight is not contiguous: " +
          ordered.map(_.height).mkString(",")))
      else if (!linked) Failure(new IllegalStateException(
        s"Canonical chain slice $fromHeight-$toHeight is not parent-linked"))
      else Success(ordered)
    }

  /** Indexed blocks for the given canonical headers, in header order, each matched to its header. */
  def blocksFor(headers: Seq[NodeHeader]): Try[Seq[BlockInfo]] =
    if (headers.isEmpty) Success(Seq.empty)
    else nodeApi.indexedBlocksByHeaderIds(headers.map(_.id)).flatMap { blocks =>
      val byId = blocks.map(block => block.header.id -> block).toMap
      headers.foldLeft(Try(Vector.empty[BlockInfo])) { (accumulated, header) =>
        accumulated.flatMap { collected =>
          byId.get(header.id) match {
            case None => Failure(new NoSuchElementException(
              s"Indexed node has no block for canonical header ${header.id} at height ${header.height}"))
            case Some(block) if block.header.height != header.height =>
              Failure(new IllegalStateException(
                s"Indexed block ${block.header.id}@${block.header.height} does not match " +
                  s"${header.id}@${header.height}"))
            case Some(block) => Success(collected :+ report(header, NodeSync.blockInfo(block)))
          }
        }
      }
    }

  def blockAt(height: Int): Try[BlockInfo] =
    for {
      header <- headerAt(height)
      blocks <- blocksFor(Seq(header))
      block <- blocks.headOption match {
        case Some(value) => Success(value)
        case None => Failure(new NoSuchElementException(
          s"Indexed node has no block for canonical header ${header.id} at height $height"))
      }
    } yield block

  /**
   * Reports suspicious indexed-block shapes without rejecting the block.
   *
   * Rejecting an unverified shape assumption would stall synchronization at that height.
   */
  private def report(header: NodeHeader, block: BlockInfo): BlockInfo = {
    if (block.txs.isEmpty)
      logger.warn(s"Indexed block ${header.id}@${header.height} decoded with no transactions. If this " +
        "repeats, the block endpoint is not returning the shape this client reads.")
    // Resolved inputs are derived from the same list as the transactions, so they cannot be absent
    // while inputs exist. What the index can return is input boxes stripped to their ids.
    else if (block.inputsLookStripped)
      logger.warn(s"Indexed block ${header.id}@${header.height} resolved ${block.resolvedInputs.size} " +
        "input box(es), none carrying a script. The caller decides whether to refetch.")
    block
  }

  def commonAncestor(cursors: Seq[state.messages.SyncMessages.SyncCursor]): Try[Option[state.messages.SyncMessages.SyncCursor]] =
    if (cursors.isEmpty) Success(None)
    else {
      val heights = cursors.map(_.height)
      headerSlice(heights.min, heights.max).flatMap { headers =>
        val byHeight = headers.filter(header => heights.contains(header.height)).groupBy(_.height)
        val ambiguous = byHeight.collect { case (height, values) if values.size != 1 => height }.toSeq.sorted
        val missing = heights.distinct.filterNot(byHeight.contains).sorted
        if (ambiguous.nonEmpty) Failure(new IllegalStateException(
          s"Canonical chain slice returned multiple headers at heights ${ambiguous.mkString(",")}"))
        else if (missing.nonEmpty) Failure(new NoSuchElementException(
          s"Canonical chain slice omitted retained heights ${missing.mkString(",")}"))
        else Success(cursors.reverse.find(cursor => byHeight(cursor.height).head.id == cursor.blockId))
      }
    }
}
