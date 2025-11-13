package utils

import org.ergoplatform.appkit._
import scorex.crypto.hash.Blake2b256

import sigmastate.eval.CostingSigmaDslBuilder.Colls

import work.lithos.mutations.Contract

import scala.io.Source

object ScriptGenerator {
  private final val BASE_PATH = "src/main/resources/contracts/"
  private final val EXT = ".ergo"

  private final val COLLAT = "collateral/"

  def mkSigTrue(ctx: BlockchainContext): Contract = {
    Contract.fromErgoScript(ctx, ConstantsBuilder.empty(), " { sigmaProp(true) } ")
  }

  def mkCollatScript(name: String): String = {
    val src = Source.fromFile(BASE_PATH + COLLAT + name + EXT)
    val script = src.mkString
    src.close()
    script
  }

}
