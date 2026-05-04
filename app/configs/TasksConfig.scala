package configs

import play.api.Configuration

import scala.concurrent.duration.{Duration, FiniteDuration}

class TasksConfig(config: Configuration){
  val stratumServerTaskConfig:   TasksConfig.TaskConfiguration =
    TasksConfig.TaskConfiguration.fromConfig(config, "stratum-server")
  val rollupSyncTask:   TasksConfig.TaskConfiguration =
    TasksConfig.TaskConfiguration.fromConfig(config, "rollup-sync-task")
  val dictionarySyncTask:   TasksConfig.TaskConfiguration =
    TasksConfig.TaskConfiguration.fromConfig(config, "dictionary-sync-task")
  val dexSyncTask:   TasksConfig.TaskConfiguration =
    TasksConfig.TaskConfiguration.fromConfig(config, "dex-sync-task")
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
