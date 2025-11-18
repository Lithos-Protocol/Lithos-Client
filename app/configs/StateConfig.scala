package configs

import lfsm.ScriptGenerator
import play.api.Configuration

class StateConfig(config: Configuration) {
  private val basePath: String = config.get[String]("state.basePath")
  ScriptGenerator.BASE_PATH    = basePath
}
