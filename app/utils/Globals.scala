package utils

import configs.NodeConfig
import lfsm.MDDatabase
import mining.MiningStratumServer
import nisp.NISPDatabase
import play.api.Configuration
import state.messages.SyncMessages.{Ready, Starting, SyncStatus}
import stratum.data.Options

import java.math.BigInteger

/** Process-wide holders retained for legacy callers outside dependency injection. */
object Globals {
  // Database singletons
  val nispDB = new NISPDatabase
  val mdDB   = new MDDatabase
  // Lazily initialized runtime configuration
  private var nodeConfig: Option[NodeConfig] = None

  // Compatibility flags mirrored from the detailed synchronization status
  @volatile private var synced: Boolean = false
  @volatile private var mdSynced: Boolean = false
  @volatile private var syncStatus: SyncStatus = Starting
  def setSynced(): Unit = {
    synced = true
  }

  def getSyncState: Boolean = {
    synced
  }

  def setMDSynced(): Unit = {
    mdSynced = true
  }

  def getMDSyncState: Boolean = mdSynced

  def setSyncStatus(status: SyncStatus): Unit = synchronized {
    syncStatus = status
    val ready = status.isInstanceOf[Ready]
    synced = ready
    mdSynced = ready
  }

  def getDetailedSyncStatus: SyncStatus = syncStatus



  // Initialize once so all consumers share the same prover and secret storage.
  def setConfigs(config: Configuration): Unit = synchronized {
    if (nodeConfig.isDefined)
      throw new IllegalStateException(
        "NodeConfig is already initialised — there must be exactly one NodeContext")
    nodeConfig = Some(new NodeConfig(config))
  }

  def getNodeConfig: NodeConfig = nodeConfig.get
}
