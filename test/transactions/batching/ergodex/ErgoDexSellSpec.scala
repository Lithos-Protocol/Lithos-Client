package transactions.batching.ergodex

import org.ergoplatform.appkit.impl.{InputBoxImpl, SignedTransactionImpl}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import support.ErgoDexFixtures.Sell._

import scala.collection.JavaConverters._

class ErgoDexSellSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  private val (nodeContext, _, wallet) = support.FakeNodeContext(mock[node.NodeApi], numAddresses = 1)
  private val pool = ErgoDexPool.native(poolBox).getOrElse(fail("sell pool fixture did not parse"))
  private val order = ErgoDexOrder.parse(orderBox).getOrElse(fail("sell order fixture did not parse"))
  private val height = orderBox.creationHeight + 4

  "A live sell order box" should "read as a v1 native sell with the terms its execution met" in {
    order.contract.kind shouldBe OrderKind.SwapSell
    order.contract.version shouldBe "v1"
    order.baseIsErg shouldBe true
    order.poolNft shouldBe pool.nft
    order.baseAmount shouldBe baseAmount
    withClue("the chain paid this fee, so the order's cap admits it: ") {
      order.maxMinerFee should be >= minerFee
    }
    withClue("the chain paid this quote, so the order's floor admits it: ") {
      order.minQuoteAmount should be <= rewardTokens
    }
    order.quoteId shouldBe Some(pool.yId)
  }

  it should "pay its reward to the key that placed it" in {
    val paidTo = "0008cd02182d06d87d208455df95976207a09a491575f70ac8ffe56ab236eef214f9cc50"
    org.bouncycastle.util.encoders.Hex.toHexString(order.redeemerPropBytes) shouldBe paidTo
  }

  it should "be refused against a pool of another token" in {
    ErgoDexExecution.price(order, pool.copy(yId = "ab" * 32), 0L) shouldBe None
  }

  "The sell quote" should "be the tokens the pool paid, and leave the reserves the chain recorded" in {
    val quote = pool.outputAmount(order.baseAmount, order.baseIsErg)
    quote shouldBe rewardTokens
    val after = pool.afterSwap(order.baseAmount, quote, order.baseIsErg)
    after.reservesX shouldBe poolErgAfter
    after.reservesY shouldBe poolTokensAfter
  }

  it should "pay the executor what the bot kept plus the fee it paid" in {
    order.dexFee(rewardTokens) shouldBe executorOutput + minerFee
  }

  "A sell execution" should "be accepted by both scripts and match the chain's outputs, fee-less and at the fee cap" in {
    nodeContext.getClient.execute { ctx =>
      import node.MutationConversions._
      val params = support.RentRule.paramsFrom(ctx)
      val poolInput = poolBox.toInputUTXO(ctx)
      val fill = ErgoDexExecution.price(order, pool, 0L).getOrElse(fail("sell fill was refused"))

      Seq(0L -> (executorOutput + minerFee), minerFee -> executorOutput).foreach { case (fee, takings) =>
        withClue(s"at a miner fee of $fee: ") {
          val signed = ErgoDexExecution.assembled(ctx, wallet, poolInput, fill, height, fee, useTrueProp = false)
          val spent = IndexedSeq(poolInput, order.boxAsInput(ctx)).map(_.input.asInstanceOf[InputBoxImpl].getErgoBox)
          val tx = signed.asInstanceOf[SignedTransactionImpl].getTx
          support.RentRule.accepts(tx, spent, height, params) shouldBe true

          val outputs = signed.getOutputsToSpend.asScala
          outputs.head.getValue shouldBe poolErgAfter
          outputs.head.getTokens.asScala.last.getValue.toLong shouldBe poolTokensAfter
          outputs(1).getValue shouldBe rewardValue
          outputs(1).getTokens.asScala.map(_.getValue.toLong) shouldBe Seq(rewardTokens)
          outputs(2).getValue shouldBe takings
          outputs.size shouldBe (if (fee > 0) 4 else 3)
        }
      }
    }
  }
}
