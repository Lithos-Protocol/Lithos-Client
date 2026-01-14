package utils

import configs.NodeConfig
import lfsm.MDDatabase
import nisp.NISPDatabase
import play.api.Configuration

// Accessor for global methods and variables
// Not DI, so instantiated lazily
object Globals {
  // Databases
  val nispDB = new NISPDatabase
  val mdDB   = new MDDatabase
  // Complex Configs
  private var nodeConfig: Option[NodeConfig] = None

  // Sync State
  private var synced: Boolean = false

  def setSynced(): Unit = {
    synced = true
  }

  def getSyncState: Boolean = {
    synced
  }
  // Should be set in eager singleton
  def setConfigs(config: Configuration): Unit = {
    nodeConfig = Some(new NodeConfig(config))
  }

  def getNodeConfig: NodeConfig = nodeConfig.get

  // TODO: Make PayoutCache
  final val TRACKED_PAYOUTS = "TRACKED_PAYOUTS"
}
