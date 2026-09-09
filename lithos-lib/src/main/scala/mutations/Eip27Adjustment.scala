package work.lithos.mutations

import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.ErgoBox.{R2, STokensRegType}
import org.ergoplatform.appkit.{NetworkType, Parameters, UnsignedTransaction}
import sigma.ast._
import sigma.ast.syntax._

import scala.collection.JavaConverters._

/**
 * Local devnet re-emission constants for the mainnet-compatible client. These are consensus values, not token metadata or configuration:
 * they are compiled in, and a wrong one is caught by the pinned fixtures rather than at runtime.
 */
object MainnetEip27Constants {
  final val TokenId = "a6f99adfd627fefe23dbd022af1b03f0433eea96856b1c79f4cdb8952ea45a31"
  final val ReemissionNft = "606f01a9634f5848ed22eef74b1ba9041673a9a1fb7f357cef80947d01dc43ce"
  final val EmissionNft = "8be30bafab107fee3400ff25dde892f39f0149364e2c33a3ee12a66d1a64d36f"
  final val ActivationHeight = 10

  /**
   * The pay-to-re-emission proposition: the first output's first token must be the re-emission NFT.
   * Consensus compares this exact ErgoTree, so its hex is pinned in the fixtures rather than trusted
   * to reconstruct identically.
   */
  val Proxy: Contract = {
    val tokens = OptionGet(ExtractRegisterAs(ByIndex(Outputs, IntConstant(0)), R2)(STokensRegType))
    val nft = SelectField(ByIndex(tokens, IntConstant(0)), 1.toByte)
    Contract(ErgoTree.fromProposition(ErgoTree.defaultHeaderWithVersion(1),
      EQ(nft, ByteArrayConstant(Hex.decode(ReemissionNft)))))
  }
}

/** Adjusts ordinary mainnet wallet spends; emission-box transitions have separate consensus rules. */
object Eip27Adjustment {

  /**
   * NanoERG this input set must pay to the proxy: the total re-emission token amount it carries,
   * which the transaction must also burn in full. Zero off mainnet and when no such token is present.
   */
  def obligation(inputs: Seq[InputUTXO], network: NetworkType): Long = {
    if (network != NetworkType.MAINNET) return 0L
    val amount = inputs.flatMap(_.tokens).filter(_.id.toString == MainnetEip27Constants.TokenId)
      .foldLeft(0L)((sum, token) => Math.addExact(sum, token.amount))
    require(amount == 0 || !inputs.exists(_.tokens.exists(_.id.toString == MainnetEip27Constants.EmissionNft)),
      "ordinary wallet spending cannot consume the Ergo emission box")
    amount
  }

  /**
   * Append the proxy payment and the token burn when the selected inputs carry re-emission tokens.
   * Runs before change is computed, since both reduce what the inputs have left to give.
   */
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

  /**
   * Recheck the finished transaction: no output keeps a re-emission token, and exactly one pays the
   * exact obligation to the proxy tree. Catches a builder that reshaped outputs after [[adjust]].
   */
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
