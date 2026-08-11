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
  val forceConfigDifficulty: Boolean = config.getOptional[Boolean]("stratum.forceConfigDiff").getOrElse(false)
  val diffRefreshInterval: Int = config.get[Int]("stratum.diffRefreshInterval")

  /** Rotate a connection's extraNonce1 after this long without new work, in ms. 0 disables it. */
  val rotateExtraNonceInterval: Int =
    config.getOptional[Int]("stratum.rotateExtraNonceInterval").getOrElse(0)

  /** Everything under `stratum.candidate`, all optional — see [[CandidateConfig]]. */
  val candidate: CandidateConfig = CandidateConfig(config)
}
