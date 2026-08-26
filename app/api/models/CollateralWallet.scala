package api.models

import play.api.libs.json._

/** One wallet address that may hold a lending position. */
case class CollateralLenderKeyStatus(address: String,
                                     status: String,
                                     position: Option[Long])

object CollateralLenderKeyStatus {
  implicit lazy val jsonFormat: Format[CollateralLenderKeyStatus] =
    Json.format[CollateralLenderKeyStatus]

  /** No live box and nothing else holding it: a join may use this key now. */
  final val Free = "FREE"
  /** Holds a queue box waiting for activation. */
  final val Queued = "QUEUED"
  /** Holds a live collateral box, so it occupies a slot in the active set. */
  final val Active = "ACTIVE"
  /**
   * Spoken for without a box to point at: its collateral box was spent recently and the retirement
   * is not yet deep enough to rely on. A join must not use it, so it is not FREE - reporting it as
   * free is how a caller gets offered a position the join then refuses.
   */
  final val Retiring = "RETIRING"
}

/**
 * One of this wallet's positions, queued or live.
 *
 * A queued position reports the permit it paid. A live box carries its block's emission LIT and the
 * permit together, indistinguishable on-chain, so it reports `carriedLit` and no permit figure —
 * the two numbers mean different things and one field cannot hold both.
 */
case class CollateralPositionSummary(kind: String,
                                     boxId: String,
                                     position: Option[Long],
                                     principalNanoErgs: String,
                                     permitLit: Option[String],
                                     carriedLit: Option[String],
                                     creationHeight: Int)

object CollateralPositionSummary {
  implicit lazy val jsonFormat: Format[CollateralPositionSummary] =
    Json.format[CollateralPositionSummary]
}

/** This wallet's locked coinbase rewards. */
case class CollateralRewardSummary(boxes: Int,
                                   unlockedBoxes: Int,
                                   totalNanoErgs: String,
                                   unlockedNanoErgs: String,
                                   blocksUntilFirstUnlock: Option[Int])

object CollateralRewardSummary {
  implicit lazy val jsonFormat: Format[CollateralRewardSummary] =
    Json.format[CollateralRewardSummary]
}

/**
 * This wallet's side of the collateral market.
 *
 * `freeLenderKeys` and `keysDerivable` are separate because they gate a join differently. A client
 * with no free key but room under `maxLenderKeys` derives one on demand and is not blocked; one at
 * its ceiling genuinely is. Collapsing them into a single "keys available" number reports the
 * second case as the first.
 */
case class WalletCollateralStatus(autoCollateralize: Boolean,
                                  maxOwnCollateral: Int,
                                  maxJoinsPerRun: Int,
                                  maxLenderKeys: Int,
                                  maxPermitPerJoinLit: String,
                                  ownPositionsQueued: Int,
                                  ownPositionsLive: Int,
                                  ownBudgetRemaining: Int,
                                  freeLenderKeys: Int,
                                  keysDerivable: Int,
                                  lenderKeys: Seq[CollateralLenderKeyStatus],
                                  positions: Seq[CollateralPositionSummary],
                                  rewards: CollateralRewardSummary)

object WalletCollateralStatus {
  implicit lazy val jsonFormat: Format[WalletCollateralStatus] =
    Json.format[WalletCollateralStatus]
}
