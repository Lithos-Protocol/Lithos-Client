package transactions.engine

import org.ergoplatform.appkit.{NetworkType, Parameters}
import org.ergoplatform.sdk.ErgoId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import support.FakeNodeContext
import work.lithos.mutations._

import scala.collection.JavaConverters._

class Eip27AdjustmentSpec extends AnyFlatSpec with Matchers {
  private val token = "a6f99adfd627fefe23dbd022af1b03f0433eea96856b1c79f4cdb8952ea45a31"
  private lazy val fake = FakeNodeContext()

  "EIP-27" should "burn aggregate reward tokens and pay the exact proxy obligation while preserving other assets" in {
    fake._1.getClient.execute { ctx =>
      val wallet = fake._3
      val other = Token("ab" * 32, 9L)
      val reward = Contract(sigma.ast.ErgoTree.fromHex(wallet.rewardTrees.keys.head))
      val a = UTXO(reward, 20000000000L, Seq(Token(token, 12000000000L), other)).setCreationHeight(1)
        .toInput(ctx, ErgoId.create("01" * 32), 0)
      val b = UTXO(reward, 15000000000L, Seq(Token(token, 12000000000L))).setCreationHeight(1)
        .toInput(ctx, ErgoId.create("02" * 32), 0)
      val unsigned = TxBuilder(ctx).setInputs(a, b)
        .setOutputs(UTXO(wallet.contract, 10999000000L, Seq(other)), UTXO.feeBox(1000000L))
        .buildTx(0, wallet.p2pk)
      val signed = wallet.sign(unsigned)
      val outputs = signed.getOutputsToSpend.asScala.toSeq
      outputs.flatMap(_.getTokens.asScala).exists(_.getId.toString == token) shouldBe false
      outputs(0).getValue shouldBe 10999000000L
      outputs(0).getTokens.asScala.map(_.getValue) shouldBe Seq(9L)
      outputs(2).getValue shouldBe 24000000000L
      // The proxy permits only the first output's first token to identify the re-emission NFT.
      MainnetEip27Constants.Proxy.ergoTreeHex shouldBe
        "193c03040004000e20606f01a9634f5848ed22eef74b1ba9041673a9a1fb7f357cef80947d01dc43ced1938cb2e4c6b2a5730000020c4d0e730100017302"
      outputs(2).getErgoTree shouldBe MainnetEip27Constants.Proxy.ergoTree
    }
  }

  it should "reject forwarding reward tokens and underfunding before signing" in {
    fake._1.getClient.execute { ctx =>
      val wallet = fake._3
      val box = UTXO(wallet.contract, 15000000000L, Seq(Token(token, 12000000000L))).toDummyInput(ctx)
      intercept[IllegalArgumentException] {
        Eip27Adjustment.adjust(Seq(box), Seq(UTXO(wallet.contract, 3000000000L)), Seq.empty,
          1000000L, NetworkType.MAINNET)
      }
      intercept[IllegalArgumentException] {
        Eip27Adjustment.adjust(Seq(box), Seq(UTXO(wallet.contract, 1000000L, Seq(Token(token, 1L)))),
          Seq.empty, 1000000L, NetworkType.MAINNET)
      }
    }
  }

  it should "reject an incorrect completed proxy payment independently of output planning" in {
    fake._1.getClient.execute { ctx =>
      val wallet = fake._3
      val box = UTXO(wallet.contract, 15000000000L, Seq(Token(token, 12000000000L))).toDummyInput(ctx)
      val wrong = ctx.newTxBuilder().addInputs(box.input)
        .addOutputs(UTXO(MainnetEip27Constants.Proxy, 11999999999L).toOutBox(ctx))
        .fee(1000000L).tokensToBurn(Token(token, 12000000000L).toErgo)
        .sendChangeTo(wallet.p2pk).build()
      intercept[IllegalArgumentException] { Eip27Adjustment.validate(wrong, NetworkType.MAINNET) }
    }
  }

  it should "keep testnet and unrelated tokens separate and reject aggregate overflow" in {
    fake._1.getClient.execute { ctx =>
      val wallet = fake._3
      val input = UTXO(wallet.contract, 2000000L, Seq(Token(token, Long.MaxValue))).toDummyInput(ctx)
      Eip27Adjustment.obligation(Seq(input), NetworkType.TESTNET) shouldBe 0L
      intercept[ArithmeticException] {
        Eip27Adjustment.obligation(Seq(input, input), NetworkType.MAINNET)
      }
      val unrelated = UTXO(wallet.contract, 2000000L,
        Seq(Token("004b1528123ef62ce2bbb7036ad2dd553e6a64252f86746a706729fa253b24cd", 1L))).toDummyInput(ctx)
      Eip27Adjustment.obligation(Seq(unrelated), NetworkType.MAINNET) shouldBe 0L
    }
  }
}
