package configs

import lfsm.ScriptGenerator
import play.api.{ConfigLoader, Configuration}

class StateConfig(config: Configuration) {
  private val basePath: Option[String] = config.getOptional("state.basePath")(ConfigLoader.stringLoader)
  ScriptGenerator.BASE_PATH    = basePath.getOrElse("")
}
