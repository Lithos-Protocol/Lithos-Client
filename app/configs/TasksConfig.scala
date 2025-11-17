package configs

import play.api.Configuration

import scala.concurrent.duration.{Duration, FiniteDuration}

class TasksConfig(config: Configuration){
  val stratumServerTaskConfig:   TasksConfig.TaskConfiguration =
    TasksConfig.TaskConfiguration.fromConfig(config, "stratum-server")
  val blockPolling:   TasksConfig.TaskConfiguration =
    TasksConfig.TaskConfiguration.fromConfig(config, "block-polling")

}

object TasksConfig {
  case class TaskConfiguration(enabled: Boolean, startup: FiniteDuration, interval: FiniteDuration)
  object TaskConfiguration {
    def fromConfig(configuration: Configuration, name: String): TaskConfiguration = {
      val isEnabled = configuration.get[Boolean](s"lithos-tasks.${name}.enabled")
      val startupTime = configuration.get[FiniteDuration](s"lithos-tasks.${name}.startup")
      val intervalTime = configuration.get[FiniteDuration](s"lithos-tasks.${name}.interval")
      TaskConfiguration(isEnabled, startupTime, intervalTime)
    }
  }
}
