package lfsm

import org.ergoplatform.appkit._
import sigma.util.NBitsUtils
import work.lithos.mutations.Contract

import scala.io.Source

object ScriptGenerator {
  var BASE_PATH = ""
  private final val SCRIPT_PATH = "scripts/"
  private final val EXT = ".ergo"

  private final val COLLAT = "collateral/"
  private final val ROLLUP = "rollup/"

  def mkSigTrue(ctx: BlockchainContext): Contract = {
    Contract.fromErgoScript(ctx, ConstantsBuilder.empty(), " { sigmaProp(true) } ")
  }

  def mkCollatScript(name: String): String = {
    val src = Source.fromResource(COLLAT + name + EXT)
    val script = src.mkString
    src.close()
    script
  }

  def mkRollupScript(name: String): String = {
    val src = Source.fromResource(ROLLUP + name + EXT)
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
