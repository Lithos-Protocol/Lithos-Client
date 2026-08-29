package configs

import play.api.Configuration

import java.nio.file.{Path, Paths}
import scala.concurrent.duration.{DurationInt, FiniteDuration}

class SyncConfig(config: Configuration){

  val startHeight: Int = config.get[Int]("sync.startHeight")

  /**
   * Canonical cursors retained for locating a fork. Cursors are ~100 bytes, so this is sized for
   * reach rather than for memory, and deeper forks recover from a snapshot instead.
   */
  val cursorWindow: Int = config.getOptional[Int]("sync.cursorWindow").getOrElse(720)

  /** Materialized AVL provers retained after a request; everything else stays digest-only. */
  val materializedDictionaryCacheEntries: Int =
    config.getOptional[Int]("sync.materializedDictionaryCacheEntries").getOrElse(4)

  val storageBackend: String = config.getOptional[String]("sync.storage.backend").getOrElse("leveldb")
  val storagePath: Path = Paths.get(config.getOptional[String]("sync.storage.path").getOrElse(".lithos/sync"))
  val snapshotsEnabled: Boolean = config.getOptional[Boolean]("sync.snapshots.enabled").getOrElse(true)
  val snapshotInterval: Int = config.getOptional[Int]("sync.snapshots.intervalBlocks").getOrElse(90)
  val snapshotRetention: Int = config.getOptional[Int]("sync.snapshots.retention").getOrElse(12)

  /**
   * Ceiling on one snapshot entry. Dictionaries are content-addressed and written as bounded
   * subtrees plus a small header; generation metadata contains only digests.
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

  /** Blocks a terminal or retry-exhausted quarantine remains available for diagnostics. */
  val quarantineRetentionBlocks: Int =
    config.getOptional[Int]("sync.quarantine.retentionBlocks").getOrElse(720)

  /** Pause catch-up periodically so a repair can install against a cursor that cannot move. */
  val quarantineMaintenanceCheckpoints: Boolean =
    config.getOptional[Boolean]("sync.quarantine.maintenanceCheckpoints").getOrElse(true)

  /** Committed catch-up blocks between quarantine maintenance checkpoints. */
  val quarantineCheckpointIntervalBlocks: Int =
    config.getOptional[Int]("sync.quarantine.checkpointIntervalBlocks").getOrElse(10)

  /** Maximum rollup spends followed during one quarantine rebuild. */
  val quarantineMaxTransforms: Int =
    config.getOptional[Int]("sync.quarantine.maxTransforms").getOrElse(100000)

  /** Maximum wall-clock time one quarantine walk may hold a maintenance checkpoint. */
  val quarantineRepairTimeout: FiniteDuration =
    config.getOptional[FiniteDuration]("sync.quarantine.repairTimeout").getOrElse(3.minutes)

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
