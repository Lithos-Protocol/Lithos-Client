package state.synchronization

import cache.{MDCache, RollupCache}
import lfsm.MDDatabase
import lfsm.states.NISPTree
import play.api.cache.SyncCacheApi
import state.messages.MempoolMessages.MempoolRollupState

import scala.util.control.NonFatal

/**
 * Publishes committed state to the rollup cache, Miner Dictionary cache, and local data-box store.
 *
 * These mirrors are not transactionally atomic; publication failures are returned to the state owner.
 */
final class SyncStateRepository(rollupCache: RollupCache,
                                mdCache: MDCache,
                                dataBox: MDDatabase) {

  /** Publishes the durable data token before updating the in-memory mirrors. */
  def publish(previous: Option[CommittedSyncState],
              next: CommittedSyncState): Either[String, Unit] =
    guard("state publication") {
      if (previous.flatMap(_.dataBoxToken) != next.dataBoxToken) {
        next.dataBoxToken match {
          case Some(token) if !dataBox.setDataBoxToken(token) =>
            throw new IllegalStateException("Miner Dictionary data-token write was rejected")
          case None if !dataBox.delDataBoxToken =>
            throw new IllegalStateException("Miner Dictionary data-token deletion was rejected")
          case _ => ()
        }
      }

      next.routedRollups.foreach { case (utxoId, tree) => rollupCache.set(utxoId, tree) }
      mdCache.setMD(next.minerTree)
      rollupCache.setTreeSet(next.routes.keySet)

      previous.toSeq.flatMap(_.routes.keys).filterNot(next.routes.contains).foreach(rollupCache.remove)
      previous.toSeq.flatMap(_.rollups.keys).foreach(rollupCache.removeMempoolState)
    }

  /** Drops every published mirror, for a full rescan. */
  def clear(previous: Option[CommittedSyncState]): Either[String, Unit] =
    guard("state reset") {
      previous.toSeq.flatMap(_.routes.keys).foreach(rollupCache.remove)
      previous.toSeq.flatMap(_.rollups.keys).foreach(rollupCache.removeMempoolState)
      rollupCache.setTreeSet(Set.empty)
      if (!dataBox.delDataBoxToken)
        throw new IllegalStateException("Miner Dictionary data-token deletion was rejected")
    }

  def setRollup(tree: NISPTree): Unit = rollupCache.set(tree.utxoId, tree)

  def setMempoolState(blockId: String, projection: MempoolRollupState): Unit =
    rollupCache.setMempoolState(blockId, projection)

  def removeMempoolState(blockId: String): Unit = rollupCache.removeMempoolState(blockId)

  private def guard(what: String)(publish: => Unit): Either[String, Unit] =
    try {
      publish
      Right(())
    } catch {
      case NonFatal(ex) => Left(s"$what failed: ${Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName)}")
    }
}

object SyncStateRepository {
  def apply(cacheApi: SyncCacheApi, dataBox: MDDatabase): SyncStateRepository =
    new SyncStateRepository(RollupCache(cacheApi), MDCache(cacheApi), dataBox)
}
