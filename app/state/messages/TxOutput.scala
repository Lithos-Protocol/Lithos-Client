package state.messages

import org.ergoplatform.appkit.{BlockchainContext, ErgoValue}
import org.ergoplatform.sdk.ErgoId
import sigma.ast.ErgoTree
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

case class TxOutput(
                     id: String,
                     value: Long,
                     ergoTree: String,
                     registers: Seq[String],
                     assets: Seq[Token],
                     txId: String,
                     creationHeight: Int,
                     index: Int
                   ) {
  def toUTXO(ctx: BlockchainContext): UTXO = {
    UTXO(Contract(ErgoTree.fromHex(ergoTree)), value, assets, registers.map(ErgoValue.fromHex), creationHeight = Some(creationHeight))
  }

  def toInput(ctx: BlockchainContext): InputUTXO = {
    toUTXO(ctx).toInput(ctx, ErgoId.create(txId), index.toShort)
  }
}
