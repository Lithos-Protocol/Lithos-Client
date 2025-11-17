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

}
