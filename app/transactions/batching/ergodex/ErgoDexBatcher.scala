package transactions.batching.ergodex

import akka.actor.ActorRef
import configs.{BatchingConfig, CandidateConfig, NodeContext}
import node.MutationConversions._
import node.model.{IndexedBox, MempoolOptions, NodeBox, Paging, SortDirection}
import org.ergoplatform.appkit.BlockchainContext
import play.api.Configuration
import state.synchronization.CompleteMempool
import transactions.batching.Batcher.{BroadcastResult, Tracked}
import transactions.batching.arb.{ArbMachina, LithosDexArbPool, MachinaHandoff}
import transactions.batching.lithosdex.LDDeployments
import transactions.batching.tracking.{PoolDeployment, PoolTracker, TrackedBox, TrackedPools}
import transactions.batching.{Batcher, BatchingMempool}
import transactions.candidate.BlockTxMessages.CandidateTx
import transactions.candidate.CandidateBundle

import javax.inject.{Inject, Named}
import scala.util.{Failure, Success, Try}
import scala.concurrent.duration._

/**
 * Scans executable v1 orders on both pool shapes and builds candidate or broadcast runs on background workers.
 *
 * @param machinaHandoff where Machina orders a LithosDex ERG pool pays more for go instead of this batcher's
 *                       candidate runs, for the arb stage to fill. None while arb is off, which routes every Machina
 *                       order through ErgoDEX
 */
class ErgoDexBatcher(nodeContext: NodeContext,
                     batching: BatchingConfig,
                     servesCandidates: Boolean,
                     engine: ActorRef,
                     useTrueProp: Boolean,
                     buildBudgetMs: Long = Batcher.DefaultBuildBudgetMs,
                     poolTracking: Option[PoolTracker] = None,
                     machinaHandoff: Option[MachinaHandoff] = None)
  extends Batcher(nodeContext, batching, servesCandidates, engine, configs.Contexts.BatchingIo, buildBudgetMs) {

  import ErgoDexBatcher._

  /** Built by `Module`, reading every setting from the application configuration. */
  @Inject()
  def this(nodeContext: NodeContext, config: Configuration, @Named("transaction-engine") engine: ActorRef,
           tracker: PoolTracker, handoff: MachinaHandoff) =
    this(nodeContext, BatchingConfig(config, ErgoDexBatcher.Name),
      Batcher.servesCandidates(config, ErgoDexBatcher.Name), engine,
      CandidateConfig(config).useTruePropCollection, Batcher.buildBudgetMs(config), Some(tracker),
      Some(handoff).filter(_.active))

  override protected def poolTracker: Option[PoolTracker] = poolTracking

  /**
   * Machina orders by box id, each with the LithosDex ERG pool that fills it for more than any ErgoDEX pool, as the
   * last discovery found them. Replaced whole by each discovery; empty while arb is off.
   */
  @volatile private var lithosDexRoutes: Map[String, String] = Map.empty

  override protected val label: String = "ErgoDEX"

  override protected def scansConfirmedOnly: Boolean = true

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
      ErgoDexPool.parse(indexed.box).map(_ -> indexed.inclusionHeight)))
    // Machina orders name a token rather than a pool, so every native pool is read once, and only when there are
    // any, and each order is routed through the one that pays most
    val machina = MachinaContracts.templates.filter(_.executable)
      .flatMap(template => scanTemplate(template.templateHash, s"Machina ${template.kind.name}")(MachinaOrder.parse))
      .filterNot(order => skipped.contains(order.boxId))
    val routed = if (machina.isEmpty) Seq.empty else ErgoDexBatching.route(machina, nativePools(tip), tip, batching)
    val toLithosDex = machinaHandoff.filter(_ => machina.nonEmpty)
      .map(handoff => routeLithosDex(machina, routed, tip, handoff.deniedPools)).getOrElse(Vector.empty)
    lithosDexRoutes = toLithosDex.map { case (id, nft, _) => id -> nft }.toMap
    // An order no ErgoDEX pool fills is tracked by its LithosDex pool, so builds still read it back
    val viaErgoDex = routed.map(_._1.boxId).toSet
    // A token/token grid names no pool: the arb stage picks two at build, so it is tracked by none
    val grids = machinaHandoff.toVector.flatMap(_ => tokenGrids(tip, skipped))
    ErgoDexBatching.track(orders ++ routed.map(_._1), pools ++ routed.map(_._2), tip, batching) ++
      toLithosDex.filterNot(route => viaErgoDex.contains(route._1)).take(batching.maxTrackedOrders)
        .map { case (id, nft, _) => id -> nft } ++
      grids.map(_.boxId -> "")
  }

  /**
   * Token/token grid orders the arb stage could fill: the pool tracker, within [[TrackedLagBlocks]] of `tip`, holds a
   * token/token pool of the order's pair and an ERG pool of its base token. At most `maxTrackedOrders`.
   */
  private def tokenGrids(tip: Int, skipped: Set[String]): Vector[MachinaOrder] = {
    val template = MachinaContracts.tokenGrid
    val found = scanTemplate(template.templateHash, s"Machina ${template.kind.name}")(MachinaOrder.parseTokenGrid)
      .filterNot(order => skipped.contains(order.boxId))
    val tracked = if (found.isEmpty) Seq.empty
      else poolTracking.flatMap(_.latest).filter(state => tip - state.height <= TrackedLagBlocks).toSeq.flatMap(_.pools)
    def tokens(t: TrackedBox, at: Int*): Seq[String] = at.flatMap(i => t.box.assets.lift(i).map(_.tokenId))
    val pairs = tracked.collect {
      case t if t.deployment == PoolDeployment.ErgoDexToken => tokens(t, 2, 3).toSet
      case t @ TrackedBox(_, _, PoolDeployment.LithosDexToken(_)) => tokens(t, 1, 2).toSet
    }.toSet
    val withErgPool = tracked.flatMap {
      case t if t.deployment == PoolDeployment.ErgoDexNative => tokens(t, 2)
      case t @ TrackedBox(_, _, PoolDeployment.LithosDexNative(_)) => tokens(t, 1)
      case _ => Seq.empty
    }.toSet
    found.filter(order => order.base.exists(base => pairs.contains(Set(base, order.tokenId)) && withErgPool.contains(base)))
      .take(batching.maxTrackedOrders)
  }

  /**
   * The Machina orders a LithosDex ERG pool fills for more than the ErgoDEX pool `routed` chose for them, or fills
   * when no ErgoDEX pool does: each with the pool's NFT and its gap, best first. Pools are the tracker's as long as
   * it is within [[TrackedLagBlocks]] of `tip`, and only those whose fee vault stands, as the arb stage requires;
   * with none, every order stays with ErgoDEX. One vault read per pool of an order's token.
   */
  private def routeLithosDex(machina: Seq[MachinaOrder], routed: Seq[(MachinaOrder, (ErgoDexPool, Int))], tip: Int,
                             denied: Set[String]): Vector[(String, String, Long)] = {
    val tokens = machina.map(_.tokenId).toSet
    val boxes = poolTracking.flatMap(_.latest).filter(state => tip - state.height <= TrackedLagBlocks).toSeq
      .flatMap(_.pools).collect {
        case t @ TrackedBox(box, _, PoolDeployment.LithosDexNative(contracts))
          if !denied.contains(t.nft.toLowerCase) && box.assets.lift(1).exists(a => tokens.contains(a.tokenId)) =>
          box -> contracts
      }.filter { case (_, contracts) => Try(LDDeployments.vaultStands(nodeApi, contracts)).getOrElse(false) }
    if (boxes.isEmpty) Vector.empty
    else nodeContext.getClient.execute { ctx =>
      val pools = boxes.flatMap { case (box, contracts) => Try(LithosDexArbPool(box.toInputUTXO(ctx), contracts)).toOption }
      val throughErgoDex = routed.flatMap { case (order, (pool, _)) =>
        ErgoDexExecution.price(order, pool, batching.minRevenueNanoErg, fundsItsOwnBox = false)
          .map(order.boxId -> _.revenue)
      }.toMap
      ArbMachina.preferLithosDex(machina, throughErgoDex, pools,
        math.max(batching.minRevenueNanoErg, work.lithos.mutations.UTXO.MIN_CHANGE))
    }
  }

  /**
   * Hands the arb stage this block's Machina orders a LithosDex pool fills for more, each routed through that pool,
   * and its token/token grids, and returns the rest for this batcher's run. Orders the arb stage could not build go
   * on this batcher's skip list, as its own unbuildable orders do. Everything stays, while arb is off.
   */
  private def handOff(candidates: Seq[PoolOrder], blockHeight: Int): Seq[PoolOrder] = machinaHandoff match {
    case None => candidates
    case Some(handoff) =>
      val refused = handoff.takeRefused()
      refused.foreach(skipOrder)
      val routes = lithosDexRoutes
      val (toArb, rest) = candidates.filterNot(order => refused.contains(order.boxId)).partition {
        case machina: MachinaOrder => ErgoDexBatching.tokenGrid(machina) || routes.contains(machina.boxId)
        case _ => false
      }
      handoff.offer(blockHeight, toArb.collect {
        case grid: MachinaOrder if ErgoDexBatching.tokenGrid(grid) => grid
        case machina: MachinaOrder => machina.routedThrough(routes(machina.boxId))
      })
      rest
  }

  /**
   * Every confirmed native pool with its inclusion height: from the pool tracker while it is within
   * [[TrackedLagBlocks]] of `tip` and no cap cut it, otherwise parsed from a scan a page at a time, up to
   * [[ErgoDexPoolScan.MaxPages]] pages.
   */
  private def nativePools(tip: Int): Seq[(ErgoDexPool, Int)] =
    poolTracking.flatMap(_.latest).filter(state => !state.cut && tip - state.height <= TrackedLagBlocks) match {
      case Some(state) =>
        state.pools.filter(_.deployment == PoolDeployment.ErgoDexNative)
          .flatMap(t => ErgoDexPool.native(t.box).map(_ -> t.inclusionHeight)).toVector
      case None =>
        val (found, exhausted) = ErgoDexPoolScan.confirmed(nodeApi, ErgoDexContracts.NativePoolErgoTree)(indexed =>
          ErgoDexPool.native(indexed.box).map(_ -> indexed.inclusionHeight))
        if (!exhausted)
          logger.warn(s"ErgoDEX native pool scan stopped after ${ErgoDexPoolScan.MaxPages} pages; Machina orders " +
            "were routed through those only")
        found
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

  /**
   * The confirmed box of each pool in `nfts` as of the block a candidate builds on: from `tracked` with no node
   * read, and from the node for a pool `tracked` lacks only when a cap cut it. Without `tracked`, from the node.
   */
  private def currentPools(nfts: Seq[String], tracked: Option[TrackedPools]): Map[String, NodeBox] =
    tracked match {
      case Some(state) =>
        val (held, missing) = nfts.partition(nft => state.pool(nft).isDefined)
        held.map(nft => nft -> state.pool(nft).get.box).toMap ++ (if (state.cut) currentPools(missing) else Map.empty)
      case None => currentPools(nfts)
    }

  /** Reloads tracked orders, retaining mempool-claimed boxes and reporting missing ids as spent. */
  private def liveOrders(orders: Tracked): Seq[PoolOrder] = {
    val live = if (orders.isEmpty) Seq.empty[NodeBox] else nodeApi.boxesWithPoolByIds(orders.keys.toSeq).get
    forget(orders.keySet -- live.map(_.boxId))
    live.flatMap(box => ErgoDexBatching.parse(box, orders.get(box.boxId)))
      .filter(order => orders.get(order.boxId).contains(order.poolNft))
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
      val candidates = handOff((liveOrders(orders) ++
        ErgoDexBatching.unconfirmedOrders(snapshot, batching.deniedPools, batching.maxMempoolOrders,
          batching.maxMempoolOrdersPerTx))
        .groupBy(_.boxId).values.map(_.head).toSeq
        .filterNot(order => skipped.contains(order.boxId) ||
          ErgoDexBatching.withdrawn(order, spenders)), blockHeight)
      within(deadline, "reading tracked orders")
      val pools = currentPools(candidates.map(_.poolNft).distinct, trackedPools(blockHeight, deadline))
        .filter { case (_, box) => !creators.contains(box.boxId) }
      within(deadline, "reading pools")
      // Load ancestry only for orders that can be executed profitably.
      val priceable = candidates.filter(order => pools.get(order.poolNft).flatMap(ErgoDexPool.parse)
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
      // An order only a LithosDex pool fills, or a token/token grid, is the arb stage's, in this miner's own block
      val routes = lithosDexRoutes
      val byPool = (liveOrders(orders) ++ unconfirmed).groupBy(_.boxId).values.map(_.head).toSeq
        .filterNot(order => snapshot.spent.contains(order.boxId) || skipped.contains(order.boxId) ||
          routes.get(order.boxId).contains(order.poolNft) || ErgoDexBatching.tokenGrid(order))
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
  private def runs(ctx: BlockchainContext, pools: Seq[(NodeBox, Seq[PoolOrder])], slots: Int,
                   blockHeight: Int, minRevenue: Long, minerFeeCeiling: Long, useTrueProp: Boolean,
                   deadline: Deadline,
                   placementOf: PoolOrder => Vector[CompleteMempool.MempoolTx] = _ => Vector.empty)
  : Vector[(ErgoDexChain, Vector[CompleteMempool.MempoolTx])] = {
    val priced = pools
      .flatMap { case (box, poolOrders) => ErgoDexPool.parse(box).map { pool =>
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

  /** How far behind the chain the pool tracker may be for a scan to route Machina orders on its pools. */
  private final val TrackedLagBlocks = 2
}
