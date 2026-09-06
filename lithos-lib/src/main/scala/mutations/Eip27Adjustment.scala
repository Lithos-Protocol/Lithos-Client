package work.lithos.mutations

import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.ErgoBox.{R2, STokensRegType}
import org.ergoplatform.appkit.{NetworkType, Parameters, UnsignedTransaction}
import sigma.ast._
import sigma.ast.syntax._

import scala.collection.JavaConverters._

object MainnetEip27Constants {
  final val TokenId = "d9a2cc8a09abfaed87afacfbb7daee79a6b26f10c6613fc13d3f3953e5521d1a"
  final val ReemissionNft = "d3feeffa87f2df63a7a15b4905e618ae3ce4c69a7975f171bd314d0b877927b8"
  final val EmissionNft = "20fa2bf23962cdf51b07722d6237c0c7b8a44f78856c0f7ec308dc1ef1a92a51"
  final val ActivationHeight = 777217

  val Proxy: Contract = {
    val tokens = OptionGet(ExtractRegisterAs(ByIndex(Outputs, IntConstant(0)), R2)(STokensRegType))
    val nft = SelectField(ByIndex(tokens, IntConstant(0)), 1.toByte)
    Contract(ErgoTree.fromProposition(ErgoTree.defaultHeaderWithVersion(1),
      EQ(nft, ByteArrayConstant(Hex.decode(ReemissionNft)))))
  }
}

/** Adjusts ordinary mainnet wallet spends; emission-box transitions have separate consensus rules. */
object Eip27Adjustment {
  def obligation(inputs: Seq[InputUTXO], network: NetworkType): Long = {
    if (network != NetworkType.MAINNET) return 0L
    val amount = inputs.flatMap(_.tokens).filter(_.id.toString == MainnetEip27Constants.TokenId)
      .foldLeft(0L)((sum, token) => Math.addExact(sum, token.amount))
    require(amount == 0 || !inputs.exists(_.tokens.exists(_.id.toString == MainnetEip27Constants.EmissionNft)),
      "ordinary wallet spending cannot consume the Ergo emission box")
    amount
  }

  def adjust(inputs: Seq[InputUTXO], outputs: Seq[UTXO], burn: Seq[Token], fee: Long,
             network: NetworkType): (Seq[UTXO], Seq[Token]) = {
    val amount = obligation(inputs, network)
    if (amount == 0L) return outputs -> burn
    require(!outputs.exists(_.tokens.exists(_.id.toString == MainnetEip27Constants.TokenId)),
      "re-emission tokens cannot be transferred")
    require(!burn.exists(_.id.toString == MainnetEip27Constants.TokenId), "duplicate re-emission burn")
    require(!outputs.exists(_.contract == MainnetEip27Constants.Proxy), "duplicate re-emission payment")
    require(amount >= Parameters.MinChangeValue, "re-emission payment is below minimum box value")
    val valueIn = inputs.foldLeft(0L)((n, b) => Math.addExact(n, b.value))
    val valueOut = outputs.foldLeft(Math.addExact(amount, fee))((n, b) => Math.addExact(n, b.value))
    require(valueIn >= valueOut, "inputs do not cover payments, fee and re-emission obligation")
    (outputs :+ UTXO(MainnetEip27Constants.Proxy, amount)) ->
      (burn :+ Token(MainnetEip27Constants.TokenId, amount))
  }

  def validate(tx: UnsignedTransaction, network: NetworkType): Unit = {
    if (network != NetworkType.MAINNET) return
    val amount = obligation(tx.getInputs.asScala.toSeq.map(InputUTXO(_)), network)
    if (amount > 0L) {
      val outputs = tx.getOutputs.asScala.toSeq
      require(!outputs.exists(_.getTokens.asScala.exists(_.getId.toString == MainnetEip27Constants.TokenId)),
        "completed transaction retains re-emission tokens")
      val payments = outputs.filter(_.getErgoTree == MainnetEip27Constants.Proxy.ergoTree)
      require(payments.size == 1 && payments.head.getValue == amount,
        "completed transaction has an incorrect re-emission payment")
    }
  }
}
