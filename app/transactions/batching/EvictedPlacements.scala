package transactions.batching

import node.NodeApi
import org.slf4j.{Logger, LoggerFactory}
import state.synchronization.CompleteMempool

import java.util.concurrent.atomic.AtomicReference
import scala.util.{Failure, Success, Try}

/**
 * Wallet placements a batcher carried into a candidate, held so later builds can carry them again.
 *
 * A candidate request that carries a transaction the node also holds in its mempool makes the node evict
 * that transaction and refuse it for hours. The next observation then lacks the placement, so the order
 * it created cannot be executed. This holds up to [[EvictedPlacements.Capacity]] carried placements, and
 * adds back into an observation the ones it lacks, while their inputs are unspent and unclaimed. The
 * adapter's own placement checks then run on them like on any other.
 *
 * Removable once nodes no longer evict requested transactions: delete this file, `Batcher.evictedPlacements`,
 * and the `restore` and `remember` calls in each adapter's `executions`.
 */
final class EvictedPlacements {

  import EvictedPlacements._

  private val logger: Logger = LoggerFactory.getLogger("EvictedPlacements")

  /** Held placements in the order they were first carried, so a parent precedes its child. */
  private val held = new AtomicReference(Vector.empty[Held])

  /**
   * `snapshot` with every held placement it lacks added back, when all of that placement's inputs are
   * unspent and no transaction in `snapshot` claims one. A placement that fails this, or has gone
   * [[MaxIdleBlocks]] without being carried, is forgotten. A failed input read restores nothing and
   * forgets nothing.
   */
  def restore(snapshot: CompleteMempool.Snapshot, blockHeight: Int, nodeApi: NodeApi): CompleteMempool.Snapshot = {
    val live = held.updateAndGet(current => current.filter(_.carriedWithin(blockHeight)))
    val missing = live.map(_.tx).filterNot(tx => snapshot.ids.contains(tx.id))
    val inputIds = missing.flatMap(_.body.inputs.map(_.boxId)).distinct
    val unspent =
      if (inputIds.isEmpty) Success(Set.empty[String])
      else Try(nodeApi.boxesWithPoolByIds(inputIds).get.map(_.boxId).toSet)
    unspent match {
      case Failure(ex) =>
        logger.warn(s"Could not read the inputs of ${missing.size} evicted placement(s), not restoring them: ${ex.getMessage}")
        snapshot
      case Success(available) =>
        // A child's input may be the output of a parent restored just before it
        val restored = missing.foldLeft(Vector.empty[CompleteMempool.MempoolTx]) { (kept, tx) =>
          val created = kept.iterator.flatMap(_.body.outputs.map(_.boxId)).toSet
          val carryable = tx.body.inputs.forall(input =>
            (available.contains(input.boxId) || created.contains(input.boxId)) && !snapshot.spent.contains(input.boxId))
          if (carryable) kept :+ tx else kept
        }
        val forgotten = missing.map(_.id).toSet -- restored.map(_.id)
        if (forgotten.nonEmpty) held.updateAndGet(current => current.filterNot(h => forgotten.contains(h.tx.id)))
        if (restored.isEmpty) snapshot
        else snapshot.copy(
          ids = snapshot.ids ++ restored.map(_.id),
          spent = snapshot.spent ++ restored.flatMap(_.body.inputs.map(_.boxId)),
          transactions = snapshot.transactions ++ restored)
    }
  }

  /**
   * Holds the placements a build carried for `blockHeight`, each once: runs on different pools can carry
   * the same ancestor, and two copies restored would read as two claims on its inputs. A full store takes
   * no new ones.
   */
  def remember(placements: Seq[CompleteMempool.MempoolTx], blockHeight: Int): Unit =
    if (placements.nonEmpty) held.updateAndGet { current =>
      val carried = placements.map(_.id).toSet
      val refreshed = current.map(h =>
        if (carried.contains(h.tx.id)) h.copy(carriedAt = math.max(h.carriedAt, blockHeight)) else h)
      val added = placements.foldLeft(Vector.empty[CompleteMempool.MempoolTx]) { (kept, tx) =>
        if (kept.exists(_.id == tx.id) || current.exists(_.tx.id == tx.id)) kept else kept :+ tx
      }.take(math.max(0, Capacity - current.size))
      refreshed ++ added.map(Held(_, blockHeight))
    }

  /** Ids of the placements held now. Package-private so a spec can see what is held without restoring it. */
  private[batching] def heldIds: Set[String] = held.get.map(_.tx.id).toSet
}

object EvictedPlacements {

  /** Placements held at once. */
  final val Capacity = 10

  /** Blocks a held placement is kept without being carried again. */
  final val MaxIdleBlocks = 30

  private final case class Held(tx: CompleteMempool.MempoolTx, carriedAt: Int) {
    def carriedWithin(blockHeight: Int): Boolean = blockHeight - carriedAt <= MaxIdleBlocks
  }
}
