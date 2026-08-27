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
    withClue("sync.snapshots.manifestDepth: ") { absent.manifestDepth shouldEqual shippedSync.manifestDepth }
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
  }

  /** Keeps the retained-state window within its configured memory bound. */
  "sync.reorgWindow" should "sit well inside the range validation permits" in {
    val window = new SyncConfig(shipped).reorgWindow
    window should be <= 1000
    window should be > 0
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
