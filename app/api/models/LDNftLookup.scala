package api.models

import play.api.libs.json._

/** Token ids to check as provision ownership NFTs, at most 100 per request. */
case class LDNftLookupRequest(tokenIds: Seq[String])

object LDNftLookupRequest {
  implicit lazy val ldNftLookupRequestJsonFormat: Format[LDNftLookupRequest] = Json.format[LDNftLookupRequest]
}

/**
 * How a provision's NFT was minted: by a deposit, or by its pool's genesis for the pool's creator.
 *
 * @param amountX what went in on the X side, ERG for an ERG/token pool. For a genesis, the whole reserve
 */
case class LDNftMint(height: Int, shares: String, amountX: String, amountY: String, genesis: Boolean)

object LDNftMint {
  implicit lazy val ldNftMintJsonFormat: Format[LDNftMint] = Json.format[LDNftMint]
}

/**
 * One token checked as a provision's ownership NFT. When it owns one, `pool` is the pool the provision is in,
 * `provision` the provision as it stands, and `amountX` / `amountY` its share of the pool's reserves now. A
 * `redeemed` NFT owned a provision since closed: `pool` and `minted` say what it was, and the rest is absent.
 *
 * @param lastClaimedHeight the block that last recreated the provision with moved entries; absent if never claimed
 * @param refreshBountyNanoErg what a refresh may take from a token/token provision's ERG now
 * @param refreshableHeight the first block anyone may refresh the provision box, renewing its age
 */
case class LDNftLookup(tokenId: String,
                       provisionNft: Boolean,
                       pool: Option[LDPoolSummary] = None,
                       provision: Option[LDProvision] = None,
                       amountX: Option[String] = None,
                       amountY: Option[String] = None,
                       redeemed: Boolean = false,
                       minted: Option[LDNftMint] = None,
                       lastClaimedHeight: Option[Int] = None,
                       refreshBountyNanoErg: Option[String] = None,
                       refreshableHeight: Option[Int] = None)

object LDNftLookup {
  implicit lazy val ldNftLookupJsonFormat: Format[LDNftLookup] = Json.using[Json.WithDefaultValues].format[LDNftLookup]
}

case class LDNftLookupList(nfts: Seq[LDNftLookup])

object LDNftLookupList {
  implicit lazy val ldNftLookupListJsonFormat: Format[LDNftLookupList] = Json.format[LDNftLookupList]
}
