package mining

import akka.actor.{Actor, ActorRef, Cancellable}
import akka.pattern.ask
import akka.util.Timeout
import configs.CandidateConfig
import mining.CandidateBuilder._
import mining.MiningMessages._
import mutations.NodeWallet
import node.NodeApi
import org.ergoplatform.appkit.ErgoClient
import org.slf4j.{Logger, LoggerFactory}
import stratum.{CollateralData, CollateralNotFoundException}
import transactions.BlockTxMessages.{BlockTxsReady, CandidateTx, RequestBlockTxs}
import work.lithos.mutations.InputUTXO

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Random, Success, Try}

/**
 * Assembles this miner's block for [[LithosPool]], its parent and only consumer.
 *
 * Builds exactly one transaction, the genesis transaction, and publishes it alone before asking the
 * transaction actors for anything else — the first candidate of a block should cost one signature.
 * Everything else is optional and capped at `maxBlockTxs`.
 *
 * Two things keep node calls off the critical path: the collateral set is pre-loaded and refreshed
 * in the background, and the box drawn from it is kept until it is gone, because the node caches one
 * candidate per miner key and the collateral box decides that key. Nothing here blocks the mailbox.
 */
class CandidateBuilder(client: ErgoClient,
                       prover: NodeWallet,
                       nodeApi: NodeApi,
                       config: CandidateConfig,
                       txSources: Seq[ActorRef]) extends Actor {

  private val logger: Logger = LoggerFactory.getLogger("CandidateBuilder")

  /** Node calls and signing run here, so a slow node cannot stall the mailbox. */
  private implicit val buildEc: ExecutionContext =
    Try(context.system.dispatchers.lookup("lithos-contexts.polling-dispatcher"))
      .getOrElse(context.dispatcher)

  private implicit val askTimeout: Timeout = Timeout(config.blockTxTimeout.milliseconds)

  /** Overridable so a test can drive the actor's selection rules without signing real transactions. */
  protected val txBuilder: CandidateTxBuilder = new CandidateTxBuilder(prover, nodeApi, config)
  private val random = new Random()

  // ─── state ────────────────────────────────────────────────────────────────

  /** Height of the block being built — one past the confirmed chain tip. */
  private var blockHeight: Int = 0

  private var currentPackage: Option[BlockPackage] = None

  /** The pre-loaded collateral set, and the box chosen out of it for as long as it lives. */
  private var collateralSet: Vector[InputUTXO] = Vector.empty
  private var selectedId: Option[String] = None

  /**
   * Boxes that failed to build or were refused, skipped for the rest of this block. Without it the
   * sticky choice re-picks the same box on every retry and every later block, so one unspendable box
   * in the set would stop collateral mining permanently.
   */
  private var skipped: Set[String] = Set.empty

  /**
   * Boxes seen to be consumed, and the height that was seen at. Kept ACROSS blocks, unlike `skipped`.
   *
   * A collateral box is spent by the block that mines it, but the indexer can still report it as
   * unspent seconds later — and the refresh two seconds after a block would then put it straight
   * back into the set for the next build. The node drops a genesis transaction whose input it
   * cannot find without a word or an error, so that costs the whole block its Lithos status.
   *
   * Forgotten after [[SpentMemoryBlocks]] blocks, because this is also set when the node merely
   * declines a transaction, where the box may be perfectly good and holding it out forever would
   * shrink the usable set one box at a time.
   */
  private var knownSpent: Map[String, Int] = Map.empty

  private var building: Boolean = false
  private var refreshing: Boolean = false

  /** The block a collection round is running for, if one is. */
  private var collectingFor: Option[Int] = None

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
      s"blockTransactions=${config.blockTransactions} (max ${config.maxBlockTxs} from " +
      s"${txSources.size} source(s))")

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

    // Refresh after the build rather than alongside it. The set can only have changed if the block
    // that just arrived was a Lithos block, and either way the result is only used by the NEXT
    // build — while the instant a block lands is the busiest the machine gets, with this build, the
    // node applying the block and then constructing a candidate all at once. A rig sharing the host
    // feels every bit of that, so nothing that can wait runs here.
    case ChainAdvanced(height) =>
      if (height > blockHeight) {
        blockHeight = height
        currentPackage = None
        blockTxsBlockedAt = None
        rebuildAfterRefresh = false
        buildRetries = 0
        skipped = Set.empty
        knownSpent = knownSpent.filter(e => height - e._2 < SpentMemoryBlocks)
        startBuild(height)
        context.system.scheduler.scheduleOnce(
          PostBlockRefreshDelay, self, RefreshCollateralSet)(context.dispatcher)
      }

    // The node refused the whole package. Drop the chosen box before rebuilding; a box spent out
    // from under us is the usual cause and looks exactly like this.
    case RebuildCandidate =>
      if (blockHeight > 0) {
        currentPackage = None
        buildRetries = 0
        selectedId.foreach(id => skipped += id)
        selectedId = None
        startBuild(blockHeight)
      }

    // Only the insertions were refused. Drop back to genesis for the rest of the block rather than
    // re-offering them; everything dropped is in the mempool anyway.
    case BlockTxsRejected(height) =>
      if (!blockTxsBlockedAt.contains(height)) {
        logger.warn(s"Node rejected the inserted transactions for block $height: " +
          "mining on the genesis transaction alone until the next block")
        blockTxsBlockedAt = Some(height)
      }
      currentPackage.filter(p => p.blockHeight == height && p.blockTxs.nonEmpty).foreach { pkg =>
        currentPackage = Some(pkg.copy(blockTxs = Seq.empty[CandidateTx]))
      }

    // Drop it now rather than waiting for the refresh to notice. The build for the next block starts
    // in the same instant this arrives, and it works off this set.
    case CollateralSpent(boxId) =>
      if (collateralSet.exists(_.id.toString == boxId)) {
        // Sent both when a block of ours spends the box and when the node refuses the transaction
        // built on it, so the message says what is known rather than assuming which.
        logger.info(s"Collateral box $boxId is no longer usable, removing it from the set")
        collateralSet = collateralSet.filterNot(_.id.toString == boxId)
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

    case GenesisReady(height, loaded, chosenId, data) =>
      building = false
      // Only carries a set when the build had to load one, which is the bootstrap case. Otherwise
      // the build worked off this actor's own snapshot and there is nothing to write back.
      loaded.foreach(boxes => collateralSet = usable(boxes))
      selectedId = Some(chosenId)
      if (height == blockHeight) {
        buildRetries = 0
        val pkg = BlockPackage(height, data)
        currentPackage = Some(pkg)
        publish(pkg)
        if (config.blockTransactions && !blockTxsBlockedAt.contains(height)) collectBlockTxs(height)
      } else {
        logger.info(s"Discarding genesis transaction for block $height: the chain is at $blockHeight")
      }
      drainPending()

    case BlockTxsCollected(height, txs) =>
      if (collectingFor.contains(height)) collectingFor = None
      if (txs.nonEmpty) {
        currentPackage.filter(_.blockHeight == height) match {
          case Some(pkg) =>
            val updated = pkg.withBlockTxs(txs)
            currentPackage = Some(updated)
            publish(updated)
          case None =>
            logger.info(s"Discarding ${txs.size} transaction(s) for block $height: " +
              "the package they were built for is gone")
        }
      }

    case CollectTimedOut(height) =>
      if (collectingFor.contains(height)) {
        collectingFor = None
        logger.warn(s"Transaction sources did not answer for block $height within " +
          s"${config.blockTxTimeout}ms. Now mining on the genesis transaction alone")
      }

    case CollateralSetLoaded(boxes) =>
      refreshing = false
      val changed = boxes.size != collateralSet.size
      collateralSet = usable(boxes)
      val lost = selectedId.exists(id => !collateralSet.exists(_.id.toString == id))
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

    case BuildFailed(height, ex) =>
      building = false
      logger.error(s"Failed to build the genesis transaction for block $height: ${ex.getMessage}", ex)
      // Skip the box before retrying, so the next attempt draws a different one. Bounded, because a
      // failure that is not about the box would otherwise spin against the node.
      selectedId.foreach(id => skipped += id)
      selectedId = None
      if (buildRetries < MaxBuildRetries) {
        buildRetries += 1
        rebuildAfterRefresh = true
        startRefresh()
      }
      drainPending()
  }

  // ─── private helpers ──────────────────────────────────────────────────────

  /**
   * A freshly loaded set with the boxes we have watched get spent taken out, and any entry the node
   * has stopped reporting dropped from that memory — that is the node agreeing, so there is nothing
   * left to remember.
   */
  private def usable(boxes: Seq[InputUTXO]): Vector[InputUTXO] = {
    val reported = boxes.map(_.id.toString).toSet
    knownSpent = knownSpent.filterKeys(reported.contains).toMap
    boxes.filterNot(b => knownSpent.contains(b.id.toString)).toVector
  }

  private def publish(pkg: BlockPackage): Unit = {
    logger.info(s"Ready: ${pkg.describe} with" +
      s" txSize: ${pkg.collateral.txBytes.length} and collatBytes: ${pkg.collateral.collateralBoxBytes.length}")
    context.parent ! BlockPackageReady(pkg)
  }

  /** Runs a build that arrived while another was in flight, if it is still the block being mined. */
  private def drainPending(): Unit =
    pendingBuild.foreach { height =>
      pendingBuild = None
      if (height == blockHeight && currentPackage.isEmpty) startBuild(height)
    }

  /**
   * The genesis transaction, off the actor thread. The collateral set goes in as a snapshot and
   * comes back with the result, so selection stays actor state while the work stays off its
   * dispatcher. The set is only loaded inside the build when nothing has been pre-loaded yet.
   */
  private def startBuild(height: Int): Unit = {
    if (building) {
      pendingBuild = Some(height)
    } else {
      building = true
      val snapshot = collateralSet
      val sticky = selectedId
      // knownSpent as well as skipped, because the bootstrap branch below loads its own set and
      // would otherwise be the one path that can still draw a box we watched get spent.
      val avoid = skipped ++ knownSpent.keySet
      Future {
        client.execute { ctx =>
          val loaded = if (snapshot.isEmpty) Some(txBuilder.loadCollateral(ctx)) else None
          val all = loaded.getOrElse(snapshot)
          val boxes = all.filterNot(b => avoid.contains(b.id.toString))
          if (boxes.isEmpty)
            throw new CollateralNotFoundException(
              s"no usable collateral boxes: ${all.size} live, ${avoid.size} skipped this block")

          // Keep the box already chosen, so a changing coinbase key does not evict the node's
          // candidate cache. The draw is uniform because the boxes are economically identical.
          val chosen = sticky
            .flatMap(id => boxes.find(_.id.toString == id))
            .getOrElse(boxes(random.nextInt(boxes.size)))

          GenesisReady(height, loaded, chosen.id.toString, txBuilder.buildGenesis(ctx, chosen, height))
        }
      }.onComplete {
        case Success(msg) => self ! msg
        case Failure(ex) => self ! BuildFailed(height, ex)
      }
    }
  }

  /**
   * Ask every transaction source what it has for this block, once the genesis package is out.
   * Answers are concatenated in ask order, so earlier sources get the slots, and each source's own
   * ordering is preserved because only it knows which of its transactions chain off which.
   */
  private def collectBlockTxs(height: Int): Unit =
    if (collectingFor.isEmpty && txSources.nonEmpty && config.maxBlockTxs > 0) {
      collectingFor = Some(height)
      context.system.scheduler.scheduleOnce(
        config.blockTxTimeout.milliseconds, self, CollectTimedOut(height))(context.dispatcher)

      // Each source is asked for the whole budget and the cap applied afterwards, so an empty first
      // source does not waste the block's slots.
      val asks = txSources.map { source =>
        (source ? RequestBlockTxs(height, config.maxBlockTxs))
          .mapTo[BlockTxsReady]
          .map(_.txs)
          .recover {
            case ex =>
              logger.warn(s"A transaction source failed for block $height: ${ex.getMessage}")
              Seq.empty[CandidateTx]
          }
      }

      Future.sequence(asks)
        .map(all => BlockTxsCollected(height, all.flatten.take(config.maxBlockTxs)))
        .onComplete {
          case Success(msg) => self ! msg
          case Failure(ex) =>
            logger.warn(s"Could not collect block transactions for $height: ${ex.getMessage}")
            self ! BlockTxsCollected(height, Seq.empty[CandidateTx])
        }
    }

  private def startRefresh(): Unit =
    if (!refreshing) {
      refreshing = true
      Future {
        client.execute(ctx => CollateralSetLoaded(txBuilder.loadCollateral(ctx)))
      }.onComplete {
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

  /**
   * Blocks a box stays in `knownSpent` while the node keeps reporting it. Long enough for an indexer
   * to apply the block that spent it, short enough that a box held out over a transaction the node
   * declined for some other reason comes back on its own.
   */
  private[mining] final val SpentMemoryBlocks: Int = 3

  /** Scheduler tick and manual trigger for the background collateral refresh. */
  private[mining] case object RefreshCollateralSet

  private[mining] case class CollateralSetLoaded(boxes: Seq[InputUTXO])

  private[mining] case class RefreshFailed(ex: Throwable)

  private[mining] case class GenesisReady(blockHeight: Int,
                                          loaded: Option[Seq[InputUTXO]],
                                          chosenId: String,
                                          data: CollateralData)

  private[mining] case class BlockTxsCollected(blockHeight: Int, txs: Seq[CandidateTx])

  private[mining] case class CollectTimedOut(blockHeight: Int)

  private[mining] case class BuildFailed(blockHeight: Int, ex: Throwable)
}
