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
import org.ergoplatform.sdk.ErgoId
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

/**
 * A provision the pool's genesis created, as the client's own creation does for the pool's creator. Its owner NFT
 * is minted apart from the pool, so it is found here rather than from the box it was minted from.
 *
 * @param amountX the pool's X reserve as created, ERG for an ERG/token pool: all of what the creator put in
 */
final case class GenesisProvision(owner: String, height: Int, shares: Long, amountX: Long, amountY: Long)

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
                             t2t: Option[LDT2TContracts],
                             genesisProvisions: Seq[GenesisProvision] = Seq.empty)

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
  private val geneses = new ConcurrentHashMap[String, Seq[GenesisProvision]]()
  private val genesisFailures = new ConcurrentHashMap[String, Integer]()
  private val genesisReads = new ConcurrentHashMap[String, (node.model.TokenInfo, IndexedBox, node.model.IndexedTransaction)]()
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
      val judged = if (verified) None else verdict(ctx, c.poolNFT.toString, c.vaultNFT.toString, c.provToken.toString, n2t = true)
      IndexedPool(VerifiedPool.Kind.N2T, c.poolNFT.toString, box.boxId, box.inclusionHeight, None, p.tokenY.toString,
        p.reservesX, p.reservesY, p.pendingX, p.pendingY, p.supply, p.feeParams.toSeq, p.provTokensLeft,
        c.vaultNFT.toString, c.provToken.toString, verified, judged, Some(c), None,
        provisionsUnlessFailed(ctx, c.poolNFT.toString, c.provToken.toString, n2t = true, verified, judged))
    }
  }

  private def t2tPool(ctx: BlockchainContext, box: IndexedBox, registry: VerifiedRegistry): Option[IndexedPool] =
    LDDeployments.verifyT2T(box.box.ergoTree, box.box.assets.map(a => a.tokenId -> a.amount))
      .filter(c => vaultStands(c.vaultNFT.toString, LDDeployments.vaultStands(nodeApi, c))).map { c =>
        val p = LDT2TLiquidityPool(box.box.toInputUTXO(ctx))
        val verified = registry.pool(c.poolNFT.toString).exists(_.kind == VerifiedPool.Kind.T2T)
        val judged = if (verified) None
          else verdict(ctx, c.poolNFT.toString, c.vaultNFT.toString, c.provToken.toString, n2t = false)
        IndexedPool(VerifiedPool.Kind.T2T, c.poolNFT.toString, box.boxId, box.inclusionHeight, Some(p.tokenX.toString),
          p.tokenY.toString, p.reservesX, p.reservesY, p.pendingX, p.pendingY, p.supply, p.feeParams.toSeq,
          p.provTokensLeft, c.vaultNFT.toString, c.provToken.toString, verified, judged, None, Some(c),
          provisionsUnlessFailed(ctx, c.poolNFT.toString, c.provToken.toString, n2t = false, verified, judged))
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
    Option(verdicts.get(poolNft)).orElse(if (gaveUp(poolNft)) {
      val unread = GenesisVerdict(Seq(GenesisUnreadable), Seq.empty)
      verdicts.put(poolNft, unread)
      Some(unread)
    } else Try {
      val (prov, pool, genesisTx) = genesisOf(provToken)
      genesisTx.outputs.find(_.box.assets.exists(_.tokenId == vaultNft)) match {
        case None => GenesisVerdict(Seq(CannotBeChecked), Seq.empty)
        case Some(vault) =>
          val (poolIn, vaultIn) = (pool.box.toInputUTXO(ctx), vault.box.toInputUTXO(ctx))
          val (poolMint, vaultMint) = (emission(poolNft).emissionAmount, emission(vaultNft).emissionAmount)
          // Every other unit of the provision token is in this transaction's outputs, so each must be a provision
          val provisions = genesisTx.outputs.filter(o => o.box.boxId != pool.box.boxId &&
            o.box.assets.exists(_.tokenId == provToken)).map(_.box.toInputUTXO(ctx))
          // An unreadable owner is left out and one the node never minted reads as 0; the checker reports both
          val owners = provisions.flatMap(p => Try(new ErgoId(p.registers(2).getValue.asInstanceOf[sigma.Coll[Byte]].toArray)
            .toString).toOption).distinct.map(id => id -> nodeApi.tokenById(id).get.map(_.emissionAmount).getOrElse(0L)).toMap
          if (n2t) {
            val r = LDGenesis.check(network, poolIn, vaultIn,
              LDGenesis.Emissions(poolMint, vaultMint, prov.emissionAmount, owners), provisions)
            GenesisVerdict(r.problems, r.warnings)
          } else {
            val r = LDT2TGenesis.check(poolIn, vaultIn,
              LDT2TGenesis.Emissions(poolMint, vaultMint, prov.emissionAmount, owners), provisions)
            GenesisVerdict(r.problems, r.warnings)
          }
      }
    } match {
      case Success(v) => verdicts.put(poolNft, v); Some(v)
      case Failure(ex) =>
        failedRead(poolNft, ex)
        None
    })

  private def emission(id: String) = nodeApi.tokenById(id).get
    .getOrElse(throw new IllegalStateException(s"the node has no token $id"))

  /**
   * The provision token's emission, the pool box as created (its mint box), and the transaction that created it,
   * read once per pool and kept until the genesis provisions are, since the verdict needs them first. A failed read
   * is not kept, so the next rebuild tries again.
   */
  private def genesisOf(provToken: String): (node.model.TokenInfo, IndexedBox, node.model.IndexedTransaction) =
    Option(genesisReads.get(provToken)).getOrElse {
      val prov = emission(provToken)
      val pool = nodeApi.indexedBoxById(prov.boxId).get
        .getOrElse(throw new IllegalStateException(s"the node has no box ${prov.boxId}"))
      val genesisTx = nodeApi.indexedTransactionById(pool.box.transactionId).get
        .getOrElse(throw new IllegalStateException(s"the node has no transaction ${pool.box.transactionId}"))
      val read = (prov, pool, genesisTx)
      genesisReads.put(provToken, read)
      read
    }

  /** The genesis provisions, unless the unverified pool's verdict just failed to read the same genesis this pass. */
  private def provisionsUnlessFailed(ctx: BlockchainContext, poolNft: String, provToken: String, n2t: Boolean,
                                     verified: Boolean, judged: Option[GenesisVerdict]): Seq[GenesisProvision] =
    if (!verified && judged.isEmpty) Seq.empty else genesisProvisions(ctx, poolNft, provToken, n2t)

  /**
   * The provisions `poolNft`'s genesis created, read once and kept, verified pools included. Each holds the
   * provision token at the pool's own guard and names a 32-byte owner in R6; the genesis checker judges the rest.
   * Empty when a read failed, so the next rebuild tries again.
   */
  private def genesisProvisions(ctx: BlockchainContext, poolNft: String, provToken: String,
                                n2t: Boolean): Seq[GenesisProvision] =
    Option(geneses.get(poolNft)).getOrElse(if (gaveUp(poolNft)) {
      geneses.put(poolNft, Seq.empty)
      Seq.empty
    } else Try {
      val (_, pool, genesisTx) = genesisOf(provToken)
      val assets = pool.box.assets.map(a => a.tokenId -> a.amount)
      // The provision token's mint box is the pool only when the pool was created as this client creates one. A
      // pool whose token was minted elsewhere and moved in later created no provision at genesis.
      val guard =
        if (n2t) LDDeployments.verify(network, pool.box.ergoTree, assets).map(_.provisionGuard.ergoTreeHex)
        else LDDeployments.verifyT2T(pool.box.ergoTree, assets).map(_.provisionGuard.ergoTreeHex)
      guard.toSeq.flatMap { guardTree =>
        val poolIn = pool.box.toInputUTXO(ctx)
        val (amountX, amountY) =
          if (n2t) { val p = LDLiquidityPool(poolIn); (p.reservesX, p.reservesY) }
          else { val p = LDT2TLiquidityPool(poolIn); (p.reservesX, p.reservesY) }
        genesisTx.outputs.filter(o => o.box.boxId != pool.box.boxId && guardTree.equalsIgnoreCase(o.box.ergoTree) &&
          o.box.assets.exists(_.tokenId == provToken)).flatMap { o =>
          val p = Try(transactions.batching.lithosdex.LDBoxes.readProvision(o.box.toInputUTXO(ctx))).toOption
          p.map(p => GenesisProvision(p.ownerNFT.toString, genesisTx.inclusionHeight, p.shares, amountX, amountY))
        }
      }
    } match {
      // The verdict, when the pool needs one, was kept before this ran, so nothing reads this genesis again
      case Success(found) => geneses.put(poolNft, found); genesisReads.remove(provToken); found
      case Failure(ex) =>
        failedRead(poolNft, ex)
        Seq.empty
    })

  /** Whether `poolNft`'s genesis has failed to read [[MaxGenesisReads]] times, so it is not read again this run. */
  private def gaveUp(poolNft: String): Boolean = genesisFailures.getOrDefault(poolNft, 0) >= MaxGenesisReads

  /** Counts one failed genesis read, logging the first and the last, so a pool that never reads fills no log. */
  private def failedRead(poolNft: String, ex: Throwable): Unit = {
    val failures = genesisFailures.merge(poolNft, 1, (a: Integer, b: Integer) => a + b)
    if (failures == 1) logger.info(s"Could not read the genesis of LithosDEX pool $poolNft: ${ex.getMessage}")
    else if (failures >= MaxGenesisReads)
      logger.warn(s"Could not read the genesis of LithosDEX pool $poolNft in $failures tries; it is not read again " +
        s"until restart and shows as unchecked: ${ex.getMessage}")
  }

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

  /** Reads of one pool's genesis that may fail before the index stops trying until restart. */
  final val MaxGenesisReads = 3

  final val GenesisUnreadable = s"genesis cannot be checked: the node could not answer for it in $MaxGenesisReads tries"

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
