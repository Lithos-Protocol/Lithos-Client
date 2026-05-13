package mining

import akka.actor.{Actor, ActorRef, Cancellable, Props, Status}
import akka.pattern.pipe
import lfsm.LFSMHelpers
import mining.MiningMessages._
import mining.LithosPool.{CollateralRefreshed, RefreshCollateral}
import mutations.NodeWallet
import nisp.{NISPDatabase, SuperShare}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.ErgoClient
import org.slf4j.{Logger, LoggerFactory}
import scorex.utils.Ints
import state.LFSMTransformer
import state.messages.StateFrameMessages.{AutoSubscribe, CheckBlock, NewBlock}
import stratum.{CollateralData, CollateralRetriever}
import stratum.data.{MiningCandidate, Options}

import java.io.{OutputStream, PrintStream}
import java.net.ConnectException
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

/**
 * Top-level pool coordination actor.
 *
 * Responsibilities
 * ────────────────
 *  • Owns and supervises LithosJobManager as a child.
 *  • Polls the Ergo node for new block templates via a scheduler tick.
 *  • Maintains the set of active ConnectionActors and broadcasts new jobs.
 *  • Forwards RequestSubscription and ProcessShare messages from ConnectionActors
 *    to LithosJobManager using `forward` so replies travel directly back to the
 *    originating StratumConnection (bypassing LithosPool on the return path).
 *  • Handles pool-level share events: block submission and NISP super-share saving.
 *
 * This actor is created programmatically (not via Guice injection) because its
 * constructor parameters are derived at runtime from the StratumConfig and
 * blockchain state.  Instantiate it with:
 *
 *   system.actorOf(Props(new LithosPool(options, ...)), "mining-pool")
 *
 * @param options             Stratum options (polling interval, tau, etc.)
 * @param useCollateral       Whether to request block candidates using collateral UTXOs
 * @param client              Ergo client for collateral retrieval
 * @param prover              Node wallet used to sign the collateral transaction
 * @param apiKey              Node API key required for /mining/candidateWithTxs
 * @param reducedShareMessages Whether to divide the tau shown to miners by 1000
 * @param nispDB              NISP database for super-share persistence
 * @param stateFrame          StateFrame actor; LithosPool auto-subscribes to receive
 *                            NewBlock events that trigger CollateralData regeneration
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
                 diffRefreshInterval: Int) extends Actor {

  private val logger: Logger = LoggerFactory.getLogger("LithosPool")
  implicit private val ec: ExecutionContext = context.dispatcher

  // ─── children & dependencies ──────────────────────────────────────────────

  /** Child actor that owns all job and share-validation state. */
  val jobManagerActor: ActorRef =
    context.actorOf(Props(new LithosJobManager(options)), "job-manager")

  private val nodeInterface = new MiningNodeInterface(options.nodeApiUrl)

  // ─── mutable state ────────────────────────────────────────────────────────

  /** connectionId → StratumConnection, used for broadcasting and connection tracking. */
  private val connections: mutable.HashMap[String, ActorRef] = mutable.HashMap.empty

  /** Current miner public key, set from the first block candidate. */
  private var pk: Option[String] = None

  private var pollTicker: Option[Cancellable] = None
  private var diffTicker: Option[Cancellable] = None
  private var tau: BigInt = BigInt(options.tau)
  /**
   * Cached collateral data.  Populated during preStart's initial template fetch
   * and subsequently refreshed asynchronously on every NewBlock from StateFrame.
   * When Some, getCandidate uses it directly instead of calling CollateralRetriever.
   */
  private var collateralData: Option[CollateralData] = None
  private var checkCollat: Boolean = true
  // ─── lifecycle ────────────────────────────────────────────────────────────

  override def preStart(): Unit = {
    // Verify node connectivity and read initial chain parameters
    if (!nodeInterface.isOnline)
      throw new ConnectException("Ergo node is offline — cannot start LithosPool")

    val info = nodeInterface.info()
    options.data.protocolVersion = info.getJSONObject("parameters").getInt("blockVersion")
    options.data.chainDifficulty = info.getBigInteger("difficulty")
      .multiply(java.math.BigInteger.valueOf(options.difficultyMultiplier))

    logger.info(s"LithosPool started. protocolVersion=${options.data.protocolVersion}, polling every ${options.blockRefreshInterval}ms")

    // Fetch the initial template synchronously so miners get a job right away.
    // This also performs the first (blocking) CollateralData retrieval and caches it.
    fetchBlockTemplate()

    // Auto-subscribe to StateFrame — no height matching needed, NewBlock events
    // will drive all subsequent CollateralData refreshes.
    // if (useCollateral) stateFrame ! AutoSubscribe(self)

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

    // ------------------------------------------------------------------
    // StateFrame new-block notification: refresh CollateralData off-thread
    // so the actor mailbox is never blocked by the HTTP/signing round-trip.
    // ------------------------------------------------------------------
//    case NewBlock(_) =>
//      refreshCollateral
    case RefreshCollateral =>
      checkCollat = true
    case RefreshDifficulty =>
      val stratumTau = {
        if(reducedShareMessages)
          tau / 1000
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
//    case CollateralRefreshed(data) =>
//      collateralData = Some(data)
//      logger.info("CollateralData refreshed for new block")
//
//    case Status.Failure(ex) =>
//      logger.error(s"Async CollateralData refresh failed: ${ex.getMessage}")
//      collateralData = None
    // ------------------------------------------------------------------
    // Block-template polling
    // ------------------------------------------------------------------
    case PollBlockTemplate =>
      fetchBlockTemplate()

    // ------------------------------------------------------------------
    // Job events from LithosJobManager
    // ------------------------------------------------------------------
    case NewJobAvailable(template) =>
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
        nodeInterface.sendSolution(Hex.toHexString(accepted.nonce), pk.getOrElse(""))
        // Immediately fetch a new template after submitting a block
        fetchBlockTemplate()
      }
      if (accepted.isSuperShare) {
        saveSuperShare(accepted)
      }
  }

  // ─── private helpers ──────────────────────────────────────────────────────

  /**
   * Fetches a block template from the Ergo node, with optional collateral
   * injection.  Mirrors Pool.getBlockTemplate exactly, including the fallback
   * to solo-mining when collateral retrieval fails.
   */
  private def fetchBlockTemplate(): Unit =
    try {
      val (candidate, usedCollateral) = getCandidate
      if (pk.isEmpty) pk = Some(candidate.pk)
      jobManagerActor ! ProcessTemplate(candidate, tau.bigInteger, usedCollateral, reducedShareMessages)
    } catch {
      case ex: Exception =>
        logger.error(s"Failed to fetch block template: ${ex.getMessage}")
    }

  private def getCandidate: (MiningCandidate, Boolean) =
    if (!useCollateral) {
      (MiningCandidate.fromJson(
        nodeInterface.miningCandidate(useCollateral = false, None, None),
        options.data.protocolVersion
      ), false)
    } else {
      // Use the cached CollateralData if available; otherwise perform the
      // initial blocking retrieval and populate the cache.
      val collDataOpt: Option[CollateralData] = //collateralData.orElse {
      {
        try {
          if(checkCollat) {
            val data = withSilencedStdout(new CollateralRetriever(client, prover).getCollateral)
            if (!collateralData.contains(data)) {
              logger.info(s"Got collateral utxo ${data.collateralId} from lender ${data.lenderAddress}")
              logger.info(s"Generated $data with tx size: ${data.txBytes.length} and input size: ${data.collateralBoxBytes.length}")
              collateralData = Some(data)
            }
            Some(data)
          }else{
            None
          }
        } catch {
          case ex: Exception =>
            logger.error(s"Collateral retrieval failed (${ex.getMessage}), falling back to solo-mining")
            logger.error(s"Collateral state will be refreshed in 30 seconds")
            checkCollat = false
            context.system.scheduler.scheduleOnce(30.seconds){
              self ! RefreshCollateral
            }
            None
        }
      }

      collDataOpt match {
        case Some(collData) =>
          try {
            val candidate = MiningCandidate.fromJson(
              nodeInterface.miningCandidate(
                useCollateral = true,
                Some(collData.txJSON),
                Some(apiKey)
              ),
              options.data.protocolVersion,
              collData
            )
            pk = Some(candidate.pk)
            (candidate, true)
          } catch {
            case ex: Exception =>
              logger.error(s"Collateral candidate fetch failed (${ex.getMessage}), falling back to solo-mining")
              val candidate = MiningCandidate.fromJson(
                nodeInterface.miningCandidate(useCollateral = false, None, None),
                options.data.protocolVersion
              )
              pk = Some(candidate.pk)
              (candidate, false)
          }

        case None =>
          val candidate = MiningCandidate.fromJson(
            nodeInterface.miningCandidate(useCollateral = false, None, None),
            options.data.protocolVersion
          )
          pk = Some(candidate.pk)
          (candidate, false)
      }
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
      logger.info(s"Saving super share for block ${share.getHeight} with nonce ${Hex.toHexString(accepted.nonce)}")
      val score   = LFSMHelpers.convertTauOrScore(scala.math.BigInt(accepted.difficulty)).longValue()
      val success = nispDB.addNISP(score, share)
      if (success) {
        logger.info(s"Super share saved. NISP-DB entries=${nispDB.size}, lastHeight=${Ints.fromByteArray(nispDB.lastHeight.get)}, currentHeight=${Ints.fromByteArray(nispDB.currentHeight.get)}")
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

  private def refreshCollateral = {
    Future(withSilencedStdout(new CollateralRetriever(client, prover).getCollateral))
      .map(CollateralRefreshed.apply)
      .pipeTo(self)
  }

  private def refreshDifficulty = {
    Future(LFSMTransformer.getCommitedTau(client, tau))
      .map(UpdatedDifficulty.apply)
      .pipeTo(self)
  }

  private def withSilencedStdout[T](block: => T): T = {
    val original = System.out
    System.setOut(new PrintStream(OutputStream.nullOutputStream()))
    try block finally System.setOut(original)
  }
}



object LithosPool {
  /** Piped back to self when an async CollateralData refresh completes successfully. */
  private[mining] case class CollateralRefreshed(data: CollateralData)
  private[mining] case object RefreshCollateral
}
