package model

import play.api.libs.json._

/**
  * Collateral UTXO which is to be used by Lithos while mining
  * @param id Id of this collateral UTXO
  * @param reward Block reward this collateral UTXO covers
  * @param lender Address of lender who created this collateral UTXO
  * @param fee Fee that this collateral UTXO charges from the entire pool
  * @param creationHeight Height this collateral UTXO was made
  */
case class CollateralUTXO(
  id: String,
  reward: Long,
  lender: String,
  fee: Long,
  creationHeight: Int
)

object CollateralUTXO {
  implicit lazy val collateralUTXOJsonFormat: Format[CollateralUTXO] = Json.format[CollateralUTXO]
}

