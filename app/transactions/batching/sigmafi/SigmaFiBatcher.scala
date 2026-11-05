package transactions.batching.sigmafi

import akka.actor.ActorRef
import configs.{CandidateConfig, NodeContext, SigmaFiBatchingConfig}
import node.model.{MempoolOptions, NodeBox, Paging, SortDirection}
import org.ergoplatform.appkit.{BlockchainContext, Parameters}
import play.api.Configuration
import sigmafi.contracts.{SigmaFiContracts, SigmaFiTemplateKind}
import state.synchronization.CompleteMempool
import transactions.batching.Batcher.{BroadcastResult, Tracked}
import transactions.batching.{Batcher, BatchingMempool}
import transactions.candidate.CandidateBundle
import transactions.candidate.CandidateCapital
import transactions.engine.EngineBroadcast
import work.lithos.mutations.InputUTXO

import javax.inject.{Inject, Named}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

/**
 * Liquidates SigmaFi V2 bonds that matured unpaid under the maturity liquidation script, taking each one's
 * batching fee.
 *
 * For `broadcastAfterBlocks` blocks after a bond matures, only this miner's own blocks carry its liquidation:
 * fee-less, its fee going to the block's holding box, superseding any pending spend of the bond. From then on
 * it is also broadcast, the miner fee paid from the fees and the rest to this wallet. No path spends a wallet
 * box, so a failure costs only the fees of the bonds left unliquidated.
 */
class SigmaFiBatcher(nodeContext: NodeContext,
                     settings: SigmaFiBatchingConfig,
                     servesCandidates: Boolean,
                     engine: ActorRef,
                     useTrueProp: Boolean,
                     buildBudgetMs: Long = Batcher.DefaultBuildBudgetMs)
  extends Batcher(nodeContext, settings.batching, servesCandidates, engine, configs.Contexts.SigmaFiBatchingIo,
    buildBudgetMs) {

  import SigmaFiBatcher._

  /** Built by `Module`, reading every setting from the application configuration. */
  @Inject()
  def this(nodeContext: NodeContext, config: Configuration, @Named("transaction-engine") engine: ActorRef) =
    this(nodeContext, SigmaFiBatchingConfig(config), Batcher.servesCandidates(config, SigmaFiBatchingConfig.Name),
      engine, CandidateConfig(config).useTruePropCollection, Batcher.buildBudgetMs(config))

  override protected val label: String = "SigmaFi"

  private val batching = settings.batching

  override protected def scansConfirmedOnly: Boolean = true

  /**
   * Pages the ERG bond tree and the token bond template, tracking bonds that can be liquidated in the block
   * after the tip: due, under the maturity liquidation, and paying at least the fee floor. Oldest maturity first.
   */
  override protected def discover(): Tracked = {
    val tip = nodeApi.info().get.fullHeight.getOrElse(throw new IllegalStateException("the node did not report a height"))
    val skipped = skippedOrders()
    val bonds = scanTree(SigmaFiContracts.ergBond.ergoTreeHex) ++
      scanTemplate(SigmaFiContracts.template(SigmaFiTemplateKind.TokenBond).templateHashHex, "token bond")(SigmaFiBoxes.bond)
    bonds.filter(b => liquidatable(b, tip + 1, settings.minBatchingFeeNanoErg) && !skipped.contains(b.boxId))
      .sortBy(b => (b.maturity, b.boxId)).take(batching.maxTrackedOrders)
      .map(b => b.boxId -> b.asset.toString).toMap
  }

  /** Every confirmed ERG bond, paged, capped at [[Batcher.MaxScannedPerTemplate]]. */
  private def scanTree(tree: String): Vector[SigmaFiBond] = {
    var offset = 0
    var exhausted = false
    var found = Vector.empty[SigmaFiBond]
    while (!exhausted && offset < Batcher.MaxScannedPerTemplate) {
      val page = nodeApi.unspentBoxesByErgoTree(tree, Paging(offset, Batcher.PageSize), SortDirection.Asc,
        MempoolOptions.ConfirmedOnly).get
      found ++= page.flatMap(indexed => SigmaFiBoxes.bond(indexed.box))
      exhausted = page.size < Batcher.PageSize
      offset += page.size
    }
    if (!exhausted)
      logger.warn(s"SigmaFi scan stopped after ${Batcher.MaxScannedPerTemplate} ERG bonds; newer ones were not read")
    found
  }

  /** One liquidation of the due bonds for this miner's block, superseding every pending spend of them. */
  override protected def executions(orders: Tracked, blockHeight: Int, slots: Int,
                                    deadline: Deadline): Seq[CandidateBundle] =
    if (orders.isEmpty) Seq.empty
    else {
      val observed = observation(Some(deadline))
      if (!observed.fresh) {
        logger.info(s"No fresh mempool observation for block $blockHeight, offering no SigmaFi liquidations: " +
          observed.failure.getOrElse("observation too old"))
        Seq.empty
      } else nodeContext.getClient.execute { ctx =>
        val spenders = BatchingMempool.spenders(observed.snapshot.get)
        val due = liveBonds(ctx, orders).filter { case (b, _) => liquidatable(b, blockHeight, settings.minBatchingFeeNanoErg) }
          .take(batching.maxOrdersPerBlock)
        within(deadline, "reading tracked bonds")
        build(ctx, due, CandidateCapital.collectionContract(nodeContext.getNodeWallet, useTrueProp), 0L, blockHeight)
          .map(l => l.bundle(BatchingMempool.competitors(spenders, l.bonds.map(_.boxId))))
          .toSeq
      }
    }

  /**
   * Liquidates bonds past the block-first window that nothing in the mempool spends, paying the miner fee from
   * their fees. A batch the node refuses is sent again one bond at a time, so one bad bond costs only itself.
   */
  override protected def broadcastPass(orders: Tracked, refused: Map[String, String]): BroadcastResult =
    nodeContext.getClient.execute { ctx =>
      val height = ctx.getHeight + 1
      val wanted = orders.filterKeys(id => !refused.contains(id))
      if (wanted.isEmpty) BroadcastResult(Map.empty)
      else {
        val observed = observation()
        require(observed.fresh, s"no fresh mempool observation: ${observed.failure.getOrElse("too old")}")
        val snapshot = observed.snapshot.get
        val due = liveBonds(ctx, wanted).filter { case (b, _) =>
          !snapshot.spent.contains(b.boxId) && liquidatable(b, height, settings.minBatchingFeeNanoErg) &&
            height >= b.maturity.toLong + settings.broadcastAfterBlocks
        }.take(batching.maxOrdersPerBlock)
        val wallet = nodeContext.getNodeWallet.contract
        val sender = broadcaster()
        build(ctx, due, wallet, Parameters.MinFee, height).fold(BroadcastResult(Map.empty)) { batch =>
          if (sent(sender, batch, observed)) BroadcastResult(Map.empty)
          else if (batch.bonds.size == 1) BroadcastResult(Map(batch.bonds.head.boxId -> batch.bonds.head.boxId))
          else BroadcastResult(due.flatMap { single =>
            build(ctx, Seq(single), wallet, Parameters.MinFee, height)
              .filterNot(one => sent(sender, one, observed)).map(_ => single._1.boxId -> single._1.boxId)
          }.toMap)
        }
      }
    }

  private def sent(sender: EngineBroadcast, batch: SigmaFiLiquidation, observed: CompleteMempool.Observation): Boolean =
    Try(sender.sendOwned(batch.signed, Seq.empty, SigmaFiTransactions.Kind, () => alive.get(),
      observed = Some(observed))) match {
      case Success(s) if s.outcome == EngineBroadcast.Accepted =>
        logger.info(s"Broadcast SigmaFi liquidation ${s.txId} of ${batch.bonds.size} bond(s)")
        true
      case Success(s) =>
        logger.warn(s"SigmaFi liquidation ${s.txId} of ${batch.bonds.size} bond(s) was ${s.outcome}: " +
          s.reason.getOrElse("no reason given"))
        false
      case Failure(ex) =>
        logger.warn(s"SigmaFi liquidation of ${batch.bonds.size} bond(s) was not sent: ${ex.getMessage}")
        false
    }

  /**
   * One liquidation of `bonds`, or None. When the batch does not sign, each bond is tried alone and those that
   * fail are left out until `skippedOrderTtlMs` passes, so one bad bond never blocks the rest.
   */
  private def build(ctx: BlockchainContext, bonds: Seq[(SigmaFiBond, InputUTXO)], feeTo: work.lithos.mutations.Contract,
                    minerFee: Long, height: Int): Option[SigmaFiLiquidation] =
    if (bonds.isEmpty) None
    else Try(SigmaFiTransactions.liquidate(ctx, nodeContext.getNodeWallet, bonds, feeTo, minerFee, height)) match {
      case Success(batch) => Some(batch)
      case Failure(ex) =>
        val buildable = bonds.filter { single =>
          val ok = Try(SigmaFiTransactions.liquidate(ctx, nodeContext.getNodeWallet, Seq(single), feeTo, minerFee, height))
          ok.failed.foreach { e =>
            logger.warn(s"Could not liquidate SigmaFi bond ${single._1.boxId} at $height, leaving it out: ${e.getMessage}")
            skipOrder(single._1.boxId)
          }
          ok.isSuccess
        }
        if (buildable.size == bonds.size) {
          logger.warn(s"SigmaFi liquidation of ${bonds.size} bonds failed though each builds alone: ${ex.getMessage}")
          None
        } else build(ctx, buildable, feeTo, minerFee, height)
      }

  /**
   * Tracked bonds still unspent on chain, oldest maturity first. A bond a mempool transaction spends is read
   * from the confirmed set, so this miner's block can supersede that spend; ids the node no longer holds are
   * dropped from tracking.
   */
  private def liveBonds(ctx: BlockchainContext, orders: Tracked): Seq[(SigmaFiBond, InputUTXO)] = {
    val wanted = orders.keySet -- skippedOrders()
    if (wanted.isEmpty) Seq.empty
    else {
      val withPool = nodeApi.boxesWithPoolByIds(wanted.toSeq).get.filter(b => wanted.contains(b.boxId))
      val pending = (wanted -- withPool.map(_.boxId)).toSeq.flatMap(id => nodeApi.boxById(id).get)
      val live: Seq[NodeBox] = withPool ++ pending
      forget(wanted -- live.map(_.boxId))
      live.flatMap(box => SigmaFiBoxes.bond(box).flatMap(b => SigmaFiBoxes.input(box, ctx).map(b -> _)))
        .sortBy { case (b, _) => (b.maturity, b.boxId) }
    }
  }
}

object SigmaFiBatcher {

  /** The batching block and candidate source key this batcher reads. */
  final val Name: String = SigmaFiBatchingConfig.Name

  /**
   * Whether `bond` can be liquidated in a block at `height` by this client: matured by then, under the maturity
   * liquidation, paying at least `floor`, and leaving its vault box at least 0.001 ERG.
   */
  def liquidatable(bond: SigmaFiBond, height: Int, floor: Long): Boolean =
    height >= bond.maturity && bond.liquidation.contains(SigmaFiContracts.MaturityLiquidation) &&
      bond.batchingFee >= floor && bond.box.value - bond.batchingFee >= SigmaFiContracts.MinBox
}
