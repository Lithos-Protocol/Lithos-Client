package api.models

import play.api.libs.json._

case class OracleFeed(id: String, name: String, network: String, protocol: String, poolNft: String,
                        ergoTreeHash: String, baseAsset: String, quoteAsset: String, rawUnit: String,
                        priceUnit: String, baseDecimals: Int, maxAgeBlocks: Int)
case class OracleFeeds(network: String, feeds: Seq[OracleFeed])

/** Raw value and rational conversion are exact. Price is rounded to 18 decimal places, as a string. */
case class OraclePoint(boxId: String, transactionId: String, creationHeight: Int, inclusionHeight: Int,
                         epoch: Int, rawValue: String, price: String, priceNumerator: String,
                         priceDenominator: String, ergoTreeHash: String, timestamp: Option[Long])

/** Confirmed node snapshot. History is newest first and includes the current box on the first page. */
case class OracleState(feed: OracleFeed, height: Int, blockId: String, indexedHeight: Int,
                         current: OraclePoint, ageBlocks: Int, stale: Boolean,
                         history: Seq[OraclePoint], offset: Int, limit: Int, total: Option[Int],
                         nextOffset: Option[Int], timestampsComplete: Boolean)

object Oracles {
  implicit val oracleFeedFormat: OFormat[OracleFeed] = Json.format[OracleFeed]
  implicit val oracleFeedsFormat: OFormat[OracleFeeds] = Json.format[OracleFeeds]
  implicit val oraclePointFormat: OFormat[OraclePoint] = Json.format[OraclePoint]
  implicit val oracleStateFormat: OFormat[OracleState] = Json.format[OracleState]
}
