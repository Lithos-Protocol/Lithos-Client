package transactions.batching.ergodex

import node.model.NodeRegisters
import support.ErgoDexFixtures._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ErgoDexQuoteSpec extends AnyFlatSpec with Matchers {

  "A live pool box" should "read its reserves and fee" in {
    val pool = ErgoDexPool.native(poolBox).getOrElse(fail("pool box did not parse"))
    pool.reservesX shouldBe 5917018418532L
    pool.reservesY shouldBe 268586053672L
    pool.feeNum shouldBe 997
    pool.nft shouldBe "7f1759300b19287adbbcd03c877eae6c6de60f4c2962b1fc5ae2c6e2a684220c"
  }

  it should "refuse a box that only looks like a pool" in {
    ErgoDexPool.native(poolBox.copy(assets = poolBox.assets.take(2))) shouldBe None
    ErgoDexPool.native(poolBox.copy(additionalRegisters = NodeRegisters.empty)) shouldBe None
    ErgoDexPool.native(orderBox) shouldBe None
  }

  "A live order box" should "read as a v1 native buy" in {
    val order = ErgoDexOrder.parse(orderBox).getOrElse(fail("order box did not parse"))
    order.contract.kind shouldBe OrderKind.SwapBuy
    order.contract.version shouldBe "v1"
    order.baseIsErg shouldBe false
    order.poolNft shouldBe "7f1759300b19287adbbcd03c877eae6c6de60f4c2962b1fc5ae2c6e2a684220c"
    order.baseAmount shouldBe 500000000L
    order.dexFeePerTokenDenom should be > 0L
  }

  it should "refuse a pool box offered as an order" in {
    ErgoDexOrder.parse(poolBox) shouldBe None
  }

  "The quote" should "equal the reserve movement the chain accepted" in {
    val pool = ErgoDexPool.native(poolBox).get
    val order = ErgoDexOrder.parse(orderBox).get
    val quote = pool.outputAmount(order.baseAmount, order.baseIsErg)
    quote shouldBe (poolBox.value - poolErgAfter)
    quote should be >= order.minQuoteAmount
  }

  it should "price the executor's pay as the fee this transaction paid, plus the fee it did not" in {
    val pool = ErgoDexPool.native(poolBox).get
    val order = ErgoDexOrder.parse(orderBox).get
    val quote = pool.outputAmount(order.baseAmount, order.baseIsErg)
    order.dexFee(quote) shouldBe (quote - (rewardValue - orderBox.value))
    order.dexFee(quote) - minerFee shouldBe executorOutput
  }

  "The pool after a swap" should "hold what the chain's own output held" in {
    val pool = ErgoDexPool.native(poolBox).get
    val order = ErgoDexOrder.parse(orderBox).get
    val quote = pool.outputAmount(order.baseAmount, order.baseIsErg)
    val after = pool.afterSwap(order.baseAmount, quote, order.baseIsErg)
    after.reservesX shouldBe poolErgAfter
    after.reservesY shouldBe poolTokensAfter
  }
}
