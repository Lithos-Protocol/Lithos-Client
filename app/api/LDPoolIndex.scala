package api

import akka.actor.ActorSystem
import configs.{Contexts, NodeContext, VerifiedPool, VerifiedRegistry}
import lithosdex.contracts.{LDContracts, LDT2TContracts}
import lithosdex.{LDGenesis, LDLiquidityPool, LDT2TGenesis, LDT2TLiquidityPool}
import node.MutationConversions._
import node.NodeApi
import node.model.IndexedBox
import org.ergoplatform.appkit.{BlockchainContext, NetworkType}
import org.ergoplatform.sdk.ErgoId
import org.slf4j.{Logger, LoggerFactory}
import transactions.batching.lithosdex.LDDeployments
import transactions.batching.tracking.{PoolDeployment, PoolTracker, TrackedBox, TrackedPools}

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import javax.inject.{Inject, Singleton}
import scala.concurrent.Future
import scala.concurrent.duration._
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
 * @param status            `building` until the first index completes, then `ready`
 * @param height            the tip the snapshot was read at
 * @param truncated         a pool may be missing: a template held more pool boxes than one scan reads, or a pool's vault
 *                          could not be read
 * @param headerId          the canonical block header id this snapshot was built from, empty until ready
 * @param genesisIncomplete at least one indexed pool's genesis provisions could not be read, so creator LP ownership
 *                          evidence is incomplete
 */
final case class PoolIndexSnapshot(status: String, height: Int, truncated: Boolean, pools: Seq[IndexedPool],
                                   tokens: Map[String, TokenMeta], headerId: String = "",
                                   genesisIncomplete: Boolean = false) {
  /** Whether this snapshot lacks complete evidence to rule a token out as a LithosDex provision NFT. */
  def ownershipIncomplete: Boolean = truncated || genesisIncomplete
}

/**
 * Every LithosDex pool on chain, of both kinds, for the API and the web panel, read from the pool tracker's
 * boxes. Never on a request's path: a request reads the last snapshot, tells the tracker the node's tip so a
 * client that does not mine tracks pools too, and starts one rebuild in the background once the tracker has
 * moved past the snapshot. A rebuild reads no pool box from the node; each pool costs one vault read the first
 * time it is seen. Genesis verdicts and token metadata never change, so each is read once per process.
 *
 * @param follow       tells the pool tracker the node's best block, its height and id
 * @param tracked      the pool tracker's newest state
 * @param awaitTracked waits briefly for the pool tracker to reach a canonical height (used by signing guards)
 */
@Singleton
class LDPoolIndex(nodeContext: NodeContext, ioContext: => scala.concurrent.ExecutionContext,
                  follow: (Int, String) => Unit, tracked: () => Option[TrackedPools],
                  awaitTracked: (Int, FiniteDuration) => Option[TrackedPools] = (_, _) => None) {

  @Inject def this(nodeContext: NodeContext, system: ActorSystem, tracker: PoolTracker) =
    this(nodeContext, new Contexts(system).dexContext, tracker.followAhead, () => tracker.latest, tracker.at)

  import LDPoolIndex._

  private val logger: Logger = LoggerFactory.getLogger("LDPoolIndex")
  private lazy val io = ioContext

  private val current = new AtomicReference(
    PoolIndexSnapshot("building", 0, truncated = false, Seq.empty, Map.empty))
  private val refreshing = new AtomicBoolean(false)

  private val verdicts = new ConcurrentHashMap[String, GenesisVerdict]()
  private val geneses = new ConcurrentHashMap[String, Seq[GenesisProvision]]()
  private val genesisBackoff = new ConcurrentHashMap[String, GenesisBackoff]()
  private val genesisReads = new ConcurrentHashMap[String, (node.model.TokenInfo, IndexedBox, node.model.IndexedTransaction)]()
  private val vaultsStanding = ConcurrentHashMap.newKeySet[String]()
  private val tokenMeta = new ConcurrentHashMap[String, TokenMeta]()

  protected def nowMs(): Long = System.currentTimeMillis()

  private def nodeApi: NodeApi = nodeContext.getNodeApi
  private def network: NetworkType = nodeContext.getNetwork

  private def publish(next: PoolIndexSnapshot): PoolIndexSnapshot =
    current.updateAndGet { held =>
      if (next.height > held.height) next
      else if (next.height == held.height &&
        (held.status != "ready" || next.headerId != held.headerId ||
          (held.ownershipIncomplete && !next.ownershipIncomplete))) next
      else held
    }

  /** The last snapshot, starting a rebuild in the background when the tracker has moved past it. */
  def snapshot(): PoolIndexSnapshot = {
    Try(nodeApi.info().get).toOption.foreach(info =>
      for (height <- info.fullHeight; id <- info.bestFullHeaderId) Try(follow(height, id)))
    val held = current.get()
    tracked().filter(s => s.height > held.height || (s.height == held.height &&
      (s.headerId != held.headerId || (held.ownershipIncomplete && (held.truncated || anyGenesisRetryReady()))))).foreach { state =>
      if (refreshing.compareAndSet(false, true))
        Future {
          try publish(build(state))
          catch {
            case NonFatal(ex) => logger.warn(s"The LithosDEX pool index did not rebuild at ${state.height}: ${ex.getMessage}")
          } finally refreshing.set(false)
        }(io)
    }
    current.get()
  }

  /**
   * A snapshot verified against the node's current canonical tip, waiting briefly for the tracker and rebuilding
   * synchronously when the cached snapshot is behind or incomplete. Throws [[LithosApiErrors.LithosUnavailable]] when
   * the canonical tip cannot be read or reached.
   */
  def freshSnapshot(wait: FiniteDuration = FreshWait): PoolIndexSnapshot = {
    val info = nodeApi.info().getOrElse(
      throw LithosApiErrors.LithosUnavailable("could not read the node's current tip for pool index verification"))
    val tipHeight = info.fullHeight.getOrElse(
      throw LithosApiErrors.LithosUnavailable("the node has no full height for pool index verification"))
    val tipHeaderId = info.bestFullHeaderId.filter(_.nonEmpty).getOrElse(
      throw LithosApiErrors.LithosUnavailable("the node has no canonical header id for pool index verification"))
    Try(follow(tipHeight, tipHeaderId))
    val held = current.get()
    if (held.status == "ready" && held.height == tipHeight && held.headerId == tipHeaderId && !held.ownershipIncomplete) held
    else {
      val state = tracked().filter(s => s.height == tipHeight && s.headerId == tipHeaderId)
        .orElse(awaitTracked(tipHeight, wait).filter(s => s.height == tipHeight && s.headerId == tipHeaderId))
        .getOrElse(throw LithosApiErrors.LithosUnavailable(
          s"the pool index is at height ${held.height} (${held.headerId}), behind canonical tip $tipHeight ($tipHeaderId); retry shortly"))
      val built = build(state, signingCheck = true)
      publish(built)
      built
    }
  }

  /**
   * `pool`'s genesis verdict, read now on the calling thread if no rebuild has read it yet, and kept. None for
   * a verified pool, and when the node could not answer.
   */
  def genesisNow(pool: IndexedPool): Option[GenesisVerdict] =
    if (pool.verified) None
    else pool.genesis.filterNot(_.problems == Seq(GenesisUnreadable)).orElse(nodeContext.getClient.execute(ctx =>
      verdict(ctx, pool.nft, pool.vaultNft, pool.provToken, n2t = pool.kind == VerifiedPool.Kind.N2T, signingCheck = true)))

  /** Rebuilds now from the tracker's newest state, on the calling thread, and keeps the result. */
  private[api] def refreshNow(): PoolIndexSnapshot = {
    tracked().foreach(state => current.set(build(state)))
    current.get()
  }

  /** The pools `state` holds at the LithosDex templates, each kind's newest [[MaxPoolsPerKind]]. */
  private[api] def build(state: TrackedPools, signingCheck: Boolean = false): PoolIndexSnapshot = nodeContext.getClient.execute { ctx =>
    val registry = VerifiedRegistry.forNetwork(network)
    def newest(boxes: Seq[TrackedBox], kind: String): (Seq[TrackedBox], Boolean) = {
      if (boxes.size > MaxPoolsPerKind) logger.warn(s"More than $MaxPoolsPerKind LithosDEX $kind pools are tracked; " +
        "indexing the newest")
      boxes.sortBy(b => (-b.inclusionHeight, b.boxId)).take(MaxPoolsPerKind) -> (boxes.size > MaxPoolsPerKind)
    }
    val all = state.pools.toVector
    val (n2tBoxes, n2tCut) = newest(all.collect { case t @ TrackedBox(_, _, _: PoolDeployment.LithosDexNative) => t },
      "ERG/token")
    val (t2tBoxes, t2tCut) = newest(all.collect { case t @ TrackedBox(_, _, _: PoolDeployment.LithosDexToken) => t },
      "token/token")
    // A box that does not parse is no pool; a vault the node could not answer for leaves its pool out unread
    var unread = 0
    var genesisUnread = 0
    def pool(read: => Option[(IndexedPool, Boolean)]): Option[IndexedPool] = Try(read) match {
      case Success(Some((p, genesisComplete))) =>
        if (!genesisComplete) genesisUnread += 1
        Some(p)
      case Success(None) => None
      case Failure(_: VaultUnread) => unread += 1; None
      case Failure(_) => None
    }
    val pools = n2tBoxes.flatMap(box => pool(n2tPool(ctx, box, registry, signingCheck))) ++
      t2tBoxes.flatMap(box => pool(t2tPool(ctx, box, registry, signingCheck)))
    if (unread > 0) logger.warn(s"$unread LithosDEX pool(s) left out of the index at ${state.height}: their vaults " +
      "could not be read; the index reads as truncated until a rebuild reads them")
    // ERG:LIT first whatever else holds, then verified pools, then newest
    val canonical = lithosdex.LDHelpers.getPoolNFT(network).toString
    val sorted = pools.sortBy(p => (p.nft != canonical, !p.verified, -p.inclusionHeight, p.nft))
    PoolIndexSnapshot("ready", state.height, state.cut || n2tCut || t2tCut || unread > 0, sorted,
      metaFor(sorted.flatMap(p => p.tokenX.toSeq :+ p.tokenY), registry),
      headerId = state.headerId, genesisIncomplete = genesisUnread > 0)
  }

  private def n2tPool(ctx: BlockchainContext, box: TrackedBox, registry: VerifiedRegistry,
                      signingCheck: Boolean): Option[(IndexedPool, Boolean)] = {
    val contracts = box.deployment match {
      case PoolDeployment.LithosDexNative(c) => Some(c)
      case _ => None
    }
    contracts.filter(c => vaultStands(c.vaultNFT.toString, LDDeployments.vaultStands(nodeApi, c))).map { c =>
      val p = LDLiquidityPool(box.box.toInputUTXO(ctx))
      val verified = registry.pool(c.poolNFT.toString).exists(_.kind == VerifiedPool.Kind.N2T)
      val judged = if (verified) None
        else verdict(ctx, c.poolNFT.toString, c.vaultNFT.toString, c.provToken.toString, n2t = true, signingCheck)
      val provisions = provisionsUnlessFailed(ctx, c.poolNFT.toString, c.provToken.toString, n2t = true, verified, judged,
        signingCheck)
      IndexedPool(VerifiedPool.Kind.N2T, c.poolNFT.toString, box.boxId, box.inclusionHeight, None, p.tokenY.toString,
        p.reservesX, p.reservesY, p.pendingX, p.pendingY, p.supply, p.feeParams.toSeq, p.provTokensLeft,
        c.vaultNFT.toString, c.provToken.toString, verified, judged, Some(c), None,
        provisions.getOrElse(Seq.empty)) -> provisions.isDefined
    }
  }

  private def t2tPool(ctx: BlockchainContext, box: TrackedBox, registry: VerifiedRegistry,
                      signingCheck: Boolean): Option[(IndexedPool, Boolean)] =
    (box.deployment match {
      case PoolDeployment.LithosDexToken(c) => Some(c)
      case _ => None
    }).filter(c => vaultStands(c.vaultNFT.toString, LDDeployments.vaultStands(nodeApi, c))).map { c =>
        val p = LDT2TLiquidityPool(box.box.toInputUTXO(ctx))
        val verified = registry.pool(c.poolNFT.toString).exists(_.kind == VerifiedPool.Kind.T2T)
        val judged = if (verified) None
          else verdict(ctx, c.poolNFT.toString, c.vaultNFT.toString, c.provToken.toString, n2t = false, signingCheck)
        val provisions = provisionsUnlessFailed(ctx, c.poolNFT.toString, c.provToken.toString, n2t = false, verified,
          judged, signingCheck)
        IndexedPool(VerifiedPool.Kind.T2T, c.poolNFT.toString, box.boxId, box.inclusionHeight, Some(p.tokenX.toString),
          p.tokenY.toString, p.reservesX, p.reservesY, p.pendingX, p.pendingY, p.supply, p.feeParams.toSeq,
          p.provTokensLeft, c.vaultNFT.toString, c.provToken.toString, verified, judged, None, Some(c),
          provisions.getOrElse(Seq.empty)) -> provisions.isDefined
      }

  /** A vault singleton stays one once it stands, so only a first sighting costs the read. A failed read throws. */
  private def vaultStands(vaultNft: String, check: => Boolean): Boolean =
    vaultsStanding.contains(vaultNft) || (Try(check) match {
      case Success(stands) => stands && vaultsStanding.add(vaultNft)
      case Failure(ex) => throw new VaultUnread(vaultNft, ex)
    })

  /**
   * The genesis verdict for an unverified pool, read once and kept. The pool as created is the provision
   * token's mint box, and the vault is the output of that transaction holding the vault NFT. Transient node/index
   * failures back off exponentially without permanently caching `GenesisUnreadable`; a cleanly read box that fails
   * to parse as a genesis is cached as uncheckable rather than poisoning ownership completeness.
   */
  private def verdict(ctx: BlockchainContext, poolNft: String, vaultNft: String, provToken: String,
                      n2t: Boolean, signingCheck: Boolean): Option[GenesisVerdict] =
    Option(verdicts.get(poolNft)).orElse(if (backedOff(poolNft, signingCheck)) {
      Some(GenesisVerdict(Seq(GenesisUnreadable), Seq.empty))
    } else Try {
      val (prov, pool, genesisTx) = genesisOf(provToken)
      genesisTx.outputs.find(_.box.assets.exists(_.tokenId == vaultNft)) match {
        case None => GenesisVerdict(Seq(CannotBeChecked), Seq.empty)
        case Some(vault) =>
          val (poolMint, vaultMint) = (emission(poolNft).emissionAmount, emission(vaultNft).emissionAmount)
          // Every other unit of the provision token is in this transaction's outputs, so each must be a provision
          val provisions = genesisTx.outputs.filter(o => o.box.boxId != pool.box.boxId &&
            o.box.assets.exists(_.tokenId == provToken)).map(_.box.toInputUTXO(ctx))
          // An unreadable owner is left out and one the node never minted reads as 0; the checker reports both
          val owners = provisions.flatMap(p => Try(new ErgoId(p.registers(2).getValue.asInstanceOf[sigma.Coll[Byte]].toArray)
            .toString).toOption).distinct.map { id =>
            val emitted = nodeApi.tokenById(id) match {
              case Success(opt) => opt.map(_.emissionAmount).getOrElse(0L)
              case Failure(ex) => throw new GenesisRpcError(s"could not read owner token $id: ${ex.getMessage}", ex)
            }
            id -> emitted
          }.toMap
          val (poolIn, vaultIn) = (pool.box.toInputUTXO(ctx), vault.box.toInputUTXO(ctx))
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
      case Failure(ex: GenesisRpcError) =>
        failedRead(poolNft, ex)
        None
      case Failure(ex) =>
        val uncheckable = GenesisVerdict(Seq(s"genesis cannot be checked: ${ex.getMessage}"), Seq.empty)
        verdicts.put(poolNft, uncheckable)
        Some(uncheckable)
    })

  private def emission(id: String) = nodeApi.tokenById(id) match {
    case Success(Some(info)) => info
    case Success(None) => throw new GenesisRpcError(s"the node has no token $id")
    case Failure(ex) => throw new GenesisRpcError(s"could not read token $id: ${ex.getMessage}", ex)
  }

  /**
   * The provision token's emission, the pool box as created (its mint box), and the transaction that created it,
   * read once per pool and kept until the genesis provisions are, since the verdict needs them first. A failed read
   * is not kept, so the next rebuild tries again.
   */
  private def genesisOf(provToken: String): (node.model.TokenInfo, IndexedBox, node.model.IndexedTransaction) =
    Option(genesisReads.get(provToken)).getOrElse {
      val prov = emission(provToken)
      val pool = nodeApi.indexedBoxById(prov.boxId) match {
        case Success(Some(b)) => b
        case Success(None) => throw new GenesisRpcError(s"the node has no box ${prov.boxId}")
        case Failure(ex) => throw new GenesisRpcError(s"could not read box ${prov.boxId}: ${ex.getMessage}", ex)
      }
      val genesisTx = nodeApi.indexedTransactionById(pool.box.transactionId) match {
        case Success(Some(tx)) => tx
        case Success(None) => throw new GenesisRpcError(s"the node has no transaction ${pool.box.transactionId}")
        case Failure(ex) => throw new GenesisRpcError(s"could not read transaction ${pool.box.transactionId}: ${ex.getMessage}", ex)
      }
      val read = (prov, pool, genesisTx)
      genesisReads.put(provToken, read)
      read
    }

  /** The genesis provisions, unless the unverified pool's verdict just failed to read the same genesis this pass. */
  private def provisionsUnlessFailed(ctx: BlockchainContext, poolNft: String, provToken: String, n2t: Boolean,
                                     verified: Boolean, judged: Option[GenesisVerdict],
                                     signingCheck: Boolean): Option[Seq[GenesisProvision]] =
    if (!verified && judged.isEmpty) None else genesisProvisions(ctx, poolNft, provToken, n2t, signingCheck)

  /**
   * The provisions `poolNft`'s genesis created, read once and kept, verified pools included. Each holds the
   * provision token at the pool's own guard and names a 32-byte owner in R6; the genesis checker judges the rest.
   * None when a transient node read failed or is backed off; a cleanly read box that does not parse as a LithosDex
   * genesis created no genesis provisions and caches `Seq.empty`.
   */
  private def genesisProvisions(ctx: BlockchainContext, poolNft: String, provToken: String,
                                n2t: Boolean, signingCheck: Boolean): Option[Seq[GenesisProvision]] =
    Option(geneses.get(poolNft)).orElse(if (backedOff(poolNft, signingCheck)) None
    else Try(genesisOf(provToken)) match {
      case Failure(ex) =>
        failedRead(poolNft, ex)
        None
      case Success((_, pool, genesisTx)) =>
        val found = Try {
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
        }.getOrElse(Seq.empty)
        // The verdict, when the pool needs one, was kept before this ran, so nothing reads this genesis again
        geneses.put(poolNft, found)
        genesisReads.remove(provToken)
        genesisBackoff.remove(poolNft)
        Some(found)
    })

  /** Whether `poolNft`'s genesis is currently waiting in retry backoff after repeated transient failures. */
  private def backedOff(poolNft: String, signingCheck: Boolean): Boolean =
    Option(genesisBackoff.get(poolNft)).exists { b =>
      b.failures >= MaxGenesisReads &&
        (if (signingCheck) nowMs() - b.lastFailedMs < BackoffBaseMs else nowMs() < b.retryAfterMs)
    }

  /** Whether any transiently failed pool genesis is ready to retry now. */
  private def anyGenesisRetryReady(): Boolean = {
    val now = nowMs()
    var ready = false
    genesisBackoff.forEach((_, b) => if (now >= b.retryAfterMs) ready = true)
    ready
  }

  /** Counts one transient failed genesis read, backing off exponentially after [[MaxGenesisReads]] tries. */
  private def failedRead(poolNft: String, ex: Throwable): Unit = {
    val now = nowMs()
    val updated = genesisBackoff.compute(poolNft, (_, prev) => {
      val failures = Option(prev).map(_.failures).getOrElse(0) + 1
      val retryAfter =
        if (failures < MaxGenesisReads) 0L
        else {
          val shift = math.min(failures - MaxGenesisReads, 10)
          now + math.min(MaxBackoffMs, BackoffBaseMs << shift)
        }
      GenesisBackoff(failures, now, retryAfter)
    })
    if (updated.failures == 1) logger.info(s"Could not read the genesis of LithosDEX pool $poolNft: ${ex.getMessage}")
    else if (updated.failures == MaxGenesisReads)
      logger.warn(s"Could not read the genesis of LithosDEX pool $poolNft in ${updated.failures} tries; " +
        s"backing off retries and showing as unchecked until the node answers: ${ex.getMessage}")
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

  /** Most pool boxes indexed per template. Each new one costs a vault read and, unverified, a genesis check. */
  final val MaxPoolsPerKind = 512

  /** The node could not say whether a pool's vault stands, so the pool is neither listed nor ruled out. */
  private final class VaultUnread(vaultNft: String, cause: Throwable)
    extends RuntimeException(s"could not read vault $vaultNft: ${cause.getMessage}", cause)

  /** Transient node/index failure while reading a pool's genesis. */
  private final class GenesisRpcError(message: String, cause: Throwable = null)
    extends RuntimeException(message, cause)

  private final case class GenesisBackoff(failures: Int, lastFailedMs: Long, retryAfterMs: Long)

  /** How long a signing guard waits for the pool tracker to reach the canonical tip before failing closed. */
  final val FreshWait: FiniteDuration = 5.seconds

  /** Reads of one pool's genesis that may fail before the index backs off retries exponentially. */
  final val MaxGenesisReads = 3

  /** Initial and maximum retry backoff for transient genesis read failures. */
  final val BackoffBaseMs: Long = 5000L
  final val MaxBackoffMs: Long = 300000L

  final val GenesisUnreadable = s"genesis cannot be checked: the node could not answer for it in $MaxGenesisReads tries"

  final val CannotBeChecked =
    "genesis cannot be checked: the vault was not created in the transaction that minted the provision token"
}
