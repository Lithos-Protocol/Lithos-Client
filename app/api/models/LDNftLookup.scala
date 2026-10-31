package api.models

import play.api.libs.json._

/** Token ids to check as provision ownership NFTs, at most 100 per request. */
case class LDNftLookupRequest(tokenIds: Seq[String])

object LDNftLookupRequest {
  implicit lazy val ldNftLookupRequestJsonFormat: Format[LDNftLookupRequest] = Json.format[LDNftLookupRequest]
}

/**
 * One token checked as a provision's ownership NFT. When it owns one, `pool` is the pool the provision is in,
 * `provision` the provision as it stands, and `amountX` / `amountY` its share of the pool's reserves now.
 */
case class LDNftLookup(tokenId: String,
                       provisionNft: Boolean,
                       pool: Option[LDPoolSummary] = None,
                       provision: Option[LDProvision] = None,
                       amountX: Option[String] = None,
                       amountY: Option[String] = None)

object LDNftLookup {
  implicit lazy val ldNftLookupJsonFormat: Format[LDNftLookup] = Json.format[LDNftLookup]
}

case class LDNftLookupList(nfts: Seq[LDNftLookup])

object LDNftLookupList {
  implicit lazy val ldNftLookupListJsonFormat: Format[LDNftLookupList] = Json.format[LDNftLookupList]
}
