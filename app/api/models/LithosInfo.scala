package api.models

import play.api.libs.json._

/**
  * Information about this Lithos client and the current LFSM state
  *
  * @param numPoolBlocks Number of tracked blocks currently being processed by Lithos contracts
  * @param synced Whether canonical synchronization is caught up and usable
  * @param status Name of the detailed synchronization state
  * @param height Height of the last committed block, when one has committed
  * @param blockId Id of the last committed block, when one has committed
  * @param canonicalReason Why canonical state is unusable, when it is not
  * @param minerDictionaryAvailable Whether the Miner Dictionary can be read and committed against
  * @param minerDictionaryReason Why the Miner Dictionary is unusable, when it is not
  * @param mempoolAvailable Whether unconfirmed projections are available
  * @param mempoolReason Why unconfirmed projections are unavailable, when they are not
  */
case class LithosInfo(
  numPoolBlocks: Int,
  synced: Boolean,
  status: String,
  height: Option[Int],
  blockId: Option[String],
  canonicalReason: Option[String],
  minerDictionaryAvailable: Boolean,
  minerDictionaryReason: Option[String],
  mempoolAvailable: Boolean,
  mempoolReason: Option[String]
)

object LithosInfo {
  implicit lazy val lithosInfoJsonFormat: Format[LithosInfo] = Json.format[LithosInfo]
}
