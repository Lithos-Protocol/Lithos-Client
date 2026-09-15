package configs

import play.api.{ConfigLoader, Configuration}

/**
 * The `lithosdex.orders` block: the fees an order placed through the API carries when the request names
 * none. Checked per request rather than at startup, so a bad value refuses orders instead of the client.
 *
 * @param executorFeeNanoErg nanoERG the order pays whoever fills it
 * @param maxMinerFeeNanoErg most of that fee a fill may spend as a miner fee
 */
case class LithosDexOrdersConfig(executorFeeNanoErg: Long, maxMinerFeeNanoErg: Long)

object LithosDexOrdersConfig {

  /** Mirrors `lithosdex.orders` in application.conf; keep the two in step. */
  val Default: LithosDexOrdersConfig = LithosDexOrdersConfig(executorFeeNanoErg = 3000000L, maxMinerFeeNanoErg = 1000000L)

  def apply(config: Configuration): LithosDexOrdersConfig = {
    def read(key: String, default: Long): Long =
      config.getOptional(s"lithosdex.orders.$key")(ConfigLoader.longLoader).getOrElse(default)
    LithosDexOrdersConfig(
      read("executorFeeNanoErg", Default.executorFeeNanoErg),
      read("maxMinerFeeNanoErg", Default.maxMinerFeeNanoErg))
  }
}
