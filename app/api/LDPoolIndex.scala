package api

import akka.actor.ActorSystem
import configs.{Contexts, NodeContext, VerifiedPool, VerifiedRegistry}
import lithosdex.contracts.{LDContracts, LDT2TContracts}
import lithosdex.{LDGenesis, LDLiquidityPool, LDT2TGenesis, LDT2TLiquidityPool}
import node.MutationConversions._
import node.NodeApi
import node.model.{IndexedBox, MempoolOptions, Paging, SortDirection}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{BlockchainContext, NetworkType}
import org.slf4j.{Logger, LoggerFactory}
import scorex.crypto.hash.Blake2b256
import transactions.batching.lithosdex.{DexContracts, LDDeployments}

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.{Inject, Singleton}
import scala.concurrent.Future
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

/**
 * A pool's genesis verdict. `problems` make it unsafe; `warnings` make it risky. A pool built in any shape
 * but the one this client creates reports that its genesis cannot be checked, as a problem.
 */
final case class GenesisVerdict(problems: Seq[String], warnings: Seq[String])

/** Display facts for one token. `verified` means the shipped list names it; otherwise the minter chose them. */
final case class TokenMeta(name: Option[String], ticker: Option[String], decimals: Option[Int], verified: Boolean)

/**
 * One pool the index found, with the deployment its box verified as.
 *
 * @param tokenX  None for an ERG/token pool, whose X side is ERG
 * @param genesis None for a verified pool, which is checked by hand, and for one whose check could not be
 *                read this time
 */
final case class IndexedPool(kind: VerifiedPool.Kind,
                             nft: String,
                             boxId: String,
                             inclusionHeight: Int,
                             tokenX: Option[String],
                             tokenY: String,
                             reservesX: Long,
                             reservesY: Long,
                             pendingX: Long,
                             pendingY: Long,
                             supply: Long,
                             feeParams: Seq[Long],
                             provTokensLeft: Long,
                             vaultNft: String,
                             provToken: String,
                             verified: Boolean,
                             genesis: Option[GenesisVerdict],
                             n2t: Option[LDContracts],
                             t2t: Option[LDT2TContracts])

/**
 * @param status    `building` until the first index completes, then `ready`
 * @param height    the tip the snapshot was read at
 * @param truncated a template held more pool boxes than one scan reads
 */
final case class PoolIndexSnapshot(status: String, height: Int, truncated: Boolean, pools: Seq[IndexedPool],
                                   tokens: Map[String, TokenMeta])

/**
 * Every LithosDex pool on chain, of both kinds, for the API and the web panel. Never on a request's path: a
 * request reads the last snapshot, and a request that finds the tip moved past it starts one rebuild in the
 * background. A rebuild is one unspent scan per pool template; each pool is verified as the batcher verifies
 * one, and costs one vault read the first time it is seen. Genesis verdicts and token metadata never change,
 * so each is read once per process.
 */
@Singleton
class LDPoolIndex @Inject()(nodeContext: NodeContext, system: ActorSystem) {

  import LDPoolIndex._

  private val logger: Logger = LoggerFactory.getLogger("LDPoolIndex")
  private lazy val io = new Contexts(system).dexContext

  @volatile private var current = PoolIndexSnapshot("building", 0, truncated = false, Seq.empty, Map.empty)
  private val refreshing = new AtomicBoolean(false)

  private val verdicts = new ConcurrentHashMap[String, GenesisVerdict]()
  private val vaultsStanding = ConcurrentHashMap.newKeySet[String]()
  private val tokenMeta = new ConcurrentHashMap[String, TokenMeta]()

  private def nodeApi: NodeApi = nodeContext.getNodeApi
  private def network: NetworkType = nodeContext.getNetwork

  /** The last snapshot, starting a rebuild in the background when the tip has moved past it. */
  def snapshot(): PoolIndexSnapshot = {
    val tip = Try(nodeApi.info().get.fullHeight.getOrElse(0)).getOrElse(0)
    if (tip > current.height && refreshing.compareAndSet(false, true))
      Future {
        try current = build(tip)
        catch { case NonFatal(ex) => logger.warn(s"The LithosDEX pool index did not rebuild at $tip: ${ex.getMessage}") }
        finally refreshing.set(false)
      }(io)
    current
  }

  /**
   * `pool`'s genesis verdict, read now on the calling thread if no rebuild has read it yet, and kept. None for
   * a verified pool, and when the node could not answer.
   */
  def genesisNow(pool: IndexedPool): Option[GenesisVerdict] =
    if (pool.verified) None
    else pool.genesis.orElse(nodeContext.getClient.execute(ctx =>
      verdict(ctx, pool.nft, pool.vaultNft, pool.provToken, n2t = pool.kind == VerifiedPool.Kind.N2T)))

  /** Rebuilds now, on the calling thread, and keeps the result. */
  def refreshNow(): PoolIndexSnapshot = {
    val tip = nodeApi.info().get.fullHeight.getOrElse(0)
    current = build(tip)
    current
  }

  private[api] def build(tip: Int): PoolIndexSnapshot = nodeContext.getClient.execute { ctx =>
    val registry = VerifiedRegistry.forNetwork(network)
    val (n2tBoxes, n2tCut) = scan(n2tTemplateHash(network))
    val (t2tBoxes, t2tCut) = scan(t2tTemplateHash(network))
    val pools =
      n2tBoxes.flatMap(box => Try(n2tPool(ctx, box, registry)).getOrElse(None)) ++
        t2tBoxes.flatMap(box => Try(t2tPool(ctx, box, registry)).getOrElse(None))
    val sorted = pools.sortBy(p => (!p.verified, -p.inclusionHeight, p.nft))
    PoolIndexSnapshot("ready", tip, n2tCut || t2tCut, sorted, metaFor(sorted.flatMap(p => p.tokenX.toSeq :+ p.tokenY), registry))
  }

  /** Every unspent pool box at `templateHash`, newest first, to [[MaxPoolsPerKind]]; and whether that cut it short. */
  private def scan(templateHash: String): (Seq[IndexedBox], Boolean) = {
    var found = Vector.empty[IndexedBox]
    var paging = Paging(0, PageSize)
    var exhausted = false
    while (!exhausted && found.size < MaxPoolsPerKind) {
      val page = nodeApi.unspentBoxesByTemplateHash(templateHash, paging, SortDirection.Desc,
        MempoolOptions.ConfirmedOnly).get
      found ++= page
      exhausted = page.size < paging.limit
      paging = paging.next
    }
    if (!exhausted) logger.warn(s"More than $MaxPoolsPerKind LithosDEX pools share template $templateHash; indexing the newest")
    (found.take(MaxPoolsPerKind), !exhausted)
  }

  private def n2tPool(ctx: BlockchainContext, box: IndexedBox, registry: VerifiedRegistry): Option[IndexedPool] = {
    val assets = box.box.assets.map(a => a.tokenId -> a.amount)
    val canonical = DexContracts(network)
    val contracts =
      if (box.box.assets.headOption.exists(_.tokenId == canonical.poolNFT.toString) &&
        box.box.ergoTree.equalsIgnoreCase(canonical.liquidityPool.ergoTreeHex)) Some(canonical)
      else LDDeployments.verify(network, box.box.ergoTree, assets)
    contracts.filter(c => vaultStands(c.vaultNFT.toString, LDDeployments.vaultStands(nodeApi, c))).map { c =>
      val p = LDLiquidityPool(box.box.toInputUTXO(ctx))
      val verified = registry.pool(c.poolNFT.toString).exists(_.kind == VerifiedPool.Kind.N2T)
      IndexedPool(VerifiedPool.Kind.N2T, c.poolNFT.toString, box.boxId, box.inclusionHeight, None, p.tokenY.toString,
        p.reservesX, p.reservesY, p.pendingX, p.pendingY, p.supply, p.feeParams.toSeq, p.provTokensLeft,
        c.vaultNFT.toString, c.provToken.toString, verified,
        if (verified) None else verdict(ctx, c.poolNFT.toString, c.vaultNFT.toString, c.provToken.toString, n2t = true),
        Some(c), None)
    }
  }

  private def t2tPool(ctx: BlockchainContext, box: IndexedBox, registry: VerifiedRegistry): Option[IndexedPool] =
    LDDeployments.verifyT2T(box.box.ergoTree, box.box.assets.map(a => a.tokenId -> a.amount))
      .filter(c => vaultStands(c.vaultNFT.toString, LDDeployments.vaultStands(nodeApi, c))).map { c =>
        val p = LDT2TLiquidityPool(box.box.toInputUTXO(ctx))
        val verified = registry.pool(c.poolNFT.toString).exists(_.kind == VerifiedPool.Kind.T2T)
        IndexedPool(VerifiedPool.Kind.T2T, c.poolNFT.toString, box.boxId, box.inclusionHeight, Some(p.tokenX.toString),
          p.tokenY.toString, p.reservesX, p.reservesY, p.pendingX, p.pendingY, p.supply, p.feeParams.toSeq,
          p.provTokensLeft, c.vaultNFT.toString, c.provToken.toString, verified,
          if (verified) None else verdict(ctx, c.poolNFT.toString, c.vaultNFT.toString, c.provToken.toString, n2t = false),
          None, Some(c))
      }

  /** A vault singleton stays one once it stands, so only a first sighting costs the read. */
  private def vaultStands(vaultNft: String, check: => Boolean): Boolean =
    vaultsStanding.contains(vaultNft) || (check && vaultsStanding.add(vaultNft))

  /**
   * The genesis verdict for an unverified pool, read once and kept. The pool as created is the provision
   * token's mint box, and the vault is the output of that transaction holding the vault NFT. None when a
   * read failed, so the next rebuild tries again.
   */
  private def verdict(ctx: BlockchainContext, poolNft: String, vaultNft: String, provToken: String,
                      n2t: Boolean): Option[GenesisVerdict] =
    Option(verdicts.get(poolNft)).orElse(Try {
      def emission(id: String) = nodeApi.tokenById(id).get
        .getOrElse(throw new IllegalStateException(s"the node has no token $id"))
      val prov = emission(provToken)
      val pool = nodeApi.indexedBoxById(prov.boxId).get
        .getOrElse(throw new IllegalStateException(s"the node has no box ${prov.boxId}"))
      val genesisTx = nodeApi.indexedTransactionById(pool.box.transactionId).get
        .getOrElse(throw new IllegalStateException(s"the node has no transaction ${pool.box.transactionId}"))
      genesisTx.outputs.find(_.box.assets.exists(_.tokenId == vaultNft)) match {
        case None => GenesisVerdict(Seq(CannotBeChecked), Seq.empty)
        case Some(vault) =>
          val (poolIn, vaultIn) = (pool.box.toInputUTXO(ctx), vault.box.toInputUTXO(ctx))
          val (poolMint, vaultMint) = (emission(poolNft).emissionAmount, emission(vaultNft).emissionAmount)
          if (n2t) {
            val r = LDGenesis.check(network, poolIn, vaultIn, LDGenesis.Emissions(poolMint, vaultMint, prov.emissionAmount))
            GenesisVerdict(r.problems, r.warnings)
          } else {
            val r = LDT2TGenesis.check(poolIn, vaultIn, LDT2TGenesis.Emissions(poolMint, vaultMint, prov.emissionAmount))
            GenesisVerdict(r.problems, r.warnings)
          }
      }
    } match {
      case Success(v) => verdicts.put(poolNft, v); Some(v)
      case Failure(ex) =>
        logger.info(s"Could not read the genesis of LithosDEX pool $poolNft this pass: ${ex.getMessage}")
        None
    })

  /** Display facts for `ids`: the shipped list first, then the node's index in one call for the rest. */
  private def metaFor(ids: Seq[String], registry: VerifiedRegistry): Map[String, TokenMeta] = {
    val distinct = ids.distinct
    distinct.flatMap(id => registry.token(id)).foreach(v =>
      tokenMeta.putIfAbsent(v.tokenId, TokenMeta(Some(v.name), Some(v.ticker), Some(v.decimals), verified = true)))
    val missing = distinct.filterNot(tokenMeta.containsKey)
    if (missing.nonEmpty)
      Try(nodeApi.tokensByIds(missing).get).getOrElse(Seq.empty).foreach(t =>
        tokenMeta.putIfAbsent(t.id, TokenMeta(Some(t.name), None, Some(t.decimals), verified = false)))
    distinct.flatMap(id => Option(tokenMeta.get(id)).map(id -> _)).toMap
  }
}

object LDPoolIndex {

  final val PageSize = 100

  /** Most pool boxes indexed per template. Each new one costs a vault read and, unverified, a genesis check. */
  final val MaxPoolsPerKind = 512

  final val CannotBeChecked =
    "genesis cannot be checked: the vault was not created in the transaction that minted the provision token"

  def n2tTemplateHash(network: NetworkType): String =
    Hex.toHexString(Blake2b256.hash(DexContracts(network).liquidityPool.ergoTree.template))

  /** Every token/token deployment shares one template, so any ids give its hash. */
  def t2tTemplateHash(network: NetworkType): String = {
    val any = DexContracts(network).poolNFT
    Hex.toHexString(Blake2b256.hash(LDT2TContracts(any, any, any).liquidityPool.ergoTree.template))
  }
}
