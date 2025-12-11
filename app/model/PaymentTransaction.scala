package model

import play.api.libs.json._

  /**
  * Transaction paid to miner by Lithos payout contract
  * @param transactionId Transaction Id of this mining reward payment
  * @param payoutUTXO UTXO id of the Lithos payout contract associated with this payment
  * @param amount Amount of ERG paid to miner
  * @param score Score associated with this miner's NISP, used to determine payout amount
  * @param paymentHeight Height that this payment was sent to miner
  * @param blockId Id of the block processed by Lithos and associated with this reward
  * @param minedBlockHeight Height of the block processed by Lithos, and associated with this reward.
   */
case class PaymentTransaction(
  transactionId: String,
  payoutUTXO: String,
  amount: Long,
  score: Long,
  paymentHeight: Int,
  blockId: String,
  minedBlockHeight: Int
)

object PaymentTransaction {
  implicit lazy val paymentTransactionJsonFormat: Format[PaymentTransaction] = Json.format[PaymentTransaction]
}

