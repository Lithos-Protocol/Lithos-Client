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
 *
 *   LithosPool ──── ChainAdvanced ─────────────────────── ► CandidateBuilder
 *                   BlockPackageReady ◄─────────────────────┘
 */
object MiningMessages {

  // ═══════════════════════════════════════════════════════════════════════════
  // LithosJobManager ↔ LithosPool / StratumConnection
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Sent by LithosPool to LithosJobManager whenever a new block template is fetched.
   * LithosJobManager replies with true if the template produced a NEW block job,
   * false if it was a duplicate of the current job.
   *
   * `mustPublish` says the candidate has already been taken from the node. The node keeps one
   * candidate per key and validates solutions against that copy, so one it has handed out and had
   * replaced is worthless — dropping such a template leaves miners hashing a header no solution can
   * be submitted against. Set it whenever the fetch has happened; leave it false only for templates
   * that can still be thrown away for free.
   */
  case class ProcessTemplate(candidate: MiningCandidate, tau: BigInteger,
                             usesCollateral: Boolean, reducedShareMessages: Boolean,
                             mustPublish: Boolean = false,
                             publication: Option[CandidatePublication] = None)

  /**
   * Exactly which work a candidate request describes. Height alone cannot distinguish a same-height
   * reorg or a replacement genesis, and `revision` separates successive packages over one genesis.
   * An empty `genesisId` means a solo candidate carrying no collateral transaction.
   */
  case class CandidateIdentity(height: Int, parentId: String, genesisId: String, revision: Int)

  /** One publication attempt for that identity. `expiresAt` is set only for augmented packages. */
  case class CandidatePublication(identity: CandidateIdentity, attempt: java.util.UUID,
                                  expiresAt: Option[Long] = None)

  /** The job manager refused this publication; its optional transactions do not reach miners. */
  case class TemplateRejected(publication: CandidatePublication)

  /** The node cache or chain no longer supports the currently served job. */
  case object InvalidateTemplate

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
  case class NewJobAvailable(template: BlockTemplate, publication: Option[CandidatePublication] = None)

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
  // LithosPool ↔ CandidateBuilder
  //
  //   LithosPool ──── ChainAdvanced / RebuildCandidate / BlockTxsRejected ─► CandidateBuilder
  //   CandidateBuilder ──── BlockPackageReady ─────────────────────────────► LithosPool
  //
  // Only LithosPool watches the node's height, so it decides when to build. The builder pushes
  // finished packages back rather than being asked, so nothing on the mining path waits on it.
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * The block now being mined is one past the full-chain tip. A changed parent invalidates
   * work even at the same or a lower height; an empty parent retains height-only legacy behavior.
   */
  case class ChainAdvanced(blockHeight: Int, parentId: String = "")

  /** Actual genesis job publication permits the builder to start optional collection. */
  case class GenesisPublished(identity: CandidateIdentity)

  /** Throw away the current package and build it again. */
  case object RebuildCandidate

  /**
   * This collateral box has just been consumed by a block we mined.
   *
   * The builder cannot wait for a refresh to notice. A found block is exactly the case where the
   * active set changes, and the next block's genesis transaction is built from the in-memory set
   * moments later — so without this it spends the box it just spent.
   */
  case class CollateralSpent(boxId: String)

  /**
   * The node refused a candidate carrying this block's inserted transactions but accepted the
   * genesis transaction alone, so only the insertions are at fault. They are in the mempool too.
   */
  case class BlockTxsRejected(blockHeight: Int, identity: Option[CandidateIdentity] = None)

  /**
   * Transactions are ready for the block at `pkg.blockHeight`. Sent once with the genesis
   * transaction alone and again for each later revision.
   */
  case class BlockPackageReady(pkg: BlockPackage)

  // ═══════════════════════════════════════════════════════════════════════════
  // LithosPool internal
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * MiningStratumServer → LithosPool, once at startup: hand back the job manager so connections
   * can send shares directly to the actor owning validation and avoid an extra mailbox hop.
   */
  case object GetJobManager

  /** Scheduler tick: ask LithosPool to fetch the latest block template from the Ergo node. */
  case object PollBlockTemplate
  /** Ask Pool to refresh difficulty from current commitment state */
  case object RefreshDifficulty

  case class UpdatedDifficulty(nextTau: Try[BigInt])
}
