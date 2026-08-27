package api.models

import play.api.libs.json._

/**
 * A confirmed payment from the Lithos payout contract to the local miner.
 *
 * @param transactionId payout transaction identifier
 * @param payoutUTXO spent payout-contract box identifier
 * @param amount amount paid in nanoERG
 * @param score associated NISP score, when independently known
 * @param paymentHeight payout transaction inclusion height
 * @param blockId rewarded Lithos block identifier, when known; not the containing chain block
 * @param minedBlockHeight rewarded Lithos block height, when known
 */
case class PaymentTransaction(
  transactionId: String,
  payoutUTXO: String,
  amount: Long,
  score: Option[Long],
  paymentHeight: Int,
  blockId: Option[String],
  minedBlockHeight: Option[Int]
)

object PaymentTransaction {
  implicit lazy val paymentTransactionJsonFormat: Format[PaymentTransaction] = Json.format[PaymentTransaction]
}

