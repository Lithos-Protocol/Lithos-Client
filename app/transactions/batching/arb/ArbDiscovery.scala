package transactions.batching.arb

import configs.BatchingConfig
import node.MutationConversions._
import node.model.NodeBox
import org.ergoplatform.appkit.BlockchainContext
import transactions.batching.ergodex.ErgoDexPool
import transactions.batching.tracking.{PoolDeployment, TrackedBox, TrackedPools}

import scala.util.Try

/**
 * A pool as discovery read it: its state, and the confirmed box that holds it. `read` takes another box as this
 * pool's state only when it carries this pool's NFT first and sits at this pool's tree, so a box a batcher
 * created is recognised as the pool's successor and nothing else is.
 */
final case class ArbPoolEntry(pool: ArbPool, box: NodeBox) {
  def nft: String = pool.nft

  /** `candidate` as this pool's state, or None when it is not this pool's box or does not parse as one. */
  def read(ctx: BlockchainContext, candidate: NodeBox): Option[ArbPool] =
    if (!candidate.ergoTree.equalsIgnoreCase(box.ergoTree) || !candidate.assets.headOption.exists(_.tokenId == nft)) None
    else Try(pool match {
      case _: ErgoDexArbPool => ErgoDexPool.parse(candidate).map(ErgoDexArbPool)
      case p: LithosDexArbPool => Some(LithosDexArbPool(candidate.toInputUTXO(ctx), p.contracts))
      case p: LithosDexT2TArbPool => Some(LithosDexT2TArbPool(candidate.toInputUTXO(ctx), p.contracts))
    }).toOption.flatten.filter(_.boxId == candidate.boxId)
}

/**
 * What discovery found as of block `headerId` at `height`: every pool some route passes through, by NFT, and the
 * routes. Valid for blocks built on that block alone. `cut` says the route cap left some routes out. `parsed`
 * holds every pool that qualified, routed or not, and `vaults` the LithosDex vault NFTs read as standing, so the
 * next block re-reads neither for a pool whose box did not move.
 */
final case class ArbIndex(height: Int, pools: Map[String, ArbPoolEntry], routes: Vector[ArbRoute],
                          cut: Boolean = false, headerId: String = "",
                          parsed: Map[String, ArbPoolEntry] = Map.empty, vaults: Set[String] = Set.empty)

/**
 * Builds arb's pool index from the tracked pools: ErgoDEX native and token pools less denied ones and those
 * whose box is older than `maxPoolAgeBlocks`, and LithosDex pools of both kinds whose vault stands. Nothing is
 * scanned; the only node reads are a LithosDex vault seen for the first time.
 */
object ArbDiscovery {

  /** Most routes held; past it the longest are left out, and discovery says so. */
  final val MaxRoutes = 20000

  /** Most vaults read for the first time in one build; a pool whose vault waits is left out until a later one. */
  final val MaxVaultReads = 4

  /**
   * The index for blocks built on `tracked`. A pool whose box `previous` parsed keeps that state, and the routes
   * are enumerated again only when the set of pools changed. `vaultStands` reads whether a LithosDex
   * deployment's vault stands; an answer of true is kept, and a failed read leaves that pool out this time.
   */
  def index(ctx: BlockchainContext, tracked: TrackedPools, previous: Option[ArbIndex], ergoDex: BatchingConfig,
            denied: Set[String], vaultStands: PoolDeployment => Boolean): ArbIndex = {
    val known = previous.map(_.parsed).getOrElse(Map.empty[String, ArbPoolEntry])
    var vaults = previous.map(_.vaults).getOrElse(Set.empty[String])
    var vaultReads = 0

    // A vault already read as standing costs nothing; a new one is read while this build has reads left
    def standing(deployment: PoolDeployment): Boolean = vaultOf(deployment).forall { vault =>
      vaults.contains(vault) || (vaultReads < MaxVaultReads && {
        vaultReads += 1
        val stands = Try(vaultStands(deployment)).getOrElse(false)
        if (stands) vaults += vault
        stands
      })
    }

    val qualifying = tracked.pools.filter { t =>
      !denied.contains(t.nft.toLowerCase) && (t.deployment match {
        case PoolDeployment.ErgoDexNative | PoolDeployment.ErgoDexToken =>
          tracked.height - t.inclusionHeight <= ergoDex.maxPoolAgeBlocks
        case _ => true
      })
    }.toVector.sortBy(_.nft)
    val parsed = qualifying.flatMap { t =>
      known.get(t.nft).filter(_.box.boxId == t.boxId).orElse(if (!standing(t.deployment)) None else entry(ctx, t))
    }.map(e => e.nft -> e).toMap

    previous.filter(p => p.parsed.keySet == parsed.keySet) match {
      case Some(p) =>
        ArbIndex(tracked.height, p.pools.keysIterator.map(nft => nft -> parsed(nft)).toMap, p.routes, p.cut,
          tracked.headerId, parsed, vaults)
      case None =>
        index(tracked.height, parsed.values.toSeq).copy(headerId = tracked.headerId, parsed = parsed, vaults = vaults)
    }
  }

  /** `pools` reduced to those some route passes through, with the routes. An NFT two pools claim is left out. */
  def index(height: Int, pools: Seq[ArbPoolEntry]): ArbIndex = {
    val distinct = pools.groupBy(_.nft).collect { case (_, Seq(only)) => only }.toVector.sortBy(e => (e.pool.kind.label, e.nft))
    val (routes, cut) = ArbRoutes.enumerate(distinct.map(_.pool), MaxRoutes)
    val used = routes.iterator.flatMap(_.pools).toSet
    ArbIndex(height, distinct.filter(e => used.contains(e.nft)).map(e => e.nft -> e).toMap, routes, cut)
  }

  /** The pool a tracked box holds, or None when its box does not read as one. */
  private def entry(ctx: BlockchainContext, tracked: TrackedBox): Option[ArbPoolEntry] = Try {
    val box = tracked.box
    val pool: Option[ArbPool] = tracked.deployment match {
      case PoolDeployment.ErgoDexNative | PoolDeployment.ErgoDexToken => ErgoDexPool.parse(box).map(ErgoDexArbPool)
      case PoolDeployment.LithosDexNative(c) => Some(LithosDexArbPool(box.toInputUTXO(ctx), c))
      case PoolDeployment.LithosDexToken(c) => Some(LithosDexT2TArbPool(box.toInputUTXO(ctx), c))
    }
    pool.filter(_.boxId == box.boxId).map(ArbPoolEntry(_, box))
  }.toOption.flatten

  private def vaultOf(deployment: PoolDeployment): Option[String] = deployment match {
    case PoolDeployment.LithosDexNative(c) => Some(c.vaultNFT.toString)
    case PoolDeployment.LithosDexToken(c) => Some(c.vaultNFT.toString)
    case _ => None
  }
}
