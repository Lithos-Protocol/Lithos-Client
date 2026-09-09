package transactions.dex

import lithosdex.contracts.LDContracts
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue}
import work.lithos.mutations.InputUTXO

import scala.collection.JavaConverters._

/**
 * The LithosDex contract set, compiled once for the life of the JVM.
 *
 * The same reasoning as [[transactions.ProtocolContracts]]: the sigma compiler mutates `_sourceContext`
 * on shared AST nodes, so compiling one script twice in a run can throw. It also keeps compilation off
 * the request path — every read-only endpoint needs the pool address to find the pool box.
 *
 */
object DexContracts {

  private var compiled: Option[LDContracts] = None

  def apply(ctx: BlockchainContext): LDContracts = synchronized {
    compiled.getOrElse {
      val all = LDContracts(ctx)
      compiled = Some(all)
      all
    }
  }

  /**
   * Attaches context vars correctly to deal with HashMap issue.
   *
   * Check `ContextExtensionOrderSpec` for explanation.
   */
  def attachCtxVars(box: InputUTXO, vars: Seq[(Byte, ErgoValue[_])]): InputUTXO = {
    require(vars.map(_._1).distinct.size == vars.size, s"duplicate context var id in ${vars.map(_._1)}")

    // Ascending by id, which is the order the node rebuilds the extension in. Signing in any other
    // order signs a different byte string than the one the node verifies: contract-only inputs still
    // pass, because they reduce to a constant, while every real signature on the same transaction
    // fails with `Success((false, <cost>))` and the transaction takes a different id.
    vars.sortBy(_._1).foldLeft(box) { case (acc, (id, value)) => acc.withCtxVar(id, value) }
  }
}
