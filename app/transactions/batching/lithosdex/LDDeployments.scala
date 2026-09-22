package transactions.batching.lithosdex

import lithosdex.contracts.LDContracts
import node.NodeApi
import node.model.{MempoolOptions, NodeBox, Paging, SortDirection}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.NetworkType
import org.ergoplatform.sdk.ErgoId
import org.slf4j.{Logger, LoggerFactory}
import scorex.crypto.hash.Blake2b256
import sigma.ast.SCollection.SByteArray
import sigma.ast.{Constant, ErgoTree, SType}

import scala.collection.concurrent.TrieMap
import scala.util.Try

/**
 * LithosDex deployments other than the canonical one, found on chain and checked before anything is built
 * against them.
 *
 * Every deployment's pool has the same ErgoTree template; only its two constants differ. So a template scan
 * lists every pool, genuine or not. A box found there is served only when the contracts compiled from its own
 * ids (pool NFT at tokens(0), provision token at tokens(2), vault NFT from the tree's CONST_VAULT_NFT) give
 * back exactly its tree, and its vault box sits alone at the compiled vault tree. That rules out a pool
 * pointing at some other guard or vault.
 *
 * It cannot tell how a pool was minted: provision tokens kept back at genesis can be forged into provisions.
 * That matters to whoever lists a pool, not to an executor, which only builds what the contracts accept.
 */
object LDDeployments {

  private val logger: Logger = LoggerFactory.getLogger("LDDeployments")

  private final val PageSize = 100

  /** New compiles per refresh, so boxes spammed at the template cannot stall a scan. */
  private final val MaxCompilesPerRefresh = 8

  /** Compiled deployments by (pool NFT, vault NFT, provision token); None where the compile failed. */
  private val compiled = TrieMap.empty[(String, String, String), Option[LDContracts]]

  private val vaultConstantIndices = TrieMap.empty[NetworkType, Int]

  /** Node indexer hash of the pool template: Blake2b256 over its bytes. The public explorer uses SHA-256. */
  def poolTemplateHash(networkType: NetworkType): String =
    Hex.toHexString(Blake2b256.hash(DexContracts(networkType).liquidityPool.ergoTree.template))

  /**
   * The deployment a pool box at the template belongs to, if its contracts compile back to exactly its tree.
   * No node reads. With `mayCompile` false, a deployment not compiled before is None.
   */
  def verify(networkType: NetworkType, ergoTreeHex: String, assets: Seq[(String, Long)],
             mayCompile: Boolean = true): Option[LDContracts] = {
    val tree = ergoTreeHex.toLowerCase
    for {
      (poolNft, poolAmount) <- assets.headOption if poolAmount == 1L && assets.size >= 3
      provToken = assets(2)._1
      parsed <- Try(ErgoTree.fromHex(tree)).toOption
      vault <- parsed.constants.lift(vaultConstantIndex(networkType)).flatMap(bytesOf) if vault.length == 32
      key = (poolNft, Hex.toHexString(vault), provToken)
      contracts <- compiled.get(key) match {
        case Some(known) => known
        case None if mayCompile =>
          val result = Try(DexContracts.compile(networkType, ErgoId.create(key._1), ErgoId.create(key._2),
            ErgoId.create(key._3))).toOption
          compiled.put(key, result)
          if (!result.exists(_.liquidityPool.ergoTreeHex == tree))
            logger.info(s"Not serving pool $poolNft: its contracts, compiled from its own ids, do not match its tree")
          result
        case None => None
      }
      if contracts.liquidityPool.ergoTreeHex == tree
    } yield contracts
  }

  /** Every deployment at the template that passes [[verify]] and whose vault stands, less `denied` pool NFTs. */
  def discover(networkType: NetworkType, nodeApi: NodeApi, maxPools: Int, denied: Set[String]): Vector[LDContracts] = {
    val hash = poolTemplateHash(networkType)
    var boxes = Vector.empty[NodeBox]
    var exhausted = false
    while (!exhausted && boxes.size < maxPools) {
      val page = nodeApi.unspentBoxesByTemplateHash(hash, Paging(boxes.size, PageSize), SortDirection.Desc,
        MempoolOptions.ConfirmedOnly).get
      boxes ++= page.map(_.box)
      exhausted = page.size < PageSize
    }

    var compiles = 0
    boxes.take(maxPools)
      .filterNot(box => box.assets.headOption.exists(asset => denied.contains(asset.tokenId.toLowerCase)))
      .flatMap { box =>
        val assets = box.assets.map(asset => asset.tokenId -> asset.amount)
        verify(networkType, box.ergoTree, assets, mayCompile = false).orElse {
          // Left for the next refresh once the budget is spent, not refused
          if (compiles >= MaxCompilesPerRefresh) None
          else { compiles += 1; verify(networkType, box.ergoTree, assets) }
        }
      }
      .filter(vaultStands(nodeApi, _))
      .groupBy(_.poolNFT.toString).values.map(_.head).toVector
  }

  /** The deployment's vault: the only unspent box carrying its NFT, at its compiled tree. */
  private def vaultStands(nodeApi: NodeApi, contracts: LDContracts): Boolean =
    nodeApi.unspentBoxesByTokenId(contracts.vaultNFT.toString, Paging(0, 2), SortDirection.Desc,
      MempoolOptions.ConfirmedOnly).toOption.exists(boxes =>
      boxes.size == 1 && boxes.head.box.ergoTree.equalsIgnoreCase(contracts.feeVault.ergoTreeHex))

  /** Where CONST_VAULT_NFT sits among the pool tree's constants; the same in every deployment. */
  private def vaultConstantIndex(networkType: NetworkType): Int =
    vaultConstantIndices.getOrElseUpdate(networkType, locateVaultConstant(networkType))

  private def locateVaultConstant(networkType: NetworkType): Int = {
    val canonical = DexContracts(networkType)
    val vault = canonical.vaultNFT.getBytes
    val found = canonical.liquidityPool.ergoTree.constants.indices
      .filter(i => bytesOf(canonical.liquidityPool.ergoTree.constants(i)).exists(java.util.Arrays.equals(_, vault)))
    require(found.size == 1, s"the canonical pool tree holds its vault NFT ${found.size} times, expected once")
    found.head
  }

  private def bytesOf(constant: Constant[SType]): Option[Array[Byte]] =
    if (constant.tpe != SByteArray) None
    else constant.value match {
      case coll: sigma.Coll[_] => Try(coll.asInstanceOf[sigma.Coll[Byte]].toArray).toOption
      case _ => None
    }
}
