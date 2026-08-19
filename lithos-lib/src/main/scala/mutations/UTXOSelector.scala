package mutations

import org.ergoplatform.sdk.ErgoId
import work.lithos.mutations.InputUTXO

trait UTXOSelector {
  def getInputs(value: Long): Seq[InputUTXO]

  def getInputs(value: Long, tokenId: ErgoId, amount: Long): Seq[InputUTXO]
}
