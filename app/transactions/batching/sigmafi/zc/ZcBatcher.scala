package transactions.batching.sigmafi.zc

import akka.actor.ActorRef
import configs.{CandidateConfig, NodeContext, SigmaFiBatchingConfig}
import org.ergoplatform.appkit.Parameters
import play.api.Configuration
import state.synchronization.CompleteMempool
import transactions.batching.Batcher.{BroadcastResult, Tracked}
import transactions.batching.{Batcher, BatchingMempool}
import transactions.candidate.{CandidateBudget, CandidateBundle, CandidateCapital}
import transactions.engine.EngineBroadcast

import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}
import javax.inject.{Inject, Named}

/**
 * SigmaFi V2 ZC Standard batcher: replaces the legacy first-generation bond batcher, reading every ZC box from
 * [[ZcTracker]] and [[ZcResolver]] rather than paging the node per tick.
 *
 * Serves candidate blocks fee-less (offer matches, maturity and early oracle bond liquidations, share/wrap mints,
 * vault pulls, expiry refunds and paid storage-rent refreshes), superseding competing mempool spends and sending
 * every fee to the block's holding top-up. When `broadcast` is enabled, broadcasts eligible work once
 * `broadcastAfterBlocks` have passed without spending any wallet box.
 */
class ZcBatcher(nodeContext: NodeContext,
                settings: SigmaFiBatchingConfig,
                servesCandidates: Boolean,
                engine: ActorRef,
                tracker: ZcTracker,
                resolver: ZcResolver,
                useTrueProp: Boolean,
                buildBudgetMs: Long = Batcher.DefaultBuildBudgetMs,
                allowance: CandidateBudget = CandidateBudget.Unbounded)
  extends Batcher(nodeContext, settings.batching, servesCandidates, engine, configs.Contexts.SigmaFiBatchingIo,
    buildBudgetMs) {

  @Inject()
  def this(nodeContext: NodeContext, config: Configuration, @Named("transaction-engine") engine: ActorRef,
           tracker: ZcTracker, resolver: ZcResolver) =
    this(nodeContext, SigmaFiBatchingConfig(config), Batcher.servesCandidates(config, SigmaFiBatchingConfig.Name),
      engine, tracker, resolver, CandidateConfig(config).useTruePropCollection, Batcher.buildBudgetMs(config),
      CandidateConfig(config).limitsFor(SigmaFiBatchingConfig.Name).budget)

  override protected val label: String = "SigmaFi ZC"

  private val batching = settings.batching

  /**
   * Passes the candidate's parent block to [[ZcTracker]] on `PrepareBlockTxs` (via `receive` override) so the
   * confirmed ZC book is already moving to `blockHeight - 1` before `RequestBlockTxs` asks for it.
   */
  override def receive: Receive = {
    case msg @ transactions.candidate.BlockTxMessages.PrepareBlockTxs(blockHeight, _, parentId) =>
      Try(tracker.follow(blockHeight - 1, parentId)).failed
        .foreach(ex => logger.warn(s"Could not pass block $parentId to ZC tracking: ${ex.getMessage}"))
      super.receive(msg)
    case other => super.receive(other)
  }

  /**
   * Background tick: warms `resolver` caches (scripts, origins, box bytes) from the tracker's confirmed book so
   * candidate builds make near-zero node reads, and reports whether the tracker holds any boxes to consider.
   */
  override protected def discover(): Tracked =
    if (!tracker.enabled) Map.empty
    else tracker.book match {
      case None => Map.empty
      case Some(book) =>
        nodeContext.getClient.execute { ctx =>
          ZcBatchPlanner.warmCaches(book, ctx, resolver, settings, book.height.toLong)
        }
        // Return a lightweight summary map so Batcher's broadcastDue triggers when the ZC book is non-empty.
        book.boxes.keysIterator.take(batching.maxTrackedOrders).map(id => id -> "zc").toMap
    }

  /**
   * Builds candidate bundles for `blockHeight` on the confirmed ZC book as of `blockHeight - 1`, fee-less,
   * superseding competing mempool spends of the boxes we take.
   */
  override protected def executions(orders: Tracked, blockHeight: Int, slots: Int,
                                    deadline: Deadline): Seq[CandidateBundle] =
    if (!tracker.enabled || slots <= 0) Seq.empty
    else {
      val wait = (buildBudgetMs / 4).milliseconds.min(deadline.timeLeft).max(Duration.Zero)
      val bookOpt = tracker.at(blockHeight - 1, wait).orElse(tracker.book.filter(_.height == blockHeight - 1))
      bookOpt match {
        case None =>
          logger.debug(s"ZC tracking has not reached the parent of block $blockHeight; offering no SigmaFi ZC work")
          Seq.empty
        case Some(book) =>
          val observed = observation(Some(deadline))
          if (!observed.fresh) {
            logger.info(s"No fresh mempool observation for block $blockHeight, offering no SigmaFi ZC work: " +
              observed.failure.getOrElse("observation too old"))
            Seq.empty
          } else {
            val snapshot = observed.snapshot.get
            val spenders = BatchingMempool.spenders(snapshot)
            val oracleBusy = book.oracle.exists(o => snapshot.spent.contains(o.box.boxId))
            within(deadline, "reading ZC book")
            nodeContext.getClient.execute { ctx =>
              val feeTo = CandidateCapital.collectionContract(nodeContext.getNodeWallet, useTrueProp)
              val built = ZcBatchPlanner.build(ctx, nodeContext.getNodeWallet, book, resolver, settings,
                signingHeight = blockHeight, minerFee = 0L, feeTo = feeTo, slots = slots, allowance = allowance,
                deadline = Some(deadline), excluded = skippedOrders(), oracleUsable = !oracleBusy,
                broadcastWait = None)
              built.failedIds.foreach(skipOrder)
              built.txs.map { case (tx, parents) =>
                tx.bundle(BatchingMempool.competitors(spenders, tx.spentIds.toSeq), parents)
              }
            }
          }
      }
    }

  /**
   * Broadcasts ZC batching work eligible past `broadcastAfterBlocks` that nothing in the mempool spends, paying
   * the 0.001 ERG miner fee out of protocol fees and sending the rest to this wallet.
   */
  override protected def broadcastPass(orders: Tracked, refused: Map[String, String]): BroadcastResult =
    if (!tracker.enabled) BroadcastResult(Map.empty)
    else tracker.book match {
      case None => BroadcastResult(Map.empty)
      case Some(book) =>
        val observed = observation()
        require(observed.fresh, s"no fresh mempool observation: ${observed.failure.getOrElse("too old")}")
        val snapshot = observed.snapshot.get
        val oracleBusy = book.oracle.exists(o => snapshot.spent.contains(o.box.boxId))
        val excluded = skippedOrders() ++ refused.keySet ++ snapshot.spent
        nodeContext.getClient.execute { ctx =>
          val tip = ctx.getHeight
          val wallet = nodeContext.getNodeWallet.contract
          val built = ZcBatchPlanner.build(ctx, nodeContext.getNodeWallet, book, resolver, settings,
            signingHeight = tip, minerFee = Parameters.MinFee, feeTo = wallet,
            slots = batching.maxOrdersPerBlock, allowance = CandidateBudget.Unbounded, deadline = None,
            excluded = excluded, oracleUsable = !oracleBusy,
            broadcastWait = Some(settings.broadcastAfterBlocks),
            resolverReads = ZcBatchPlanner.BackgroundResolverReads)
          built.failedIds.foreach(skipOrder)
          val sender = broadcaster()
          val refusedOut = scala.collection.mutable.Map.empty[String, String]
          val failedTxs = scala.collection.mutable.HashSet.empty[String]
          built.txs.foreach { case (tx, parents) =>
            if (parents.exists(failedTxs.contains)) {
              failedTxs += tx.txId
              tx.spentIds.foreach(id => refusedOut += id -> id)
            } else if (!sent(sender, tx, observed)) {
              failedTxs += tx.txId
              tx.spentIds.foreach(id => refusedOut += id -> id)
            }
          }
          BroadcastResult(refusedOut.toMap)
        }
    }

  private def sent(sender: EngineBroadcast, tx: ZcBatchTx, observed: CompleteMempool.Observation): Boolean =
    Try(sender.sendOwned(tx.signed, Seq.empty, tx.kind, () => alive.get(), observed = Some(observed))) match {
      case Success(s) if s.outcome == EngineBroadcast.Accepted =>
        logger.info(s"Broadcast ${tx.kind} ${s.txId} (${tx.spentIds.size} box(es), ${tx.fees} nanoERG reward)")
        true
      case Success(s) =>
        logger.warn(s"${tx.kind} ${s.txId} was ${s.outcome}: ${s.reason.getOrElse("no reason given")}")
        false
      case Failure(ex) =>
        logger.warn(s"${tx.kind} was not sent: ${ex.getMessage}")
        false
    }
}
