package model

import play.api.libs.json._

/**
  * Representation of a 10 super share NISP
  * @param score Score associated with this NISP, to be used in payout calculations
  * @param height Height all super-shares in this NISP must be under
  * @param headers Header bytes associated with each super-share in this NISP
  * @param bytes Serialized (hex-encoded) bytes of this NISP
  */
case class NISPRepresentation(
  score: Long,
  height: Int,
  headers: List[String],
  bytes: String
)

object NISPRepresentation {
  implicit lazy val NISPRepresentationJsonFormat: Format[NISPRepresentation] = Json.format[NISPRepresentation]
}

