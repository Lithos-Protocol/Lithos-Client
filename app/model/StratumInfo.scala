package model

import play.api.libs.json._

/**
  * Information about the Stratum server
  * @param diff Difficulty used for Lithos calculations
  * @param tau Tau representation of the mining diff
  * @param reducedMessaging Whether the stratum server has the `reduceShareMessages` setting enabled
  * @param superSharesPerHour Number of super shares produced by the mining client within the last hour
  */
case class StratumInfo(
  diff: String,
  tau: BigInt,
  reducedMessaging: Boolean,
  superSharesPerHour: Double
)

object StratumInfo {
  implicit lazy val stratumInfoJsonFormat: Format[StratumInfo] = Json.format[StratumInfo]
}

