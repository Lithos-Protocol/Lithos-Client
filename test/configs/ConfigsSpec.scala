package configs

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.Configuration

/** Verifies that startup validation accepts shipped configuration and aggregates invalid keys. */
class ConfigsSpec extends AnyFlatSpec with Matchers {

  private def shipped: Configuration =
    Configuration(ConfigFactory.parseResources("application.conf").resolve())

  "Configs.validateAll" should "accept the shipped application.conf" in {
    try Configs.validateAll(shipped)
    catch {
      case t: ConfigValidationException => fail(t.getMessage)
    }
  }

  it should "allow a maintenance checkpoint after every committed catch-up block" in {
    val configured = Configuration(ConfigFactory.parseString(
      "sync.quarantine.checkpointIntervalBlocks = 1")
      .withFallback(shipped.underlying).resolve())
    noException should be thrownBy Configs.validateAll(configured)
  }

  it should "reject a quarantine repair timeout below the supported floor" in {
    val configured = Configuration(ConfigFactory.parseString(
      "sync.quarantine.repairTimeout = 500 milliseconds")
      .withFallback(shipped.underlying).resolve())
    val thrown = the[ConfigValidationException] thrownBy Configs.validateAll(configured)
    thrown.getMessage should include("sync.quarantine.repairTimeout")
  }

  it should "name every required key missing from an empty configuration" in {
    // Required keys are those read without an application default.
    val thrown = the[ConfigValidationException] thrownBy
      Configs.validateAll(Configuration(ConfigFactory.empty()))
    val message = thrown.getMessage

    List(
      "node.url",
      "node.storagePath",
      "node.pass",
      "node.networkType",
      // NodeConfig falls back to ONE address, while emission.maxLenderKeys defaults to 32.
      "node.numAddresses",
      "stratum.diff",
      "stratum.stratumPort",
      "stratum.extraNonce1Size",
      "stratum.connectionTimeout",
      "stratum.blockRefreshInterval",
      "stratum.reduceShareMessages",
      "stratum.diffRefreshInterval",
      "sync.startHeight",
      // Every authenticated controller reads this at construction.
      "lithos.apiKeyHash",
      // TasksConfig reads all nine of these with `get`.
      "lithos-tasks.stratum-server.enabled",
      "lithos-tasks.rollup-sync-task.startup",
      "lithos-tasks.dictionary-sync-task.interval",
      // `Contexts` resolves these during Guice provisioning, past the readable report.
      "lithos-contexts.dex-dispatcher",
      "lithos-contexts.tx-dispatcher"
    ).foreach(key => withClue(s"expected $key in report:\n$message\n")(message should include(key)))
  }

  it should "name every dispatcher Contexts looks up" in {
    // Keep runtime dispatcher lookups and validation names aligned.
    Contexts.Names should contain theSameElementsAs
      Seq(Contexts.Stratum, Contexts.Polling, Contexts.Sync, Contexts.Tx, Contexts.Dex,
        Contexts.Database, Contexts.SnapshotIo)
  }

  it should "reject an apiKeyHash that is the key rather than its hash" in {
    // A plaintext key must be reported even when the configuration has other errors.
    val thrown = the[ConfigValidationException] thrownBy
      Configs.validateAll(Configuration(ConfigFactory.parseString("""lithos.apiKeyHash = "hello"""")))
    thrown.getMessage should include("lithos.apiKeyHash")
  }

  it should "report every bad value together, naming each offending key" in {
    val broken = Configuration(ConfigFactory.parseString("""
      node.url = "ftp://not-a-node"
      node.networkType = "MAINNETT"
      node.numAddresses = 16
      stratum.stratumPort = 99999
      stratum.diff = "4.0Z"
      emission.maxLenderKeys = 64
    """).resolve())

    val thrown = the[ConfigValidationException] thrownBy Configs.validateAll(broken)
    val message = thrown.getMessage

    List(
      "node.url",
      "node.networkType",
      "stratum.stratumPort",
      "stratum.diff"
    ).foreach(key => withClue(s"expected $key in report:\n$message\n")(message should include(key)))

    // The cross-config rule: lender keys past the prover's last address strand funds.
    message should include("emission.maxLenderKeys")
  }

  "Configs.fail" should "throw a single-problem exception" in {
    val thrown = the[ConfigValidationException] thrownBy Configs.fail("node.pass", "rejected")
    thrown.getMessage should include("node.pass")
  }

  /** Distinguishes required task construction from tolerant status-message lookup. */
  "TasksConfig" should "throw on a missing task block, and read the flag tolerantly" in {
    val empty = Configuration(ConfigFactory.empty())

    an[Exception] should be thrownBy new TasksConfig(empty)
    TasksConfig.isEnabled(empty, TasksConfig.DictionarySync) shouldBe false

    val on = Configuration(ConfigFactory.parseString(
      s"""${TasksConfig.key(TasksConfig.DictionarySync, "enabled")} = true"""))
    TasksConfig.isEnabled(on, TasksConfig.DictionarySync) shouldBe true
  }

  it should "name every task the shipped conf configures" in {
    // Keep task construction and validation names aligned.
    TasksConfig.Names should contain theSameElementsAs
      Seq(TasksConfig.StratumServer, TasksConfig.RollupSync, TasksConfig.DictionarySync)
    noException should be thrownBy new TasksConfig(shipped)
  }
}
