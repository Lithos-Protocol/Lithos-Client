package transactions.dex

import lithosdex.contracts.LDContracts
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue}
import work.lithos.mutations.InputUTXO

import scala.jdk.CollectionConverters.iterableAsScalaIterableConverter

/** Caches the compiled LithosDex contract set for the lifetime of the JVM. */
object DexContracts {

  private var compiled: Option[LDContracts] = None

  def apply(ctx: BlockchainContext): LDContracts = synchronized {
    compiled.getOrElse {
      val all = LDContracts(ctx)
      compiled = Some(all)
      all
    }
  }

  /** Attaches unique context variables in ascending ID order. */
  def attachCtxVars(box: InputUTXO, vars: Seq[(Byte, ErgoValue[_])], useWireOrder: Boolean = false): InputUTXO = {
    require(vars.map(_._1).distinct.size == vars.size, s"duplicate context var id in ${vars.map(_._1)}")
    if(!useWireOrder)
      vars.sortBy(_._1).foldLeft(box) { case (acc, (id, value)) => acc.withCtxVar(id, value) }
    else {
      val probe = new java.util.HashMap[String, String](vars.size)
      vars.foreach { case (id, _) => probe.put(id.toString, "") }
      val wireOrder = probe.keySet().asScala.toSeq.map(_.toByte)

      wireOrder.foldLeft(box) { (acc, id) =>
        acc.withCtxVar(id, vars.find(_._1 == id).get._2)
      }
    }
  }
}
