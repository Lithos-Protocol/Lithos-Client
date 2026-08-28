package configs

import play.api.Configuration

import java.nio.file.{Path, Paths}
import scala.concurrent.duration.{DurationInt, FiniteDuration}

class SyncConfig(config: Configuration){

  val startHeight: Int = config.get[Int]("sync.startHeight")

  /**
   * Committed states retained for exact in-memory rollback. Each holds a dictionary copy per rollup
   * touched in its block, so this is the subsystem's largest memory term.
   */
  val reorgWindow: Int = config.getOptional[Int]("sync.reorgWindow").getOrElse(5)

  /**
   * Canonical cursors retained for locating a fork. Cursors are ~100 bytes, so this is sized for
   * reach rather than for memory, and deeper forks recover from a snapshot instead.
   */
  val cursorWindow: Int = config.getOptional[Int]("sync.cursorWindow").getOrElse(720)

  val storageBackend: String = config.getOptional[String]("sync.storage.backend").getOrElse("leveldb")
  val storagePath: Path = Paths.get(config.getOptional[String]("sync.storage.path").getOrElse(".lithos/sync"))
  val snapshotsEnabled: Boolean = config.getOptional[Boolean]("sync.snapshots.enabled").getOrElse(true)
  val snapshotInterval: Int = config.getOptional[Int]("sync.snapshots.intervalBlocks").getOrElse(720)
  val snapshotRetention: Int = config.getOptional[Int]("sync.snapshots.retention").getOrElse(3)

  /**
   * Ceiling on one snapshot entry. A generation is one entry per rollup plus a manifest, so this
   * bounds the largest rollup rather than the whole state.
   */
  val snapshotMaxEntryBytes: Int =
    config.getOptional[Int]("sync.snapshots.maxEntryBytes").getOrElse(64 * 1024 * 1024)

  /** Whole blocks fetched per canonical catch-up request. */
  val catchUpBatchBlocks: Int = config.getOptional[Int]("sync.catchUpBatchBlocks").getOrElse(16)

  /** Maximum transactions in one complete mempool revision. */
  val mempoolMaxTransactions: Int =
    config.getOptional[Int]("sync.mempool.maxTransactions").getOrElse(10000)

  /** How often the canonical producer polls the node. Dominates node load and tip latency. */
  val pollInterval: FiniteDuration =
    config.getOptional[FiniteDuration]("sync.pollInterval").getOrElse(5.seconds)

  /** How often the relevant mempool is re-read while ready. Independent of block polling. */
  val mempoolRefreshInterval: FiniteDuration =
    config.getOptional[FiniteDuration]("sync.mempool.refreshInterval").getOrElse(5.seconds)

  /**
   * Rebuild attempts for a quarantined rollup before it is dropped for good. Zero never rebuilds.
   * Terminal faults are dropped regardless — see `QuarantineFault.retryable`.
   */
  val quarantineRepairAttempts: Int =
    config.getOptional[Int]("sync.quarantine.repairAttempts").getOrElse(3)

  val minerDictionaryBootstrap: Boolean =
    config.getOptional[Boolean]("sync.minerDictionary.bootstrap").getOrElse(true)

  /** Maximum dictionary spends followed during bootstrap. */
  val minerDictionaryMaxTransforms: Int =
    config.getOptional[Int]("sync.minerDictionary.maxTransforms").getOrElse(100000)

  /** Consecutive failures at one height before the client reports its cursor stalled. */
  val retriesBeforeAlarm: Int = config.getOptional[Int]("sync.retriesBeforeAlarm").getOrElse(12)

  /** How often the committed header is re-read while idle at the chain tip. */
  val revalidationChecks: FiniteDuration =
    config.getOptional[FiniteDuration]("sync.revalidationChecks").getOrElse(30.seconds)

  /** How often a failed Miner Dictionary bootstrap is retried while the rest of sync runs. */
  val dictionaryRepairInterval: FiniteDuration =
    config.getOptional[FiniteDuration]("sync.minerDictionary.repairInterval").getOrElse(5.minutes)
}
