package configs

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.Configuration

/** Ensures code defaults match the shipped configuration. */
class ConfigDefaultsSpec extends AnyFlatSpec with Matchers {

  private val shipped: Configuration =
    Configuration(ConfigFactory.parseResources("application.conf").resolve())

  "CandidateConfig.Default" should "equal what the shipped application.conf parses" in {
    CandidateConfig(shipped) shouldEqual CandidateConfig.Default
  }

  "EmissionConfig.Default" should "equal what the shipped application.conf parses" in {
    EmissionConfig(shipped) shouldEqual EmissionConfig.Default
  }

  /** Compares optional sync defaults field by field so failures identify the divergent key. */
  "SyncConfig defaults" should "equal what the shipped application.conf parses" in {
    val shippedSync = new SyncConfig(shipped)
    val absent = new SyncConfig(Configuration(ConfigFactory.parseString(
      s"sync.startHeight = ${shippedSync.startHeight}")))

    withClue("sync.reorgWindow: ") { absent.reorgWindow shouldEqual shippedSync.reorgWindow }
    withClue("sync.storage.backend: ") { absent.storageBackend shouldEqual shippedSync.storageBackend }
    withClue("sync.storage.path: ") { absent.storagePath shouldEqual shippedSync.storagePath }
    withClue("sync.snapshots.enabled: ") { absent.snapshotsEnabled shouldEqual shippedSync.snapshotsEnabled }
    withClue("sync.snapshots.intervalBlocks: ") { absent.snapshotInterval shouldEqual shippedSync.snapshotInterval }
    withClue("sync.snapshots.retention: ") { absent.snapshotRetention shouldEqual shippedSync.snapshotRetention }
    withClue("sync.catchUpBatchBlocks: ") { absent.catchUpBatchBlocks shouldEqual shippedSync.catchUpBatchBlocks }
    withClue("sync.mempool.maxTransactions: ") {
      absent.mempoolMaxTransactions shouldEqual shippedSync.mempoolMaxTransactions
    }
    withClue("sync.minerDictionary.bootstrap: ") {
      absent.minerDictionaryBootstrap shouldEqual shippedSync.minerDictionaryBootstrap
    }
    withClue("sync.minerDictionary.maxTransforms: ") {
      absent.minerDictionaryMaxTransforms shouldEqual shippedSync.minerDictionaryMaxTransforms
    }
    withClue("sync.retriesBeforeAlarm: ") {
      absent.retriesBeforeAlarm shouldEqual shippedSync.retriesBeforeAlarm
    }
    withClue("sync.revalidationChecks: ") { absent.revalidationChecks shouldEqual shippedSync.revalidationChecks }
    withClue("sync.cursorWindow: ") { absent.cursorWindow shouldEqual shippedSync.cursorWindow }
    withClue("sync.minerDictionary.repairInterval: ") {
      absent.dictionaryRepairInterval shouldEqual shippedSync.dictionaryRepairInterval
    }
    withClue("sync.pollInterval: ") { absent.pollInterval shouldEqual shippedSync.pollInterval }
    withClue("sync.mempool.refreshInterval: ") {
      absent.mempoolRefreshInterval shouldEqual shippedSync.mempoolRefreshInterval
    }
    withClue("sync.quarantine.repairAttempts: ") {
      absent.quarantineRepairAttempts shouldEqual shippedSync.quarantineRepairAttempts
    }
    withClue("sync.snapshots.maxEntryBytes: ") {
      absent.snapshotMaxEntryBytes shouldEqual shippedSync.snapshotMaxEntryBytes
    }
  }

  /**
   * Every optional key is compared above. This fails when one is added to `SyncConfig` and not to the
   * comparison, which is how a default silently drifts from the shipped file.
   */
  "SyncConfig" should "have every optional key covered by the defaults comparison" in {
    // Every value except `startHeight`, which is required and so has no default to drift from.
    val comparedOptional = 18
    val required = 1
    val fields = classOf[SyncConfig].getDeclaredMethods.count(m => m.getParameterCount == 0 &&
      !m.getName.contains("$") && m.getName != "config")
    withClue(s"SyncConfig exposes $fields values; the defaults test compares $comparedOptional " +
      s"optional plus $required required: ") {
      fields shouldEqual comparedOptional + required
    }
  }

  /**
   * The retained window is the subsystem's largest memory term, and its cost rises with the square of
   * miners per rollup, so the ceiling is what stops a config change from being unsurvivable.
   */
  "sync.reorgWindow" should "sit inside the range validation permits" in {
    val window = new SyncConfig(shipped).reorgWindow
    window should be <= 20
    window should be > 0
  }

  /**
   * Intervals are floors: a zero repair interval makes every tick due, and the repair would then
   * crowd out block polling for as long as the dictionary stays faulted.
   */
  "sync interval keys" should "sit above the floors validation enforces" in {
    val sync = new SyncConfig(shipped)
    sync.pollInterval.toMillis should be >= 1000L
    sync.mempoolRefreshInterval.toMillis should be >= 1000L
    sync.revalidationChecks.toMillis should be >= 1000L
    sync.dictionaryRepairInterval.toMillis should be >= 30000L
  }

  /** Cursors are cheap, so this is sized for reach and must outrange the state window. */
  "sync.cursorWindow" should "reach further back than the retained-state window" in {
    val sync = new SyncConfig(shipped)
    sync.cursorWindow should be > sync.reorgWindow
    // The node serves at most 16384 headers in one chainSlice, and commonAncestor asks in one call.
    sync.cursorWindow should be <= 16000
  }

  // Every configured lender key must have a corresponding wallet secret.
  "node.numAddresses" should "cover every lender key the shipped config permits" in {
    val numAddresses = shipped.get[Int]("node.numAddresses")
    val maxLenderKeys = shipped.get[Int]("emission.maxLenderKeys")
    withClue(s"numAddresses=$numAddresses must be >= maxLenderKeys=$maxLenderKeys, or keys past " +
      s"index ${numAddresses - 1} receive funds this client cannot sign for: ") {
      numAddresses should be >= maxLenderKeys
    }
  }
}
