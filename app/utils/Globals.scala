package utils

import configs.NodeConfig
import lfsm.MDDatabase
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
  private var pdSynced: Boolean = false

  def setPDSynced(): Unit = {
    pdSynced = true
  }

  def getPDSyncState: Boolean = {
    pdSynced
  }

  def setSynced(): Unit = {
    synced = true
  }

  def getSyncState: Boolean = {
    synced
  }

  // Stratum Options
  private var stratumOptions: Option[Options] = None

  def setStratumOptions(options: Options): Unit = stratumOptions = Some(options)

  def getTau: Option[BigInteger] = stratumOptions.map(_.getTau)
  // Return true if tau value is set
  def setTau(tau: BigInt): Boolean = {
    if(stratumOptions.isDefined){
      stratumOptions.get.setTau(tau.bigInteger)
      true
    }else{
      false
    }
  }


  // Should be set in eager singleton
  def setConfigs(config: Configuration): Unit = {
    nodeConfig = Some(new NodeConfig(config))
  }

  def getNodeConfig: NodeConfig = nodeConfig.get

  // TODO: Make PayoutCache
  final val TRACKED_PAYOUTS = "TRACKED_PAYOUTS"
}
