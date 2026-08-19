package api.models

import play.api.libs.json._

/**
 * A liquidity provision box, with both what it has earned and what it can currently be paid.
 *
 * `accrued*` is what the provision has earned according to the pool, unflushed fees included, and is
 * the figure to show a provider as "earned". `claimable*` is what the vault can actually pay right
 * now. `unflushed*` is the difference — money a flush would release.
 *
 * `awaitingFlush` is set when the provision's entry has run AHEAD of the vault's accumulator, which
 * a deposit or a resize does routinely on a pool with unflushed fees. The contract's own arithmetic
 * yields a negative figure there;
 */
case class LDProvision(boxId: String,
                       ownerNFT: String,
                       shares: String,
                       entryX: String,
                       entryY: String,
                       value: String,
                       createdHeight: Int,
                       shareOfSupply: Double,
                       accruedX: String,
                       accruedY: String,
                       claimableX: String,
                       claimableY: String,
                       unflushedX: String,
                       unflushedY: String,
                       canClaim: Boolean,
                       awaitingFlush: Boolean)

object LDProvision {
  implicit lazy val ldProvisionJsonFormat: Format[LDProvision] = Json.format[LDProvision]
}

case class LDProvisionList(provisions: Seq[LDProvision])

object LDProvisionList {
  implicit lazy val ldProvisionListJsonFormat: Format[LDProvisionList] = Json.format[LDProvisionList]
}

/**
 * Bounds for a claim. Every field is optional and the whole body may be omitted, since a claim is
 * paid what the vault says is owed rather than what the caller asks for — but the vault moves, so a
 * caller that quoted a figure can still pin it.
 *
 * @param expectedVaultBoxId     the vault box the caller was quoted against
 * @param expectedProvisionBoxId the provision box the caller was quoted against
 * @param minAmountX             least the caller will accept on the ERG side
 */
case class LDClaimRequest(expectedVaultBoxId: Option[String] = None,
                          expectedProvisionBoxId: Option[String] = None,
                          minAmountX: Option[String] = None,
                          minAmountY: Option[String] = None)

object LDClaimRequest {
  val Empty: LDClaimRequest = LDClaimRequest()

  implicit lazy val ldClaimRequestJsonFormat: Format[LDClaimRequest] = Json.format[LDClaimRequest]
}

/** @param successorBoxId the provision box this claim creates; the old id is spent */
case class LDClaimResult(claimedX: String,
                         claimedY: String,
                         successorBoxId: String,
                         txId: String)

object LDClaimResult {
  implicit lazy val ldClaimResultJsonFormat: Format[LDClaimResult] = Json.format[LDClaimResult]
}
