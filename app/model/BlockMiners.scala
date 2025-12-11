package model

import play.api.libs.json._

/**
  * Miner information associated with a Lithos block
  * @param propBytesHash Hexadecimal representation of hashed proposition bytes of the miner
  */
case class BlockMiners(
  propBytesHash: String
)

object BlockMiners {
  implicit lazy val blockMinersJsonFormat: Format[BlockMiners] = Json.format[BlockMiners]
}

