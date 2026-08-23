package mining

import akka.actor.{Actor, ActorRef, Cancellable, Props}
import akka.pattern.pipe
import configs.CandidateConfig
import lfsm.LFSMHelpers
import evaluation.NTable
import mining.LithosPool._
import mining.MiningMessages._
import mutations.NodeWallet
import nisp.{NISPDatabase, SuperShare}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.ErgoClient
import org.slf4j.{Logger, LoggerFactory}
import scorex.utils.Ints
import transactions.rollups.{CommitmentTransactions, DataBoxSource}
import state.messages.StateFrameMessages.CheckBlock
import stratum.data.{MiningCandidate, Options}
import utils.Globals

import java.net.ConnectException
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
 * Top-level pool coordination actor.
 *
 * Responsibilities
 * ────────────────
 *  • Owns and supervises LithosJobManager and CandidateBuilder as children.
 *  • Polls the Ergo node for new block templates via a scheduler tick.
 *  • Maintains the set of active ConnectionActors and broadcasts new jobs.
 *  • Forwards RequestSubscription and ProcessShare messages from ConnectionActors
 *    to LithosJobManager using `forward` so replies travel directly back to the
 *    originating StratumConnection (bypassing LithosPool on the return path).
 *  • Handles pool-level share events: block submission and NISP super-share saving.
 *
 * CandidateBuilder pushes a finished BlockPackage here as soon as it has one; this actor never
 * builds or fetches it, and mining continues whether or not a poll has one.
 *
 * Created programmatically rather than through Guice, since its parameters come from StratumConfig
 * and blockchain state at runtime: `system.actorOf(Props(new LithosPool(options, ...)))`.
 *
 * @param options             Stratum options (polling interval, tau, etc.)
 * @param useCollateral       Whether to request block candidates using collateral UTXOs
 * @param client              Ergo client for collateral retrieval
 * @param prover              Node wallet used to sign the collateral transaction
 * @param apiKey              Node API key required for /mining/candidateWithTxsAndPk
 * @param reducedShareMessages Whether to divide the tau shown to miners by 1000
 * @param nispDB              NISP database for super-share persistence
 * @param stateFrame          StateFrame actor, told to re-check the chain on every new job
 * @param candidateConfig     Tuning for CandidateBuilder — what goes into a block and how much
 *                            work may happen while a miner waits for a candidate
 */
class LithosPool(options: Options,
                 useCollateral: Boolean,
                 client: ErgoClient,
                 prover: NodeWallet,
                 apiKey: String,
                 reducedShareMessages: Boolean,
                 nispDB: NISPDatabase,
                 stateFrame: ActorRef,
                 forceConfigDiff: Boolean,
                 diffRefreshInterval: Int,
                 candidateConfig: CandidateConfig = CandidateConfig.Default,
                 txSources: Seq[ActorRef] = Seq.empty[ActorRef]) extends Actor {

  private val logger: Logger = LoggerFactory.getLogger("LithosPool")
  implicit private val ec: ExecutionContext = context.dispatcher

  // ─── children & dependencies ──────────────────────────────────────────────

  /** Child actor that owns all job and share-validation state. */
  val jobManagerActor: ActorRef =
    context.actorOf(Props(new LithosJobManager(options)), "job-manager")

  /** Child actor that builds the genesis transaction and assembles the rest of the block. */
  val candidateBuilder: Option[ActorRef] =
    if (useCollateral)
      Some(context.actorOf(Props(new CandidateBuilder(
        client, prover, Globals.getNodeConfig.getNodeApi, candidateConfig, txSources)), "candidate-builder"))
    else None

  private val nodeInterface = new MiningNodeInterface(options.nodeApiUrl)

  /**
   * Reader for this miner's difficulty commitment. Lazy and built from the singleton, the same
   * deliberate exception as `CandidateBuilder` above: this actor takes its client and prover as
   * constructor arguments rather than a NodeContext, and the two paths move together.
   */
  private lazy val commitments =
    new CommitmentTransactions(Globals.getNodeConfig, DataBoxSource.Stored)

  // ─── mutable state ────────────────────────────────────────────────────────

  /** connectionId → StratumConnection, used for broadcasting and connection tracking. */
  private val connections: mutable.HashMap[String, ActorRef] = mutable.HashMap.empty

  private var pollTicker: Option[Cancellable] = None
  private var diffTicker: Option[Cancellable] = None
  private var tau: BigInt = BigInt(options.tau)

  /**
   * What CandidateBuilder has ready, and the block it was built for. Only usable at the exact
   * height it names, since the genesis transaction pins that height into the holding box's R7 and
   * its proof-of-spend box's creation height, so a stale one is dropped rather than offered.
   */
  private var blockPackage: Option[BlockPackage] = None

  /** Last block height observed from the node, i.e. the block currently being mined. */
  private var lastBlockHeight: Int = 0

  /** Height whose inserted transactions the node refused; its genesis transaction is still good. */
  private var extrasRejectedAt: Option[Int] = None

  /** Collateral rebuilds asked for during this block, capped by [[MaxRebuildsPerBlock]]. */
  private var rebuildsThisBlock: Int = 0

  /**
   * Genesis transactions the node has already refused, so they are not offered again on every poll.
   *
   * Keyed by TRANSACTION, not by height: a rebuild for the same block produces a different genesis
   * transaction from a different collateral box, and that one deserves a try. Keying by height
   * blocked the corrected package too and sent the whole block solo.
   */
  private var rejectedGenesisTxs: Set[String] = Set.empty

  /** When to stop waiting for this block's genesis transaction and put out a solo job instead. */
  private var genesisDeadline: Option[Long] = None

  /**
   * What the candidate miners are currently working was fetched for: height, genesis transaction,
   * and whether the inserted transactions were included.
   *
   * Asking the node for a candidate REPLACES the one it has cached, and it validates submitted
   * solutions against that cache rather than against whatever it handed out. So polling the
   * candidate endpoint on a timer quietly invalidates the job miners are hashing: a solution found
   * more than a moment after its job comes back `h(f) < b`. So a candidate is only fetched when it
   * will also be published: when this key changes, or on the mempool refresh below.
   */
  private var servedCandidate: Option[(Int, String, Boolean)] = None

  /** When a job last went out, so nothing is fetched that the job manager would then hold back. */
  private var lastJobAt: Long = 0L

  /** The header last published, so a refetch that changed nothing is not broadcast again. */
  private var servedMsg: Array[Byte] = Array.emptyByteArray

  private def stillWaitingForGenesis: Boolean =
    candidateBuilder.isDefined && genesisDeadline.exists(System.currentTimeMillis() < _)

  /**
   * Whether to pick up transactions that reached the mempool since this block's job went out.
   *
   * Worth doing — those are fees — and a same-height job costs a rig very little. It is paced rather
   * than done every poll because each fetch replaces the copy the node validates solutions against,
   * so a block found on the previous job in the moments after a refresh comes back `h(f) < b`. That
   * window is the notify round trip; the interval decides how often it is opened.
   *
   * The decision belongs here, not where the job goes out: a candidate fetched and then held back
   * strands the job miners are already on, which is the same failure with none of the benefit.
   */
  private def mempoolRefreshDue: Boolean =
    candidateConfig.mempoolRefreshMs > 0 &&
      System.currentTimeMillis() - lastJobAt >= candidateConfig.mempoolRefreshMs

  // ─── lifecycle ────────────────────────────────────────────────────────────

  override def preStart(): Unit = {
    // Verify node connectivity and read initial chain parameters
    if (!nodeInterface.isOnline)
      throw new ConnectException("Ergo node is offline: cannot start LithosPool")

    val info = nodeInterface.info()
    options.data.protocolVersion = info.parameters.blockVersion
    options.data.chainDifficulty = info.difficulty.getOrElse(BigInt(0)).bigInteger
      .multiply(java.math.BigInteger.valueOf(options.difficultyMultiplier))

    logger.info(s"LithosPool started. protocolVersion=${options.data.protocolVersion}, polling every ${options.blockRefreshInterval}ms")

    // Force the N table now. It is a lazy val built by scanning every height to 4.2M, so whichever
    // share arrives first would otherwise pay for it and answer seconds late.
    NTable.lookUp(1)

    // A solo candidate, since there is no package yet, but it is also what tells CandidateBuilder
    // which block to build for.
    fetchBlockTemplate()

    // Schedule periodic template refresh (mirrors Pool.start's ScheduledExecutor)
    pollTicker = Some(
      context.system.scheduler.scheduleWithFixedDelay(
        options.blockRefreshInterval.milliseconds,
        options.blockRefreshInterval.milliseconds,
        self, PollBlockTemplate
      )(context.dispatcher)
    )

    diffTicker = Some(
      context.system.scheduler.scheduleWithFixedDelay(
        diffRefreshInterval.milliseconds,
        diffRefreshInterval.milliseconds,
        self, RefreshDifficulty
      )(context.dispatcher)
    )
  }

  override def postStop(): Unit = {
    pollTicker.foreach(_.cancel())
    diffTicker.foreach(_.cancel())
  }

  // ─── receive ──────────────────────────────────────────────────────────────

  override def receive: Receive = {

    // Arrives at least twice per block: the genesis transaction alone, then each later revision.
    case BlockPackageReady(pkg) =>
      if (pkg.blockHeight >= lastBlockHeight) {
        blockPackage = Some(pkg)
        // Push the job out now rather than at the next poll tick, which is the whole point of
        // publishing the genesis transaction on its own.
        fetchBlockTemplate()
      } else {
        logger.info(s"Ignoring package for block ${pkg.blockHeight}; the chain is at $lastBlockHeight")
      }

    case GetJobManager =>
      sender() ! jobManagerActor

    case RefreshDifficulty =>
      val stratumTau = {
        if(reducedShareMessages)
          tau / 10000
        else
          tau
      }
      logger.info(s"LithosPool is using the following difficulty settings:")
      logger.info(s"NISP    | diff/tau/score: ${LFSMHelpers.formatTau(tau)} / $tau / ${LFSMHelpers.convertTauOrScore(tau)} ")
      logger.info(s"Stratum | diff/tau/score: ${LFSMHelpers.formatTau(stratumTau)} / $stratumTau / ${LFSMHelpers.convertTauOrScore(stratumTau)} ")
      refreshDifficulty
    case UpdatedDifficulty(nextTau) =>
      nextTau match {
        case Failure(exception) =>
          logger.error("Got error while refreshing difficulty", exception)
        case Success(updatedTau) =>
          if(updatedTau != tau){
            if(!forceConfigDiff) {
              tau = updatedTau
              logger.info(s"Updated stratum diff ${LFSMHelpers.formatTau(updatedTau)} (score: ${LFSMHelpers.convertTauOrScore(updatedTau)})" +
                s" and tau $updatedTau")
            }else{
              logger.warn(s"Got updated stratum diff ${LFSMHelpers.formatTau(updatedTau)} and tau ${updatedTau}," +
                s"but forceConfigDiff was enabled")
            }
          }
      }
    // ------------------------------------------------------------------
    // Block-template polling
    // ------------------------------------------------------------------
    case PollBlockTemplate =>
      fetchBlockTemplate()

    // ------------------------------------------------------------------
    // Job events from LithosJobManager
    // ------------------------------------------------------------------
    case NewJobAvailable(template) =>
      lastJobAt = System.currentTimeMillis()
      logger.info(s"Broadcasting new job ${template.jobId} to ${connections.size} miner(s)")
      connections.values.foreach(_ ! BroadcastJob(template))
      stateFrame ! CheckBlock

    case JobUpdated(template) =>
      logger.info(s"Broadcasting refreshed job ${template.jobId} to ${connections.size} miner(s)")
      connections.values.foreach(_ ! BroadcastJob(template))

    // ------------------------------------------------------------------
    // Connection lifecycle
    // ------------------------------------------------------------------
    case MinerConnected(connectionId, connectionActor) =>
      connections.put(connectionId, connectionActor)
      logger.info(s"Miner connected: $connectionId (${connections.size} active)")

    case MinerDisconnected(connectionId) =>
      connections.remove(connectionId)
      logger.info(s"Miner disconnected: $connectionId (${connections.size} active)")

    // ------------------------------------------------------------------
    // Forward subscription and share messages to LithosJobManager.
    // Using `forward` preserves the original sender (StratumConnection) so
    // LithosJobManager's reply goes directly back without a second hop.
    // ------------------------------------------------------------------
    case RequestSubscription =>
      jobManagerActor.forward(RequestSubscription)

    case msg: ProcessShare =>
      jobManagerActor.forward(msg)

    // ------------------------------------------------------------------
    // Pool-level share handling: block submission and NISP super-share saving.
    // This message is sent by StratumConnection after it has already replied
    // OK to the miner, so these operations run asynchronously.
    // ------------------------------------------------------------------
    case accepted: ShareAccepted =>
      if (accepted.isBlock) {
        logger.info(s"Submitting block solution from ${accepted.workerName} with nonce ${Hex.toHexString(accepted.nonce)}")
        // The solved job's own key, never the pool's latest: a block starts on a solo candidate
        // under the node's key and switches to the lender's when genesis lands, so the two differ
        // within one block and a stale job would otherwise be submitted under the wrong one.
        nodeInterface.sendSolution(Hex.toHexString(accepted.nonce), accepted.candidate.pk)
        // If this block was mined on a collateral box, that box is now spent. Tell the builder before
        // the next ChainAdvanced, which is the build it would otherwise poison.
        Option(accepted.candidate.collateralData).foreach { data =>
          candidateBuilder.foreach(_ ! CollateralSpent(data.collateralId))
        }
        // Immediately fetch a new template after submitting a block
        fetchBlockTemplate()
      }
      if (accepted.isSuperShare) {
        // Off-thread: this deserialises the candidate proof, hashes it and writes to the NISP
        // database. Doing it inline holds up every other message this actor handles, including the
        // next block template.
        Future(saveSuperShare(accepted))
      }
  }

  // ─── private helpers ──────────────────────────────────────────────────────

  /**
   * Fetches a block template from the Ergo node, with the current BlockPackage inserted when there
   * is one for the block being mined.  Mirrors Pool.getBlockTemplate, including the fallback to
   * solo-mining whenever the collateral candidate cannot be produced.
   */
  private def fetchBlockTemplate(): Unit =
    try {
      getCandidate.foreach { case (candidate, usedCollateral) =>
        // A mempool refresh often gets the candidate the node already had, because the mempool has
        // not moved since the last fetch. Publishing that is a notify that restarts every rig's work
        // for the header they are already on, and buys nothing.
        //
        // This is the one case where dropping a fetched candidate is safe, and it is safe for the
        // exact reason 3.30 says the others are not: an identical header IS what the node still has
        // cached, so there is no job to strand. Compare the header, not the key it was fetched under.
        if (java.util.Arrays.equals(candidate.msg, servedMsg)) ()
        else {
          servedMsg = candidate.msg
          // mustPublish, because getCandidate only answers with something it has just taken from the
          // node — which cost the node's copy of whatever miners were on.
          jobManagerActor ! ProcessTemplate(candidate, tau.bigInteger, usedCollateral,
            reducedShareMessages, mustPublish = true)
        }
      }
    } catch {
      case ex: Exception =>
        logger.error(s"Failed to fetch block template: ${ex.getMessage}")
    }

  /**
   * The candidate to hand miners, or None to leave them on the job they already have.
   *
   * None only happens in the window after a new block while the genesis transaction is being built.
   * Publishing a solo job there and replacing it a moment later means two notifies in quick
   * succession, and rigs lose more to that than to a sub-second wait for the real job.
   */
  private def getCandidate: Option[(MiningCandidate, Boolean)] = {
    noteChainHeight()

    if (!useCollateral) {
      if (servedCandidate.exists(_._1 == lastBlockHeight) && !mempoolRefreshDue) None
      else Some(soloCandidate)
    } else {
      // The height comes from /info rather than from /mining/candidate, which would answer under
      // the node's own reward key and so evict the cached collateral candidate on every poll.
      usablePackage.filterNot(p => rejectedGenesisTxs.contains(p.collateral.txId)) match {
        case Some(pkg) =>
          genesisDeadline = None
          val withExtras = pkg.blockTxs.nonEmpty && !extrasRejectedAt.contains(pkg.blockHeight)
          val key = (pkg.blockHeight, pkg.collateral.txId, withExtras)
          // A changed key is mandatory — new block, new genesis transaction, or the inserted set
          // dropped — and goes at once. An unchanged key only refetches on the mempool interval.
          if (servedCandidate.contains(key) && !mempoolRefreshDue) None
          else Some(fetchCollateralCandidate(pkg, withExtras, key))

        // No package yet. Hold the miners on their current job while the genesis transaction is
        // built, but not indefinitely — past the deadline a solo job is better than none, since
        // otherwise they are working a height that is already gone.
        case None if stillWaitingForGenesis =>
          None

        // Falling back to solo for this block, under the same rule as everything else.
        case None if servedCandidate.exists(k => k._1 == lastBlockHeight && k._2.isEmpty) &&
          !mempoolRefreshDue =>
          None

        case None =>
          genesisDeadline = None
          Some(soloCandidate)
      }
    }
  }

  /**
   * Fetch the candidate for this package, dropping the inserted transactions if the node refuses
   * them and falling back to solo if it refuses the whole thing.
   */
  private def fetchCollateralCandidate(pkg: BlockPackage,
                                       withExtras: Boolean,
                                       key: (Int, String, Boolean)): (MiningCandidate, Boolean) = {
    servedCandidate = Some(key)
    try candidateFor(pkg, if (withExtras) pkg.allTxs else pkg.genesisOnly)
    catch {
      case ex: Exception if withExtras =>
        // Only the insertions are optional, so retry without them before giving up on collateral.
        // Everything dropped here is in the mempool anyway.
        logger.warn(s"Candidate with ${pkg.blockTxs.size} inserted transaction(s) was refused " +
          s"(${ex.getMessage}); retrying with the genesis transaction alone")
        extrasRejectedAt = Some(pkg.blockHeight)
        candidateBuilder.foreach(_ ! BlockTxsRejected(pkg.blockHeight))
        servedCandidate = Some((pkg.blockHeight, pkg.collateral.txId, false))
        try candidateFor(pkg, pkg.genesisOnly)
        catch { case ex2: Exception => genesisRefused(pkg, ex2) }
      case ex: Exception => genesisRefused(pkg, ex)
    }
  }

  /** The package for the block currently being mined, if CandidateBuilder has one yet. */
  private def usablePackage: Option[BlockPackage] =
    blockPackage.filter(p => lastBlockHeight == 0 || p.blockHeight == lastBlockHeight)

  /**
   * Even the genesis transaction was refused. Mine solo and ask for one rebuild — the usual cause is
   * a collateral box just spent, which a rebuild resolves by drawing another. Once per block only,
   * so a failure that will not clear does not spin against the node.
   */
  private def genesisRefused(pkg: BlockPackage, ex: Exception): (MiningCandidate, Boolean) = {
    logger.error(s"Collateral candidate fetch failed (${ex.getMessage}), falling back to solo-mining")
    requestRebuild()
    soloCandidate
  }

  /**
   * Ask for a different collateral box for this block, a bounded number of times.
   *
   * A spent box is the ordinary case and deserves a retry rather than writing the block off, but each
   * retry costs a signature, a POST and eventually a job change, so the count is capped. The job gap
   * spaces out whatever this produces.
   */
  private def requestRebuild(): Unit =
    if (rebuildsThisBlock < MaxRebuildsPerBlock) {
      rebuildsThisBlock += 1
      candidateBuilder.foreach(_ ! RebuildCandidate)
    } else
      logger.warn(s"Already rebuilt the candidate $rebuildsThisBlock times for block " +
        s"$lastBlockHeight; mining solo for the rest of it")

  /** The builder's only trigger: which height to build for, and that a block arrived at all. */
  private def noteChainHeight(): Unit =
    Try(nodeInterface.nextBlockHeight()).toOption.flatten.foreach { height =>
      if (height != lastBlockHeight) {
        lastBlockHeight = height
        extrasRejectedAt = None
        rebuildsThisBlock = 0
        genesisDeadline = Some(System.currentTimeMillis() + candidateConfig.genesisWaitMs)
        // Bounded, since a genesis transaction is only valid at one height anyway.
        if (rejectedGenesisTxs.size > 64) rejectedGenesisTxs = Set.empty
        candidateBuilder.foreach(_ ! ChainAdvanced(height))
      }
    }

  /**
   * Retrieves the block candidate for the given package and transaction set
   */
  private def candidateFor(pkg: BlockPackage, txs: Seq[String]): (MiningCandidate, Boolean) = {
    val json = nodeInterface.candidateWithTxs(txs, Some(apiKey), Some(pkg.collateral.pk))
    val withCollateral = MiningCandidate.fromJson(json, options.data.protocolVersion, pkg.collateral)

    // Check if at minimum, the genesis transaction was included in the candidate
    // Node sometimes does not carry it
    genesisMissingBecause(withCollateral, pkg) match {
      case None => (withCollateral, true)
      case Some(why) =>
        noteGenesisRejected(pkg, withCollateral, why)
      // If genesis is rejected we mine solo
      soloCandidate
    }
  }

  /**
   * Why the node's candidate does not prove inclusion of our genesis transaction, or None if it does.
   *
   * The node derives the proof over the transactions it actually prioritised, so ours being absent
   * means it was dropped — but it does not say at which stage. `CandidateGenerator` filters supplied
   * transactions on `inputsNotSpent` BEFORE any validation, silently, so the common case is not a
   * refusal at all: the input was missing from the state the candidate is built against. A missing
   * proof is a third thing again, and worth naming rather than folding into the other two.
   */
  private def genesisMissingBecause(candidate: MiningCandidate, pkg: BlockPackage): Option[String] =
    Option(candidate.proof) match {
      case None =>
        Some("the candidate carries NO proof at all, so the node did not derive one over any " +
          "prioritised transaction")
      case Some(proof) =>
        Try {
          val leaves = proof.getJSONArray("txProofs").toString
          if (leaves.toLowerCase.contains(pkg.collateral.txId.toLowerCase)) None
          else {
            // If merkle proof exists, transaction was likely included even if it doesnt match.
            // This is due to node error which is returning incorrect merkle proofs.
            // Because proof is invalid, we cannot mine super-shares for this block,
            // but since we know this is a bug, we do allow lithos blocks to still be mined.
            // Should be fixed in upcoming node updates.

            // TODO: Revert to rejection here when node is known to be fixed
            None
          }
        }.getOrElse(Some("Proof could not be read."))
    }

  /**
   * Record that the node will not take this block's genesis transaction, once per block.
   *
   * Once is enough: re-offering a transaction the node has already refused just repeats the POST on
   * every poll. The pk comparison is the diagnostic that matters — if the candidate is not built for
   * the lender's key then the node is ignoring the `pk` we send, `lenderIsBlockMiner` can never be
   * satisfied, and no collateral box is spendable at all.
   */
  private def noteGenesisRejected(pkg: BlockPackage, candidate: MiningCandidate, why: String): Unit =
    if (!rejectedGenesisTxs.contains(pkg.collateral.txId)) {
      rejectedGenesisTxs += pkg.collateral.txId
      // Drop the box AND ask for another package. Nothing else will: the refresh only rebuilds when
      // it notices the selected box has gone, and CollateralSpent has already cleared the selection.
      // Without the rebuild the whole block falls back to solo over one spent box.
      candidateBuilder.foreach(_ ! CollateralSpent(pkg.collateral.collateralId))
      requestRebuild()
      logger.error(s"Node dropped genesis transaction ${pkg.collateral.txId} for block " +
        s"${pkg.blockHeight}; mining solo for the rest of it. Requested pk ${pkg.collateral.pk}, got " +
        s"a candidate for pk ${candidate.pk}" +
        (if (candidate.pk != pkg.collateral.pk)
          " | THESE DIFFER. The node is not honouring the requested coinbase key, so no collateral " +
            "box can ever be spent. Check the node is 6.0.2+ and the api key is accepted"
        else ", which match") + s". Why: $why")

      // The node already knows why and writes it down; nothing we can ask from here beats reading it.
      // `CandidateGenerator.collectTxs` treats a supplied transaction exactly like a mempool one and
      // drops it silently on a missing input, a double spend against what it has already taken, a
      // validation failure, or simply running out of block cost before reaching it.
      logger.error(s"The node logs its own reason at INFO: search the node log for " +
        s"'Not included transaction ${pkg.collateral.txId}'. Raise " +
        "org.ergoplatform.mining.CandidateGenerator to DEBUG to also catch the double-spend and " +
        "block-limit paths, which are logged one level lower")
    }


  /**
   * The node's own candidate, recorded as served in the same step that fetches it.
   *
   * Recorded here rather than at the call sites because one of them is the collateral path's fallback:
   * when the node drops the genesis transaction the candidate that was fetched for the lender's key
   * is unusable, and this replaces it. Leaving the collateral key recorded there sent the next poll
   * back for a second solo candidate — which the node then validated against while miners stayed on
   * the first, so every block found for the rest of that height was lost.
   */
  private def soloCandidate: (MiningCandidate, Boolean) = {
    val candidate = MiningCandidate.fromJson(nodeInterface.soloCandidate(), options.data.protocolVersion)
    // Only once it is in hand. Recording a fetch that threw would leave the block with no job at all.
    servedCandidate = Some((lastBlockHeight, "", false))
    (candidate, false)
  }

  /**
   * Persists a super-share to the NISP database.
   * Mirrors Pool.setupJobManager's Share listener exactly, including all
   * error-case log messages.
   */
  private def saveSuperShare(accepted: ShareAccepted): Unit =
    try {
      val share = SuperShare.fromCandidate(
        accepted.nonce, accepted.candidate, accepted.candidate.collateralData
      )

      val score   = LFSMHelpers.convertTauOrScore(scala.math.BigInt(accepted.difficulty)).longValue()
      val success = nispDB.addNISP(score, share)
      if (success) {
        logger.info(s"Super share with nonce ${Hex.toHexString(accepted.nonce)} was saved. NISP-DB entries=${nispDB.size}, lastHeight=${Ints.fromByteArray(nispDB.lastHeight.get)}, currentHeight=${Ints.fromByteArray(nispDB.currentHeight.get)}")
      } else {
        throw new RuntimeException("Failed to save super share to NISP database")
      }
    } catch {
      case ex: Exception =>
        val msg = Option(ex.getMessage).getOrElse("")
        if (msg.contains("merkle leaf for collateral"))
          logger.warn("Skipped super share: non-matching merkle leaf")
        else if (msg.contains("Cannot store non-distinct NISP"))
          logger.warn("Got duplicate nonce on super-share submission")
        else
          logger.error("Error while saving super-share", ex)
    }

  private def refreshDifficulty = {
    // Read on the actor thread and passed in, not read inside the Future.
    val current = tau
    Future(commitments.committedTau(current))
      .map(UpdatedDifficulty.apply)
      .pipeTo(self)
  }
}




object LithosPool {

  /**
   * How many times one block may draw a fresh collateral box after the node refuses the genesis
   * transaction.
   */
  private final val MaxRebuildsPerBlock: Int = 3
}
