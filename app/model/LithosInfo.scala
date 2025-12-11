package model

import play.api.libs.json._

/**
  * Information about this Lithos client and the current LFSM state
  * @param numPoolBlocks Number of tracked blocks currently being processed by Lithos contracts
  * @param synced Whether this Lithos client has synced to the blockchain
  */
case class LithosInfo(
  numPoolBlocks: Int,
  synced: Boolean
)

object LithosInfo {
  implicit lazy val lithosInfoJsonFormat: Format[LithosInfo] = Json.format[LithosInfo]
}

