package state.messages

import org.ergoplatform.restapi.client.{ErgoTransactionDataInput, ErgoTransactionInput}
import state.InputSpendingProof

case class TxInput(
                    id: String,
                    spendingProof: Option[InputSpendingProof]
                  )

object TxInput {
  def fromSyncInput(eti: ErgoTransactionInput): TxInput = {
    TxInput(eti.getBoxId, Some(InputSpendingProof.fromSync(eti.getSpendingProof)))
  }

  def fromSyncDataInput(etdi: ErgoTransactionDataInput): TxInput = {
    TxInput(etdi.getBoxId, None)
  }
}
