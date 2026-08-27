package state.messages


case class TxInput(
                    id: String,
                    spendingProof: Option[InputSpendingProof]
                  )
