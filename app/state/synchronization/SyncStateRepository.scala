package state.synchronization

import lfsm.MDDatabase

import scala.util.control.NonFatal

/**
 * Publishes the durable part of committed state: this miner's Miner Dictionary data-box token.
 * Everything else consumers read travels in `Globals.syncView` or in the message they were given.
 */
final class SyncStateRepository(dataBox: MDDatabase) {

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
    }

  /** Drops durable state, for a full rescan. */
  def clear(): Either[String, Unit] =
    guard("state reset") {
      if (!dataBox.delDataBoxToken)
        throw new IllegalStateException("Miner Dictionary data-token deletion was rejected")
    }

  private def guard(what: String)(publish: => Unit): Either[String, Unit] =
    try {
      publish
      Right(())
    } catch {
      case NonFatal(ex) => Left(s"$what failed: ${Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName)}")
    }
}

object SyncStateRepository {
  def apply(dataBox: MDDatabase): SyncStateRepository = new SyncStateRepository(dataBox)
}
