package transactions.batching.lithosdex

import lithosdex.contracts.LDContracts
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue, NetworkType}
import org.ergoplatform.sdk.ErgoId
import work.lithos.mutations.InputUTXO

import scala.collection.concurrent.TrieMap
import scala.jdk.CollectionConverters.iterableAsScalaIterableConverter

/**
 * Caches the compiled LithosDex contract sets for the lifetime of the JVM: the canonical deployment
 * `LDHelpers` names, and every other deployment [[LDDeployments]] has verified on chain.
 *
 * The builders look a deployment up by the ids of the box they are building, so a successor, vault or
 * provision is always built under the contracts of the pool it belongs to. An id no verified deployment
 * holds throws rather than falling back to the canonical contracts.
 */
object DexContracts {

  private var compiled: Option[LDContracts] = None

  /** Verified deployments other than the canonical one, by pool NFT. Only [[register]] writes it. */
  private val verified = TrieMap.empty[String, LDContracts]

  def apply(ctx: BlockchainContext): LDContracts = apply(ctx.getNetworkType)

  def apply(networkType: NetworkType): LDContracts = synchronized {
    compiled.getOrElse {
      val all = LDContracts(networkType)
      compiled = Some(all)
      all
    }
  }

  /**
   * Compiles one deployment's contracts. Takes the same lock as the canonical compile: the Sigma
   * compiler mutates shared state during construction, so two compiles must never overlap.
   */
  def compile(networkType: NetworkType, poolNFT: ErgoId, vaultNFT: ErgoId, provToken: ErgoId): LDContracts =
    synchronized(LDContracts(poolNFT, vaultNFT, provToken, networkType))

  /** Makes a deployment available to the builders. Call only with contracts checked against the chain. */
  def register(contracts: LDContracts): Unit = verified.put(contracts.poolNFT.toString, contracts)

  /** The deployment whose pool NFT is `poolNFT`. */
  def forPool(networkType: NetworkType, poolNFT: ErgoId): LDContracts =
    find(networkType, s"pool NFT $poolNFT")(_.poolNFT.toString == poolNFT.toString)

  /** The deployment whose vault NFT is `vaultNFT`. */
  def forVault(networkType: NetworkType, vaultNFT: ErgoId): LDContracts =
    find(networkType, s"vault NFT $vaultNFT")(_.vaultNFT.toString == vaultNFT.toString)

  /** The deployment whose provision token is `provToken`. */
  def forProvToken(networkType: NetworkType, provToken: ErgoId): LDContracts =
    find(networkType, s"provision token $provToken")(_.provToken.toString == provToken.toString)

  private def find(networkType: NetworkType, what: String)(matches: LDContracts => Boolean): LDContracts = {
    val canonical = apply(networkType)
    if (matches(canonical)) canonical
    else verified.values.find(matches).getOrElse(
      throw new IllegalStateException(s"no verified LithosDex deployment holds $what"))
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
