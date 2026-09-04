package configs

import lfsm.LFSMHelpers
import org.ergoplatform.appkit.Parameters
import play.api.{ConfigLoader, Configuration}

import java.net.URI
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success, Try}

/** One wrong value: where it lives, what is wrong with it, and what to do about it. */
final case class ConfigProblem(key: String, problem: String)

object ConfigProblem {
  def render(problems: Seq[ConfigProblem]): String = {
    val body = problems.map(p => s"  ${p.key}\n      ${p.problem}").mkString("\n\n")
    val plural = if (problems.size == 1) "problem" else "problems"
    s"""|
        |The client found ${problems.size} configuration $plural and cannot start safely:
        |
        |$body
        |
        |Fix these in conf/application.conf and restart.""".stripMargin
  }
}

/** Thrown once at startup carrying every problem found, so one edit session fixes all of them. */
final class ConfigValidationException(problems: Seq[ConfigProblem])
  extends RuntimeException(ConfigProblem.render(problems))

/** Validates all runtime configuration before dependency injection constructs its consumers. */
object Configs {

  /** Throws immediately for a single fatal problem found outside the batch validation pass. */
  def fail(key: String, problem: String): Nothing =
    throw new ConfigValidationException(Seq(ConfigProblem(key, problem)))

  def validateAll(config: Configuration): Unit = {
    val v = new ConfigValidator(config)

    // ---- node ----
    v.url("node.url", v.string("node.url"))
    v.requireExisting("node.key", v.string("node.key"))
    // Empty shipped values are placeholders validated by NodeConfig after user configuration.
    v.requireExisting("node.storagePath", v.string("node.storagePath"))
    v.requireExisting("node.pass", v.string("node.pass"))
    v.requireExisting("node.networkType", v.string("node.networkType")).foreach { raw =>
      if (!Set("MAINNET", "TESTNET").contains(raw.trim.toUpperCase))
        v.problem("node.networkType", s""""$raw" is not a network type - use MAINNET or TESTNET""")
    }
    v.url("node.explorerURL", v.string("node.explorerURL"), allowDefaultSentinel = true)
    // Require an explicit address count so lender keys cannot exceed available wallet secrets.
    val numAddresses =
      v.range("node.numAddresses", v.intReq("node.numAddresses"), 1, 1000,
        "how many EIP-3 addresses the prover holds keys for")

    // ---- stratum ----
    v.requireExisting("stratum.diff", v.string("stratum.diff")).foreach { d =>
      LFSMHelpers.parseDiffValueForStratum(d) match {
        case Failure(_) =>
          v.problem("stratum.diff",
            s""""$d" is not a difficulty value. Format it as <number><K|M|G|T|P>, e.g. "4.0G" or "1.52M"""")
        case Success(tau) if tau.signum() <= 0 =>
          v.problem("stratum.diff", s""""$d" resolves to zero or negative difficulty""")
        case Success(_) => ()
      }
    }
    // Require keys that StratumConfig reads without defaults.
    v.port("stratum.stratumPort", v.intReq("stratum.stratumPort"))
    v.range("stratum.extraNonce1Size", v.intReq("stratum.extraNonce1Size"), 1, 8,
      "hex bytes of extraNonce1; 2 is recommended, higher invites duplicate shares")
    v.range("stratum.connectionTimeout", v.intReq("stratum.connectionTimeout"), 1000, 3600000, "ms")
    v.range("stratum.blockRefreshInterval", v.intReq("stratum.blockRefreshInterval"), 100, 600000, "ms")
    v.boolReq("stratum.reduceShareMessages")
    v.range("stratum.diffRefreshInterval", v.intReq("stratum.diffRefreshInterval"), 1000, 3600000, "ms")
    v.range("stratum.rotateExtraNonceInterval", v.int("stratum.rotateExtraNonceInterval"), 0, 3600000,
      "ms without work before extraNonce rotation; 0 disables it")
    v.bool("stratum.forceConfigDiff")

    // ---- stratum.candidate ----
    v.range("stratum.candidate.collateralPoolSize", v.int("stratum.candidate.collateralPoolSize"), 1, 100,
      "collateral boxes pre-loaded; the active set never holds more than 100")
    v.range("stratum.candidate.collateralRefreshInterval", v.int("stratum.candidate.collateralRefreshInterval"), 100, 3600000, "ms")
    v.range("stratum.candidate.maxBlockTxs", v.int("stratum.candidate.maxBlockTxs"), 0, 100, "transactions inserted per block")
    v.range("stratum.candidate.genesisWaitMs", v.int("stratum.candidate.genesisWaitMs"), 0, 60000, "ms to wait for the genesis transaction before falling back to a solo candidate")
    v.range("stratum.candidate.mempoolRefreshMs", v.int("stratum.candidate.mempoolRefreshMs"), 0, 3600000, "ms between candidate refreshes within a block; 0 mines the block's initial transaction set")
    v.range("stratum.candidate.blockTxTimeout", v.int("stratum.candidate.blockTxTimeout"), 0, 600000, "ms")
    v.bool("stratum.candidate.logTimings")

    // ---- emission ----
    v.bool("emission.enabled")
    v.range("emission.queueInterval", v.int("emission.queueInterval"), 1000, 3600000, "ms")
    v.range("emission.maxQueueSpends", v.int("emission.maxQueueSpends"), 1, 128, "chained emission spends per pass")
    v.bool("emission.mempoolChaining")
    v.range("emission.maxChainDepth", v.int("emission.maxChainDepth"), 0, 4096, "unconfirmed spends followed before falling back to confirmed state")
    v.range("emission.queueScanLimit", v.int("emission.queueScanLimit"), 1, 100000, "queue boxes paged through per head search")
    v.long("emission.txFee").foreach { fee =>
      if (fee < Parameters.MinFee || fee > 1000000000L)
        v.problem("emission.txFee",
          s"$fee nanoERG is outside ${Parameters.MinFee}..1000000000 (at least the network minimum fee, at most 1 ERG)")
    }
    v.bool("emission.autoCollateralize")
    v.range("emission.collateralizeInterval", v.int("emission.collateralizeInterval"), 1000, 86400000, "ms")
    v.range("emission.maxJoinsPerRun", v.int("emission.maxJoinsPerRun"), 1, 128, "queue boxes created per pass")
    v.range("emission.maxOwnCollateral", v.int("emission.maxOwnCollateral"), 0, 2000,
      "own boxes live at once; includes collateral, queue, and newly made pos boxes")
    val maxLenderKeys =
      v.range("emission.maxLenderKeys", v.int("emission.maxLenderKeys"), 1, 1000, "wallet addresses dedicated to lending")
    v.long("emission.maxPermitPerJoin").foreach { permit =>
      if (permit <= 0)
        v.problem("emission.maxPermitPerJoin", s"$permit must be positive (LIT base units); joins stop once the thermostat passes this price")
    }

    // Every lender key must have a wallet secret capable of spending returned funds.
    for {
      addr <- numAddresses
      keys <- maxLenderKeys
      if keys > addr
    } v.problem("emission.maxLenderKeys",
      s"$keys exceeds node.numAddresses ($addr). Addresses past index ${addr - 1} would receive funds this client cannot sign for. Raise node.numAddresses to at least $keys")

    // ---- state ----
    v.bool("state.disableTransforms")
    v.bool("state.autoCommit")

    // ---- sync ----
    v.range("sync.startHeight", v.intReq("sync.startHeight"), 2, 2000000000,
      "block height to start synchronizing from; minimum 1")
    // Cursors are ~100 bytes, and the node serves at most 16384 headers in one chainSlice.
    v.range("sync.cursorWindow", v.int("sync.cursorWindow"), 1, 16000,
      "canonical cursors retained for locating a fork after restart")
    v.range("sync.materializedDictionaryCacheEntries", v.int("sync.materializedDictionaryCacheEntries"),
      1, 64, "authenticated dictionaries retained after lazy materialization")
    v.range("sync.catchUpBatchBlocks", v.int("sync.catchUpBatchBlocks"), 1, 64,
      "blocks fetched per canonical round trip; each is held whole until it commits")
    // Intervals are floors, not just parseable durations. Zero or negative makes every tick due, and
    // the repair holds the producer, so a zero interval stops canonical synchronization outright.
    v.durationRangeReq("sync.pollInterval", 1000L, 600000L)
    v.durationRangeReq("sync.revalidationChecks", 1000L, 3600000L)
    v.durationRangeReq("sync.minerDictionary.repairInterval", 30000L, 86400000L)
    v.durationRangeReq("sync.mempool.refreshInterval", 1000L, 600000L)
    v.range("sync.retriesBeforeAlarm", v.int("sync.retriesBeforeAlarm"), 1, 10000,
      "consecutive failures at one height before synchronization reports itself stalled")
    v.range("sync.mempool.maxTransactions", v.int("sync.mempool.maxTransactions"), 1, 100000,
      "unconfirmed transactions read before the mempool view gives up on a complete revision")
    v.range("sync.quarantine.repairAttempts", v.int("sync.quarantine.repairAttempts"), 0, 100,
      "rebuild attempts for a quarantined rollup before it is dropped for good")
    v.range("sync.quarantine.retentionBlocks", v.int("sync.quarantine.retentionBlocks"), 1, 10000000,
      "committed blocks a terminal or retry-exhausted quarantine is retained for diagnostics")
    v.bool("sync.quarantine.maintenanceCheckpoints")
    v.range("sync.quarantine.checkpointIntervalBlocks",
      v.int("sync.quarantine.checkpointIntervalBlocks"), 1, 100000,
      "committed catch-up blocks between quarantine repair checkpoints")
    v.range("sync.quarantine.maxTransforms", v.int("sync.quarantine.maxTransforms"), 1, 10000000,
      "rollup spends followed during one quarantine rebuild")
    v.durationRangeReq("sync.quarantine.repairTimeout", 1000L, 3600000L)
    v.bool("sync.minerDictionary.bootstrap")
    v.range("sync.minerDictionary.maxTransforms", v.int("sync.minerDictionary.maxTransforms"), 1, 10000000,
      "dictionary spends followed during bootstrap")
    v.requireExisting("sync.storage.backend", v.string("sync.storage.backend")).foreach { backend =>
      storage.KeyValueStore.factoryFor(backend).left.foreach(error =>
        v.problem("sync.storage.backend", error.message))
    }
    v.requireExisting("sync.storage.path", v.string("sync.storage.path"))
    v.bool("sync.snapshots.enabled")
    v.range("sync.snapshots.intervalBlocks", v.int("sync.snapshots.intervalBlocks"), 1, 10000000,
      "committed blocks between persistent snapshots")
    v.range("sync.snapshots.retention", v.int("sync.snapshots.retention"), 2, 100,
      "complete snapshot generations retained")
    // Dictionary subtrees, headers, and the small generation record are each independently bounded.
    v.range("sync.snapshots.maxEntryBytes", v.int("sync.snapshots.maxEntryBytes"), 1048576, 536870912,
      "bytes one snapshot entry may reach before the generation is abandoned")

    // ---- lithos-tasks ----
    // Require every task field that TasksConfig reads without a default.
    TasksConfig.Names.foreach { name =>
      v.boolReq(TasksConfig.key(name, "enabled"))
      v.durationRangeReq(TasksConfig.key(name, "startup"), 1, 604800000L)
      v.durationRangeReq(TasksConfig.key(name, "interval"), 1, 604800000L)
    }

    // ---- lithos-contexts ----
    // Akka validates dispatcher contents; this pass verifies that each named block exists.
    Contexts.Names.foreach(name => v.requireBlock(Contexts.key(name)))

    // ---- api ----
    // Controllers require a Blake2b256 API-key hash at construction.
    v.requireExisting("lithos.apiKeyHash", v.string("lithos.apiKeyHash")).foreach { raw =>
      val hash = raw.trim
      if (!hash.matches("(?i)[0-9a-f]{64}"))
        v.problem("lithos.apiKeyHash",
          "is not a 64-character hex hash. This is the BLAKE2b-256 of your api key, not the key " +
            "itself - hash it the same way you set your node's apiKeyHash")
    }

    v.finish()
  }
}

/**
 * Accumulates problems across every section and raises them together in [[finish]], so a user sees
 * all their mistakes in one startup instead of fixing them one crash at a time.
 */
final class ConfigValidator(config: Configuration) {

  private val problems = ListBuffer.empty[ConfigProblem]
  private val flagged  = mutable.Set.empty[String]

  def problem(key: String, message: String): Unit = {
    problems += ConfigProblem(key, message)
    flagged += key
  }

  def finish(): Unit =
    if (problems.nonEmpty) throw new ConfigValidationException(problems.toList)

  def string(key: String): Option[String]         = read(ConfigLoader.stringLoader, key, "a string")
  def int(key: String): Option[Int]               = read(ConfigLoader.intLoader, key, "an integer")
  def long(key: String): Option[Long]             = read(ConfigLoader.longLoader, key, "an integer")
  def bool(key: String): Option[Boolean]          = read(ConfigLoader.booleanLoader, key, "true or false")
  def duration(key: String): Option[FiniteDuration] =
    read(ConfigLoader.finiteDurationLoader, key, "a duration such as \"30 seconds\"")

  /** An int whose absence is itself a problem - required keys the client cannot default sensibly. */
  def intReq(key: String): Option[Int] = requireExisting(key, int(key))

  /** As [[intReq]]. Use wherever the reading code calls `Configuration.get`, which throws. */
  def boolReq(key: String): Option[Boolean] = requireExisting(key, bool(key))

  /** A whole config block must exist, without saying anything about what is inside it. */
  def requireBlock(key: String): Unit =
    requireExisting(key, read(ConfigLoader.configurationLoader, key, "a configuration block"))

  /** Reports absence unless something was already reported for this key. */
  def requireExisting[A](key: String, value: Option[A]): Option[A] =
    value.orElse {
      if (!flagged.contains(key)) problem(key, "is required but missing from application.conf")
      None
    }

  def range(key: String, value: Option[Int], min: Long, max: Long, unit: String): Option[Int] =
    value match {
      case Some(v) if v < min || v > max =>
        problem(key, s"$v is outside $min..$max ($unit)")
        None
      case other => other
    }

  def port(key: String, value: Option[Int]): Option[Int] =
    range(key, value, 1, 65535, "a TCP port")

  /** Validates a required task duration and reports one error for a malformed value. */
  def durationRangeReq(key: String, minMs: Long, maxMs: Long): Unit =
    requireExisting(key, duration(key)).foreach { d =>
      if (d.toMillis < minMs || d.toMillis > maxMs)
        problem(key, s"${d.toString} is outside ${minMs}ms..${maxMs}ms")
    }

  /** http(s) URL with a host; `allowDefaultSentinel` accepts the literal "default". */
  def url(key: String, value: Option[String], allowDefaultSentinel: Boolean = false): Unit = {
    requireExisting(key, value).foreach { raw =>
      if (!(allowDefaultSentinel && raw.trim == "default")) {
        val ok = Try(new URI(raw.trim)) match {
          case Success(uri) if Set("http", "https").contains(Option(uri.getScheme).getOrElse("")) =>
            Option(uri.getHost).exists(_.nonEmpty)
          case _ => false
        }
        if (!ok)
          problem(key, s""""$raw" is not a usable URL - use http:// or https:// with a host, e.g. http://127.0.0.1""")
      }
    }
  }

  private def read[A](loader: ConfigLoader[A], key: String, expected: String): Option[A] =
    Try(config.getOptional[A](key)(loader)) match {
      case Success(v) => v
      case Failure(_) =>
        problem(key, s"must be $expected")
        None
    }
}
