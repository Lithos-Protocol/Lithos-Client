package mining

import akka.actor.{Actor, ActorRef, Cancellable, Props}
import configs.{CandidateConfig, Contexts}
import evaluation.NTable
import lfsm.LFSMHelpers
import mining.LithosPool._
import mining.MiningMessages._
import mutations.NodeWallet
import nisp.{NISPDatabase, SuperShare}
import node.model.NodeInfo
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.HeaderWithoutPowSerializer
import org.ergoplatform.appkit.ErgoClient
import org.slf4j.LoggerFactory
import scorex.crypto.hash.Blake2b256
import scorex.utils.Ints
import state.messages.StateFrameMessages.CheckBlock
import stratum.BlockTemplate
import stratum.data.{MiningCandidate, Options}
import transactions.BlockTxMessages.CandidateTx
import transactions.rollups.{CommitmentTransactions, DataBoxSource}
import utils.Globals

import java.util.UUID
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

/** Coordinates this miner's jobs. Candidate cache mutations are serialized off-mailbox;
  * chain observations and solved blocks retain independent capacity during slow candidate HTTP.
  * Only a qualified, acknowledged job permits optional transaction collection.
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
                 txSources: Seq[CandidateSource] = Seq.empty) extends Actor {

  private val logger = LoggerFactory.getLogger("LithosPool")
  private implicit val ec: ExecutionContext = context.dispatcher
  private val candidateEc = context.system.dispatchers.lookup(Contexts.key(Contexts.CandidateIo))
  private val controlEc = context.system.dispatchers.lookup(Contexts.key(Contexts.MiningControlIo))
  private val backgroundEc = context.system.dispatchers.lookup(Contexts.key(Contexts.Database))
  private val incarnation = UUID.randomUUID()

  protected def createJobManager(): ActorRef = context.actorOf(Props(new LithosJobManager(options)), "job-manager")
  lazy val jobManagerActor: ActorRef = createJobManager()

  /** Dependency seams keep the production handshake testable without Globals or live HTTP. */
  protected lazy val nodeInterface: MiningNodeInterface = new MiningNodeInterface(options.nodeApiUrl)
  protected def createCandidateBuilder(): Option[ActorRef] =
    if (useCollateral) Some(context.actorOf(Props(new CandidateBuilder(
      client, prover, Globals.getNodeConfig.getNodeApi, candidateConfig, txSources)), "candidate-builder"))
    else None
  lazy val candidateBuilder: Option[ActorRef] = createCandidateBuilder()
  protected def nowNanos(): Long = System.nanoTime()

  private lazy val commitments = new CommitmentTransactions(Globals.getNodeConfig, DataBoxSource.Stored)
  private val connections = mutable.HashMap.empty[String, ActorRef]
  private var pollTicker: Option[Cancellable] = None
  private var diffTicker: Option[Cancellable] = None
  private var candidateTimer: Option[Cancellable] = None
  private var tau = BigInt(options.tau)
  private var refreshingDifficulty = false
  private var tip: Option[ChainTip] = None
  private var protocolVersion = options.data.protocolVersion
  private var observing: Option[UUID] = None
  private var observationRequired = true
  private var blockPackage: Option[BlockPackage] = None
  private var activeRequest: Option[CandidateRequest] = None
  private var publishing: Option[(CandidateRequest, MiningCandidate)] = None
  private var servedCandidate: Option[CandidateIdentity] = None
  private var servedWork: Option[(String, String)] = None
  private var publishedGenesis: Option[(ChainTip, String)] = None
  private var extrasRejected: Option[(ChainTip, String)] = None
  private var rejectedGenesis = Set.empty[String]
  private var rebuilds = 0
  private var genesisDeadline = 0L
  private var lastJobAt = 0L
  private var cacheDirty = true
  private var restoreGenesis = false

  // One useful solution per work message is sufficient. The bound covers the job manager's
  // retained job window; a flood cannot create an unbounded HTTP queue.
  private var submitting: Option[SolutionWork] = None
  private var solutionQueue = Vector.empty[SolutionWork]

  override def preStart(): Unit = {
    NTable.lookUp(1)
    jobManagerActor
    candidateBuilder
    self ! PollBlockTemplate
    pollTicker = Some(context.system.scheduler.scheduleWithFixedDelay(
      options.blockRefreshInterval.milliseconds, options.blockRefreshInterval.milliseconds,
      self, PollBlockTemplate))
    diffTicker = Some(context.system.scheduler.scheduleWithFixedDelay(
      diffRefreshInterval.milliseconds, diffRefreshInterval.milliseconds, self, RefreshDifficulty))
  }

  override def postStop(): Unit = {
    pollTicker.foreach(_.cancel())
    diffTicker.foreach(_.cancel())
    candidateTimer.foreach(_.cancel())
  }

  override def receive: Receive = {
    case PollBlockTemplate => observeChain()
    case ChainObserved(id, result) if observing.contains(id) =>
      observing = None
      result match {
        case Success(info) =>
          acceptObservation(info)
          driveCandidate()
        case Failure(ex) =>
          observationRequired = true
          logger.warn(s"Cannot refresh mining chain state: ${ex.getMessage}; retaining existing work")
      }

    case BlockPackageReady(pkg) =>
      if (tip.contains(ChainTip(pkg.blockHeight, pkg.parentId)) &&
        rejectedGenesis.size <= MaxRebuildsPerBlock &&
        !rejectedGenesis.contains(pkg.collateral.txId) &&
        blockPackage.forall(p => p.collateral.txId != pkg.collateral.txId || pkg.revision >= p.revision)) {
        blockPackage = Some(pkg)
        driveCandidate()
      }

    case CandidateFetched(id, result) if activeRequest.exists(_.id == id) =>
      val request = activeRequest.get
      activeRequest = None
      admitCandidate(request, result)

    case CandidateExpired(id) if activeRequest.orElse(publishing.map(_._1)).exists(_.id == id) =>
      val request = activeRequest.orElse(publishing.map(_._1)).get
      if (request.hasExtras && current(request)) {
        rejectExtras(request, "candidate request exceeded its publication deadline")
        invalidateCachedJob()
        driveCandidate()
      }

    case NewJobAvailable(template, publication) if sender() == jobManagerActor =>
      publishing.filter { case (request, candidate) =>
        publication.contains(publicationFor(request)) &&
          work(candidate) == work(template.candidate)
      }.foreach { case (request, _) =>
        candidateTimer.foreach(_.cancel())
        candidateTimer = None
        publishing = None
        if (current(request) && !expired(request)) recordPublication(request, template)
        else {
          if (current(request) && expired(request)) rejectExtras(request, "job publication missed its deadline")
          invalidateCachedJob()
        }
        driveCandidate()
      }

    case TemplateRejected(publication) if publishing.exists { case (request, _) =>
      publication == publicationFor(request)
    } =>
      publishing.foreach { case (request, _) =>
        if (request.hasExtras && current(request)) rejectExtras(request, "job manager refused augmentation")
      }
      publishing = None
      invalidateCachedJob()
      observationRequired = true
      observeChain()
    case true | false => ()

    case GetJobManager => sender() ! jobManagerActor
    case MinerConnected(id, connection) => connections.put(id, connection)
    case MinerDisconnected(id) => connections.remove(id)
    case RequestSubscription => jobManagerActor.forward(RequestSubscription)
    case share: ProcessShare => jobManagerActor.forward(share)

    case accepted: ShareAccepted =>
      if (accepted.isBlock) enqueueSolution(accepted)
      if (accepted.isSuperShare) run(backgroundEc)(saveSuperShare(accepted))(BackgroundDone.apply)

    case SolutionSubmitted(id, result) if submitting.exists(_.id == id) =>
      val submitted = submitting.get
      submitting = None
      if (result.getOrElse(false)) {
        submitted.collateralId.foreach(id => candidateBuilder.foreach(_ ! CollateralSpent(id)))
        logger.info(s"Block solution submitted for ${submitted.key._1}${timing("solutionMs", submitted.startedAt)}")
      } else logger.warn(s"Block solution failed for ${submitted.key._1}: ${result.failed.toOption.map(_.getMessage).getOrElse("node refused solution")}")
      observationRequired = true
      startSolution()
      observeChain()

    case RefreshDifficulty if !refreshingDifficulty =>
      refreshingDifficulty = true
      val currentTau = tau
      run(backgroundEc)(commitments.committedTau(currentTau).get)(DifficultyRead(incarnation, _))
    case DifficultyRead(id, result) if id == incarnation =>
      refreshingDifficulty = false
      result match {
        case Success(next) if !forceConfigDiff => tau = next
        case Failure(ex) => logger.warn(s"Cannot refresh mining difficulty: ${ex.getMessage}")
        case _ => ()
      }
    case BackgroundDone(Failure(ex)) => logger.warn(s"Super-share worker unavailable: ${ex.getMessage}")
    case _: ChainObserved | _: CandidateFetched | _: CandidateExpired | _: SolutionSubmitted |
         _: DifficultyRead | RefreshDifficulty | _: BackgroundDone => ()
  }

  /** Dispatch rejection is an ordinary completion, so saturation cannot restart the mailbox. */
  private def run[A](worker: ExecutionContext)(body: => A)(completed: Try[A] => Any): Unit =
    Try(Future(Try(body))(worker).foreach(result => self ! completed(result)))
      .failed.foreach(ex => self ! completed(Failure(ex)))

  private def observeChain(): Unit = if (observing.isEmpty) {
    val id = UUID.randomUUID()
    observing = Some(id)
    val node = nodeInterface
    run(controlEc) {
      val info = node.info()
      chainTip(info)
      require(info.parameters != null && info.parameters.blockVersion > 0, "node has no mining parameters")
      info
    }(ChainObserved(id, _))
  }

  /**
   * Apply a chain observation. A new tip discards every package, publication record and rejection
   * from the old height, because none of them describe work that can still be mined.
   */
  private def acceptObservation(info: NodeInfo): Unit = {
    val observed = chainTip(info)
    protocolVersion = info.parameters.blockVersion
    options.data.protocolVersion = protocolVersion
    options.data.chainDifficulty = info.difficulty.getOrElse(BigInt(0)).bigInteger
      .multiply(java.math.BigInteger.valueOf(options.difficultyMultiplier))
    observationRequired = false
    if (!tip.contains(observed)) {
      tip = Some(observed)
      blockPackage = None
      publishedGenesis = None
      extrasRejected = None
      rejectedGenesis = Set.empty
      rebuilds = 0
      genesisDeadline = nowNanos() + candidateConfig.genesisWaitMs.milliseconds.toNanos
      invalidateCachedJob()
      candidateBuilder.foreach(_ ! ChainAdvanced(observed.height, observed.parentId))
      stateFrame ! CheckBlock
    }
  }

  /**
   * The package that should be mined right now, derived from current state rather than queued, so
   * a superseding package replaces its predecessor instead of occupying a second slot.
   *
   * Optional transactions are only carried once genesis has been published for this exact package
   * and nothing has rejected them; otherwise the same package is offered genesis-only. None means
   * wait, because a collateral genesis is still expected within its deadline.
   */
  private def desired: Option[(CandidateIdentity, Option[BlockPackage])] = tip.flatMap { chain =>
    blockPackage.filterNot(candidate => rejectedGenesis.contains(candidate.collateral.txId)) match {
      case Some(pkg) =>
        val carriesExtras = pkg.blockTxs.nonEmpty && publishedGenesis.contains(chain -> pkg.collateral.txId) &&
          !extrasRejected.contains(chain -> pkg.collateral.txId) && !restoreGenesis
        val selected = if (carriesExtras) pkg else pkg.copy(blockTxs = Seq.empty, revision = 0)
        Some(selected.identity -> Some(selected))
      case None if candidateBuilder.isDefined && nowNanos() < genesisDeadline => None
      case None => Some(CandidateIdentity(chain.height, chain.parentId, "", 0) -> None)
    }
  }

  /**
   * Issue at most one candidate request. Candidate calls mutate the node's cached mining work, so
   * they are serialised here rather than overlapped, and a found block always takes precedence.
   */
  private def driveCandidate(): Unit = {
    if (observationRequired || activeRequest.nonEmpty || publishing.nonEmpty || submitting.nonEmpty ||
      solutionQueue.nonEmpty) return
    desired.foreach { case (identity, pkg) =>
      val refresh = candidateConfig.mempoolRefreshMs > 0 &&
        nowNanos() - lastJobAt >= candidateConfig.mempoolRefreshMs.milliseconds.toNanos
      if (cacheDirty || !servedCandidate.contains(identity) || refresh) {
        val request = CandidateRequest(UUID.randomUUID(), identity, pkg, protocolVersion, nowNanos())
        activeRequest = Some(request)
        if (request.hasExtras) candidateTimer = Some(context.system.scheduler.scheduleOnce(
          candidateConfig.blockTxTimeout.milliseconds, self, CandidateExpired(request.id)))
        val node = nodeInterface
        run(candidateEc)(fetch(node, request, apiKey))(CandidateFetched(request.id, _))
      }
    }
  }

  /**
   * Whether this request still describes work worth publishing. Height alone is not enough: a
   * same-height reorg or a replacement genesis changes the identity while the number stays put.
   */
  private def current(request: CandidateRequest): Boolean =
    tip.contains(request.chain) && request.version == protocolVersion && (request.pkg match {
      case None => desired.exists(_._1.genesisId.isEmpty)
      case Some(pkg) => blockPackage.exists { latest =>
        latest.collateral.txId == pkg.collateral.txId &&
          !rejectedGenesis.contains(pkg.collateral.txId) &&
          (!request.hasExtras || (latest.identity == request.identity &&
            !extrasRejected.contains(request.chain -> pkg.collateral.txId)))
      }
    })

  /**
   * Decide what to do with a returned candidate. The deadline is enforced here as well as by the
   * timer, so a collection that completes late cannot restore extras that were already rejected.
   */
  private def admitCandidate(request: CandidateRequest, result: Try[Fetched]): Unit = {
    if (!current(request)) {
      invalidateCachedJob()
      restoreGenesis = true
      driveCandidate()
      return
    }
    result match {
      case Success(fetched) if fetched.chain != request.chain || fetched.version != request.version =>
        invalidateCachedJob()
        observationRequired = true
        observeChain()
      case Success(_) if expired(request) =>
        rejectExtras(request, "candidate completed after its publication deadline")
        invalidateCachedJob()
        driveCandidate()
      case Success(fetched) =>
        val candidate = fetched.candidate
        fetched.materialized.foreach(recordMaterialization)
        if (!cacheDirty && servedWork.contains(work(candidate)) && servedCandidate.contains(request.identity)) {
          candidateTimer.foreach(_.cancel())
          candidateTimer = None
          lastJobAt = nowNanos()
        } else {
          publishing = Some(request -> candidate)
          jobManagerActor ! ProcessTemplate(candidate, tau.bigInteger, request.pkg.isDefined,
            reducedShareMessages, mustPublish = true,
            publication = Some(publicationFor(request)))
        }
      case Failure(ex) =>
        invalidateCachedJob()
        if (request.hasExtras) rejectExtras(request, ex.getMessage)
        else request.pkg.foreach { pkg =>
          rejectedGenesis += pkg.collateral.txId
          blockPackage = None
          genesisDeadline = 0L
          if (rebuilds < MaxRebuildsPerBlock) {
            rebuilds += 1
            candidateBuilder.foreach(_ ! RebuildCandidate)
          }
        }
        logger.warn(s"Candidate request failed: ${ex.getMessage}; falling back to ${if (request.hasExtras) "genesis" else "solo"}")
        // A failed solo request waits for the next poll; it must not spin against an unavailable node.
        if (request.pkg.isDefined) driveCandidate()
    }
  }

  /**
   * Keep what the node's proofs say about the package it was handed.
   *
   * Unproven correspondence is reported, not acted on: the supported node can return proofs that do
   * not correspond to the transactions it was given, so a mismatch is not evidence the work is
   * absent. `CandidateMaterialized.requireCorrespondence` is where that becomes a rejection once the
   * node is fixed, and dependent revenue work is what will need it.
   */
  private def recordMaterialization(materialized: CandidateMaterialized): Unit = {
    if (materialized.fullyProven)
      logger.debug(s"Candidate ${materialized.identity.height} proved all " +
        s"${materialized.included.size} requested transaction(s), " +
        s"${materialized.knownBytes} byte(s) and ${materialized.knownCost} cost supplied")
    else
      logger.debug(s"Candidate ${materialized.identity.height} left " +
        s"${materialized.unproven.size} of ${materialized.included.size + materialized.unproven.size} " +
        "requested transaction(s) unproven; the node-selected remainder is not enumerated")
    if (CandidateMaterialized.requireCorrespondence && !materialized.fullyProven)
      logger.warn(s"Candidate ${materialized.identity.height} has unproven required membership: " +
        materialized.unproven.map(_.take(8)).mkString(", "))
  }

  /** Fall back to genesis-only for this package. Mining continues; only the extras are dropped. */
  private def rejectExtras(request: CandidateRequest, reason: String): Unit = {
    extrasRejected = Some(request.chain -> request.identity.genesisId)
    candidateBuilder.foreach(_ ! BlockTxsRejected(request.identity.height, Some(request.identity)))
    logger.warn(s"Dropping optional candidate transactions: $reason; mining genesis for this package")
  }

  private def publicationFor(request: CandidateRequest): CandidatePublication =
    CandidatePublication(request.identity, request.id,
      if (request.hasExtras) Some(request.startedAt + candidateConfig.blockTxTimeout.milliseconds.toNanos) else None)

  /** Only augmented requests have a publication deadline; a genesis-only request never expires. */
  private def expired(request: CandidateRequest): Boolean = request.hasExtras &&
    nowNanos() - request.startedAt >= candidateConfig.blockTxTimeout.milliseconds.toNanos

  /**
   * Forget the served job and the node's cached work. Called whenever a late or failed request may
   * have left the node holding work this client can no longer identify.
   */
  private def invalidateCachedJob(): Unit = {
    candidateTimer.foreach(_.cancel())
    candidateTimer = None
    cacheDirty = true
    servedCandidate = None
    publishing = None
    jobManagerActor ! InvalidateTemplate
  }

  private def recordPublication(request: CandidateRequest, template: BlockTemplate): Unit = {
    servedCandidate = Some(request.identity)
    servedWork = Some(work(template.candidate))
    cacheDirty = false
    restoreGenesis = false
    lastJobAt = nowNanos()
    connections.values.foreach(_ ! BroadcastJob(template))
    logger.info(s"Broadcasting job ${template.jobId} to ${connections.size} miner(s); " +
      s"candidateStage=${if (request.hasExtras) "augmented" else if (request.pkg.isDefined) "genesis" else "solo"}" +
      timing("publicationMs", request.startedAt))
    request.pkg.filter(_ => !request.hasExtras).foreach { pkg =>
      publishedGenesis = Some(request.chain -> pkg.collateral.txId)
      candidateBuilder.foreach(_ ! GenesisPublished(request.identity))
    }
    stateFrame ! CheckBlock
  }

  private def enqueueSolution(accepted: ShareAccepted): Unit = {
    val key = work(accepted.candidate)
    if (submitting.exists(_.key == key) || solutionQueue.exists(_.key == key)) return
    if (solutionQueue.size >= MaxQueuedSolutions) {
      logger.error("Solved-block queue is full; refusing another solution until node submission progresses")
    } else {
      solutionQueue :+= SolutionWork(UUID.randomUUID(), key, Hex.toHexString(accepted.nonce),
        Option(accepted.candidate.collateralData).map(_.collateralId), nowNanos())
      startSolution()
    }
  }

  private def startSolution(): Unit = if (submitting.isEmpty && solutionQueue.nonEmpty) {
    val solution = solutionQueue.head
    solutionQueue = solutionQueue.tail
    submitting = Some(solution)
    val node = nodeInterface
    run(controlEc)(node.sendSolution(solution.nonce, solution.key._2))(SolutionSubmitted(solution.id, _))
  }

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

  private def timing(label: String, startedAt: Long): String =
    if (candidateConfig.logTimings) s"; $label=${(nowNanos() - startedAt) / 1000000L}" else ""
}

object LithosPool {
  private val MaxRebuildsPerBlock = 3
  private val MaxQueuedSolutions = 6
  private[mining] case class ChainTip(height: Int, parentId: String)
  private[mining] case class CandidateRequest(id: UUID, identity: CandidateIdentity,
                                             pkg: Option[BlockPackage], version: Int, startedAt: Long) {
    def chain: ChainTip = ChainTip(identity.height, identity.parentId)
    def hasExtras: Boolean = pkg.exists(_.blockTxs.nonEmpty)
  }
  private[mining] case class Fetched(candidate: MiningCandidate, chain: ChainTip, version: Int,
                                     materialized: Option[CandidateMaterialized] = None)
  private case class ChainObserved(id: UUID, result: Try[NodeInfo])
  private case class CandidateFetched(id: UUID, result: Try[Fetched])
  private case class CandidateExpired(id: UUID)
  private case class SolutionWork(id: UUID, key: (String, String), nonce: String,
                                 collateralId: Option[String], startedAt: Long)
  private case class SolutionSubmitted(id: UUID, result: Try[Boolean])
  private case class DifficultyRead(incarnation: UUID, result: Try[BigInt])
  private case class BackgroundDone(result: Try[Unit])

  private def work(candidate: MiningCandidate): (String, String) = (Hex.toHexString(candidate.msg), candidate.pk)

  private[mining] def chainTip(info: NodeInfo): ChainTip = {
    val height = info.fullHeight.filter(h => h >= 0 && h < Int.MaxValue)
      .getOrElse(throw new IllegalArgumentException("node has no usable full height"))
    val parent = info.bestFullHeaderId.filter(_.matches("[0-9a-fA-F]{64}"))
      .getOrElse(throw new IllegalArgumentException("node has no full-header identity"))
    ChainTip(height + 1, parent.toLowerCase(java.util.Locale.ROOT))
  }

  /** One cache-changing call and a fresh chain observation. Fallbacks are chosen by the actor
    * after checking whether this attempt still owns the package, never by a stale worker.
    */
  private[mining] def fetch(node: MiningNodeInterface, request: CandidateRequest, apiKey: String): Fetched = {
    val candidateJson = request.pkg match {
      case Some(pkg) => node.candidateWithTxs(pkg.allTxs, Some(apiKey), Some(pkg.collateral.pk))
      case None => node.soloCandidate()
    }
    val candidate = request.pkg match {
      case Some(pkg) => MiningCandidate.fromJson(candidateJson, request.version, pkg.collateral)
      case None => MiningCandidate.fromJson(candidateJson, request.version)
    }
    require(candidate.msg.length == 32 && candidate.height == request.identity.height,
      "candidate work message or height does not match the request")
    require(candidate.b != null && candidate.b.signum() > 0, "candidate has no positive target")
    request.pkg.foreach { pkg =>
      require(candidate.pk.equalsIgnoreCase(pkg.collateral.pk), "candidate uses a different miner key")
      require(candidate.proof != null, "collateral candidate has no proof")
      val preimage = Hex.decode(candidate.proof.getString("msgPreimage"))
      val header = HeaderWithoutPowSerializer.fromBytes(preimage)
      require(Blake2b256(preimage).sameElements(candidate.msg), "candidate preimage does not bind its work message")
      require(header.parentId == request.identity.parentId && header.height == request.identity.height &&
        header.version >= 2, "candidate preimage names another chain or unsupported PoW version")
      // At a voting boundary the upcoming header can activate a newer version than /info.
      // The bound preimage supplies that version before this fresh candidate leaves the worker.
      candidate.version = header.version
      require(candidate.proof.getJSONArray("txProofs") != null, "collateral candidate has no transaction proofs")
    }
    // Retained for every collateral candidate, whether or not its correspondence checks out: the
    // proofs are the only evidence of what the node did with the package, and a later consumer
    // needs them as returned rather than as this client would have preferred them.
    val materialized = request.pkg.map { pkg =>
      val genesis = CandidateTx(pkg.collateral.txId, pkg.collateral.txJSON, "genesis",
        sizeBytes = pkg.collateral.signedSizeBytes, cost = pkg.collateral.cost,
        leaf = Hex.toHexString(Blake2b256(pkg.collateral.txBytes)))
      CandidateMaterialized(request.identity, request.id, Hex.toHexString(candidate.msg),
        candidate.proof, genesis +: pkg.blockTxs)
    }
    val observed = node.info()
    Fetched(candidate, chainTip(observed), observed.parameters.blockVersion, materialized)
  }
}
