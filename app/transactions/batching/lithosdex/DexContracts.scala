package transactions.batching.lithosdex

import lithosdex.contracts.LDContracts
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue, NetworkType}
import org.ergoplatform.sdk.ErgoId
import work.lithos.mutations.InputUTXO

import scala.jdk.CollectionConverters.iterableAsScalaIterableConverter

/**
 * The LithosDex contract sets the builders use: the canonical deployment `LDHelpers` names, kept for the
 * lifetime of the JVM, and the other deployments currently served, as [[serve]] last set them.
 *
 * The builders look a deployment up by the ids of the box they are building, so a successor, vault or
 * provision is always built under the contracts of the pool it belongs to. An id no served deployment
 * holds throws rather than falling back to the canonical contracts.
 */
object DexContracts {

  private var canonical: Option[LDContracts] = None

  /** Served deployments other than the canonical one, by pool NFT. Replaced whole by [[serve]]. */
  @volatile private var verified: Map[String, LDContracts] = Map.empty

  def apply(ctx: BlockchainContext): LDContracts = apply(ctx.getNetworkType)

  def apply(networkType: NetworkType): LDContracts = synchronized {
    canonical.getOrElse {
      val all = LDContracts(networkType)
      canonical = Some(all)
      all
    }
  }

  /**
   * Makes exactly `deployments` available to the builders, replacing those served before, and returns the
   * ones kept. Call only with contracts checked against the chain. One sharing its pool NFT, vault NFT or
   * provision token with the canonical deployment or another is left out, so every id resolves to one.
   */
  def serve(networkType: NetworkType, deployments: Seq[LDContracts]): Seq[LDContracts] = {
    val all = apply(networkType) +: deployments
    def shared(id: LDContracts => ErgoId): Set[String] =
      all.groupBy(contracts => id(contracts).toString).collect { case (key, group) if group.size > 1 => key }.toSet
    val (pools, vaults, provTokens) = (shared(_.poolNFT), shared(_.vaultNFT), shared(_.provToken))
    val kept = deployments.filterNot(contracts => pools(contracts.poolNFT.toString) ||
      vaults(contracts.vaultNFT.toString) || provTokens(contracts.provToken.toString))
    verified = kept.map(contracts => contracts.poolNFT.toString -> contracts).toMap
    kept
  }

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
      throw new IllegalStateException(s"no served LithosDex deployment holds $what"))
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
