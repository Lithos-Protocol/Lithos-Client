package mining

import akka.actor.{Actor, ActorRef, Cancellable}
import akka.pattern.ask
import akka.util.Timeout
import configs.CandidateConfig
import configs.Contexts
import mining.CandidateBuilder._
import mining.MiningMessages._
import mutations.NodeWallet
import node.NodeApi
import org.ergoplatform.appkit.ErgoClient
import org.slf4j.{Logger, LoggerFactory}
import stratum.{CollateralData, CollateralNotFoundException}
import transactions.candidate.BlockTxMessages.{BlockTxsReady, CandidateTx, CandidateTxsDropped, PrepareBlockTxs, RequestBlockTxs}
import transactions.candidate.{CandidateBudget, CandidateBundle, CandidateCapital, CandidateRefusal}
import java.util.UUID

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Random, Success, Try}

/** Builds genesis and bounded source packages asynchronously for the mining pool. */
class CandidateBuilder(client: ErgoClient,
                       prover: NodeWallet,
                       nodeApi: NodeApi,
                       config: CandidateConfig,
                       txSources: Seq[MiningMessages.CandidateSource]) extends Actor {

  private val logger: Logger = LoggerFactory.getLogger("CandidateBuilder")

  private implicit val ec: ExecutionContext = context.dispatcher
  /** Only genesis construction uses this capacity; refresh cannot delay a cached-input build. */
  private val buildEc: ExecutionContext =
    context.system.dispatchers.lookup(Contexts.key(Contexts.Genesis))
  private val refreshEc: ExecutionContext =
    context.system.dispatchers.lookup(Contexts.key(Contexts.Polling))
  private val collectionEc: ExecutionContext =
    context.system.dispatchers.lookup(Contexts.key(Contexts.EngineCandidate))

  private implicit val askTimeout: Timeout = Timeout(config.blockTxTimeout.milliseconds)

  private def limitsFor(name: String): configs.CandidateSourceConfig =
    config.sources.getOrElse(name, configs.CandidateSourceConfig.Default)

  /** Sources this client will actually ask. A disabled one costs no build and no node read. */
  private val enabledSources: Seq[MiningMessages.CandidateSource] =
    txSources.filter(source => limitsFor(source.name).enabled && limitsFor(source.name).maxTxs > 0)

  /** The package cannot hold more than every enabled source is allowed between them. */
  private val totalTxLimit: Int = enabledSources.map(source => limitsFor(source.name).maxTxs).sum

  /** Overridable so a test can drive the actor's selection rules without signing real transactions. */
  protected val txBuilder: CandidateTxBuilder = new CandidateTxBuilder(prover, nodeApi, config)
  private val random = new Random()

  // ─── state ────────────────────────────────────────────────────────────────

  /** Height of the block being built — one past the confirmed chain tip. */
  private var blockHeight: Int = 0
  private var parentId: String = ""
  private var augmentationStarted: Boolean = false

  private var currentPackage: Option[BlockPackage] = None

  /** The pre-loaded collateral set, and the box ranked out of it for the height being mined. */
  private var collateralSet: Vector[CollateralCandidate] = Vector.empty
  private var selectedId: Option[String] = None

  /** Collateral boxes excluded after a build failure or rejection at this height. */
  private var skipped: Set[String] = Set.empty

  /** Spent collateral IDs and their observation heights, retained briefly across blocks for indexer lag. */
  private var knownSpent: Map[String, Int] = Map.empty

  /** The genesis build a worker currently owns. A result carrying any other id is discarded. */
  private var activeBuild: Option[UUID] = None
  private var refreshing: Boolean = false

  /** Identity and deadline of the only collection allowed to update the current genesis. */
  private var collectingFor: Option[CollectionAttempt] = None

  /** Fences build results across chain changes and replacement genesis transactions. */
  private var buildGeneration: UUID = UUID.randomUUID()

  /** Monotonic clock seam for testing result admission independently of scheduler delivery. */
  protected def nowNanos(): Long = System.nanoTime()

  /** `System.nanoTime` when the in-flight build and collection began; 0 when none is. */
  private var buildStartedAt: Long = 0L
  private var collectStartedAt: Long = 0L

  /** A height that asked for a build while one was already running. */
  private var pendingBuild: Option[Int] = None

  /** Set when a build fails so the next completed refresh retries it, bounded by [[MaxBuildRetries]]. */
  private var rebuildAfterRefresh: Boolean = false
  private var buildRetries: Int = 0

  /** The height whose block transactions the node refused — do not offer them again for that block. */
  private var blockTxsBlockedAt: Option[Int] = None

  private var refreshTicker: Option[Cancellable] = None

  // ─── lifecycle ────────────────────────────────────────────────────────────

  override def preStart(): Unit = {
    logger.info(s"CandidateBuilder started: collateralPoolSize=${config.collateralPoolSize}, " +
      s"blockTransactions=${config.blockTransactions}, sources=[" +
      enabledSources.map(s => s"${s.name}:${limitsFor(s.name).maxTxs}").mkString(", ") + "]")

    // Warms both the collateral set and the compiled contract cache before the first block.
    startRefresh()

    refreshTicker = Some(context.system.scheduler.scheduleWithFixedDelay(
      config.collateralRefreshInterval.milliseconds,
      config.collateralRefreshInterval.milliseconds,
      self, RefreshCollateralSet)(context.dispatcher))
  }

  override def postStop(): Unit = refreshTicker.foreach(_.cancel())

  // ─── receive ──────────────────────────────────────────────────────────────

  override def receive: Receive = {

    // Replace chain-local work and defer collateral refresh until after genesis starts.
    case ChainAdvanced(height, parent) =>
      if (height > 0 && (height > blockHeight ||
        (parent.nonEmpty && (parent != parentId || height != blockHeight)))) {
        // Told before anything else: a source holding a wallet box for the previous height's
        // candidate has no other way to learn that height is over.
        if (blockHeight > 0) dropCandidates(blockHeight)
        blockHeight = height
        parentId = parent
        buildGeneration = UUID.randomUUID()
        currentPackage = None
        blockTxsBlockedAt = None
        rebuildAfterRefresh = false
        buildRetries = 0
        skipped = Set.empty
        // Re-ranked from scratch at every height. Holding the previous choice would freeze the set
        // on whichever box happened to be picked first and no box would ever become overdue.
        selectedId = None
        // Retires the previous height's collection round. Its answer can no longer reach a package
        collectingFor = None
        knownSpent = knownSpent.filter(e => height - e._2 < SpentMemoryBlocks)
        startBuild(height)
        prepareBlockTxs(height)
        context.system.scheduler.scheduleOnce(
          PostBlockRefreshDelay, self, RefreshCollateralSet)(context.dispatcher)
      }

    // The node refused the whole package. Drop the chosen box before rebuilding; a box spent out
    // from under us is the usual cause and looks exactly like this.
    case RebuildCandidate =>
      if (blockHeight > 0) {
        buildGeneration = UUID.randomUUID()
        collectingFor = None
        dropCandidates(blockHeight)
        currentPackage = None
        buildRetries = 0
        selectedId.foreach(id => skipped += id)
        selectedId = None
        startBuild(blockHeight)
      }

    // Stop offering additions for the rejected genesis.
    case BlockTxsRejected(height, identity) if height == blockHeight &&
      identity.forall(id => currentPackage.exists(_.identity.sameGenesis(id))) =>
      collectingFor = None
      if (!blockTxsBlockedAt.contains(height)) {
        logger.warn(s"Node rejected the inserted transactions for block $height: " +
          "mining on the genesis transaction alone until the next block")
        blockTxsBlockedAt = Some(height)
        dropCandidates(height)
      }
      currentPackage.filter(p => p.blockHeight == height && p.blockTxs.nonEmpty).foreach { pkg =>
        currentPackage = Some(pkg.copy(blockTxs = Seq.empty[CandidateTx], revenue = 0L))
      }

    // Without the package wait, collection starts after this genesis reaches miners.
    case GenesisPublished(identity) =>
      currentPackage.filter(pkg => pkg.identity == identity && pkg.blockTxs.isEmpty).foreach { _ =>
        if (!augmentationStarted && config.blockTransactions && !blockTxsBlockedAt.contains(blockHeight)) {
          augmentationStarted = true
          collectBlockTxs(blockHeight)
        }
      }

    case RefreshBlockPackage(identity) =>
      currentPackage.filter(_.identity.sameGenesis(identity)).foreach { pkg =>
        if (config.blockTransactions && enabledSources.nonEmpty && !blockTxsBlockedAt.contains(blockHeight))
          collectBlockTxs(blockHeight, refresh = true)
        else context.parent ! BlockPackageReady(pkg, refreshed = true)
      }

    // Drop it now rather than waiting for the refresh to notice. The build for the next block starts
    // in the same instant this arrives, and it works off this set.
    case CollateralSpent(boxId) =>
      if (collateralSet.exists(_.id == boxId)) {
        // Sent both when a block of ours spends the box and when the node refuses the transaction
        // built on it, so the message says what is known rather than assuming which.
        logger.info(s"Collateral box $boxId is no longer usable, removing it from the set")
        collateralSet = collateralSet.filterNot(_.id == boxId)
      }
      // Remembered across blocks, not just this one: the refresh runs against an indexer that has
      // not necessarily applied the block yet, and would otherwise hand the box straight back.
      knownSpent += boxId -> blockHeight
      skipped += boxId
      if (selectedId.contains(boxId)) selectedId = None

    case RefreshCollateralSet =>
      startRefresh()

    // ------------------------------------------------------------------
    // Build results
    // ------------------------------------------------------------------

    case GenesisReady(generation, height, loaded, chosenId, data) if activeBuild.contains(generation) =>
      activeBuild = None
      // Only carries a set when the build had to load one, which is the bootstrap case. Otherwise
      // the build worked off this actor's own snapshot and there is nothing to write back.
      if (height == blockHeight && generation == buildGeneration) {
        loaded.foreach(boxes => collateralSet = usable(boxes))
        // Height-local, so it is claimed here rather than before the check.
        selectedId = Some(chosenId)
        buildRetries = 0
        val pkg = BlockPackage(height, data, parentId = parentId)
        currentPackage = Some(pkg)
        if (config.waitForBlockPackage && config.blockTransactions && !blockTxsBlockedAt.contains(height)) {
          augmentationStarted = true
          collectBlockTxs(height)
        }
        publish(pkg, "genesisBuildMs", buildStartedAt)
      } else {
        logger.info(s"Discarding genesis transaction for block $height: the chain is at $blockHeight")
      }
      drainPending()

    case BlockTxsCollected(attempt, txs, revenue) =>
      if (collectingFor.contains(attempt)) {
        if (nowNanos() - attempt.startedAt >= config.blockTxTimeout.milliseconds.toNanos) {
          expireCollection(attempt)
        } else {
          collectingFor = None
          currentPackage.filter(p => p.blockHeight == attempt.height &&
            p.collateral.txId == attempt.genesisId && !blockTxsBlockedAt.contains(attempt.height))
            .filter(_ => txs.nonEmpty || attempt.refresh || config.waitForBlockPackage).foreach { pkg =>
              val updated = pkg.withBlockTxs(txs, revenue)
              currentPackage = Some(updated)
              publish(updated, "augmentedBuildMs", collectStartedAt, refreshed = attempt.refresh)
            }
        }
      }

    case CollectTimedOut(attempt) =>
      if (collectingFor.contains(attempt)) expireCollection(attempt)

    case CollateralSetLoaded(boxes) =>
      refreshing = false
      val changed = boxes.size != collateralSet.size
      collateralSet = usable(boxes)
      val lost = selectedId.exists(id => !collateralSet.exists(_.id == id))
      if (lost) {
        logger.info(s"Collateral box ${selectedId.get} is gone, a new one will be chosen")
        selectedId = None
      }
      if (changed) logger.info(s"Collateral set holds ${collateralSet.size} live box(es)")
      if ((lost || rebuildAfterRefresh) && blockHeight > 0) {
        rebuildAfterRefresh = false
        startBuild(blockHeight)
      }

    case RefreshFailed(ex) =>
      refreshing = false
      // Whatever was already loaded still mines, so this is not fatal on its own.
      logger.error(s"Failed to refresh the collateral set: ${ex.getMessage}", ex)

    case BuildFailed(generation, height, failedId, ex) if activeBuild.contains(generation) =>
      activeBuild = None
      // Everything a failure touches — the skip set, the current choice and the retry budget — is
      // owned by the height it was raised for.
      if (height == blockHeight && generation == buildGeneration) {
        logger.error(s"Failed to build the genesis transaction for block $height: ${ex.getMessage}", ex)
        // Skip the box before retrying, so the next attempt draws a different one. Bounded until MaxBuildRetries.
        failedId.foreach(id => skipped += id)
        selectedId.foreach(id => skipped += id)
        selectedId = None
        if (buildRetries < MaxBuildRetries) {
          buildRetries += 1
          rebuildAfterRefresh = true
          startRefresh()
        }
      } else {
        logger.info(s"Discarding a failed build for block $height: the chain is at $blockHeight " +
          s"(${ex.getMessage})")
      }
      drainPending()
  }

  // ─── private helpers ──────────────────────────────────────────────────────

  /** Filters observed spends and retires spent IDs the indexer no longer reports. */
  private def usable(boxes: Seq[CollateralCandidate]): Vector[CollateralCandidate] = {
    val reported = boxes.map(_.id).toSet
    knownSpent = knownSpent.filterKeys(reported.contains).toMap
    boxes.filterNot(b => knownSpent.contains(b.id)).toVector
  }

  private def dropCandidates(height: Int): Unit =
    txSources.foreach(_.ref ! CandidateTxsDropped(height))

  private def expireCollection(attempt: CollectionAttempt): Unit = {
    collectingFor = None
    if (attempt.refresh) {
      logger.warn(s"Package refresh for block ${attempt.height} exceeded ${config.blockTxTimeout}ms; retaining published work")
    } else {
      blockTxsBlockedAt = Some(attempt.height)
      dropCandidates(attempt.height)
      if (config.waitForBlockPackage) currentPackage.foreach(pkg => context.parent ! BlockPackageReady(pkg))
      logger.warn(s"Transaction collection for block ${attempt.height} exceeded " +
        s"${config.blockTxTimeout}ms; mining on genesis alone until the next block")
    }
  }

  private def publish(pkg: BlockPackage, stage: String, startedAtNanos: Long, refreshed: Boolean = false): Unit = {
    val publication = pkg.copy(elapsedTime = Some(elapsed(stage, startedAtNanos)))

    context.parent ! BlockPackageReady(publication, collecting = collectingFor.nonEmpty, refreshed = refreshed)
    if (config.logBudgets && publication.revision == 0 && collectingFor.isEmpty)
      readBudgets(publication.blockHeight).foreach { case (block, budget) =>
        logBudgets(publication.blockHeight, Seq.empty, Seq.empty, Seq.empty, None,
          publication.collateral.signedSizeBytes.toLong, publication.collateral.cost, block, budget)
      }
  }

  /** `; genesisBuildMs=N`, or empty when timing is off or the stage never started. */
  private def elapsed(stage: String, startedAtNanos: Long): String =
    if (!config.logTimings || startedAtNanos == 0L) ""
    else s"; $stage=${(System.nanoTime() - startedAtNanos) / 1000000L}"

  /** Runs a build that arrived while another was in flight, if it is still the block being mined. */
  private def drainPending(): Unit =
    pendingBuild.foreach { height =>
      pendingBuild = None
      if (height == blockHeight && currentPackage.isEmpty) startBuild(height)
    }

  /** Signs genesis on its own worker, loading collateral only when the cached set is empty. */
  private def startBuild(height: Int): Unit = {
    if (activeBuild.nonEmpty) {
      pendingBuild = Some(height)
    } else {
      activeBuild = Some(buildGeneration)
      augmentationStarted = false
      collectingFor = None
      val generation = buildGeneration
      buildStartedAt = System.nanoTime()
      val snapshot = collateralSet
      val sticky = selectedId
      // knownSpent as well as skipped, because the bootstrap branch below loads its own set and
      // would otherwise be the one path that can still draw a box we watched get spent.
      val avoid = skipped ++ knownSpent.keySet
      Future {
        client.execute { ctx =>
          val loaded = if (snapshot.isEmpty) Some(txBuilder.loadCollateral(ctx)) else None
          val all = loaded.getOrElse(snapshot)
          val boxes = all.filterNot(b => avoid.contains(b.id))
          if (boxes.isEmpty)
            throw new CollateralNotFoundException(
              s"no usable collateral boxes: ${all.size} live, ${avoid.size} skipped this block")

          // Keep the selected miner key at this height; draw uniformly among equally ranked collateral boxes.
          val chosen = sticky
            .flatMap(id => boxes.find(_.id == id))
            .getOrElse {
              val best = CandidateTxBuilder.bestCandidates(boxes, height)
              best(random.nextInt(best.size))
            }

          val chosenId = chosen.id
          Try(txBuilder.buildGenesis(ctx, chosen.input, height)) match {
            case Success(data) => GenesisReady(generation, height, loaded, chosenId, data)
            case Failure(ex) => BuildFailed(generation, height, Some(chosenId), ex)
          }
        }
      }(buildEc).onComplete {
        case Success(msg) => self ! msg
        case Failure(ex) => self ! BuildFailed(generation, height, None, ex)
      }
    }
  }

  /** Starts enabled sources alongside genesis construction. */
  private def prepareBlockTxs(height: Int): Unit =
    if (config.blockTransactions)
      enabledSources.foreach(source =>
        source.ref ! PrepareBlockTxs(height, limitsFor(source.name).maxTxs))

  /** Reports bundles rejected by source or package admission. */
  private def logRefusals(where: String, height: Int, refused: Seq[CandidateRefusal]): Unit =
    if (refused.nonEmpty)
      logger.info(s"Block $height $where refused ${refused.size} bundle(s): " +
        refused.map(_.toString).mkString("; "))

  /** Collects one bounded round in source order; refresh requests rebuild mempool-dependent offers. */
  private def collectBlockTxs(height: Int, refresh: Boolean = false): Unit =
    if (collectingFor.isEmpty && enabledSources.nonEmpty && totalTxLimit > 0) {
      val attempt = CollectionAttempt(UUID.randomUUID(), height,
        currentPackage.get.collateral.txId, nowNanos(), refresh)
      collectingFor = Some(attempt)
      collectStartedAt = System.nanoTime()
      context.system.scheduler.scheduleOnce(
        config.blockTxTimeout.milliseconds, self, CollectTimedOut(attempt))(context.dispatcher)

      // Each source is asked for its own allowance and bounded against it before anything is
      // combined, so a busy source cannot take the space a quieter one was given.
      val asks = enabledSources.map { source =>
        val limits = limitsFor(source.name)
        (source.ref ? RequestBlockTxs(height, limits.maxTxs, refresh))
          .mapTo[BlockTxsReady]
          .map { reply =>
            if (reply.blockHeight != height) Seq.empty[CandidateBundle]
            else {
              val (fitted, _, refused) =
                CandidateBundle.admit(reply.bundles, limits.maxTxs, limits.budget)
              logRefusals(s"${source.name} allowance", height, refused)
              fitted
            }
          }
          .recover {
            case ex =>
              logger.warn(s"Transaction source ${source.name} failed for block $height: ${ex.getMessage}")
              Seq.empty[CandidateBundle]
          }.map(source.name -> _)
      }

      // Charge the signed genesis bytes and cost before admitting source bundles.
      val genesis = currentPackage.map(_.collateral)
      val genesisBytes = genesis.map(_.signedSizeBytes.toLong).getOrElse(0L)
      val genesisCost = genesis.map(_.cost).getOrElse(0L)
      Future.sequence(asks).zip(readBudgets(height))
        .map { case (all, (blockBudget, packageBudget)) =>
          val budget = packageBudget.less(genesisBytes, genesisCost)
          val (kept, txs, refused) = CandidateBundle.admit(all.flatMap(_._2), totalTxLimit, budget)
          logRefusals("package", height, refused)
          val ledger = capitalFor(height, kept)
          val topUp = topUpFor(height, ledger, txs, budget, genesis)
          if (config.logBudgets)
            logBudgets(height, all, kept, txs, topUp, genesisBytes, genesisCost, blockBudget, packageBudget)
          BlockTxsCollected(attempt, txs ++ topUp, ledger.availableErg)
        }(collectionEc)
        .onComplete {
          case Success(msg) => self ! msg
          case Failure(ex) =>
            logger.warn(s"Could not collect block transactions for $height: ${ex.getMessage}")
            self ! BlockTxsCollected(attempt, Seq.empty[CandidateTx])
        }
    }

  /** Credits each admitted revenue output once. */
  private def capitalFor(height: Int, kept: Seq[CandidateBundle]): CandidateCapital =
    kept.flatMap(_.capital).foldLeft(CandidateCapital(height)) {
      (ledger, entry) =>
        // Two sources naming one output is a defect in a source, and the box is spendable once
        // either way. Taking the first keeps the rest of the package rather than losing it here.
        if (ledger.entries.exists(_.outputId == entry.outputId)) {
          logger.warn(s"Candidate output ${entry.outputId} was declared twice for block $height; " +
            "the later declaration is ignored")
          ledger
        } else ledger.credit(entry)
    }

  private def topUpFor(height: Int,
                       ledger: CandidateCapital,
                       selected: Seq[CandidateTx],
                       budget: CandidateBudget,
                       genesis: Option[CollateralData]): Option[CandidateTx] = {
    if (ledger.entries.isEmpty) None
    else {
      val remaining = budget.less(selected.map(_.sizeBytes.toLong).sum, selected.map(_.cost).sum)
      val built = genesis.flatMap(data => Try(client.execute(ctx =>
        transactions.candidate.CandidateTopUp.build(ctx, prover, data, ledger, height))).toOption.flatten)
      // Dropped rather than truncated: the transactions that earned this revenue are already
      // selected, and their outputs stay spendable in a later block.
      built.filter { tx =>
        val fits = tx.sizeBytes <= remaining.maxBytes && tx.cost <= remaining.maxCost
        if (!fits) logger.info(s"Holding top-up for block $height does not fit what is left of the " +
          s"package budget (${tx.sizeBytes}B, ${tx.cost} cost)")
        fits
      }
    }
  }

  /** Active node limits and the configured package share, read off the mailbox. */
  private def readBudgets(height: Int): Future[(CandidateBudget, CandidateBudget)] =
    Future(client.execute { ctx =>
      val parameters = ctx.getDataSource.getParameters
      val block = CandidateBudget(parameters.getMaxBlockSize, parameters.getMaxBlockCost)
      block -> CandidateBudget.of(block.maxBytes, block.maxCost, config.blockShare)
    })(refreshEc).recover { case ex =>
      logger.warn(s"Cannot read candidate budgets for block $height: ${ex.getMessage}")
      CandidateBudget.Unbounded -> CandidateBudget.Unbounded
    }

  /** Source contributions count shared ancestors once, in package admission order. */
  private def logBudgets(height: Int, sources: Seq[(String, Seq[CandidateBundle])],
                          kept: Seq[CandidateBundle], txs: Seq[CandidateTx], topUp: Option[CandidateTx],
                          genesisBytes: Long, genesisCost: Long,
                          block: CandidateBudget, budget: CandidateBudget): Unit = {
    val admitted = kept.toSet
    var charged = Set.empty[String]
    sources.foreach { case (name, offered) =>
      val fitted = CandidateBundle.select(offered, limitsFor(name).maxTxs)
      val contributed = offered.filter(admitted.contains).flatMap(_.members).filter { tx =>
        val first = !charged.contains(tx.id)
        charged += tx.id
        first
      }
      val limits = limitsFor(name)
      logger.info(s"Block $height src=$name | txs=${fitted.size}/${limits.maxTxs}, " +
        s"bytes=${fitted.map(_.sizeBytes.toLong).sum}/${limits.maxBytes}, " +
        s"cost=${fitted.map(_.cost).sum}/${limits.maxCost}; package contribution: " +
        s"txs=${contributed.size}, bytes=${contributed.map(_.sizeBytes.toLong).sum}, cost=${contributed.map(_.cost).sum}")
    }
    val bytes = genesisBytes + txs.map(_.sizeBytes.toLong).sum + topUp.map(_.sizeBytes.toLong).getOrElse(0L)
    val cost = genesisCost + txs.map(_.cost).sum + topUp.map(_.cost).getOrElse(0L)
    logger.info(s"Block $height package budgets: bytes=$bytes/${budget.maxBytes}, cost=$cost/${budget.maxCost}; " +
      s"block limits: bytes=${block.maxBytes}, cost=${block.maxCost}; " +
      s"genesis: bytes=$genesisBytes, cost=$genesisCost; " +
      s"top-up: bytes=${topUp.map(_.sizeBytes).getOrElse(0)}, cost=${topUp.map(_.cost).getOrElse(0L)}; " +
      "carried ancestor cost is unknown")
  }

  private def startRefresh(): Unit =
    if (!refreshing) {
      refreshing = true
      Future {
        client.execute(ctx => CollateralSetLoaded(txBuilder.loadCollateral(ctx)))
      }(refreshEc).onComplete {
        case Success(msg) => self ! msg
        case Failure(ex) => self ! RefreshFailed(ex)
      }
    }
}

object CandidateBuilder {

  /** How many times a failed genesis build is retried before the block is left to solo mining. */
  private[mining] final val MaxBuildRetries = 2

  /** How long after a block to run the collateral refresh, so it is not competing with the build. */
  private[mining] final val PostBlockRefreshDelay: FiniteDuration = 2.seconds

  /** Number of blocks to exclude a spent collateral box still reported by the indexer. */
  private[mining] final val SpentMemoryBlocks: Int = 3

  /** Scheduler tick and manual trigger for the background collateral refresh. */
  private[mining] case object RefreshCollateralSet

  private[mining] case class CollateralSetLoaded(boxes: Seq[CollateralCandidate])

  private[mining] case class RefreshFailed(ex: Throwable)

  private[mining] case class GenesisReady(generation: UUID, blockHeight: Int,
                                          loaded: Option[Seq[CollateralCandidate]],
                                          chosenId: String,
                                          data: CollateralData)

  private[mining] case class CollectionAttempt(id: UUID, height: Int, genesisId: String, startedAt: Long,
                                              refresh: Boolean = false)

  private[mining] case class BlockTxsCollected(attempt: CollectionAttempt, txs: Seq[CandidateTx], revenue: Long = 0L)

  private[mining] case class CollectTimedOut(attempt: CollectionAttempt)

  private[mining] case class BuildFailed(generation: UUID, blockHeight: Int, failedId: Option[String], ex: Throwable)
}
