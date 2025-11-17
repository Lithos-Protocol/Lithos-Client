package configs

import play.api.Configuration

import scala.concurrent.duration.{Duration, FiniteDuration}

class SyncConfig(config: Configuration){

  val startHeight: Int = config.get[Int]("sync.startHeight")
  val listeningInterval: FiniteDuration = config.get[FiniteDuration]("sync.listeningInterval")
}
