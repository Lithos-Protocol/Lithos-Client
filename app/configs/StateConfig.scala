package configs

import play.api.{ConfigLoader, Configuration}

class StateConfig(config: Configuration) {
  val disableTransforms: Option[Boolean] = config.getOptional("state.disableTransforms")(ConfigLoader.booleanLoader)
  /** Register and commit `stratum.diff` on chain. Off unless the key says otherwise, as shipped. */
  val autoCommit: Boolean =
    config.getOptional("state.autoCommit")(ConfigLoader.booleanLoader).getOrElse(StateConfig.DefaultAutoCommit)
}

object StateConfig {
  /** Mirrors `state.autoCommit` in application.conf. */
  final val DefaultAutoCommit: Boolean = false
}
