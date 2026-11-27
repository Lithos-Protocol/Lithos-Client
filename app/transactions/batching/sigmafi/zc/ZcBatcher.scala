package transactions.batching.sigmafi.zc

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import configs.{ArbConfig, BatchingConfig, CandidateConfig, CandidateSourceConfig, LithosDexBatchingConfig, NodeContext,
  SigmaFiBatchingConfig}
import org.ergoplatform.appkit.{BlockchainContext, Parameters}
import play.api.Configuration
import play.api.libs.json.JsObject
import state.synchronization.CompleteMempool
import transactions.batching.Batcher.{BroadcastResult, Tracked}
import transactions.batching.arb.{ArbDiscovery, ArbIndex}
import transactions.batching.sigmafi.SigmaFiBoxes
import transactions.batching.lithosdex.LDDeployments
import transactions.batching.tracking.{PoolDeployment, PoolTracker}
import transactions.batching.{Batcher, BatchingMempool}
import transactions.candidate.BlockTxMessages.{CandidateTx, ChainFromMempool, IncludeExisting}
import transactions.candidate.{CandidateBudget, CandidateBundle, CandidateCapital}
import transactions.engine.{EngineBroadcast, EngineIntent, TransactionEngine, ZcIntent}

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Await
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
                allowance: CandidateBudget = CandidateBudget.Unbounded,
                pools: Option[PoolTracker] = None,
                ergoDex: BatchingConfig = BatchingConfig.ErgoDexDefault,
                deniedPools: Set[String] = Set.empty,
                minLegProceeds: Long = ArbConfig.Default.zcMinLegProceedsNanoErg)
  extends Batcher(nodeContext, settings.batching, servesCandidates, engine, configs.Contexts.SigmaFiBatchingIo,
    buildBudgetMs) {

  @Inject()
  def this(nodeContext: NodeContext, config: Configuration, @Named("transaction-engine") engine: ActorRef,
           tracker: ZcTracker, resolver: ZcResolver, poolTracker: PoolTracker) =
    this(nodeContext, SigmaFiBatchingConfig(config), Batcher.servesCandidates(config, SigmaFiBatchingConfig.Name),
      engine, tracker, resolver, CandidateConfig(config).useTruePropCollection, Batcher.buildBudgetMs(config),
      CandidateConfig(config).limitsFor(SigmaFiBatchingConfig.Name).budget, Some(poolTracker),
      BatchingConfig(config, CandidateSourceConfig.ErgoDex),
      BatchingConfig(config, CandidateSourceConfig.ErgoDex).deniedPools ++ LithosDexBatchingConfig(config).batching.deniedPools,
      ArbConfig(config).zcMinLegProceedsNanoErg)

  override protected val label: String = "SigmaFi ZC"

  private val batching = settings.batching

  /** Off-critical-path precomputed offer matches for the newest confirmed book revision. */
  private val cachedMatches = new AtomicReference[Option[ZcPrecomputedMatches]](None)
  /** Boxes refused by broadcast, suppressed for `skippedOrderTtlMs` even when the book exceeds `maxTrackedOrders`. */
  private val broadcastRefused = new AtomicReference[Map[String, Long]](Map.empty)

  private def activeBroadcastRefused(): Set[String] = {
    val cutoff = System.currentTimeMillis() - batching.skippedOrderTtlMs
    broadcastRefused.get.collect { case (id, at) if at > cutoff => id }.toSet
  }

  private def noteBroadcastRefused(ids: Iterable[String]): Unit = if (ids.nonEmpty) {
    val now = System.currentTimeMillis()
    val cutoff = now - batching.skippedOrderTtlMs
    broadcastRefused.updateAndGet { held =>
      val live = held.filter { case (_, at) => at > cutoff } ++ ids.iterator.map(_ -> now)
      if (live.size <= batching.maxSkippedOrders) live
      else live.toSeq.sortBy(-_._2).take(batching.maxSkippedOrders).toMap
    }
  }

  /** ZC broadcasts only from confirmed book state; skip idle mempool polling when the book is empty. */
  override protected def broadcastDue(tracked: Tracked): Boolean = tracked.nonEmpty

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
   * Background tick: warms `resolver` caches (scripts, origins, box bytes) from the tracker's confirmed book and
   * precomputes offer matches off the candidate critical path so candidate builds make near-zero node reads and
   * skip `ZcMatcher.batch` on large books.
   */
  override protected def discover(): Tracked =
    if (!tracker.enabled) Map.empty
    else tracker.book match {
      case None => Map.empty
      case Some(book) =>
        nodeContext.getClient.execute { ctx =>
          ZcBatchPlanner.warmCaches(book, ctx, resolver, settings, book.height.toLong)
        }
        // Matches are priced at the oracle candidates will read: a single pending refresh's box, when there is one
        val planned = tracker.view.filter(_.book.headerId == book.headerId).flatMap(_.pendingOracle)
          .fold(book)(book.withOracle)
        val key = (book.headerId, book.height, resolver.generation, planned.oracle.map(_.box.boxId))
        if (!cachedMatches.get().exists(p => (p.headerId, p.height, p.resolverGen, p.oracleBoxId) == key))
          cachedMatches.set(Some(ZcBatchPlanner.precomputeMatches(planned, resolver)))
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
            val oracle = oracleFor(book, snapshot, spenders)
            within(deadline, "reading ZC book")
            nodeContext.getClient.execute { ctx =>
              val feeTo = CandidateCapital.collectionContract(nodeContext.getNodeWallet, useTrueProp)
              val built = ZcBatchPlanner.build(ctx, nodeContext.getNodeWallet, oracle.book, resolver, settings,
                signingHeight = blockHeight, minerFee = 0L, feeTo = feeTo, slots = slots, allowance = allowance,
                deadline = Some(deadline), excluded = skippedOrders(), oracleUsable = oracle.usable,
                broadcastWait = None, precomputed = cachedMatches.get(),
                onComputed = p => cachedMatches.set(Some(p)))
              built.failedIds.foreach(skipOrder)
              built.txs.map { case (tx, parents) =>
                val bundle = tx.bundle(BatchingMempool.competitors(spenders, tx.spentIds.toSeq), parents)
                // The node validates a package in the order given, so the refresh goes ahead of what reads its box
                oracle.carried.filter(r => readsPending(tx, r)).fold(bundle)(r => bundle.copy(
                  members = CandidateTx.ancestor(r.body) +: bundle.members,
                  interactions = Seq(ChainFromMempool(r.txId), IncludeExisting(r.txId)) ++ bundle.interactions))
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
        val oracle = oracleFor(book, snapshot, BatchingMempool.spenders(snapshot))
        val excluded = skippedOrders() ++ refused.keySet ++ activeBroadcastRefused() ++ snapshot.spent
        nodeContext.getClient.execute { ctx =>
          val tip = ctx.getHeight
          val wallet = nodeContext.getNodeWallet.contract
          val built = ZcBatchPlanner.build(ctx, nodeContext.getNodeWallet, oracle.book, resolver, settings,
            signingHeight = tip, minerFee = Parameters.MinFee, feeTo = wallet,
            slots = batching.maxOrdersPerBlock, allowance = CandidateBudget.Unbounded, deadline = None,
            excluded = excluded, oracleUsable = oracle.usable,
            broadcastWait = Some(settings.broadcastAfterBlocks),
            resolverReads = ZcBatchPlanner.BackgroundResolverReads)
          built.failedIds.foreach(skipOrder)
          val sender = broadcaster()
          val refusedOut = scala.collection.mutable.Map.empty[String, String]
          val failedTxs = scala.collection.mutable.HashSet.empty[String]
          // Work reading a pending refresh's box that miners could reach first waits, as do its children
          val waiting = scala.collection.mutable.HashSet.empty[String]
          built.txs.foreach { case (tx, parents) =>
            val held = oracle.pending.filter(r => readsPending(tx, r)).flatMap(r => ZcRefreshGuard.refusal(
              Parameters.MinFee, tx.sizeBytes, tx.cost, r, System.nanoTime()))
            if (parents.exists(waiting.contains) || held.isDefined) {
              waiting += tx.txId
              held.foreach(r => logger.debug(s"${tx.kind} ${tx.txId} waits for the oracle refresh: $r"))
            } else if (parents.exists(failedTxs.contains)) {
              failedTxs += tx.txId
              tx.spentIds.foreach(id => refusedOut += id -> id)
            } else if (!sent(sender, tx, observed)) {
              failedTxs += tx.txId
              tx.spentIds.foreach(id => refusedOut += id -> id)
            }
          }
          noteBroadcastRefused(refusedOut.keys)
          val arbRefused = collateralArbPass(ctx, book, observed,
            excluded ++ built.txs.flatMap(_._1.spentIds), tip + 1)
          noteBroadcastRefused(arbRefused)
          BroadcastResult(refusedOut.toMap ++ arbRefused.map(id => id -> id))
        }
    }

  /** The last pool index the collateral arb pass built, which the next one updates rather than rebuilds. */
  private val arbIndex = new AtomicReference[Option[ArbIndex]](None)

  /**
   * Sends up to [[ZcMempoolArb.MaxPerPass]] collateral arbs, each one transaction through the engine, on the pools
   * the pool tracker holds at the observation's block. A ZC box or pool any mempool transaction spends is skipped,
   * and so is each box an earlier send in this pass took. Returns the ZC boxes whose arb was refused or failed.
   */
  private[zc] def collateralArbPass(ctx: BlockchainContext, book: ZcBook, observed: CompleteMempool.Observation,
                                    excluded: Set[String], height: Int): Set[String] = {
    val arb = settings.collateralArb
    val snapshot = observed.snapshot.get
    tracker.ownArbs.prune(snapshot.ids, snapshot.observedAt)
    val tracked = pools.flatMap(_.latest).filter(t => t.headerId.equalsIgnoreCase(snapshot.anchor) &&
      book.headerId.equalsIgnoreCase(snapshot.anchor))
    if (!arb.broadcast || arb.maxErgIn <= 0L || tracked.isEmpty) Set.empty
    else {
      val nodeApi = nodeContext.getNodeApi
      val index = ArbDiscovery.index(ctx, tracked.get, arbIndex.get, ergoDex, deniedPools, {
        case PoolDeployment.LithosDexNative(c) => LDDeployments.vaultStands(nodeApi, c)
        case PoolDeployment.LithosDexToken(c) => LDDeployments.vaultStands(nodeApi, c)
        case _ => true
      })
      arbIndex.set(Some(index))
      var poolStates = ZcMempoolArb.freePools(index.parsed, snapshot.spent)
      val poolBoxes = index.parsed.map { case (nft, e) => nft -> e.box }
      var skip = excluded ++ snapshot.spent
      val budget = new ZcBudget(ZcCollateralArb.MaxResolverReads)
      val resolveScript: ZcRecord => Option[work.lithos.mutations.Contract] = r =>
        resolver.script(r, pending = false, budget).flatMap(SigmaFiBoxes.parse).map(work.lithos.mutations.Contract(_))
      var refused = Set.empty[String]
      var sent = 0
      var attempts = 0
      val passDeadline = Deadline.now + collateralArbPassBudget
      var going = true
      while (going && sent < ZcMempoolArb.MaxPerPass && attempts < ZcBatcher.MaxArbAttemptsPerPass &&
        !passDeadline.isOverdue() && alive.get()) {
        ZcMempoolArb.best(book, poolStates, poolBoxes, resolveScript, height, arb, minLegProceeds, skip) match {
          case None => going = false
          case Some(planned) =>
            attempts += 1
            skip += planned.targetId
            poolStates -= planned.swap.pool.nft
            submitArb(planned) match {
              case Success(result) if (result \ "outcome").asOpt[String].contains(EngineBroadcast.Accepted) =>
                val txId = (result \ "txId").as[String]
                tracker.ownArbs.note(Seq(planned.targetId, planned.poolBox.boxId), txId)
                sent += 1
                logger.info(s"Broadcast ZC collateral arb $txId for ${planned.targetId} through pool " +
                  s"${planned.swap.pool.nft}: ${(result \ "netNanoErg").asOpt[String].getOrElse("?")} nanoERG net " +
                  s"on ${planned.paid} paid out")
              case Success(result) if (result \ "outcome").asOpt[String].contains(EngineBroadcast.Uncertain) =>
                (result \ "txId").asOpt[String].foreach(txId =>
                  tracker.ownArbs.note(Seq(planned.targetId, planned.poolBox.boxId), txId))
                refused += planned.targetId
                going = false
                logger.warn(s"ZC collateral arb for ${planned.targetId} had uncertain broadcast outcome: $result; stopping pass")
              case Success(result) =>
                refused += planned.targetId
                logger.warn(s"ZC collateral arb for ${planned.targetId} was not accepted: $result")
              case Failure(ex: java.util.concurrent.TimeoutException) =>
                refused += planned.targetId
                going = false
                logger.warn(s"ZC collateral arb for ${planned.targetId} timed out waiting on engine: ${ex.getMessage}; stopping pass")
              case Failure(ex) =>
                refused += planned.targetId
                logger.warn(s"ZC collateral arb for ${planned.targetId} was not sent: ${ex.getMessage}")
            }
        }
      }
      refused
    }
  }

  protected def collateralArbPassBudget: FiniteDuration = ZcBatcher.CollateralArbPassBudget

  /** Hands `planned` to the engine, which funds, signs, checks and sends it, and waits for its answer. */
  protected def submitArb(planned: ZcMempoolPlan): Try[JsObject] = Try {
    val now = System.currentTimeMillis()
    val intent = ZcIntent.CollateralArb(planned, settings.collateralArb, tracker, resolver)
    val scheduled = TransactionEngine.Schedule(EngineIntent.Zc(intent), now, now + ArbSendWindow.toMillis)
    Await.result(engine.ask(scheduled)(Timeout(ArbAnswerWait)), ArbAnswerWait) match {
      case result: JsObject => result
      case TransactionEngine.Deferred(_, reason) => throw new IllegalStateException(s"the engine did not run it: $reason")
      case akka.actor.Status.Failure(ex) => throw ex
      case other => throw new IllegalStateException(s"the engine answered ${other.getClass.getSimpleName}")
    }
  }

  private final val ArbSendWindow: FiniteDuration = 30.seconds
  private final val ArbAnswerWait: FiniteDuration = ArbSendWindow + 40.seconds

  /**
   * The oracle one build reads. With no spend of the confirmed oracle box pending, `book` as it is. With exactly one,
   * whose successor parses and which [[standsAlone]], `book` reading that successor, with the refresh as `pending`:
   * candidates carry it ahead of what reads its box, and broadcasts send only under [[ZcRefreshGuard]]. Otherwise
   * oracle-dependent work waits (`usable` false).
   */
  private def oracleFor(book: ZcBook, snapshot: CompleteMempool.Snapshot,
                        spenders: BatchingMempool.Spenders): ZcBatcher.OracleBasis = {
    val seen = tracker.view.flatMap(_.mempool).toSeq.flatMap(_.refreshes)
    val (spends, refreshes) = ZcRefresh.of(snapshot.transactions.map(_.body), book.oracle.map(_.box.boxId),
      snapshot.observedAt, seen)
    if (spends == 0) ZcBatcher.OracleBasis(book, usable = true, None)
    else refreshes.headOption.filter(r => spends == 1 && r.successor.isDefined && standsAlone(r, snapshot, spenders)) match {
      case Some(r) => ZcBatcher.OracleBasis(book.withOracle(r.successor.get), usable = true, Some(r))
      case None => ZcBatcher.OracleBasis(book, usable = false, None)
    }
  }

  /**
   * Whether `r` can be carried alone: every box it spends or reads is confirmed, and nothing else in the mempool
   * spends what it spends. Carried with an unconfirmed parent or beside a rival, the candidate would be refused.
   */
  private def standsAlone(r: ZcRefresh, snapshot: CompleteMempool.Snapshot, spenders: BatchingMempool.Spenders): Boolean = {
    val created = snapshot.transactions.iterator.flatMap(_.body.outputs.iterator.map(_.boxId)).toSet
    (r.body.inputs.map(_.boxId) ++ r.body.dataInputs.map(_.boxId)).forall(id => !created.contains(id)) &&
      r.body.inputs.forall(in => spenders.get(in.boxId).exists(c => c.size == 1 && c.head.id == r.txId))
  }

  /** Whether `tx` reads the box `r` creates. */
  private def readsPending(tx: ZcBatchTx, r: ZcRefresh): Boolean = r.successor.exists { o =>
    tx.signed.asInstanceOf[org.ergoplatform.appkit.impl.SignedTransactionImpl].getTx.dataInputs
      .exists(d => org.bouncycastle.util.encoders.Hex.toHexString(d.boxId) == o.box.boxId)
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

object ZcBatcher {

  /** Most collateral arb submissions attempted in one broadcast pass, whether accepted or refused. */
  final val MaxArbAttemptsPerPass: Int = ZcMempoolArb.MaxPerPass * 2

  /** Wall-clock ceiling for one collateral arb broadcast pass so engine slowness cannot stall ZC discovery. */
  final val CollateralArbPassBudget: FiniteDuration = 90.seconds

  /** The book one build plans on, whether oracle-dependent work may run, and the pending refresh it reads, if any. */
  final case class OracleBasis(book: ZcBook, usable: Boolean, pending: Option[ZcRefresh]) {
    def carried: Option[ZcRefresh] = pending
  }
}
