package transactions.batching.ergodex

import node.MutationConversions._
import org.ergoplatform.appkit.impl.{InputBoxImpl, SignedTransactionImpl}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import support.ErgoDexLiquidityFixtures._

import scala.collection.JavaConverters._
import scala.util.Try

class ErgoDexLiquiditySpec extends AnyFlatSpec with Matchers with MockitoSugar {
  private val (nodeContext, _, wallet) = support.FakeNodeContext(mock[node.NodeApi], numAddresses = 1)

  "Native liquidity orders" should "sign balanced deposits, both change directions and redemptions at either fee ceiling" in {
    nodeContext.getClient.execute { ctx =>
      val box = poolBox(ctx)
      val pool = ErgoDexPool.native(box).get
      val height = box.creationHeight + 10
      val cases = Seq(
        (order(ctx, OrderKind.Deposit), 1001000000000L, 1001000L, 2000000L, Seq(1000000L)),
        (order(ctx, OrderKind.Deposit, amountX = 2000000000L), 1001000000000L, 1001000L, 1002000000L, Seq(1000000L)),
        (order(ctx, OrderKind.Deposit, amountY = 2000L), 1001000000000L, 1001000L, 2000000L, Seq(1000000L, 1000L)),
        (order(ctx, OrderKind.Redeem), 999000000000L, 999000L, 1002000000L, Seq(1000L)))
      cases.foreach { case (placed, x, y, reward, tokens) =>
        val fill = ErgoDexExecution.price(placed, pool, 0L).get
        fill.revenue shouldBe Fee
        Seq(0L, MinerFee).foreach { minerFee =>
          val signed = ErgoDexExecution.assembled(ctx, wallet, box.toInputUTXO(ctx), fill, height, minerFee, useTrueProp = false)
          val spent = IndexedSeq(box.toInputUTXO(ctx), placed.boxAsInput(ctx))
            .map(_.input.asInstanceOf[InputBoxImpl].getErgoBox)
          support.RentRule.accepts(signed.asInstanceOf[SignedTransactionImpl].getTx, spent,
            height, support.RentRule.paramsFrom(ctx)) shouldBe true
          val outputs = signed.getOutputsToSpend.asScala
          outputs.head.getValue shouldBe x
          outputs.head.getTokens.asScala.last.getValue.toLong shouldBe y
          outputs(1).getValue shouldBe reward
          outputs(1).getTokens.asScala.map(_.getValue.toLong) shouldBe tokens
          outputs(1).getErgoTree.bytesHex shouldBe org.bouncycastle.util.encoders.Hex.toHexString(placed.redeemerPropBytes)
          outputs(2).getValue shouldBe Fee - minerFee
        }
      }
    }
  }

  it should "reject underpaid LP rewards and reserve returns at signing" in {
    nodeContext.getClient.execute { ctx =>
      val box = poolBox(ctx)
      val pool = ErgoDexPool.native(box).get
      val deposit = ErgoDexExecution.price(order(ctx, OrderKind.Deposit), pool, 0L).get
      val redeem = ErgoDexExecution.price(order(ctx, OrderKind.Redeem), pool, 0L).get
      val underpaidLP = deposit.copy(poolAfter = deposit.poolAfter.copy(lpSupply = deposit.poolAfter.lpSupply + 1),
        rewardTokens = deposit.rewardTokens.map(t => t.copy(amount = t.amount - 1)))
      val underpaidErg = redeem.copy(poolAfter = redeem.poolAfter.copy(reservesX = redeem.poolAfter.reservesX + 1),
        rewardValue = redeem.rewardValue - 1)
      val underpaidToken = redeem.copy(poolAfter = redeem.poolAfter.copy(reservesY = redeem.poolAfter.reservesY + 1),
        rewardTokens = redeem.rewardTokens.map(t => t.copy(amount = t.amount - 1)))
      Seq(underpaidLP, underpaidErg, underpaidToken).foreach { fill =>
        val result = Try(ErgoDexExecution.assembled(ctx, wallet, box.toInputUTXO(ctx), fill,
          box.creationHeight + 10, 0L, useTrueProp = false))
        result.failed.get.getMessage should include("UnprovenSchnorr")
      }
    }
  }

  it should "reprice a mixed run after every reserve and LP change" in {
    nodeContext.getClient.execute { ctx =>
      val box = poolBox(ctx)
      val pool = ErgoDexPool.native(box).get
      val deposit = order(ctx, OrderKind.Deposit, fee = 8000000L)
      val redeem = order(ctx, OrderKind.Redeem)
      val fills = ErgoDexExecution.priceChain(Seq(redeem, swap(ctx), deposit), pool, 0L, 3)
      fills.map(_.order.contract.kind) shouldBe Vector(OrderKind.Deposit, OrderKind.Redeem, OrderKind.SwapBuy)
      fills(1).pool shouldBe fills.head.poolAfter
      fills(2).pool shouldBe fills(1).poolAfter
      val chain = ErgoDexExecution.run(ctx, wallet, box.toInputUTXO(ctx), pool, Seq(redeem, swap(ctx), deposit), 3, 0L,
        box.creationHeight + 10, 0L, useTrueProp = false, scala.concurrent.duration.Deadline.now +
          scala.concurrent.duration.Duration(1, "minute")).chain.get
      chain.transactions should have size 3
      chain.fills.map(_.revenue) shouldBe fills.map(_.revenue)
      chain.takings.value shouldBe fills.map(_.revenue).sum
      chain.bundle(Set.empty).capital should have size 1
    }
  }

  it should "reject inconsistent liquidity constants and malformed token layouts" in {
    nodeContext.getClient.execute { ctx =>
      import sigma.ast.{ErgoTree, LongConstant}
      Seq(OrderKind.Deposit, OrderKind.Redeem).foreach { kind =>
        val placed = order(ctx, kind)
        val tree = ErgoTree.fromHex(placed.box.ergoTree)
        val positions = if (kind == OrderKind.Deposit) Seq(2, 7, 15, 16, 17, 22) else Seq(8, 12, 16)
        positions.foreach { index =>
          val script = ErgoTree(tree.header, tree.constants.updated(index, LongConstant(-1L)), tree.root.right.get)
          ErgoDexOrder.parse(placed.box.copy(ergoTree = script.bytesHex)) shouldBe None
        }
        ErgoDexOrder.parse(placed.box.copy(assets = Seq.empty)) shouldBe None
        ErgoDexOrder.parse(placed.box.copy(assets = placed.box.assets ++ placed.box.assets)) shouldBe None
      }
    }
  }

  "Deposit rounding" should "return a token when the change branch requires one and the pool can fund it" in {
    nodeContext.getClient.execute { ctx =>
      val original = poolBox(ctx)
      val small = original.copy(value = 120000000L, assets = Seq(original.assets.head,
        original.assets(1).copy(amount = Long.MaxValue - 6L), original.assets(2).copy(amount = 4L)))
      val box = small.copy(boxId = small.toInputUTXO(ctx).id.toString)
      val pool = ErgoDexPool.native(box).get
      val (_, after) = pool.deposit(60000000L, 3L).get
      after.reservesY shouldBe 6L
      after.lpSupply shouldBe pool.lpSupply - 3L
      pool.deposit(40000000L, 2L) shouldBe None
      pool.deposit(Long.MaxValue, Long.MaxValue) shouldBe None
      val placed = order(ctx, OrderKind.Deposit, amountX = 60000000L, amountY = 3L)
      val fill = ErgoDexExecution.price(placed, pool, 0L).get
      val height = box.creationHeight + 10
      val signed = ErgoDexExecution.assembled(ctx, wallet, box.toInputUTXO(ctx), fill,
        height, 0L, useTrueProp = false)
      val spent = IndexedSeq(box.toInputUTXO(ctx), placed.boxAsInput(ctx))
        .map(_.input.asInstanceOf[InputBoxImpl].getErgoBox)
      support.RentRule.accepts(signed.asInstanceOf[SignedTransactionImpl].getTx, spent,
        height, support.RentRule.paramsFrom(ctx)) shouldBe true
    }
  }

  it should "refuse wrong tokens, exhausted LP, empty reserves and fills below the net fee floor" in {
    nodeContext.getClient.execute { ctx =>
      val pool = ErgoDexPool.native(poolBox(ctx)).get
      val deposit = order(ctx, OrderKind.Deposit)
      val redeem = order(ctx, OrderKind.Redeem)
      ErgoDexExecution.price(deposit, pool.copy(yId = "ab" * 32), 0L) shouldBe None
      ErgoDexExecution.price(redeem, pool.copy(lpId = "ab" * 32), 0L) shouldBe None
      ErgoDexExecution.price(deposit, pool.copy(lpSupply = Long.MaxValue), 0L) shouldBe None
      ErgoDexExecution.price(deposit, pool.copy(lpSupply = 1L), 0L) shouldBe None
      ErgoDexExecution.price(redeem.copy(baseAmount = CirculatingLP), pool, 0L) shouldBe None
      ErgoDexExecution.price(redeem, pool.copy(reservesX = 10000001L), 0L) shouldBe None
      ErgoDexExecution.price(deposit.copy(fixedDexFee = MinerFee), pool, 0L,
        fundsItsOwnBox = false, minerFeeCeiling = MinerFee) shouldBe None
    }
  }
}
