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

/**
 * Startup-time validation for every config the client reads. `Module.configure` runs
 * [[Configs.validateAll]] before anything binds, so a bad value stops startup here with a readable
 * report naming every offending key, instead of surfacing later as a crash inside whatever actor
 * happened to read it first.
 *
 * Ranges are deliberately generous - they catch typos, wrong units and nonsense, not tuning choices.
 */
object Configs {

  /** Throws immediately for a single fatal problem found outside the batch validation pass. */
  def fail(key: String, problem: String): Nothing =
    throw new ConfigValidationException(Seq(ConfigProblem(key, problem)))

  def validateAll(config: Configuration): Unit = {
    val v = new ConfigValidator(config)

    // ---- node ----
    v.url("node.url", v.string("node.url"))
    v.requireExisting("node.key", v.string("node.key"))
    // Empty is what ships: these are placeholders the miner fills with their own keystore path and
    // password. An empty one fails later, inside NodeConfig, with a message naming the key.
    v.requireExisting("node.storagePath", v.string("node.storagePath"))
    v.requireExisting("node.pass", v.string("node.pass"))
    v.string("node.networkType").foreach { raw =>
      if (!Set("MAINNET", "TESTNET").contains(raw.trim.toUpperCase))
        v.problem("node.networkType", s""""$raw" is not a network type - use MAINNET or TESTNET""")
    }
    v.url("node.explorerURL", v.string("node.explorerURL"), allowDefaultSentinel = true)
    val numAddresses =
      v.range("node.numAddresses", v.int("node.numAddresses"), 1, 1000,
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
    v.port("stratum.stratumPort", v.intReq("stratum.stratumPort"))
    v.range("stratum.extraNonce1Size", v.int("stratum.extraNonce1Size"), 1, 8,
      "hex bytes of extraNonce1; 2 is recommended, higher invites duplicate shares")
    v.range("stratum.rotateExtraNonceInterval", v.int("stratum.rotateExtraNonceInterval"), 0, 3600000,
      "ms without work before extraNonce rotation; 0 disables it")
    v.range("stratum.connectionTimeout", v.int("stratum.connectionTimeout"), 1000, 3600000, "ms")
    v.range("stratum.blockRefreshInterval", v.int("stratum.blockRefreshInterval"), 100, 600000, "ms")
    v.bool("stratum.reduceShareMessages")
    v.bool("stratum.forceConfigDiff")
    v.range("stratum.diffRefreshInterval", v.int("stratum.diffRefreshInterval"), 1000, 3600000, "ms")

    // ---- stratum.candidate ----
    v.range("stratum.candidate.collateralPoolSize", v.int("stratum.candidate.collateralPoolSize"), 1, 100,
      "collateral boxes pre-loaded; the active set never holds more than 100")
    v.range("stratum.candidate.collateralRefreshInterval", v.int("stratum.candidate.collateralRefreshInterval"), 100, 3600000, "ms")
    v.range("stratum.candidate.maxBlockTxs", v.int("stratum.candidate.maxBlockTxs"), 0, 100, "transactions inserted per block")
    v.range("stratum.candidate.genesisWaitMs", v.int("stratum.candidate.genesisWaitMs"), 0, 60000, "ms to wait for the genesis transaction before falling back to a solo candidate")
    v.range("stratum.candidate.mempoolRefreshMs", v.int("stratum.candidate.mempoolRefreshMs"), 0, 3600000, "ms between candidate refreshes within a block; 0 mines the block's initial transaction set")
    v.range("stratum.candidate.blockTxTimeout", v.int("stratum.candidate.blockTxTimeout"), 0, 600000, "ms")

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

    // Cross-config: lender keys derived past the prover's last address receive permit returns and
    // coinbases nothing can ever spend. This is stranded funds, not degraded performance.
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
    v.range("sync.startHeight", v.intReq("sync.startHeight"), 1, 2000000000,
      "block height to start synchronizing from; minimum 1")

    // ---- lithos-tasks ----
    Seq("stratum-server", "rollup-sync-task", "dictionary-sync-task").foreach { name =>
      val base = s"lithos-tasks.$name"
      v.bool(s"$base.enabled")
      v.durationRange(s"$base.startup", 1, 604800000L)
      v.durationRange(s"$base.interval", 1, 604800000L)
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

  def durationRange(key: String, minMs: Long, maxMs: Long): Unit =
    duration(key).foreach { d =>
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
