package model

import play.api.libs.json._

/**
  * Information about block mined by Lithos pool contracts
  * @param utxoId UTXO id of Lithos contract currently processing this block
  * @param blockId Header id associated with this block
  * @param blockHeight Height of this Lithos-mined block
  * @param numMiners Number of miners which are currently being processed by this Lithos pool contract
  * @param phase Current LFSM phase of the associated Lithos contract
  */
case class PoolBlock(
  utxoId: String,
  blockId: String,
  blockHeight: Int,
  numMiners: Int,
  phase: String
)

object PoolBlock {
  implicit lazy val poolBlockJsonFormat: Format[PoolBlock] = Json.format[PoolBlock]
}

