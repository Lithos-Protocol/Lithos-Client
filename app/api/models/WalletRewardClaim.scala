package api.models

import play.api.libs.json._

/** One broadcast batch of a reward sweep. */
case class WalletRewardClaimedChunk(txId: String, boxes: Int, nanoErgs: String, outcome: String = "accepted")

object WalletRewardClaimedChunk {
  implicit lazy val jsonFormat: Format[WalletRewardClaimedChunk] =
    Json.format[WalletRewardClaimedChunk]
}

/**
 * Result of claiming unlocked coinbase rewards to the primary address.
 *
 * Boxes are never dropped for being small — consolidating them is the point — so an empty `claims`
 * means another claim held the leases, not that nothing was eligible.
 */
case class WalletRewardClaimResult(claims: Seq[WalletRewardClaimedChunk],
                                       claimedNanoErgs: String)

object WalletRewardClaimResult {
  implicit lazy val jsonFormat: Format[WalletRewardClaimResult] =
    Json.format[WalletRewardClaimResult]
}
