package transactions.batching.ergodex

import node.MutationConversions._
import node.model.{NodeAsset, NodeBox}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.BlockchainContext
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import sigma.ast.{ByteArrayConstant, IntConstant}
import support.ErgoDexTokenFixtures
import support.ErgoDexTokenFixtures.Execution

import scala.collection.JavaConverters._
import scala.util.Try

/**
 * ErgoDEX token pool v1 orders against real mainnet executions: each is parsed, priced and signed against the
 * pinned pool script and the order's own script. Pricing must reproduce what the reference executor put on
 * chain to the unit, since the pool and every order check exact balances.
 */
class ErgoDexTokenPoolSpec extends AnyFlatSpec with Matchers with MockitoSugar {
  import ErgoDexTokenFixtures._

  private val (nodeContext, _, wallet) = support.FakeNodeContext(mock[node.NodeApi], numAddresses = 1)

  private def parsed(e: Execution): (ErgoDexOrder, ErgoDexPool) =
    (ErgoDexOrder.parse(e.order).get, ErgoDexPool.token(e.pool).get)

  private def withId(ctx: BlockchainContext, box: NodeBox): NodeBox = box.copy(boxId = box.toInputUTXO(ctx).id.toString)

  private def sign(ctx: BlockchainContext, e: Execution, fill: ErgoDexFill, minerFee: Long) =
    ErgoDexExecution.assembled(ctx, wallet, e.pool.toInputUTXO(ctx), fill,
      math.max(e.pool.creationHeight, e.order.creationHeight) + 10, minerFee, useTrueProp = false)

  "A live token pool order" should "parse as a v1 token order naming the pool it was executed against" in {
    val kinds = Map(swapXIn -> OrderKind.Swap, swapYIn -> OrderKind.Swap, deposit -> OrderKind.Deposit,
      redeem -> OrderKind.Redeem)
    executions.foreach { e =>
      withClue(e.name) {
        val (order, pool) = parsed(e)
        ErgoDexPool.native(e.pool) shouldBe None
        order.contract.shape shouldBe PoolShape.Token
        order.contract.kind shouldBe kinds(e)
        order.poolNft shouldBe pool.nft
        Hex.toHexString(order.redeemerPropBytes) shouldBe e.reward.ergoTree
      }
    }
  }

  it should "price exactly what the reference executor executed" in {
    executions.foreach { e =>
      withClue(e.name) {
        val (order, pool) = parsed(e)
        val fill = ErgoDexExecution.price(order, pool, 0L, fundsItsOwnBox = false).get
        ErgoDexPool.token(e.poolAfter).get.copy(boxId = pool.boxId) shouldBe fill.poolAfter
        fill.rewardValue shouldBe e.reward.value
        fill.rewardTokens shouldBe e.reward.assets
        fill.revenue shouldBe e.revenue
      }
    }
  }

  it should "sign this client's own execution, fee-less and at the order's miner fee cap" in {
    nodeContext.getClient.execute { ctx =>
      executions.foreach { e =>
        withClue(e.name) {
          val (order, pool) = parsed(e)
          val fill = ErgoDexExecution.price(order, pool, 0L).get
          Seq(0L, order.maxMinerFee).foreach { minerFee =>
            val outputs = sign(ctx, e, fill, minerFee).getOutputsToSpend.asScala
            outputs.head.getValue shouldBe e.pool.value
            outputs.head.getTokens.asScala.map(t => NodeAsset(t.getId.toString, t.getValue)) shouldBe e.poolAfter.assets
            outputs(1).getValue shouldBe e.reward.value
            outputs(2).getValue shouldBe e.revenue - minerFee
          }
        }
      }
    }
  }

  // ─── deposits with X in excess, which no live execution covers ───────────

  /** The live deposit with its X raised to `amountX`, so X is the side in excess. */
  private def depositOf(ctx: BlockchainContext, amountX: Long): ErgoDexOrder =
    ErgoDexOrder.parse(withId(ctx, deposit.order.copy(assets = deposit.order.assets.updated(0,
      deposit.order.assets.head.copy(amount = amountX))))).get

  "A deposit with X in excess" should "return the excess X after the LP tokens, and sign" in {
    nodeContext.getClient.execute { ctx =>
      val pool = ErgoDexPool.token(deposit.pool).get
      val order = depositOf(ctx, deposit.order.assets.head.amount * 2)
      val fill = ErgoDexExecution.price(order, pool, 0L).get
      fill.rewardTokens.map(_.tokenId) shouldBe Seq(pool.lpId, pool.xId.get)
      fill.rewardTokens(1).amount should be > 1L
      Try(sign(ctx, deposit, fill, 0L)).isSuccess shouldBe true
    }
  }

  it should "be refused when the excess is under a unit, which neither script lets anyone fill" in {
    nodeContext.getClient.execute { ctx =>
      val pool = ErgoDexPool.token(deposit.pool).get
      val circulating = BigInt(Long.MaxValue) - pool.lpSupply
      val amountY = deposit.order.assets(1).amount
      val byY = BigInt(amountY) * circulating / pool.reservesY
      // The least X that puts X in excess but leaves less than a unit of it to return
      val amountX = (deposit.order.assets.head.amount to deposit.order.assets.head.amount * 2).find { x =>
        val byX = BigInt(x) * circulating / pool.reservesX
        byX > byY && (byX - byY) * pool.reservesX / circulating == 0
      }
      withClue("the fixture pool has no such amount: ")(amountX should not be empty)
      val order = depositOf(ctx, amountX.get)
      ErgoDexExecution.price(order, pool, 0L) shouldBe None

      // The order's script wants byY of LP and, with X in excess, an X change entry. Returning a unit of X leaves
      // the pool unlocking fewer shares than that; returning none leaves the order without its entry.
      val issued = byY.toLong
      def forced(changeX: Long) = ErgoDexFill(order, pool, issued, deposit.revenue,
        pool.copy(reservesX = pool.reservesX + amountX.get - changeX, reservesY = pool.reservesY + amountY,
          lpSupply = pool.lpSupply - issued),
        order.box.value - deposit.revenue,
        NodeAsset(pool.lpId, issued) +: (if (changeX > 0) Seq(NodeAsset(pool.xId.get, changeX)) else Seq.empty))
      Seq(1L, 0L).foreach { changeX =>
        withClue(s"returning $changeX of X: ")(Try(sign(ctx, deposit, forced(changeX), 0L)).isFailure shouldBe true)
      }
    }
  }

  // ─── what is not priced ──────────────────────────────────────────────────

  /** `e`'s order with its own fee numerator set to `fee`. */
  private def pricedAt(ctx: BlockchainContext, e: Execution, fee: Int): ErgoDexOrder =
    ErgoDexOrder.parse(withId(ctx, e.order.copy(ergoTree = withTerms(e.order.ergoTree,
      Map(3 -> IntConstant(fee).asInstanceOf[sigma.ast.Constant[sigma.ast.SType]])).bytesHex))).get

  "A token pool swap" should "be refused when it prices itself at a lower fee than the pool charges" in {
    nodeContext.getClient.execute { ctx =>
      val (live, pool) = parsed(swapXIn)
      // Pool and order both keep 980 of 1000. An order keeping 985 expects more than the pool gives.
      val greedy = pricedAt(ctx, swapXIn, pool.feeNum + 5)
      greedy.orderFeeNum shouldBe Some(pool.feeNum + 5)
      ErgoDexExecution.price(greedy, pool, 0L) shouldBe None
      // Its own script refuses the pool's price too, so pricing it would only have wasted a signature
      val forced = ErgoDexExecution.price(live, pool, 0L).get.copy(order = greedy)
      Try(sign(ctx, swapXIn, forced, 0L)).isFailure shouldBe true
      // One keeping less than the pool does accepts the pool's better price
      val lenient = pricedAt(ctx, swapXIn, pool.feeNum - 5)
      val fill = ErgoDexExecution.price(lenient, pool, 0L).get
      fill.quote shouldBe ErgoDexExecution.price(live, pool, 0L).get.quote
      Try(sign(ctx, swapXIn, fill, 0L)).isSuccess shouldBe true
    }
  }

  it should "allow the unit of slack its own script allows, which on a small output covers a fee gap" in {
    nodeContext.getClient.execute { ctx =>
      // Twelve units out: one unit of slack is over 8%, more than a 990 to 995 gap, so both checks accept
      val (_, pool) = parsed(swapYIn)
      val fill = ErgoDexExecution.price(pricedAt(ctx, swapYIn, pool.feeNum + 5), pool, 0L).get
      Try(sign(ctx, swapYIn, fill, 0L)).isSuccess shouldBe true
    }
  }

  it should "be refused when it sells a token the pool does not hold, or quotes one" in {
    nodeContext.getClient.execute { ctx =>
      val (_, pool) = parsed(swapXIn)
      val stranger = "5e" * 32
      val sellsStranger = ErgoDexOrder.parse(withId(ctx, swapXIn.order.copy(
        assets = Seq(swapXIn.order.assets.head.copy(tokenId = stranger))))).get
      ErgoDexExecution.price(sellsStranger, pool, 0L) shouldBe None
      val quotesLP = ErgoDexOrder.parse(withId(ctx, swapXIn.order.copy(ergoTree = withTerms(swapXIn.order.ergoTree,
        Map(2 -> ByteArrayConstant(Hex.decode(pool.lpId)).asInstanceOf[sigma.ast.Constant[sigma.ast.SType]])).bytesHex))).get
      ErgoDexExecution.price(quotesLP, pool, 0L) shouldBe None
    }
  }

  "A token pool deposit" should "be refused with its two tokens the wrong way round" in {
    nodeContext.getClient.execute { ctx =>
      val (_, pool) = parsed(deposit)
      val reversed = ErgoDexOrder.parse(withId(ctx, deposit.order.copy(assets = deposit.order.assets.reverse))).get
      ErgoDexExecution.price(reversed, pool, 0L) shouldBe None
    }
  }

  "Orders and pools of different shapes" should "never price against each other" in {
    val native = ErgoDexPool.native(support.ErgoDexFixtures.poolBox).get
    val nativeOrder = ErgoDexOrder.parse(support.ErgoDexFixtures.orderBox).get
    executions.foreach { e =>
      val (order, pool) = parsed(e)
      ErgoDexExecution.price(order, native.copy(nft = pool.nft), 0L) shouldBe None
    }
    val token = ErgoDexPool.token(swapXIn.pool).get
    ErgoDexExecution.price(nativeOrder, token.copy(nft = native.nft, yId = native.yId), 0L) shouldBe None
  }

  "The token pool script" should "be the one live mainnet pools sit at" in {
    executions.foreach(e => e.pool.ergoTree shouldBe ErgoDexContracts.TokenPoolErgoTree)
  }
}
