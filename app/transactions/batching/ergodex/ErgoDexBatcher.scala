package transactions.batching.ergodex

import akka.actor.ActorRef
import configs.{BatchingConfig, CandidateConfig, NodeContext}
import node.MutationConversions._
import node.model.{IndexedBox, MempoolOptions, NodeBox, Paging, SortDirection}
import org.ergoplatform.appkit.BlockchainContext
import play.api.Configuration
import state.synchronization.CompleteMempool
import transactions.batching.Batcher.{BroadcastResult, Tracked}
import transactions.batching.{Batcher, BatchingMempool}
import transactions.candidate.BlockTxMessages.CandidateTx
import transactions.candidate.CandidateBundle

import javax.inject.{Inject, Named}
import scala.util.{Failure, Success, Try}
import scala.concurrent.duration._

/** Scans executable native orders and builds candidate or broadcast runs on background workers. */
class ErgoDexBatcher(nodeContext: NodeContext,
                     batching: BatchingConfig,
                     servesCandidates: Boolean,
                     engine: ActorRef,
                     useTrueProp: Boolean,
                     buildBudgetMs: Long = Batcher.DefaultBuildBudgetMs)
  extends Batcher(nodeContext, batching, servesCandidates, engine, configs.Contexts.BatchingIo, buildBudgetMs) {

  import ErgoDexBatcher._

  /** Built by `Module`, reading every setting from the application configuration. */
  @Inject()
  def this(nodeContext: NodeContext, config: Configuration, @Named("transaction-engine") engine: ActorRef) =
    this(nodeContext, BatchingConfig(config, ErgoDexBatcher.Name),
      Batcher.servesCandidates(config, ErgoDexBatcher.Name), engine,
      CandidateConfig(config).useTruePropCollection, Batcher.buildBudgetMs(config))

  override protected val label: String = "ErgoDEX"

  /** Scans executable templates and their pools. A failed read leaves the previous tracked set intact. */
  override protected def discover(): Tracked = {
    val tip = nodeApi.info().get.fullHeight.getOrElse(
      throw new IllegalStateException("the node did not report a height"))
    // Left out before ranking, so an order declaring a fee it can never pay holds no tracked slot
    val skipped = skippedOrders()
    val orders = ErgoDexContracts.orders.filter(_.executable)
      .flatMap(contract => scanTemplate(contract.templateHash, contract.name)(ErgoDexOrder.parse))
      .filterNot(order => skipped.contains(order.boxId))
    val nfts = orders.map(_.poolNft).distinct.filterNot(batching.deniedPools.contains)
    if (nfts.size > MaxPoolReads)
      logger.warn(s"ErgoDEX orders name ${nfts.size} pools; reading the first $MaxPoolReads this pass")
    val pools = nfts.take(MaxPoolReads).flatMap(nft => confirmedPool(nft).flatMap(indexed =>
      ErgoDexPool.native(indexed.box).map(_ -> indexed.inclusionHeight)))
    ErgoDexBatching.track(orders, pools, tip, batching)
  }

  /** Returns the uniquely indexed confirmed box carrying the pool NFT. */
  private def confirmedPool(nft: String): Option[IndexedBox] = {
    val boxes = nodeApi.unspentBoxesByTokenId(nft, Paging(0, 2), SortDirection.Desc,
      MempoolOptions.ConfirmedOnly).get
    if (boxes.size == 1) boxes.headOption else None
  }

  /** Rechecks indexed pool boxes against the UTXO set to exclude spent boxes. */
  private def currentPools(nfts: Seq[String]): Map[String, NodeBox] = {
    val listed = nfts.flatMap(nft => confirmedPool(nft).map(_.boxId -> nft)).toMap
    if (listed.isEmpty) Map.empty
    else nodeApi.boxesWithPoolByIds(listed.keys.toSeq).get
      .flatMap(box => listed.get(box.boxId).map(_ -> box)).toMap
  }

  /** Reloads tracked orders, retaining mempool-claimed boxes and reporting missing ids as spent. */
  private def liveOrders(orders: Tracked): Seq[ErgoDexOrder] = {
    val live = if (orders.isEmpty) Seq.empty[NodeBox] else nodeApi.boxesWithPoolByIds(orders.keys.toSeq).get
    forget(orders.keySet -- live.map(_.boxId))
    live.flatMap(ErgoDexOrder.parse).filter(order => orders.get(order.boxId).contains(order.poolNft))
  }

  /** Builds fee-less runs against confirmed pools, carrying verified wallet ancestry for mempool orders. */
  override protected def executions(orders: Tracked, blockHeight: Int, slots: Int,
                                    deadline: Deadline): Seq[CandidateBundle] = {
    val observed = observation(Some(deadline))
    if (!observed.fresh) {
      logger.info(s"No fresh mempool observation for block $blockHeight, offering no ErgoDEX " +
        s"executions: ${observed.failure.getOrElse("observation too old")}")
      Seq.empty
    } else nodeContext.getClient.execute { ctx =>
      val snapshot = evictedPlacements.restore(observed.snapshot.get, blockHeight, nodeApi)
      val spenders = BatchingMempool.spenders(snapshot)
      val creators = BatchingMempool.creators(snapshot)
      val skipped = skippedOrders()
      val candidates = (liveOrders(orders) ++
        ErgoDexBatching.unconfirmedOrders(snapshot, batching.deniedPools, batching.maxMempoolOrders,
          batching.maxMempoolOrdersPerTx))
        .groupBy(_.boxId).values.map(_.head).toSeq
        .filterNot(order => skipped.contains(order.boxId) ||
          BatchingMempool.withdrawn(order.boxId, order.poolNft, spenders))
      within(deadline, "reading tracked orders")
      val pools = currentPools(candidates.map(_.poolNft).distinct)
        .filter { case (_, box) => !creators.contains(box.boxId) }
      within(deadline, "reading pools")
      // Load ancestry only for orders that can be executed profitably.
      val priceable = candidates.filter(order => pools.get(order.poolNft).flatMap(ErgoDexPool.native)
        .exists(pool => ErgoDexExecution.price(order, pool, batching.minRevenueNanoErg, fundsItsOwnBox = false).nonEmpty))
      val placed = walletPlacements(priceable.flatMap(order => creators.get(order.boxId)), creators, spenders,
        math.min(slots - 1, batching.maxAncestorTxs), ErgoDexBatching.placedByWallet(_, _, spenders))
      val byPool = priceable
        .filter(order => creators.get(order.boxId).forall(tx => placed.contains(tx.id)))
        .groupBy(_.poolNft)
      within(deadline, "reading placements")
      val built = runs(ctx, byPool.toSeq.map { case (nft, poolOrders) => pools(nft) -> poolOrders }, slots, blockHeight,
        batching.minRevenueNanoErg, 0L, useTrueProp, deadline,
        order => creators.get(order.boxId).flatMap(tx => placed.get(tx.id)).getOrElse(Vector.empty))
      evictedPlacements.remember(built.flatMap(_._2), blockHeight)
      built.map { case (chain, placements) =>
          val spent = chain.fills.head.pool.boxId +: chain.fills.map(_.order.boxId)
          chain.bundle(BatchingMempool.competitors(spenders, spent),
            placements.map(tx => CandidateTx.ancestor(tx.body)))
        }
    }
  }

  /** Builds unclaimed orders against the mempool pool tip and records rejected executions. */
  override protected def broadcastPass(orders: Tracked, refused: Map[String, String]): BroadcastResult = {
    val observed = observation()
    require(observed.fresh, s"no fresh mempool observation: ${observed.failure.getOrElse("too old")}")
    val snapshot = observed.snapshot.get
    val spenders = BatchingMempool.spenders(snapshot)
    val sender = broadcaster()
    nodeContext.getClient.execute { ctx =>
      val skipped = skippedOrders()
      val unconfirmed =
        if (batching.broadcastMempoolOrders)
          ErgoDexBatching.unconfirmedOrders(snapshot, batching.deniedPools, batching.maxMempoolOrders,
            batching.maxMempoolOrdersPerTx)
        else Vector.empty
      val byPool = (liveOrders(orders) ++ unconfirmed).groupBy(_.boxId).values.map(_.head).toSeq
        .filterNot(order => snapshot.spent.contains(order.boxId) || skipped.contains(order.boxId))
        .groupBy(_.poolNft)
      val pools = currentPools(byPool.keys.toSeq).toSeq.flatMap { case (nft, current) =>
        for {
          tip <- BatchingMempool.poolTip(current, nft, spenders)
          open = byPool(nft).filterNot(order => refused.get(order.boxId).contains(tip.boxId))
          if open.nonEmpty
        } yield tip -> open
      }
      // The tip, not the block a broadcast might reach: a pool box stamped above the tip can only be
      // chained onto by a transaction that leaves no change, and other executors chain onto these pools.
      val refusedNow = runs(ctx, pools, batching.maxOrdersPerBlock, ctx.getHeight, batching.broadcastMinRevenueNanoErg,
        batching.broadcastMinerFeeCeiling, useTrueProp = false, BroadcastBudget.fromNow)
        .flatMap { case (chain, _) => send(sender, chain, observed) }.toMap
      BroadcastResult(refusedNow, unconfirmed.map(_.boxId).toSet)
    }
  }

  /**
   * The highest-revenue runs that fit, one per pool, counting shared ancestors once. Pools are ranked by
   * what their orders price at; each pool's run then passes over any order that cannot be built.
   */
  private def runs(ctx: BlockchainContext, pools: Seq[(NodeBox, Seq[ErgoDexOrder])], slots: Int,
                   blockHeight: Int, minRevenue: Long, minerFeeCeiling: Long, useTrueProp: Boolean,
                   deadline: Deadline,
                   placementOf: ErgoDexOrder => Vector[CompleteMempool.MempoolTx] = _ => Vector.empty)
  : Vector[(ErgoDexChain, Vector[CompleteMempool.MempoolTx])] = {
    val priced = pools
      .flatMap { case (box, poolOrders) => ErgoDexPool.native(box).map { pool =>
        (box, pool, poolOrders, ErgoDexExecution.priceChain(poolOrders, pool, minRevenue, slots, minerFeeCeiling, strategy))
      } }
      .filter(_._4.nonEmpty)
      .sortBy { case (box, _, _, fills) => (-fills.map(_.revenue).sum, box.boxId) }
    var left = slots
    var carried = Set.empty[String]
    priced.toVector.flatMap { case (box, pool, poolOrders, _) =>
      if (left <= 0 || deadline.isOverdue()) None
      else {
        // A pool box that cannot be read back costs that pool's run, never the others
        Try(ErgoDexExecution.run(ctx, nodeContext.getNodeWallet, box.toInputUTXO(ctx), pool, poolOrders, left,
          minRevenue, blockHeight, minerFeeCeiling, useTrueProp, deadline, placementOf, carried,
          Batcher.UnbuildableLimits(batching), strategy, searchBudget)) match {
          case Failure(ex) =>
            logger.warn(s"Could not run ErgoDEX pool ${pool.nft.take(12)} for block $blockHeight: ${ex.getMessage}")
            None
          case Success(run) =>
            run.unbuildable.foreach(skipOrder)
            if (run.cutShort)
              logger.warn(s"ErgoDEX run on pool ${pool.nft.take(12)} for block $blockHeight stopped with orders " +
                s"left after ${run.chain.map(_.transactions.size).getOrElse(0)} execution(s) and " +
                s"${run.unbuildable.size} unbuildable order(s): it reached its budget or its unbuildable limit")
            run.chain.map { chain =>
              left -= chain.transactions.size + run.placements.count(tx => !carried.contains(tx.id))
              carried ++= run.placements.map(_.id)
              chain -> run.placements
            }
        }
      }
    }
  }
}

object ErgoDexBatcher {
  /** The batching block and candidate source key this batcher reads. */
  final val Name: String = configs.CandidateSourceConfig.ErgoDex

  /** Maximum pool lookups per scan; each lookup requires a node call. */
  private[ergodex] final val MaxPoolReads = 512

  /** A broadcast pass is not bound by the stratum's deadline; this only ends a run that would not. */
  private final val BroadcastBudget = 60.seconds
}
