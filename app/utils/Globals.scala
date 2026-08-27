package utils

import configs.NodeConfig
import lfsm.MDDatabase
import mining.MiningStratumServer
import nisp.NISPDatabase
import play.api.Configuration
import state.messages.SyncMessages.SyncStatus
import state.messages.SyncView
import stratum.data.Options

import java.math.BigInteger

/** Process-wide holders retained for legacy callers outside dependency injection. */
object Globals {
  // Database singletons
  val nispDB = new NISPDatabase
  val mdDB   = new MDDatabase
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
