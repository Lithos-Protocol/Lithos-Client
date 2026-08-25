package configs

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.Configuration

/**
 * `Configs.validateAll` must accept exactly what ships, and must report every bad key at once.
 *
 * The shipped conf is the one artefact every miner starts from, so if validation rejects it the
 * client never boots. And the point of the batch pass is aggregation — a validator that stops at
 * the first problem puts a user fixing five mistakes through five startups, which is the failure
 * mode this system exists to remove.
 */
class ConfigsSpec extends AnyFlatSpec with Matchers {

  private def shipped: Configuration =
    Configuration(ConfigFactory.parseResources("application.conf").resolve())

  "Configs.validateAll" should "accept the shipped application.conf" in {
    try Configs.validateAll(shipped)
    catch {
      case t: ConfigValidationException => fail(t.getMessage)
    }
  }

  it should "name every required key missing from an empty configuration" in {
    // The required set is exactly the keys whose READER calls `Configuration.get`, which throws.
    // Anything read with `getOptional` has a real fallback and is legitimately absent. Getting that
    // line wrong in the tolerant direction is what this pins: the key passes validation, and the
    // client then dies inside whichever actor or controller reads it first, which is the outcome
    // validateAll exists to prevent.
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
    // The validator walks Contexts.Names, so a dispatcher added to the class and not to the list
    // would go unvalidated. ContextsSpec proves the shipped conf resolves them; this is about the
    // conf a miner has edited.
    Contexts.Names should contain theSameElementsAs
      Seq(Contexts.Stratum, Contexts.Polling, Contexts.Sync, Contexts.Tx, Contexts.Dex)
  }

  it should "reject an apiKeyHash that is the key rather than its hash" in {
    // The common mistake, and silent without this: every authenticated call 403s and nothing says
    // why. Only this key is supplied, so the report is full of other problems - the assertion is
    // that this one is among them.
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

  /**
   * The two readers of a task's `enabled` flag differ on purpose, and the difference is the whole
   * point of the tolerant one. `SubmissionHandler` wanted the flag only to pick which of three
   * warnings to print, and building a `TasksConfig` to get it threw out of `runBatch` on a config
   * without the block — losing every stub in the batch to a log line.
   */
  "TasksConfig" should "throw on a missing task block, and read the flag tolerantly" in {
    val empty = Configuration(ConfigFactory.empty())

    an[Exception] should be thrownBy new TasksConfig(empty)
    TasksConfig.isEnabled(empty, TasksConfig.DictionarySync) shouldBe false

    val on = Configuration(ConfigFactory.parseString(
      s"""${TasksConfig.key(TasksConfig.DictionarySync, "enabled")} = true"""))
    TasksConfig.isEnabled(on, TasksConfig.DictionarySync) shouldBe true
  }

  it should "name every task the shipped conf configures" in {
    // The validator walks TasksConfig.Names, so a task added to the class and not to the list would
    // be unvalidated - and a name in the list with no block would fail the shipped-conf test above.
    TasksConfig.Names should contain theSameElementsAs
      Seq(TasksConfig.StratumServer, TasksConfig.RollupSync, TasksConfig.DictionarySync)
    noException should be thrownBy new TasksConfig(shipped)
  }
}
