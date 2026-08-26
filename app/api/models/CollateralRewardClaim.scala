package api.models

import play.api.libs.json._

/** One broadcast batch of a reward sweep. */
case class CollateralRewardClaimedChunk(txId: String, boxes: Int, nanoErgs: String)

object CollateralRewardClaimedChunk {
  implicit lazy val jsonFormat: Format[CollateralRewardClaimedChunk] =
    Json.format[CollateralRewardClaimedChunk]
}

/**
 * Result of claiming unlocked coinbase rewards to the primary address.
 *
 * Boxes are never dropped for being small — consolidating them is the point — so an empty `claims`
 * means another claim held the leases, not that nothing was eligible.
 */
case class CollateralRewardClaimResult(claims: Seq[CollateralRewardClaimedChunk],
                                       claimedNanoErgs: String)

object CollateralRewardClaimResult {
  implicit lazy val jsonFormat: Format[CollateralRewardClaimResult] =
    Json.format[CollateralRewardClaimResult]
}
