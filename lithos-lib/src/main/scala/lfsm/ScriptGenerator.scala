package lfsm

import org.ergoplatform.appkit._
import sigma.util.NBitsUtils
import work.lithos.mutations.Contract

import scala.io.Source

object ScriptGenerator {
  var BASE_PATH = "C:/Users/Kirat/IdeaProjects/Lithos-Client/conf/scripts/"
  private final val EXT = ".ergo"

  private final val COLLAT = "collateral/"
  private final val ROLLUP = "rollup/"

  def mkSigTrue(ctx: BlockchainContext): Contract = {
    Contract.fromErgoScript(ctx, ConstantsBuilder.empty(), " { sigmaProp(true) } ")
  }

  def mkCollatScript(name: String): String = {
    val src = Source.fromFile(BASE_PATH + COLLAT + name + EXT)
    val script = src.mkString
    src.close()
    script
  }

  def mkRollupScript(name: String): String = {
    val src = Source.fromFile(BASE_PATH + ROLLUP + name + EXT)
    val script = src.mkString
    src.close()
    script
  }

}
