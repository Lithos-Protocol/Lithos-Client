package utils

import configs.NodeConfig
import lfsm.MDDatabase
import mining.MiningStratumServer
import nisp.NISPDatabase
import org.slf4j.LoggerFactory
import play.api.Configuration
import state.messages.SyncMessages.SyncStatus
import state.messages.SyncView
import stratum.data.Options

import java.math.BigInteger
import scala.util.{Failure, Success, Try}

/** Process-wide holders retained for legacy callers outside dependency injection. */
object Globals {
  // Database singletons
  val nispDB = new NISPDatabase
  /**
   * Opened and migrated here, before any reader or writer of the store exists. Migrating inside a
   * consumer races the one publish that rewrites the data-box token.
   *
   * A failure is logged and left for the next start: this runs in an object initializer, where a
   * throw makes every later use of Globals fail with NoClassDefFoundError.
   */
  val mdDB   = {
    val db = new MDDatabase
    val log = LoggerFactory.getLogger("Globals")
    Try(db.migrate()) match {
      case Success(true) =>
        log.warn(s"Miner Dictionary store was below schema ${MDDatabase.Schema} and has been " +
          "cleared; it refills as synchronization republishes")
      case Success(false) => ()
      case Failure(ex) =>
        log.error(s"Could not migrate the Miner Dictionary store to schema ${MDDatabase.Schema}: " +
          s"${ex.getMessage}. It is left untouched and retried on the next start", ex)
    }
    db
  }
  // Lazily initialized runtime configuration
  private var nodeConfig: Option[NodeConfig] = None

  /**
   * Current sync state and overview published by its owner and accessible globally
   */
  @volatile private var view: SyncView = SyncView.initial

  def setSyncView(next: SyncView): Unit = view = next

  def syncView: SyncView = view

  def getDetailedSyncStatus: SyncStatus = view.status



  // Initialize once so all consumers share the same prover and secret storage.
  def setConfigs(config: Configuration): Unit = synchronized {
    if (nodeConfig.isDefined)
      throw new IllegalStateException(
        "NodeConfig is already initialised — there must be exactly one NodeContext")
    nodeConfig = Some(new NodeConfig(config))
  }

  def getNodeConfig: NodeConfig = nodeConfig.get
}
