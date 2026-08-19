package api.models

import play.api.libs.json._

/**
 * State of the LithosDex fee vault.
 *
 * Its accumulators are as of the last flush and therefore trail the pool's — the difference is fees
 * the vault does not yet hold and cannot pay out. `payableX` excludes `vaultMin`, which is held back
 * so the box survives storage rent and is never paid to a claimant.
 */
case class LDVaultInfo(vaultNFT: String,
                       utxoId: String,
                       accX: String,
                       accY: String,
                       balanceX: String,
                       balanceY: String,
                       payableX: String,
                       payableY: String,
                       tokenY: Option[String],
                       vaultMin: String,
                       lastFlushHeight: Option[Int],
                       syncHeight: Int)

object LDVaultInfo {
  implicit lazy val ldVaultInfoJsonFormat: Format[LDVaultInfo] = Json.format[LDVaultInfo]
}
