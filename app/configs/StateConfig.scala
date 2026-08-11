package configs

import lfsm.ScriptGenerator
import play.api.{ConfigLoader, Configuration}

class StateConfig(config: Configuration) {
  private val basePath: Option[String] = config.getOptional("state.basePath")(ConfigLoader.stringLoader)
  ScriptGenerator.BASE_PATH    = basePath.getOrElse("")
  val disableTransforms: Option[Boolean] = config.getOptional("state.disableTransforms")(ConfigLoader.booleanLoader)
  val autoCommit: Option[Boolean] = config.getOptional("state.autoCommit")(ConfigLoader.booleanLoader)
  // `state.autoCollateralize` / `state.maxCollateral` are now `emission.autoCollateralize` /
  // `emission.maxJoinsPerRun`: the collateral queue is driven by EmissionHandler on its own timer,
  // not by the rollup submission pipeline.
}
