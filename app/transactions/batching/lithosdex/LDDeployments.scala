package transactions.batching.lithosdex

import lithosdex.contracts.{LDContractKind, LDContracts, LithosDexContracts}
import node.NodeApi
import node.model.{MempoolOptions, Paging, SortDirection}
import org.ergoplatform.appkit.NetworkType
import org.ergoplatform.sdk.ErgoId
import sigma.ast.SCollection.SByteArray
import sigma.ast.{Constant, ErgoTree, SType}

import scala.util.Try

/**
 * Checks that a pool box belongs to a LithosDex deployment before anything is built against it.
 *
 * Every deployment's contracts are the same four templates with its own ids put in. A pool box is one
 * deployment's only when the contracts built from its own ids (pool NFT at tokens(0), provision token at
 * tokens(2), vault NFT from its tree's CONST_VAULT_NFT) reproduce its tree exactly, and its vault is the
 * only box holding the vault NFT, at the vault tree built from the same ids. The pool tree never names
 * the provision token, so only the vault check ties it to the deployment.
 *
 * It cannot tell how a pool was minted: provision tokens kept back at genesis can be forged into provisions.
 * That matters to whoever lists a pool, not to an executor, which only builds what the contracts accept.
 */
object LDDeployments {

  /**
   * The deployment a pool box belongs to, if the contracts built from its own ids reproduce its tree and
   * it holds exactly three distinct tokens, the first a single unit. No node reads and no compiling.
   */
  def verify(networkType: NetworkType, ergoTreeHex: String, assets: Seq[(String, Long)]): Option[LDContracts] = {
    val tree = ergoTreeHex.toLowerCase
    for {
      (poolNft, poolAmount) <- assets.headOption
      if poolAmount == 1L && assets.size == 3 && assets.map(_._1.toLowerCase).distinct.size == 3
      parsed <- Try(ErgoTree.fromHex(tree)).toOption
      vault <- parsed.constants.lift(VaultNftIndex).flatMap(bytesOf) if vault.length == 32
      contracts <- Try(LDContracts(ErgoId.create(poolNft), new ErgoId(vault), ErgoId.create(assets(2)._1),
        networkType)).toOption
      if contracts.liquidityPool.ergoTreeHex == tree
    } yield contracts
  }

  /** The deployment's vault: the only unspent box carrying its NFT, at the vault tree built from its ids. */
  def vaultStands(nodeApi: NodeApi, contracts: LDContracts): Boolean = {
    val boxes = nodeApi.unspentBoxesByTokenId(contracts.vaultNFT.toString, Paging(0, 2), SortDirection.Desc,
      MempoolOptions.ConfirmedOnly).get
    boxes.size == 1 && boxes.head.box.ergoTree.equalsIgnoreCase(contracts.feeVault.ergoTreeHex)
  }

  /** Where CONST_VAULT_NFT sits among a pool tree's constants; the same in every deployment. */
  private lazy val VaultNftIndex: Int =
    LithosDexContracts.indexOf(LDContractKind.LiquidityPool, LithosDexContracts.VAULT_NFT)

  private def bytesOf(constant: Constant[SType]): Option[Array[Byte]] =
    if (constant.tpe != SByteArray) None
    else constant.value match {
      case coll: sigma.Coll[_] => Try(coll.asInstanceOf[sigma.Coll[Byte]].toArray).toOption
      case _ => None
    }
}
