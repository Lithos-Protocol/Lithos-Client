package transactions.batching.lithosdex

import lithosdex.contracts.LDContracts
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue, NetworkType}
import work.lithos.mutations.InputUTXO

import scala.jdk.CollectionConverters.iterableAsScalaIterableConverter

/**
 * The canonical LithosDex deployment `LDHelpers` names, built once for the lifetime of the JVM.
 *
 * Other deployments are never looked up here: the batcher hands each builder the contracts of the pool
 * it is building, so no shared table can drop a pool another build is still using.
 */
object DexContracts {

  @volatile private var canonical: Option[LDContracts] = None

  def apply(ctx: BlockchainContext): LDContracts = apply(ctx.getNetworkType)

  /** Built under the lock once; read without it after, so a candidate build never waits on it. */
  def apply(networkType: NetworkType): LDContracts = canonical.getOrElse(synchronized {
    canonical.getOrElse {
      val all = LDContracts(networkType)
      canonical = Some(all)
      all
    }
  })

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
