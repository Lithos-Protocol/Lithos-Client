package transactions.engine

import org.ergoplatform.appkit.{BlockchainContext, InputBox, Parameters}
import org.ergoplatform.sdk.ErgoId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import support.FakeNodeContext
import work.lithos.mutations._

import scala.collection.JavaConverters._

/**
 * The creation heights `TxBuilder.buildTx` gives a spend, change included.
 *
 * Consensus refuses any output created below the newest input, and appkit creates change at the
 * context's tip. Signing checks neither, so every test asserts the heights on the outputs themselves.
 */
class TxBuilderSpec extends AnyFlatSpec with Matchers {
  private lazy val fake = FakeNodeContext()

  private val Fee = Parameters.MinFee
  private val Other = "ab" * 32

  /** A wallet box created at `height`: above the tip it models an unconfirmed box built for the next block. */
  private def walletBox(ctx: BlockchainContext, value: Long, height: Int, tokens: Seq[Token] = Seq.empty): InputUTXO =
    UTXO(fake._3.contract, value, tokens).setCreationHeight(height).toInput(ctx, ErgoId.create("01" * 32), 0)

  private def signedOutputs(builder: TxBuilder): Seq[InputBox] =
    fake._3.sign(builder.buildTx(0, fake._3.p2pk)).getOutputsToSpend.asScala.toSeq

  "TxBuilder.buildTx" should "create an output with no height of its own at the newest input above the tip" in {
    fake._1.getClient.execute { ctx =>
      val newest = ctx.getHeight + 1
      val outputs = signedOutputs(TxBuilder(ctx)
        .setInputs(walletBox(ctx, Parameters.OneErg + Fee, newest))
        .setOutputs(UTXO(fake._3.contract, Parameters.OneErg), UTXO.feeBox(Fee)))

      outputs.map(_.getValue) shouldBe Seq(Parameters.OneErg, Fee)
      outputs.map(_.getCreationHeight) shouldBe Seq(newest, newest)
    }
  }

  it should "carry change at the newest input's height when that input is above the tip" in {
    fake._1.getClient.execute { ctx =>
      val newest = ctx.getHeight + 1
      val in = walletBox(ctx, 3 * Parameters.OneErg + Fee, newest, Seq(Token(Other, 10L)))
      val outputs = signedOutputs(TxBuilder(ctx)
        .setInputs(in)
        .setOutputs(UTXO(fake._3.contract, Parameters.OneErg, Seq(Token(Other, 4L))), UTXO.feeBox(Fee)))

      withClue("consensus refuses an output below the newest input, the change box included: ") {
        outputs.map(_.getCreationHeight).foreach(_ shouldBe newest)
      }
      // One change output, after the fee, holding exactly what the plan left: nothing lost, nothing doubled
      outputs should have size 3
      outputs(2).getErgoTree shouldBe fake._3.contract.ergoTree
      outputs(2).getValue shouldBe 2 * Parameters.OneErg
      outputs(2).getTokens.asScala.map(t => t.getId.toString -> t.getValue) shouldBe Seq(Other -> 6L)
      outputs.map(_.getValue).sum shouldBe in.value
    }
  }

  it should "leave a spend of inputs at or below the tip on the tip, change included" in {
    fake._1.getClient.execute { ctx =>
      val outputs = signedOutputs(TxBuilder(ctx)
        .setInputs(walletBox(ctx, 3 * Parameters.OneErg + Fee, ctx.getHeight - 10, Seq(Token(Other, 10L))))
        .setOutputs(UTXO(fake._3.contract, Parameters.OneErg, Seq(Token(Other, 4L))), UTXO.feeBox(Fee)))

      outputs should have size 3
      outputs.map(_.getCreationHeight).foreach(_ shouldBe ctx.getHeight)
    }
  }

  it should "refuse an output its builder pinned below the newest input" in {
    fake._1.getClient.execute { ctx =>
      val builder = TxBuilder(ctx)
        .setInputs(walletBox(ctx, Parameters.OneErg + Fee, ctx.getHeight + 1))
        .setOutputs(UTXO(fake._3.contract, Parameters.OneErg).setCreationHeight(ctx.getHeight), UTXO.feeBox(Fee))

      val refused = intercept[IllegalArgumentException](builder.buildTx(0, fake._3.p2pk))
      refused.getMessage should include("below the newest input")
    }
  }
}
