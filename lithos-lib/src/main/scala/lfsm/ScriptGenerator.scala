package lfsm

import org.ergoplatform.appkit._
import sigma.util.NBitsUtils
import work.lithos.mutations.{Contract, Mutator}

import scala.io.Source

object ScriptGenerator {
  var BASE_PATH = ""
  private final val SCRIPT_PATH = "scripts/"
  private final val EXT = ".ergo"

  private final val COLLAT = "collateral/"
  private final val ROLLUPS = "rollups/"
  private final val FRAUD_PROOFS = "fraudproofs/"
  private final val DICTIONARIES = "dictionaries/"
  private final val PLASMA_DEX = "plasmadex/"
  private final val LITHOS_DEX = "lithosdex/"

  def mkSigTrue(ctx: BlockchainContext): Contract = {
    mkSigTrue(ctx.getNetworkType)
  }

  def mkSigTrue(networkType: NetworkType): Contract = {
    Contract.fromErgoScript(networkType, ConstantsBuilder.empty(), " { sigmaProp(true) } ", Seq.empty[Mutator])
  }

  def mkCollatScript(name: String): String = {
    val src = Source.fromResource(COLLAT + name + EXT)
    val script = src.mkString
    src.close()
    script
  }

  def mkRollupScript(name: String): String = {
    val src = Source.fromResource(ROLLUPS + name + EXT)
    val script = src.mkString
    src.close()
    script
  }

  def mkFraudProofScript(name: String): String = {
    val src = Source.fromResource(FRAUD_PROOFS + name + EXT)
    val script = src.mkString
    src.close()
    script
  }

  def mkDictionaryScript(name: String): String = {
    val src = Source.fromResource(DICTIONARIES + name + EXT)
    val script = src.mkString
    src.close()
    script
  }


  def mkPlasmaDexScript(name: String): String = {
    val src = Source.fromResource(PLASMA_DEX + name + EXT)
    val script = src.mkString
    src.close()
    script
  }

  def mkLithosDexScript(name: String): String = {
    val src = Source.fromResource(LITHOS_DEX + name + EXT)
    val script = src.mkString
    src.close()
    script
  }

  private def mkBasePath: String = {
    if(BASE_PATH.last != '/') {
      BASE_PATH = BASE_PATH + "/"
      BASE_PATH
    } else
      BASE_PATH
  }
}
