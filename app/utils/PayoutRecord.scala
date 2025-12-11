package utils

case class PayoutRecord(txId: String, amount: Long, score: Long, utxoId: String, blockId: String,
                        minedHeight: Int, creationHeight: Int)
