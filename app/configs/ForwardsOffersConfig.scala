package configs

import play.api.{ConfigLoader, Configuration}

/**
 * The `forwards.offers` block: the size limit on short windows for offers this client creates or fills.
 * Checked per request rather than at startup, so a bad value refuses requests instead of the client.
 *
 * @param shortWindowEpochs         windows shorter than this many epochs are limited
 * @param shortWindowMaxNotionalLit most notional, in whole LIT, such a window may carry
 */
case class ForwardsOffersConfig(shortWindowEpochs: Int, shortWindowMaxNotionalLit: Long) {

  /** The largest notional, in LIT base units, for a window `windowLength` epochs long, or None for no limit. */
  def maxNotional(windowLength: Int): Option[Long] =
    if (windowLength >= shortWindowEpochs) None
    else Some((BigInt(shortWindowMaxNotionalLit) * ForwardsOffersConfig.LitUnit).min(BigInt(Long.MaxValue)).toLong)

  /** Why these values cannot be used, naming the key, or None when they can. */
  def invalid: Option[String] =
    if (shortWindowEpochs < 1 || shortWindowEpochs > forwards.DFHelpers.MAX_WINDOW + 1)
      Some(s"forwards.offers.shortWindowEpochs is $shortWindowEpochs, outside 1..${forwards.DFHelpers.MAX_WINDOW + 1}")
    else if (shortWindowMaxNotionalLit < 0)
      Some(s"forwards.offers.shortWindowMaxNotionalLit is $shortWindowMaxNotionalLit, below 0")
    else None
}

object ForwardsOffersConfig {

  /** Base units in one LIT. */
  final val LitUnit: Long = 1000000000L

  /** Mirrors `forwards.offers` in application.conf; keep the two in step. */
  val Default: ForwardsOffersConfig = ForwardsOffersConfig(shortWindowEpochs = 8, shortWindowMaxNotionalLit = 10000L)

  def apply(config: Configuration): ForwardsOffersConfig =
    ForwardsOffersConfig(
      config.getOptional("forwards.offers.shortWindowEpochs")(ConfigLoader.intLoader)
        .getOrElse(Default.shortWindowEpochs),
      config.getOptional("forwards.offers.shortWindowMaxNotionalLit")(ConfigLoader.longLoader)
        .getOrElse(Default.shortWindowMaxNotionalLit))
}
