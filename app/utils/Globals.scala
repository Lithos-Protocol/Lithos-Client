package utils

import configs.NodeConfig
import lfsm.MDDatabase
import mining.MiningStratumServer
import nisp.NISPDatabase
import play.api.Configuration
import stratum.data.Options

import java.math.BigInteger

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
  private var mdSynced: Boolean = false
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



  // Should be set in eager singleton
  def setConfigs(config: Configuration): Unit = {
    nodeConfig = Some(new NodeConfig(config))
  }

  def getNodeConfig: NodeConfig = nodeConfig.get

  // TODO: Make PayoutCache
  final val TRACKED_PAYOUTS = "TRACKED_PAYOUTS"
}
