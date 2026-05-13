package mining

import akka.actor.ActorRef
import stratum.BlockTemplate
import stratum.data.MiningCandidate

import java.math.BigInteger
import scala.util.Try

/**
 * All Akka messages used by the actor-based mining system.
 *
 * Message flow overview:
 *
 *   Task / MiningStratumServer
 *       │
 *       ├─ MinerConnected ──────────────────────────────► LithosPool
 *       │                                                     │
 *       │                                                     │ (child)
 *       │                                                 LithosJobManager
 *       │
 *   StratumConnection ──── RequestSubscription / ProcessShare ──► LithosPool
 *                                                                  │  forward
 *                                                             LithosJobManager
 *                                                                  │  reply
 *                         SubscriptionData / ShareResult ◄─────────┘
 *
 *   LithosPool ──── BroadcastJob ──────────────────────── ► StratumConnection
 *   StratumConnection ──── ShareAccepted (block/supershare) ► LithosPool
 */
object MiningMessages {

  // ═══════════════════════════════════════════════════════════════════════════
  // LithosJobManager ↔ LithosPool / StratumConnection
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Sent by LithosPool to LithosJobManager whenever a new block template is fetched.
   * LithosJobManager replies with true if the template produced a NEW block job,
   * false if it was a duplicate of the current job.
   */
  case class ProcessTemplate(candidate: MiningCandidate, tau: BigInteger,
                             usesCollateral: Boolean, reducedShareMessages: Boolean)

  /**
   * Sent by StratumConnection (via LithosPool.forward) when a miner subscribes.
   * LithosJobManager replies with SubscriptionData.
   */
  case object RequestSubscription

  /** Reply to RequestSubscription. */
  case class SubscriptionData(extraNonce1: String, extraNonce2Size: Int,
                              currentJob: Option[BlockTemplate])

  /**
   * Sent by StratumConnection (via LithosPool.forward) when a miner submits a share.
   * LithosJobManager replies with a ShareResult.
   */
  case class ProcessShare(jobId: String, difficulty: BigInteger, extraNonce1: Array[Byte],
                          extraNonce2: Array[Byte], nTime: String, ipAddress: String,
                          port: Int, workerName: String)

  /** Sealed response type for ProcessShare. */
  sealed trait ShareResult

  /**
   * A valid share.  isBlock / isSuperShare determine pool-level follow-up actions
   * (block submission and NISP database write respectively).
   */
  case class ShareAccepted(jobId: String, ipAddress: String, workerName: String,
                           difficulty: BigInteger, height: Long, msg: Array[Byte],
                           shareDiff: BigInteger, isBlock: Boolean, blockDiffActual: BigInteger,
                           blockHash: Array[Byte], isSuperShare: Boolean,
                           candidate: MiningCandidate, nonce: Array[Byte]) extends ShareResult

  /**
   * A rejected share.  Error codes follow the Stratum protocol:
   *   20 = malformed nonce
   *   21 = stale job
   *   22 = duplicate share
   *   32 = below miner difficulty
   */
  case class ShareRejected(id: Int, message: String, extraNonce1: Array[Byte]) extends ShareResult

  // ═══════════════════════════════════════════════════════════════════════════
  // LithosJobManager → LithosPool events
  // ═══════════════════════════════════════════════════════════════════════════

  /** A new block height has been detected; all connections should receive the new job. */
  case class NewJobAvailable(template: BlockTemplate)

  /** The same block height has a refreshed template (e.g. extra data changed). */
  case class JobUpdated(template: BlockTemplate)

  // ═══════════════════════════════════════════════════════════════════════════
  // Connection lifecycle  (MiningStratumServer / StratumConnection ↔ LithosPool)
  // ═══════════════════════════════════════════════════════════════════════════

  /** MiningStratumServer → LithosPool: a miner TCP connection has been accepted. */
  case class MinerConnected(connectionId: String, connectionActor: ActorRef)

  /** StratumConnection → LithosPool: the miner disconnected or was evicted. */
  case class MinerDisconnected(connectionId: String)

  /** LithosPool → every StratumConnection: push a new or refreshed mining job. */
  case class BroadcastJob(template: BlockTemplate)

  // ═══════════════════════════════════════════════════════════════════════════
  // Stratum protocol messages  (JStratum bridge → StratumConnection)
  // ═══════════════════════════════════════════════════════════════════════════

  /** JStratum input thread forwarded a mining.subscribe request. */
  case class MinerSubscribe(requestId: String)

  /** JStratum input thread forwarded a mining.authorize request. */
  case class MinerAuthorize(requestId: String, workerName: String, password: String)

  /** JStratum input thread forwarded a mining.submit request. */
  case class MinerSubmit(requestId: String, workerName: String, jobId: String,
                         extraNonce2Hex: String, nTime: String, extraNonce1Hex: String)

  // ═══════════════════════════════════════════════════════════════════════════
  // LithosPool internal
  // ═══════════════════════════════════════════════════════════════════════════

  /** Scheduler tick: ask LithosPool to fetch the latest block template from the Ergo node. */
  case object PollBlockTemplate
  /** Ask Pool to refresh difficulty from current commitment state */
  case object RefreshDifficulty

  case class UpdatedDifficulty(nextTau: Try[BigInt])
}
