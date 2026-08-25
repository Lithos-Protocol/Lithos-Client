package configs

import play.api.Configuration

import scala.concurrent.duration.FiniteDuration

class TasksConfig(config: Configuration){
  val stratumServerTaskConfig:   TasksConfig.TaskConfiguration =
    TasksConfig.TaskConfiguration.fromConfig(config, TasksConfig.StratumServer)
  val rollupSyncTask:   TasksConfig.TaskConfiguration =
    TasksConfig.TaskConfiguration.fromConfig(config, TasksConfig.RollupSync)
  val dictionarySyncTask:   TasksConfig.TaskConfiguration =
    TasksConfig.TaskConfiguration.fromConfig(config, TasksConfig.DictionarySync)
}

object TasksConfig {

  final val StratumServer:  String = "stratum-server"
  final val RollupSync:     String = "rollup-sync-task"
  final val DictionarySync: String = "dictionary-sync-task"

  /** Every task block the client reads. `Configs.validateAll` checks exactly these, so the list
   * lives here rather than being written out again beside the validator. */
  final val Names: Seq[String] = Seq(StratumServer, RollupSync, DictionarySync)

  def key(name: String, leaf: String): String = s"lithos-tasks.$name.$leaf"

  case class TaskConfiguration(enabled: Boolean, startup: FiniteDuration, interval: FiniteDuration)
  object TaskConfiguration {
    def fromConfig(configuration: Configuration, name: String): TaskConfiguration = {
      val isEnabled = configuration.get[Boolean](key(name, "enabled"))
      val startupTime = configuration.get[FiniteDuration](key(name, "startup"))
      val intervalTime = configuration.get[FiniteDuration](key(name, "interval"))
      TaskConfiguration(isEnabled, startupTime, intervalTime)
    }
  }

  /**
   * One task's enabled flag, tolerant of the key being absent.
   *
   * The constructor above reads with `get`, which throws — right for the startup wiring, where a
   * missing task block should stop the client. Wrong anywhere the answer only picks a log message:
   * there the throw costs the caller its actual work, and false is the safe reading of a task block
   * that is not there.
   */
  def isEnabled(configuration: Configuration, name: String): Boolean =
    configuration.getOptional[Boolean](key(name, "enabled")).getOrElse(false)
}
