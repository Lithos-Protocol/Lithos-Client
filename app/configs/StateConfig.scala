package configs

import lfsm.ScriptGenerator
import play.api.{ConfigLoader, Configuration}

class StateConfig(config: Configuration) {
  val disableTransforms: Option[Boolean] = config.getOptional("state.disableTransforms")(ConfigLoader.booleanLoader)
  val autoCommit: Option[Boolean] = config.getOptional("state.autoCommit")(ConfigLoader.booleanLoader)
}
