package api.models

import play.api.libs.json._

/**
 * One point on the price series.
 *
 * `price` is a display value and is the only float in the `lithosdex` tag that is not a ratio — it
 * carries the token's decimals already applied, so a client can plot it directly. The raw reserves
 * are alongside it so the same client can recompute the price exactly when it needs to rather than
 * trusting a double.
 */
case class LDPricePoint(height: Int,
                        timestamp: Option[Long],
                        price: Double,
                        reservesX: String,
                        reservesY: String)

object LDPricePoint {
  implicit lazy val ldPricePointJsonFormat: Format[LDPricePoint] = Json.format[LDPricePoint]
}

/**
 * @param range     the range actually served, echoed back so a client can tell a default from a choice
 * @param changePct signed fraction across the range; 0.018 is +1.8%
 * @param partial   true when the series does not reach back over the whole range
 */
case class LDPriceHistory(range: String,
                          changePct: Double,
                          partial: Boolean,
                          history: Seq[LDPricePoint])

object LDPriceHistory {
  implicit lazy val ldPriceHistoryJsonFormat: Format[LDPriceHistory] = Json.format[LDPriceHistory]

  /** The ranges a request may ask for, and how many blocks each covers at two minutes a block. */
  val Ranges: Map[String, Int] = Map(
    "1H" -> 30,
    "24H" -> 720,
    "7D" -> 5040,
    "30D" -> 21600)

  val Default: String = "24H"

  /** Roughly how many points a chart wants, which sets the default bucket for each range. */
  val TargetPoints: Int = 60

  /** Ceiling on points in one response, so a small `bucket` override cannot ask for thousands. */
  val MaxPoints: Int = 500
}
