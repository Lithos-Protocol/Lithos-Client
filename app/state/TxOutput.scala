package state

import org.ergoplatform.appkit.{BlockchainContext, ErgoId, ErgoValue, JavaHelpers}
import org.ergoplatform.restapi.client.ErgoTransactionOutput
import sigma.ast.ErgoTree
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

case class TxOutput(
                     id: String,
                     value: Long,
                     ergoTree: String,
                     registers: Seq[String],
                     assets: Seq[Token],
                     txId: String,
                     index: Int
                   ) {
  def toUTXO(ctx: BlockchainContext): UTXO = {
    UTXO(Contract(ErgoTree.fromHex(ergoTree)), value, assets, registers.map(ErgoValue.fromHex))
  }

  def toInput(ctx: BlockchainContext): InputUTXO = {
    toUTXO(ctx).toInput(ctx, ErgoId.create(txId), index.toShort)
  }
}

object TxOutput {
  def fromSync(eto: ErgoTransactionOutput): TxOutput = {
    val regHexes = for (i <- 4 to 9) yield eto.getAdditionalRegisters.getOrDefault("R" + i, "none")
    val validRegHexes = regHexes.filter(_ != "none")

    val assets = for (a <- JavaHelpers.toIndexedSeq(eto.getAssets)) yield Token(a.getTokenId, a.getAmount.longValue())
    TxOutput(eto.getBoxId, eto.getValue, eto.getErgoTree, validRegHexes, assets, eto.getTransactionId, eto.getIndex)
  }
}
