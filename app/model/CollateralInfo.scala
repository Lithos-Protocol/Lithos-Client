package model

import play.api.libs.json._

/**
  * Information needed to create collateral UTXOs
  * @param reward Block reward this collateral UTXO covers
  * @param fee Fee that this collateral UTXO charges from the entire pool
  * @param numUtxos Number of collateral UTXOs to create in this transaction
  */
case class CollateralInfo(
  reward: Long,
  fee: Long,
  numUtxos: Int
)

object CollateralInfo {
  implicit lazy val collateralInfoJsonFormat: Format[CollateralInfo] = Json.format[CollateralInfo]
}

