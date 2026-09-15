package transactions.batching.lithosdex

import akka.actor.ActorRef
import configs.{CandidateConfig, LithosDexBatchingConfig, NodeContext}
import lithosdex.contracts.{LDOrderKind, LDOrderContracts}
import lithosdex.{LDHelpers, LDLiquidityPool}
import node.MutationConversions._
import node.model.{MempoolOptions, NodeBox, Paging, SortDirection}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.BlockchainContext
import play.api.Configuration
import state.synchronization.CompleteMempool
import transactions.batching.Batcher.{MaxMempoolOrders, Tracked}
import transactions.batching.{Batcher, BatchingMempool}
import transactions.candidate.BlockTxMessages.CandidateTx
import transactions.candidate.CandidateBundle

import javax.inject.{Inject, Named}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

/**
 * Executes LithosDex orders against the single ERG:LIT pool, for this miner's block or by broadcast.
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
  private val vaultNft: String = LDHelpers.getVaultNFT(nodeContext.getNetwork).toString

  /** Every order template's bytes as hex. A tree ends with its template, so this finds candidate outputs cheaply. */
  private val templatesHex: Seq[String] =
    LDOrderKind.all.map(kind => Hex.toHexString(LDOrderContracts.template(kind).templateBytes))

  private def poolTree: String = DexContracts(nodeContext.getNetwork).liquidityPool.ergoTreeHex

  private def vaultTree: String = DexContracts(nodeContext.getNetwork).feeVault.ergoTreeHex

  private lazy val claims = new LDClaimPlacement(poolNft, vaultNft, vaultTree,
    DexContracts(nodeContext.getNetwork).provisionGuard.ergoTreeHex,
    LDHelpers.getProvToken(nodeContext.getNetwork).toString)

  /** Scans every order template and prices what names this pool against its confirmed box. */
  override protected def discover(): Tracked = nodeContext.getClient.execute { ctx =>
    val pool = confirmedSingleton(poolNft, poolTree).map(box => LDLiquidityPool(box.toInputUTXO(ctx)))
      .getOrElse(throw new IllegalStateException(
        s"no confirmed LithosDEX pool box carries $poolNft: is the pool launched on this network?"))
    // Left out before ranking, so an order declaring a fee it can never pay holds no tracked slot
    val skipped = skippedOrders()
    val orders = LDOrderKind.all
      .flatMap(kind => scanTemplate(LDOrderContracts.template(kind).templateHash, kind.scriptName)(LithosDexOrder.parse))
      .filter(order => order.poolNft == poolNft && !skipped.contains(order.boxId))
      .groupBy(_.boxId).values.map(_.head).toSeq
    val provisions = provisionsFor(ctx, orders, MempoolOptions.ConfirmedOnly)
    orders
      .flatMap(order => LithosDexExecution.price(order, pool, batching.minRevenueNanoErg, provisions, fundsItsOwnBox = false))
      .sortBy(fill => (-fill.revenue, fill.order.boxId))
      .take(batching.maxTrackedOrders)
      .map(fill => fill.order.boxId -> fill.order.poolNft)
      .toMap
  }

  /** Fee-less runs against the confirmed pool, superseding every competing spend of what they claim. */
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
      val candidates = (liveOrders(orders) ++ unconfirmedOrders(snapshot))
        .groupBy(_.boxId).values.map(_.head).toSeq
        .filterNot(order => skipped.contains(order.boxId) ||
          BatchingMempool.withdrawn(order.boxId, order.poolNft, spenders))

      within(deadline, "reading tracked orders")
      currentSingleton(poolNft, poolTree).filterNot(box => creators.contains(box.boxId)) match {
        case None => Seq.empty
        case Some(poolNode) =>
          val poolBox = poolNode.toInputUTXO(ctx)
          val pool = LDLiquidityPool(poolBox)
          // Confirmed provisions, including one a refresh is spending, since this block supersedes the refresh
          val provisions = claimedProvisions(ctx, provisionsFor(ctx, candidates, MempoolOptions.ConfirmedOnly),
            candidates, creators)
          within(deadline, "reading the pool and provisions")
          // The flush takes one slot of its own
          val runSlots = slots - (if (settings.autoFlush) 1 else 0)
          val priceable = candidates.filter(order =>
            LithosDexExecution.price(order, pool, batching.minRevenueNanoErg, provisions, fundsItsOwnBox = false).nonEmpty)
          val placed = walletPlacements(priceable.flatMap(order => creators.get(order.boxId)), creators, spenders,
            math.min(runSlots - 1, batching.maxAncestorTxs), (tx, boxes) =>
              BatchingMempool.placedByWallet(tx, boxes, spenders, createsPool) || claims.accepts(tx, boxes, spenders))
          val usable = priceable.filter(order => creators.get(order.boxId).forall(tx => placed.contains(tx.id)))
          val placementOf = (order: LithosDexOrder) =>
            creators.get(order.boxId).flatMap(tx => placed.get(tx.id)).getOrElse(Vector.empty[CompleteMempool.MempoolTx])
          within(deadline, "reading placements")

          val run = LithosDexExecution.run(ctx, nodeContext.getNodeWallet, poolBox, usable, runSlots,
            batching.minRevenueNanoErg, provisions, blockHeight, 0L, useTrueProp, deadline, placementOf)
          settle(run, blockHeight)
          evictedPlacements.remember(run.placements, blockHeight)
          run.chain.toSeq.map { chain =>
            val claimed = poolBox.id.toString +: (chain.fills.map(_.order.boxId) ++
              chain.fills.flatMap(_.provision.map(_.boxId)))
            val competitors = BatchingMempool.competitors(spenders, claimed)
            // The vault is one more node read, so a run that has used its budget goes without the flush
            val closed = if (settings.autoFlush && !deadline.isOverdue())
              flushed(ctx, chain, blockHeight, spenders, creators, competitors) else chain
            closed.bundle(competitors, run.placements.map(tx => CandidateTx.ancestor(tx.body)))
          }
      }
    }
  }

  /**
   * `confirmed`, except for a redeem order placed by the claim that settled its provision: that claim
   * spends the confirmed provision, so the only one the order can close is the claim's own output. Whether
   * the claim may be carried is decided with the other placements.
   */
  private def claimedProvisions(ctx: BlockchainContext, confirmed: LithosDexExecution.Provisions,
                                candidates: Seq[LithosDexOrder],
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
  private def flushed(ctx: BlockchainContext, chain: LithosDexChain, blockHeight: Int,
                      spenders: BatchingMempool.Spenders, creators: Map[String, CompleteMempool.MempoolTx],
                      competitors: Set[String]): LithosDexChain =
    // A vault that cannot be read costs the flush, never the run
    Try(currentSingleton(vaultNft, vaultTree)).toOption.flatten
      .filter(box => !creators.contains(box.boxId) &&
        spenders.getOrElse(box.boxId, Vector.empty).forall(tx => competitors.contains(tx.id)))
      .map(box => LithosDexExecution.withFlush(ctx, nodeContext.getNodeWallet, chain, box.toInputUTXO(ctx), blockHeight))
      .getOrElse(chain)

  /** Builds unclaimed orders against the mempool's pool tip, at the fee ceiling, and records refusals. */
  override protected def broadcastPass(orders: Tracked, refused: Map[String, String]): Map[String, String] = {
    val observed = observation()
    require(observed.fresh, s"no fresh mempool observation: ${observed.failure.getOrElse("too old")}")
    val snapshot = observed.snapshot.get
    val spenders = BatchingMempool.spenders(snapshot)
    val sender = broadcaster()
    nodeContext.getClient.execute { ctx =>
      val skipped = skippedOrders()
      val open = liveOrders(orders).filterNot(order => snapshot.spent.contains(order.boxId) || skipped.contains(order.boxId))
      val tip = if (open.isEmpty) None
      else currentSingleton(poolNft, poolTree).flatMap(BatchingMempool.poolTip(_, poolNft, spenders))
      tip.toSeq.flatMap { tipBox =>
        val poolBox = tipBox.toInputUTXO(ctx)
        val provisions = provisionsFor(ctx, open, MempoolOptions.WithMempool)
        val run = LithosDexExecution.run(ctx, nodeContext.getNodeWallet, poolBox,
          open.filterNot(order => refused.get(order.boxId).contains(tipBox.boxId)), batching.maxOrdersPerBlock,
          batching.broadcastMinRevenueNanoErg, provisions, ctx.getHeight + 1, batching.broadcastMinerFeeCeiling,
          useTrueProp = false, BroadcastBudget.fromNow)
        settle(run, ctx.getHeight + 1)
        run.chain.flatMap(chain => send(sender, chain, observed))
      }.toMap
    }
  }

  /** A box carrying this pool's NFT, so a placement creating one is not a wallet placement. */
  private def createsPool(box: NodeBox): Boolean = box.assets.exists(_.tokenId == poolNft)

  /** The one confirmed box carrying `nft` under `tree`, from the index. */
  private def confirmedSingleton(nft: String, tree: String): Option[NodeBox] = {
    val boxes = nodeApi.unspentBoxesByTokenId(nft, Paging(0, 2), SortDirection.Desc, MempoolOptions.ConfirmedOnly).get
    boxes.headOption.map(_.box).filter(box => boxes.size == 1 && box.ergoTree == tree)
  }

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

  /** Orders created by unconfirmed transactions that name this pool, up to [[Batcher.MaxMempoolOrders]]. */
  private def unconfirmedOrders(snapshot: CompleteMempool.Snapshot): Vector[LithosDexOrder] =
    snapshot.transactions.iterator
      .flatMap(_.body.outputs)
      .filter(box => templatesHex.exists(box.ergoTree.endsWith))
      .flatMap(box => LithosDexOrder.parse(box))
      .filter(_.poolNft == poolNft)
      .take(MaxMempoolOrders)
      .toVector

  /**
   * The provision each redemption among `orders` closes. Scans the guard address only when there is a
   * redemption to price. `ConfirmedOnly` keeps a provision an unconfirmed transaction spends, for a block
   * that supersedes it; `WithMempool` drops it and adds unconfirmed ones, for a broadcast that chains.
   * Every provision found is rechecked against the UTXO set, since the index can list a spent box.
   */
  private def provisionsFor(ctx: BlockchainContext, orders: Seq[LithosDexOrder],
                            mempool: MempoolOptions): LithosDexExecution.Provisions = {
    val wanted = orders.collect { case redeem: LithosDexOrder.Redeem => redeem.ownerNft }.toSet
    if (wanted.isEmpty) LithosDexExecution.NoProvisions
    else Try {
      val (listed, complete) = LDBoxes.provisionsOwnedBy(ctx, nodeApi, wanted, mempool)
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
}
