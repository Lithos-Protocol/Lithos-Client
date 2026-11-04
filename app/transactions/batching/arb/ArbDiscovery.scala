package transactions.batching.arb

import api.IndexedPool
import configs.BatchingConfig
import node.MutationConversions._
import node.NodeApi
import node.model.{IndexedBox, MempoolOptions, NodeBox, Paging, SortDirection}
import org.ergoplatform.appkit.BlockchainContext
import org.slf4j.Logger
import transactions.batching.ergodex.{ErgoDexContracts, ErgoDexPool, ErgoDexPoolScan}

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
 * What one discovery found at indexed height `height`: every pool some route passes through, by NFT, and the
 * routes. Valid for blocks built on that height alone, since a later block may have spent any of its boxes.
 * `cut` says the route cap left some routes out.
 */
final case class ArbIndex(height: Int, pools: Map[String, ArbPoolEntry], routes: Vector[ArbRoute],
                          cut: Boolean = false)

/**
 * Reads the pools arb can trade: ErgoDEX native and token pools from one paged scan of each script, and the
 * LithosDex pools `LDPoolIndex` verified, each from its confirmed box. All confirmed, so the same height
 * gives the same pools.
 */
object ArbDiscovery {

  /** Most routes held; past it the longest are left out, and discovery says so. */
  final val MaxRoutes = 20000

  /**
   * The index at the node's indexed height, or None when the index is behind the chain or below `atLeast`, or
   * moved while it was read, which leaves pools from two heights. Only the first of those costs a scan. A failed
   * read throws.
   *
   * @param ldPools the LithosDex pools `LDPoolIndex` verified; their boxes are read here, not taken from it
   * @param denied  pool NFTs either batcher denies
   */
  def discover(nodeApi: NodeApi, ctx: BlockchainContext, ergoDex: BatchingConfig, denied: Set[String],
               ldPools: Seq[IndexedPool], atLeast: Int, logger: Logger): Option[ArbIndex] = {
    val before = nodeApi.indexedHeight().get
    if (before.indexedHeight != before.fullHeight || before.indexedHeight < atLeast) None
    else {
      val tip = before.indexedHeight
      val pools = ergoDexPools(nodeApi, ergoDex, denied, tip, logger) ++ lithosDexPools(nodeApi, ctx, denied, ldPools)
      val after = nodeApi.indexedHeight().get
      if (after.indexedHeight != tip || after.fullHeight != tip) None
      else Some(index(tip, pools))
    }
  }

  /** `pools` reduced to those some route passes through, with the routes. An NFT two pools claim is left out. */
  def index(height: Int, pools: Seq[ArbPoolEntry]): ArbIndex = {
    val distinct = pools.groupBy(_.nft).collect { case (_, Seq(only)) => only }.toVector.sortBy(e => (e.pool.kind.label, e.nft))
    val (routes, cut) = ArbRoutes.enumerate(distinct.map(_.pool), MaxRoutes)
    val used = routes.iterator.flatMap(_.pools).toSet
    ArbIndex(height, distinct.filter(e => used.contains(e.nft)).map(e => e.nft -> e).toMap, routes, cut)
  }

  /**
   * Both ErgoDEX pool shapes, less denied pools and those whose box is older than `maxPoolAgeBlocks`. Each box
   * kept shares one copy of its script's tree, since every pool of a shape has the same one.
   */
  private def ergoDexPools(nodeApi: NodeApi, ergoDex: BatchingConfig, denied: Set[String], tip: Int,
                           logger: Logger): Vector[ArbPoolEntry] =
    Seq(ErgoDexContracts.NativePoolErgoTree -> "native", ErgoDexContracts.TokenPoolErgoTree -> "token").flatMap {
      case (tree, shape) =>
        val (found, exhausted) = ErgoDexPoolScan.confirmed(nodeApi, tree)(indexed => ergoDexEntry(indexed, tree)
          .filter(_ => tip - indexed.inclusionHeight <= ergoDex.maxPoolAgeBlocks))
        if (!exhausted) logger.warn(s"Arb discovery stopped the ErgoDEX $shape pool scan after " +
          s"${ErgoDexPoolScan.MaxPages} pages; cycles use those pools only")
        found.filterNot(e => denied.contains(e.nft))
    }.toVector

  private def ergoDexEntry(indexed: IndexedBox, tree: String): Option[ArbPoolEntry] =
    ErgoDexPool.parse(indexed.box).map(pool => ArbPoolEntry(ErgoDexArbPool(pool), indexed.box.copy(ergoTree = tree)))

  /**
   * Each verified LithosDex pool's one confirmed box, if it sits at the tree its deployment builds. A pool whose
   * box is not unique, or does not read, is left out this pass.
   */
  private def lithosDexPools(nodeApi: NodeApi, ctx: BlockchainContext, denied: Set[String],
                             ldPools: Seq[IndexedPool]): Vector[ArbPoolEntry] =
    ldPools.iterator.filterNot(p => denied.contains(p.nft.toLowerCase)).flatMap { p =>
      val boxes = nodeApi.unspentBoxesByTokenId(p.nft, Paging(0, 2), SortDirection.Desc, MempoolOptions.ConfirmedOnly).get
      boxes match {
        case Seq(only) =>
          val box = only.box
          (p.n2t, p.t2t) match {
            case (Some(c), _) if box.ergoTree.equalsIgnoreCase(c.liquidityPool.ergoTreeHex) =>
              Try(ArbPoolEntry(LithosDexArbPool(box.toInputUTXO(ctx), c), box)).toOption
            case (_, Some(c)) if box.ergoTree.equalsIgnoreCase(c.liquidityPool.ergoTreeHex) =>
              Try(ArbPoolEntry(LithosDexT2TArbPool(box.toInputUTXO(ctx), c), box)).toOption
            case _ => None
          }
        case _ => None
      }
    }.filter(e => e.pool.boxId == e.box.boxId).toVector
}
