package model

import play.api.libs.json._

/**
  * Transaction which was successfully sent to the node
  * @param txId Hex-encoded id of this transaction
  * @param height Height this transaction was sent
  */
case class SuccessfulTransaction(
  txId: String,
  height: Int
)

object SuccessfulTransaction {
  implicit lazy val successfulTransactionJsonFormat: Format[SuccessfulTransaction] = Json.format[SuccessfulTransaction]
}

