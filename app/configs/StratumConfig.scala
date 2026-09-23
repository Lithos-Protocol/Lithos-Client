package configs

import play.api.Configuration

import java.sql.DriverManager
import java.util.Properties
import scala.concurrent.duration.FiniteDuration

class StratumConfig(config: Configuration){


  val diff: String = config.get[String]("stratum.diff")
  val stratumPort: Int = config.get[Int]("stratum.stratumPort")
  val extraNonce1Size: Int = config.get[Int]("stratum.extraNonce1Size")
  val connectionTimeout: Int = config.get[Int]("stratum.connectionTimeout")
  val blockRefreshInterval: Int = config.get[Int]("stratum.blockRefreshInterval")
  val reduceShareMessages: Boolean = config.get[Boolean]("stratum.reduceShareMessages")

  /** How many times the committed diff miners are sent while `reduceShareMessages` is on. */
  val reductionMultiplier: Int = config.getOptional[Int]("stratum.reductionMultiplier")
    .getOrElse(StratumConfig.DefaultReductionMultiplier)
  val forceConfigDifficulty: Boolean = config.getOptional[Boolean]("stratum.forceConfigDiff").getOrElse(false)
  val diffRefreshInterval: Int = config.get[Int]("stratum.diffRefreshInterval")

  /** Rotate a connection's extraNonce1 after this long without new work, in ms. 0 disables it. */
  val rotateExtraNonceInterval: Int =
    config.getOptional[Int]("stratum.rotateExtraNonceInterval").getOrElse(0)

  /** Everything under `stratum.candidate`, all optional — see [[CandidateConfig]]. */
  val candidate: CandidateConfig = CandidateConfig(config)
}

object StratumConfig {
  /**
   * The values `stratum.reductionMultiplier` may take. None exceeds the super-share coefficient: above
   * it, miners would stop submitting the super shares a NISP is built from.
   */
  val ReductionMultipliers: Seq[Int] = Seq(10, 100, 1000, 10000)

  /** Sends miners the super-share diff itself, so every share they submit is a super share. */
  val DefaultReductionMultiplier: Int = 10000
}
