package transactions.batching.lithosdex

import akka.actor.ActorRef
import configs.{CandidateConfig, LithosDexBatchingConfig, NodeContext}
import lithosdex.contracts.{LDContracts, LDOrderKind, LDOrderContracts}
import lithosdex.{LDHelpers, LDLiquidityPool}
import node.MutationConversions._
import node.model.{IndexedBox, MempoolOptions, NodeBox, Paging, SortDirection}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.BlockchainContext
import play.api.Configuration
import state.synchronization.CompleteMempool
import transactions.batching.Batcher.{BroadcastResult, Tracked}
import transactions.batching.{Batcher, BatchingMempool}
import transactions.candidate.BlockTxMessages.CandidateTx
import transactions.candidate.CandidateBundle
import work.lithos.mutations.InputUTXO

import javax.inject.{Inject, Named}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

/**
 * Executes LithosDex orders against the ERG:LIT pool, for this miner's block or by broadcast. With
 * `discoverPools`, both also serve every other deployment [[LDDeployments]] verifies on chain, each run
 * built under its own deployment's contracts.
 *
 * In this miner's own block every unconfirmed spend of the pool, of an order it fills and of a
 * provision it closes is superseded: the pool is meant to be used through orders, and whatever else
 * spends it is resent against the pool this block leaves. With `autoFlush`, each run ends by moving the
 * pool's pending fees into the vault. A broadcast displaces nothing and never flushes.
 *
 *  TODO: provision refreshes belong to this adapter. LD_Provision's refresh path (op 2) is
 *  permissionless once a provision box is `LDHelpers.REFRESH_AGE` (525600) blocks old, and a box left
 *  unspent for a full storage period can lose its provision token to storage rent. Scan the provision
 *  token's boxes and refresh any past that age. No box can reach it until two years after launch.
 *
 */
class LithosDexBatcher(nodeContext: NodeContext,
                       settings: LithosDexBatchingConfig,
                       servesCandidates: Boolean,
                       engine: ActorRef,
                       useTrueProp: Boolean,
                       buildBudgetMs: Long = Batcher.DefaultBuildBudgetMs)
  extends Batcher(nodeContext, settings.batching, servesCandidates, engine, configs.Contexts.LithosDexBatchingIo,
    buildBudgetMs) {

  import LithosDexBatcher._

  /** Built by `Module`, reading every setting from the application configuration. */
  @Inject()
  def this(nodeContext: NodeContext, config: Configuration, @Named("transaction-engine") engine: ActorRef) =
    this(nodeContext, LithosDexBatchingConfig(config), Batcher.servesCandidates(config, LithosDexBatchingConfig.Name),
      engine, CandidateConfig(config).useTruePropCollection, Batcher.buildBudgetMs(config))

  override protected val label: String = "LithosDEX"

  private val batching = settings.batching
  private val poolNft: String = LDHelpers.getPoolNFT(nodeContext.getNetwork).toString

  /** Deployments besides the canonical one the last scan served, by pool NFT, so their vaults need no second read. */
  @volatile private var served: Map[String, LDContracts] = Map.empty

  /** Every order template's bytes as hex. A tree ends with its template, so this finds candidate outputs cheaply. */
  private val templatesHex: Seq[String] =
    LDOrderKind.all.map(kind => Hex.toHexString(LDOrderContracts.template(kind).templateBytes))

  /** The pool contract's template bytes as hex, which every deployment's pool tree ends with. */
  private lazy val poolTemplateHex: String =
    Hex.toHexString(DexContracts(nodeContext.getNetwork).liquidityPool.ergoTree.template)

  /** The claim that may place a redeem order on `contracts`' pool, checked against that deployment's boxes. */
  private def claimPlacement(contracts: LDContracts): LDClaimPlacement =
    new LDClaimPlacement(contracts.poolNFT.toString, contracts.vaultNFT.toString, contracts.feeVault.ergoTreeHex,
      contracts.provisionGuard.ergoTreeHex, contracts.provToken.toString)

  /** Pools this batcher may execute against: the canonical one, and with `discoverPools` any other, less denied. */
  private def eligible(nft: String): Boolean =
    !batching.deniedPools.contains(nft.toLowerCase) && (nft == poolNft || settings.discoverPools)

  /**
   * The deployment `box` is the pool of: the canonical one when the box sits at its tree, otherwise one
   * [[LDDeployments.verify]] accepts whose vault stands. A deployment in `known` with the same ids already
   * had its vault read, so it costs no node read; any other costs one.
   */
  private def deploymentOf(box: NodeBox, known: Map[String, LDContracts]): Option[LDContracts] =
    box.assets.headOption.map(_.tokenId).filter(eligible).flatMap { nft =>
      if (nft == poolNft) Some(DexContracts(nodeContext.getNetwork)).filter(_.liquidityPool.ergoTreeHex == box.ergoTree)
      else LDDeployments.verify(nodeContext.getNetwork, box.ergoTree, box.assets.map(asset => asset.tokenId -> asset.amount))
        .filter(found => known.get(nft).exists(sameIds(_, found)) || LDDeployments.vaultStands(nodeApi, found))
    }

  private def sameIds(a: LDContracts, b: LDContracts): Boolean =
    a.poolNFT.toString == b.poolNFT.toString && a.vaultNFT.toString == b.vaultNFT.toString &&
      a.provToken.toString == b.provToken.toString

  /**
   * Scans every order template, reads the pool each distinct pool NFT names, and prices the orders against
   * it. As for ErgoDEX, the pools come from the orders and a node read that fails fails the scan, which
   * keeps the previous set; a pool box that is not a served deployment's, or will not read, costs its own orders.
   */
  override protected def discover(): Tracked = nodeContext.getClient.execute { ctx =>
    // Left out before ranking, so an order declaring a fee it can never pay holds no tracked slot
    val skipped = skippedOrders()
    val orders = LDOrderKind.all
      .flatMap(kind => scanTemplate(LDOrderContracts.template(kind).templateHash, kind.scriptName)(LithosDexOrder.parse))
      .filter(order => eligible(order.poolNft) && !skipped.contains(order.boxId))
      .groupBy(_.boxId).values.map(_.head).toSeq
    // The canonical pool is read first, so no number of other pools can push it out
    val nfts = orders.map(_.poolNft).distinct.sortBy(nft => (nft != poolNft, nft))
    if (nfts.size > MaxPoolReads)
      logger.warn(s"LithosDEX orders name ${nfts.size} pools; reading the first $MaxPoolReads this pass")
    val known = served
    val read = nfts.take(MaxPoolReads).flatMap { nft =>
      val found = confirmedPool(nft).flatMap(indexed => deploymentOf(indexed.box, known).flatMap(contracts =>
        Try(LDLiquidityPool(indexed.box.toInputUTXO(ctx))).toOption.map(pool => (contracts, pool, indexed.inclusionHeight))))
      if (found.isEmpty && nft == poolNft)
        logger.warn(s"No confirmed box carrying $nft reads as the LithosDEX pool; its orders are not tracked this scan")
      found
    }
    // As for ErgoDEX, the newest maxTrackedPools, with the canonical pool always kept
    val (canonical, others) = read.partition(_._1.poolNFT.toString == poolNft)
    val pools = canonical ++ others
      .sortBy { case (contracts, _, included) => (-included, contracts.poolNFT.toString) }
      .take(math.max(0, batching.maxTrackedPools - canonical.size))
    if (others.map(_._1.poolNFT.toString).toSet != served.keySet)
      logger.info(s"LithosDEX serves ${others.size} pool(s) besides the canonical one")
    served = others.map { case (contracts, _, _) => contracts.poolNFT.toString -> contracts }.toMap

    val byPool = orders.groupBy(_.poolNft)
    pools
      .flatMap { case (contracts, pool, _) =>
        val poolOrders = byPool.getOrElse(contracts.poolNFT.toString, Seq.empty)
        val provisions = provisionsFor(ctx, contracts, poolOrders, MempoolOptions.ConfirmedOnly)
        poolOrders.flatMap(order =>
          LithosDexExecution.price(order, pool, batching.minRevenueNanoErg, provisions, fundsItsOwnBox = false))
      }
      .sortBy(fill => (-fill.revenue, fill.order.boxId))
      .take(batching.maxTrackedOrders)
      .map(fill => fill.order.boxId -> fill.order.poolNft)
      .toMap
  }

  /**
   * Fee-less runs against each named pool's confirmed box, superseding every competing spend of what they
   * claim. As for ErgoDEX, one run per pool shares the block's slots, pools ranked by what their orders price
   * at, and a placement two runs share takes one slot. A pool that cannot be read or run costs that pool alone.
   */
  override protected def executions(orders: Tracked, blockHeight: Int, slots: Int,
                                    deadline: Deadline): Seq[CandidateBundle] = {
    val observed = observation(Some(deadline))
    if (!observed.fresh) {
      logger.info(s"No fresh mempool observation for block $blockHeight, offering no LithosDEX " +
        s"executions: ${observed.failure.getOrElse("observation too old")}")
      Seq.empty
    } else nodeContext.getClient.execute { ctx =>
      val snapshot = evictedPlacements.restore(observed.snapshot.get, blockHeight, nodeApi)
      val spenders = BatchingMempool.spenders(snapshot)
      val creators = BatchingMempool.creators(snapshot)
      val skipped = skippedOrders()
      val candidates = (liveOrders(orders) ++ unconfirmedOrders(snapshot, eligible))
        .groupBy(_.boxId).values.map(_.head).toSeq
        .filterNot(order => !eligible(order.poolNft) || skipped.contains(order.boxId) ||
          BatchingMempool.withdrawn(order.boxId, order.poolNft, spenders))
      within(deadline, "reading tracked orders")

      val known = served
      val plans = candidates.groupBy(_.poolNft).toSeq.sortBy { case (nft, _) => readOrder(nft, known) }.flatMap {
        case (nft, poolOrders) =>
          if (deadline.isOverdue()) None
          else Try(plan(ctx, nft, poolOrders, known, creators)) match {
            case Success(found) => found
            case Failure(ex) =>
              logger.warn(s"Could not read LithosDEX pool $nft for block $blockHeight, the other pools go on: " +
                ex.getMessage)
              None
          }
      }
      within(deadline, "reading the pools and provisions")

      // Each run's flush takes one slot of its own
      val flushSlot = if (settings.autoFlush) 1 else 0
      val nfts = plans.map(_.contracts.poolNFT.toString).toSet + poolNft
      val placed = walletPlacements(plans.flatMap(_.fills).flatMap(fill => creators.get(fill.order.boxId)), creators,
        spenders, math.min(slots - flushSlot - 1, batching.maxAncestorTxs), (tx, boxes) =>
          BatchingMempool.placedByWallet(tx, boxes, spenders, createsPool(nfts)) ||
            plans.exists(_.claims.accepts(tx, boxes, spenders)))
      val placementOf = (order: LithosDexOrder) =>
        creators.get(order.boxId).flatMap(tx => placed.get(tx.id)).getOrElse(Vector.empty[CompleteMempool.MempoolTx])
      within(deadline, "reading placements")

      var left = slots
      var carried = Vector.empty[CompleteMempool.MempoolTx]
      // Ranked by what the strategy would take from each pool on its own
      val ranked = plans.map(plan => plan -> LithosDexExecution.priceChain(plan.fills.map(_.order),
        LDLiquidityPool(plan.poolBox), batching.minRevenueNanoErg, plan.provisions, slots, strategy = strategy)
        .map(_.revenue).sum)
        .sortBy { case (plan, value) => (-value, plan.contracts.poolNFT.toString) }.map(_._1)
      val bundles = ranked.flatMap { plan =>
        val runSlots = left - flushSlot
        if (runSlots <= 0 || deadline.isOverdue()) None
        else Try(runPlan(ctx, plan, runSlots, blockHeight, deadline, spenders, creators, placed, placementOf,
          carried.map(_.id).toSet)) match {
          case Success(Some((closed, competitors, placements))) =>
            val added = placements.filterNot(tx => carried.exists(_.id == tx.id))
            left -= closed.members.size + added.size
            carried ++= added
            Some(closed.bundle(competitors, placements.map(tx => CandidateTx.ancestor(tx.body))))
          case Success(None) => None
          case Failure(ex) =>
            logger.warn(s"Could not run LithosDEX pool ${plan.contracts.poolNFT} for block $blockHeight, the other " +
              s"pools go on: ${ex.getMessage}")
            None
        }
      }
      evictedPlacements.remember(carried, blockHeight)
      bundles
    }
  }

  /**
   * `nft`'s pool as this block would run it: its confirmed box, provisions and the fills its orders price at.
   * None when the box is not a served deployment's pool, a mempool transaction created it, or nothing prices.
   */
  private def plan(ctx: BlockchainContext, nft: String, orders: Seq[LithosDexOrder], known: Map[String, LDContracts],
                   creators: Map[String, CompleteMempool.MempoolTx]): Option[PoolPlan] =
    for {
      (box, contracts) <- currentPool(nft, known).filterNot { case (box, _) => creators.contains(box.boxId) }
      poolBox = box.toInputUTXO(ctx)
      // A pool box that will not read costs its own orders, as a malformed ErgoDEX pool does
      pool <- Try(LDLiquidityPool(poolBox)).toOption
      claims = claimPlacement(contracts)
      // Confirmed provisions, including one a refresh is spending, since this block supersedes the refresh
      provisions = claimedProvisions(ctx, claims,
        provisionsFor(ctx, contracts, orders, MempoolOptions.ConfirmedOnly), orders, creators)
      fills = orders.flatMap(order =>
        LithosDexExecution.price(order, pool, batching.minRevenueNanoErg, provisions, fundsItsOwnBox = false))
      if fills.nonEmpty
    } yield PoolPlan(contracts, poolBox, claims, provisions, fills)

  /**
   * The order pools are read in: the canonical one first, then those the last scan served, then new ones, so
   * mempool orders naming many new pools cannot use up a build's deadline before the known ones are read.
   */
  private def readOrder(nft: String, known: Map[String, LDContracts]): (Boolean, Boolean, String) =
    (nft != poolNft, !known.contains(nft), nft)

  /** `plan`'s run within `runSlots`, closed with a flush, with the competitors it supersedes and its placements. */
  private def runPlan(ctx: BlockchainContext, plan: PoolPlan, runSlots: Int, blockHeight: Int, deadline: Deadline,
                      spenders: BatchingMempool.Spenders, creators: Map[String, CompleteMempool.MempoolTx],
                      placed: Map[String, Vector[CompleteMempool.MempoolTx]],
                      placementOf: LithosDexOrder => Vector[CompleteMempool.MempoolTx], alreadyCarried: Set[String])
  : Option[(LithosDexChain, Set[String], Vector[CompleteMempool.MempoolTx])] = {
    val usable = plan.fills.map(_.order).filter(order => creators.get(order.boxId).forall(tx => placed.contains(tx.id)))
    val run = LithosDexExecution.run(ctx, nodeContext.getNodeWallet, plan.contracts, plan.poolBox, usable, runSlots,
      batching.minRevenueNanoErg, plan.provisions, blockHeight, 0L, useTrueProp, deadline, placementOf,
      Batcher.UnbuildableLimits(batching), alreadyCarried, strategy, searchBudget)
    settle(run, blockHeight)
    run.chain.map { chain =>
      val claimed = plan.poolBox.id.toString +: (chain.fills.map(_.order.boxId) ++
        chain.fills.flatMap(_.provision.map(_.boxId)))
      val competitors = BatchingMempool.competitors(spenders, claimed)
      // The vault is one more node read, so a run that has used its budget goes without the flush
      val closed = if (settings.autoFlush && !deadline.isOverdue())
        flushed(ctx, plan.contracts, chain, blockHeight, spenders, creators, competitors) else chain
      (closed, competitors, run.placements)
    }
  }

  /**
   * `confirmed`, except for a redeem order placed by the claim that settled its provision: that claim
   * spends the confirmed provision, so the only one the order can close is the claim's own output. Whether
   * the claim may be carried is decided with the other placements.
   */
  private def claimedProvisions(ctx: BlockchainContext, claims: LDClaimPlacement,
                                confirmed: LithosDexExecution.Provisions, candidates: Seq[LithosDexOrder],
                                creators: Map[String, CompleteMempool.MempoolTx]): LithosDexExecution.Provisions = {
    val claimed = candidates
      .collect { case redeem: LithosDexOrder.Redeem => redeem }
      // An output that will not read as a provision costs its own order, never the run
      .flatMap(redeem => creators.get(redeem.boxId).flatMap(claims.provisionFor(_, redeem))
        .flatMap(box => Try(LDBoxes.readProvision(box.toInputUTXO(ctx))).toOption)
        .map(redeem.ownerNft -> _))
      .toMap
    if (claimed.isEmpty) confirmed else nft => claimed.get(nft).orElse(confirmed(nft))
  }

  /** Leaves this run's unbuildable orders out from now on, and says when the run stopped with orders left. */
  private def settle(run: LithosDexRun, blockHeight: Int): Unit = {
    run.unbuildable.foreach(skipOrder)
    if (run.cutShort)
      logger.warn(s"LithosDEX run for block $blockHeight stopped with orders left after " +
        s"${run.chain.map(_.transactions.size).getOrElse(0)} execution(s) and ${run.unbuildable.size} unbuildable " +
        "order(s): it reached its budget or its unbuildable limit")
  }

  /**
   * `chain` closed with a flush when the vault is confirmed and nothing outside the run's own competitors
   * spends it. A claim already spending the vault is left alone: the flush is optional and the claim is not.
   */
  private def flushed(ctx: BlockchainContext, contracts: LDContracts, chain: LithosDexChain, blockHeight: Int,
                      spenders: BatchingMempool.Spenders, creators: Map[String, CompleteMempool.MempoolTx],
                      competitors: Set[String]): LithosDexChain =
    // A vault that cannot be read costs the flush, never the run
    Try(currentSingleton(contracts.vaultNFT.toString, contracts.feeVault.ergoTreeHex)).toOption.flatten
      .filter(box => !creators.contains(box.boxId) &&
        spenders.getOrElse(box.boxId, Vector.empty).forall(tx => competitors.contains(tx.id)))
      .map(box => LithosDexExecution.withFlush(ctx, nodeContext.getNodeWallet, contracts, chain, box.toInputUTXO(ctx),
        blockHeight))
      .getOrElse(chain)

  /**
   * Builds unclaimed orders against each pool's mempool tip, at the fee ceiling, and records refusals. As for
   * ErgoDEX, a pool only a mempool order names is read and checked here. Each pool is its own run with its
   * own takings box, and a pool that fails, in its read or its run, costs that pool alone.
   */
  override protected def broadcastPass(orders: Tracked, refused: Map[String, String]): BroadcastResult = {
    val observed = observation()
    require(observed.fresh, s"no fresh mempool observation: ${observed.failure.getOrElse("too old")}")
    val snapshot = observed.snapshot.get
    val spenders = BatchingMempool.spenders(snapshot)
    val sender = broadcaster()
    val known = served
    nodeContext.getClient.execute { ctx =>
      val skipped = skippedOrders()
      val unconfirmed =
        if (batching.broadcastMempoolOrders) unconfirmedOrders(snapshot, eligible) else Vector.empty
      val byPool = (liveOrders(orders) ++ unconfirmed).groupBy(_.boxId).values.map(_.head).toSeq
        .filterNot(order => snapshot.spent.contains(order.boxId) || skipped.contains(order.boxId))
        .groupBy(_.poolNft)

      val pools = byPool.keys.toSeq.sortBy(readOrder(_, known)).flatMap { nft =>
        Try(currentPool(nft, known)) match {
          case Success(found) => found.map(nft -> _)
          case Failure(ex) =>
            logger.warn(s"Could not read LithosDEX pool $nft for a broadcast, the other pools go on: ${ex.getMessage}")
            None
        }
      }
      val height = ctx.getHeight
      val refusedNow = pools.flatMap { case (nft, (current, contracts)) =>
        Try {
          BatchingMempool.poolTip(current, nft, spenders).toSeq
            // A pool box that will not read costs its own orders, without a warning every pass
            .filter(tipBox => Try(LDLiquidityPool(tipBox.toInputUTXO(ctx))).isSuccess)
            .flatMap { tipBox =>
              val poolOrders = byPool(nft)
              val poolBox = tipBox.toInputUTXO(ctx)
              val provisions = provisionsFor(ctx, contracts, poolOrders, MempoolOptions.WithMempool)
              val run = LithosDexExecution.run(ctx, nodeContext.getNodeWallet, contracts, poolBox,
                poolOrders.filterNot(order => refused.get(order.boxId).contains(tipBox.boxId)),
                batching.maxOrdersPerBlock, batching.broadcastMinRevenueNanoErg, provisions, height,
                batching.broadcastMinerFeeCeiling, useTrueProp = false, BroadcastBudget.fromNow,
                unbuildableLimits = Batcher.UnbuildableLimits(batching), strategy = strategy,
                searchBudget = searchBudget)
              settle(run, height)
              run.chain.flatMap(chain => send(sender, chain, observed))
            }
        } match {
          case Success(refusals) => refusals
          case Failure(ex) =>
            logger.warn(s"LithosDEX broadcast for pool $nft failed, the other pools go on: ${ex.getMessage}")
            Seq.empty
        }
      }.toMap
      BroadcastResult(refusedNow, unconfirmed.map(_.boxId).toSet)
    }
  }

  /**
   * A box at the pool contract of any deployment, or carrying one of `nfts`, so a placement creating one is
   * not a wallet placement.
   */
  private def createsPool(nfts: Set[String])(box: NodeBox): Boolean =
    box.ergoTree.endsWith(poolTemplateHex) || box.assets.exists(asset => nfts.contains(asset.tokenId))

  /** The one confirmed box carrying `nft`, from the index, whatever its script. */
  private def confirmedPool(nft: String): Option[IndexedBox] = {
    val boxes = nodeApi.unspentBoxesByTokenId(nft, Paging(0, 2), SortDirection.Desc, MempoolOptions.ConfirmedOnly).get
    if (boxes.size == 1) boxes.headOption else None
  }

  /** The confirmed pool box `nft` names and the deployment it belongs to, rechecked against the UTXO set. */
  private def currentPool(nft: String, known: Map[String, LDContracts]): Option[(NodeBox, LDContracts)] =
    confirmedPool(nft).flatMap(indexed => deploymentOf(indexed.box, known).map(indexed.box -> _))
      .flatMap { case (listed, contracts) =>
        nodeApi.boxesWithPoolByIds(Seq(listed.boxId)).get.find(_.boxId == listed.boxId).map(_ -> contracts)
      }

  /** The one confirmed box carrying `nft` under `tree`, from the index. */
  private def confirmedSingleton(nft: String, tree: String): Option[NodeBox] =
    confirmedPool(nft).map(_.box).filter(_.ergoTree == tree)

  /** The confirmed singleton, rechecked against the UTXO set so a box the chain has spent is not offered. */
  private def currentSingleton(nft: String, tree: String): Option[NodeBox] =
    confirmedSingleton(nft, tree).flatMap(listed =>
      nodeApi.boxesWithPoolByIds(Seq(listed.boxId)).get.find(_.boxId == listed.boxId))

  /** Reloads tracked orders, keeping mempool-claimed boxes and reporting missing ids as spent. */
  private def liveOrders(orders: Tracked): Seq[LithosDexOrder] = {
    val live = if (orders.isEmpty) Seq.empty[NodeBox] else nodeApi.boxesWithPoolByIds(orders.keys.toSeq).get
    forget(orders.keySet -- live.map(_.boxId))
    live.flatMap(LithosDexOrder.parse).filter(order => orders.get(order.boxId).contains(order.poolNft))
  }

  /** Orders created by unconfirmed transactions naming a pool `keep` accepts, selected by [[Batcher.mempoolOrderBoxes]]. */
  private def unconfirmedOrders(snapshot: CompleteMempool.Snapshot, keep: String => Boolean): Vector[LithosDexOrder] =
    Batcher.mempoolOrderBoxes(snapshot, box => templatesHex.exists(box.ergoTree.endsWith),
      batching.maxMempoolOrders, batching.maxMempoolOrdersPerTx)
      .flatMap(LithosDexOrder.parse)
      .filter(order => keep(order.poolNft))

  /**
   * The provision each redemption among `orders` closes. Scans the guard address only when there is a
   * redemption to price. `ConfirmedOnly` keeps a provision an unconfirmed transaction spends, for a block
   * that supersedes it; `WithMempool` drops it and adds unconfirmed ones, for a broadcast that chains.
   * Every provision found is rechecked against the UTXO set, since the index can list a spent box.
   */
  private def provisionsFor(ctx: BlockchainContext, contracts: LDContracts, orders: Seq[LithosDexOrder],
                            mempool: MempoolOptions): LithosDexExecution.Provisions = {
    val wanted = orders.collect { case redeem: LithosDexOrder.Redeem => redeem.ownerNft }.toSet
    if (wanted.isEmpty) LithosDexExecution.NoProvisions
    else Try {
      val (listed, complete) = LDBoxes.provisionsOwnedBy(ctx, nodeApi, contracts, wanted, mempool)
      if (!complete)
        logger.warn(s"LithosDEX provisions exceed the scan ceiling; redemptions whose provision was not reached are skipped")
      val current =
        if (listed.isEmpty) Set.empty[String]
        else nodeApi.boxesWithPoolByIds(listed.map(_.boxId)).get.map(_.boxId).toSet
      listed.filter(provision => current.contains(provision.boxId))
        .map(provision => provision.ownerNFT.toString -> provision)
        .toMap
    } match {
      case Success(byOwner) => byOwner.get
      // Costs the redemptions alone: every other order still prices without a provision
      case Failure(ex) =>
        logger.warn(s"Could not read LithosDEX provisions, skipping ${wanted.size} redemption(s): ${ex.getMessage}")
        LithosDexExecution.NoProvisions
    }
  }
}

object LithosDexBatcher {

  /** A broadcast pass is not bound by the stratum's deadline; this only ends a run that would not. */
  private final val BroadcastBudget = 60.seconds

  /** Most pools one scan reads, as for ErgoDEX; each costs a node read, and a new deployment one more for its vault. */
  private final val MaxPoolReads = 512

  /** One pool as a block would run it: its deployment, confirmed box, claim check, provisions and fills. */
  private final case class PoolPlan(contracts: LDContracts, poolBox: InputUTXO, claims: LDClaimPlacement,
                                    provisions: LithosDexExecution.Provisions, fills: Seq[LithosDexFill])
}
