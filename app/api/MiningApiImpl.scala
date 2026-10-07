package api

import akka.actor.ActorRef
import akka.pattern.{AskTimeoutException, ask}
import akka.util.Timeout
import configs.{NodeContext, StateConfig, StratumConfig, TasksConfig}
import lfsm.LFSMHelpers
import models.ApiError
import models.CandidateSettings
import models.{CommitmentBlock, CommitmentRequest, CommitmentResult, DifficultyCommitment}
import models.NISPRepresentation
import models.StratumInfo
import org.bouncycastle.util.encoders.Hex
import play.api.Configuration
import state.messages.SyncView
import transactions.engine.{EngineBroadcast, TransactionEngine}
import transactions.rollups.{CommitmentProgress, CommitmentSends, CommitmentTransactions, DataBoxSource}
import utils.Globals

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.{Inject, Named, Singleton}
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success, Try}

/**
  * Provides a default implementation for [[MiningApi]].
  */
@Singleton
class MiningApiImpl @Inject()(nodeContext: NodeContext,
                              config: Configuration,
                              @Named("transaction-engine") engine: ActorRef,
                              sends: CommitmentSends) extends MiningApi {
  private lazy val commitments = new CommitmentTransactions(nodeContext, DataBoxSource.Stored)
  private lazy val nodeApi = nodeContext.getNodeApi
  private val configDiff = new StratumConfig(config).diff
  private val stateConfig = new StateConfig(config)
  private val transformsDisabled = stateConfig.disableTransforms.getOrElse(false)
  /** Whether the auto-commit loop runs, and so keeps the commitment at `stratum.diff`. */
  private val autoCommit = stateConfig.autoCommit && !transformsDisabled &&
    new TasksConfig(config).dictionarySyncTask.enabled
  /** Held while one request decides and sends, so two cannot build spends of the same box. */
  private val committing = new AtomicBoolean(false)
  // Below Play's 75 s idle timeout. A send that lands later is still recorded in `sends`.
  private implicit val timeout: Timeout = Timeout(60.seconds)

  /**
    * @inheritdoc
    */
  override def getBestNISPAtHeight(height: Int, score: Long): Option[NISPRepresentation] = {

    val nispDb = Globals.nispDB
    val bestNISP = nispDb.getBestValidNISP(height, score)
    bestNISP.map(n => NISPRepresentation(n.score, height,
      n.shares.map(s => Hex.toHexString(s.headerBytes)).toList, Hex.toHexString(n.serialize)))

  }

  /**
    * @inheritdoc
    */
  override def getStratumInfo(config: Configuration): StratumInfo = {

    val stratumConfig = new StratumConfig(config)
    val stratumTau = LFSMHelpers.parseDiffValueForStratum(stratumConfig.diff).get
    val realTau = LFSMHelpers.convertTauOrScore(LFSMHelpers.convertTauOrScore(stratumTau))
    StratumInfo(stratumConfig.diff, realTau, stratumConfig.reduceShareMessages,
      stratumConfig.reductionMultiplier, -1.0)
  }

  /**
    * @inheritdoc
    */
  override def getCandidateSettings(config: Configuration): CandidateSettings =
    CandidateSettings.from(config)

  override def getCommitment: DifficultyCommitment = {
    val view = Globals.syncView
    DifficultyCommitment.of(commitments.commitmentState, registration(view), sends.outstanding(nodeApi, view),
      autoCommit, transformsDisabled, configDiff)
  }

  /**
    * Sends a registration when this miner has none, otherwise a change. Every condition the status
    * reports is checked against a fresh read, and the engine checks the data box again before it signs.
    */
  override def commit(request: CommitmentRequest): CommitmentResult = {
    val diff = request.diff.trim
    val score = DifficultyCommitment.scoreOf(diff).getOrElse(throw LithosApiErrors.LithosBadRequest(
      s"'${request.diff}' is not a diff. Write it the way stratum.diff is: a number and one of K, M, G, T or P, such as 1.5M"))
    if (!committing.compareAndSet(false, true))
      throw LithosApiErrors.LithosStateChanged("another commitment request is being sent; read GET /mining/commitment and try again")
    try {
      val status = getCommitment
      refuse(status)
      val newest = status.pending.orElse(status.inForce)
      if (newest.exists(_.score == score.toString))
        throw LithosApiErrors.LithosBadRequest(s"$diff is already your newest commitment" +
          status.pending.map(p => s"; it binds at height ${p.inForceFromHeight}").getOrElse(""))
      val registering = status.state == DifficultyCommitment.Unregistered
      val message = if (registering) TransactionEngine.RegisterMiner(diff) else TransactionEngine.CommitDifficulty(diff)
      val kind = if (registering) CommitmentSends.Registration else CommitmentSends.Change
      Try(Await.result(engine ? message, timeout.duration)) match {
        case Success(sent: CommitmentProgress.Sent) if sent.score == score =>
          result(sent, kind, newest.map(_.score.toLong))
        // Coalesced onto a request for another diff that reached the engine first.
        case Success(sent: CommitmentProgress.Sent) =>
          throw LithosApiErrors.LithosStateChanged(s"another request was sent first, committing " +
            s"${DifficultyCommitment.diffOf(sent.score)} as ${sent.txId}")
        case Success(CommitmentProgress.Settled) =>
          throw LithosApiErrors.LithosBadRequest(s"$diff is already your newest commitment")
        case Success(CommitmentProgress.Waiting(reason)) =>
          throw LithosApiErrors.LithosStateChanged(s"nothing was sent, waiting on $reason")
        case Success(TransactionEngine.Deferred(_, reason)) =>
          throw LithosApiErrors.LithosUnavailable(s"the transaction engine did not run the request: $reason")
        case Success(other) =>
          throw new IllegalStateException(s"unexpected answer from the transaction engine: $other")
        case Failure(refused: EngineBroadcast.SubmissionOutcomeException) =>
          throw LithosApiErrors.LithosUnprocessable(s"the node refused ${refused.txId}" +
            refused.reason.map(": " + _).getOrElse(""))
        case Failure(_: AskTimeoutException) =>
          throw LithosApiErrors.LithosUnavailable("the transaction engine has not answered and may still send. " +
            "Read GET /mining/commitment before trying again")
        // The dictionary or the engine attempt moved between the read above and the build.
        case Failure(moved: IllegalArgumentException) => throw LithosApiErrors.LithosStateChanged(moved.getMessage)
        case Failure(gone: IllegalStateException) => throw LithosApiErrors.LithosUnavailable(gone.getMessage)
        case Failure(ex) => throw ex
      }
    } finally committing.set(false)
  }

  /** Whether the synced Miner Dictionary holds this miner, or why it cannot say. */
  private def registration(view: SyncView): Either[String, Boolean] =
    if (!view.canonical.available) Left(view.canonical.reason.getOrElse("synchronization is not ready"))
    else if (!view.minerDictionary.available) Left(view.minerDictionary.reason.getOrElse("not loaded"))
    else view.minerDictionaryMetadata.map(_.hasMiner).toRight("not loaded")

  /** Throws the error matching the status's `blockedReason`, if it has one. */
  private def refuse(status: DifficultyCommitment): Unit = status.blockedReason.foreach { block =>
    val why = status.reason.map(": " + _).getOrElse("")
    throw (block match {
      case CommitmentBlock.TransformsDisabled =>
        LithosApiErrors.LithosUnprocessable("state.disableTransforms is true, so this client sends no registration or commitment")
      case CommitmentBlock.AutoCommit =>
        LithosApiErrors.LithosStateChanged(s"state.autoCommit is on and keeps the commitment at stratum.diff ($configDiff). " +
          "Change stratum.diff, or turn auto-commit off to commit here")
      case CommitmentBlock.InFlight =>
        val flight = status.inFlight.map(f => s" ${f.kind} ${f.txId}").getOrElse("")
        LithosApiErrors.LithosStateChanged(s"the$flight has not settled yet$why")
      case CommitmentBlock.Locked =>
        LithosApiErrors.LithosUnprocessable(s"the newest commitment cannot be replaced before height " +
          s"${status.replaceableFromHeight.getOrElse(0)}; the tip is ${status.height.getOrElse(0)}")
      case _ => LithosApiErrors.LithosUnavailable(s"the commitment cannot be decided now$why")
    })
  }

  private def result(sent: CommitmentProgress.Sent, kind: String, previous: Option[Long]): CommitmentResult =
    CommitmentResult(sent.txId, kind, sent.outcome, sent.score.toString, DifficultyCommitment.diffOf(sent.score),
      sentAtHeight = sent.declaredHeight - CommitmentTransactions.DeclareAfter,
      declaredHeight = sent.declaredHeight,
      servedFromHeight = CommitmentTransactions.servedFrom(sent.declaredHeight, sent.score, previous),
      inForceFromHeight = CommitmentTransactions.inForceFrom(sent.declaredHeight),
      replaceableFromHeight = CommitmentTransactions.replaceableFrom(sent.declaredHeight))
}
