package transactions.batching.arb

import lithosdex.LDLiquidityPool
import node.MutationConversions._
import node.model.{NodeAsset, NodeBox}
import node.rest.NodeCodecs
import org.ergoplatform.appkit.{BlockchainContext, NetworkType}
import org.ergoplatform.sdk.ErgoId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import support.{ErgoDexFixtures, ErgoDexTokenFixtures, LDNodeFixtures}
import transactions.batching.ergodex.ErgoDexPool
import transactions.batching.lithosdex.LDDeployments
import work.lithos.mutations.{InputUTXO, Token, UTXO}

import scala.collection.JavaConverters._
import scala.io.Source
import scala.util.Try

/**
 * Direct swaps through every pool kind, in both directions, against real mainnet trades: each is priced from
 * the pool box before the trade and must reproduce the successor the chain recorded to the unit, then signs
 * as a leg against the pool's real contract with a wallet box paying for it.
 */
class ArbLegSpec extends AnyFlatSpec with Matchers with MockitoSugar {
  import ArbLegSpec.Trade

  private val (nodeContext, _, wallet) = support.FakeNodeContext(mock[node.NodeApi], numAddresses = 1)

  private def text(path: String): String = {
    val source = Source.fromResource(path)
    try source.mkString finally source.close()
  }

  private def recorded(name: String): (NodeBox, NodeBox) = {
    val doc = new com.google.gson.JsonParser().parse(text(s"arb/$name.json")).getAsJsonObject
    NodeCodecs.box(doc.getAsJsonObject("before")) -> NodeCodecs.box(doc.getAsJsonObject("after"))
  }

  private def assets(box: NodeBox): Seq[(String, Long)] = box.assets.map(a => a.tokenId -> a.amount)

  private def ergoDex(before: NodeBox, after: NodeBox, xIn: Boolean, name: String): Trade = {
    val parse = (box: NodeBox) => ErgoDexArbPool(ErgoDexPool.parse(box).get): ArbPool
    Trade(name, before, after, _ => parse(before), parse, xIn)
  }

  private def lithosDex(ctx: BlockchainContext, name: String, xIn: Boolean): Trade = {
    val (before, after) = recorded(name)
    val contracts = LDDeployments.verify(NetworkType.MAINNET, before.ergoTree, assets(before)).get
    Trade(name, before, after, box => LithosDexArbPool(box, contracts),
      box => LithosDexArbPool(LDLiquidityPool(box.toInputUTXO(ctx)), contracts, box.boxId), xIn)
  }

  private def lithosDexT2T(ctx: BlockchainContext, name: String, xIn: Boolean): Trade = {
    val (before, after) = recorded(name)
    val contracts = LDDeployments.verifyT2T(before.ergoTree, assets(before)).get
    Trade(name, before, after, box => LithosDexT2TArbPool(box, contracts),
      box => LithosDexT2TArbPool(lithosdex.LDT2TLiquidityPool(box.toInputUTXO(ctx)), contracts, box.boxId), xIn)
  }

  /** Every recorded swap: two ErgoDEX native, two ErgoDEX token pool, two of each LithosDex kind. */
  private def trades(ctx: BlockchainContext): Seq[Trade] = {
    val nativeSell = ergoDex(ErgoDexFixtures.Sell.poolBox, ErgoDexFixtures.Sell.poolBox.copy(
      value = ErgoDexFixtures.Sell.poolErgAfter,
      assets = ErgoDexFixtures.Sell.poolBox.assets.updated(2,
        ErgoDexFixtures.Sell.poolBox.assets(2).copy(amount = ErgoDexFixtures.Sell.poolTokensAfter))), xIn = true,
      "ergodex native, ERG in")
    val nativeBuy = ergoDex(ErgoDexFixtures.poolBox, ErgoDexFixtures.poolBox.copy(
      value = ErgoDexFixtures.poolErgAfter,
      assets = ErgoDexFixtures.poolBox.assets.updated(2,
        ErgoDexFixtures.poolBox.assets(2).copy(amount = ErgoDexFixtures.poolTokensAfter))), xIn = false,
      "ergodex native, token in")
    val tokenX = ergoDex(ErgoDexTokenFixtures.swapXIn.pool, ErgoDexTokenFixtures.swapXIn.poolAfter, xIn = true,
      "ergodex token pool, X in")
    val tokenY = ergoDex(ErgoDexTokenFixtures.swapYIn.pool, ErgoDexTokenFixtures.swapYIn.poolAfter, xIn = false,
      "ergodex token pool, Y in")
    Seq(nativeSell, nativeBuy, tokenX, tokenY,
      lithosDex(ctx, "ld-n2t-x-in", xIn = true), lithosDex(ctx, "ld-n2t-y-in", xIn = false),
      lithosDexT2T(ctx, "ld-t2t-x-in", xIn = true), lithosDexT2T(ctx, "ld-t2t-y-in", xIn = false))
  }

  private def registersOf(box: NodeBox): Seq[String] =
    (4 to 9).flatMap(r => box.additionalRegisters.get(r)).map(_.toLowerCase)

  private def tokensOf(utxo: UTXO): Seq[NodeAsset] = utxo.tokens.map(t => NodeAsset(t.id.toString, t.amount))

  /** A plain box at the wallet's address holding what a leg's input needs, plus spare ERG. */
  private def carryFor(ctx: BlockchainContext, swap: ArbSwap, spare: Long, index: Int = 3): InputUTXO = {
    val tokens = swap.assetIn.tokenId.map(id => Token(ErgoId.create(id), swap.amountIn)).toSeq
    val value = (if (swap.assetIn.isErg) swap.amountIn else 0L) + spare
    LDNodeFixtures.nodeBox(ctx, UTXO(wallet.contract, value, tokens), index, "ab" * 32).toInputUTXO(ctx)
  }

  "A direct swap" should "price every recorded mainnet trade to the unit, on every pool kind and in both directions" in {
    nodeContext.getClient.execute { ctx =>
      trades(ctx).map(t => t.parsed(t.before).kind).distinct.size shouldBe 4
      trades(ctx).foreach { t =>
        withClue(t.name) {
          val pool = t.parsed(t.before)
          t.amountIn should be > 0L
          pool.quote(t.amountIn, t.xIn) shouldBe t.amountOut
          pool.quote(t.amountIn, t.xIn) should be > 0L
        }
      }
    }
  }

  it should "build the successor the chain recorded, value, tokens and registers alike" in {
    nodeContext.getClient.execute { ctx =>
      trades(ctx).foreach { t =>
        withClue(t.name) {
          val box = t.before.toInputUTXO(ctx)
          box.id.toString shouldBe t.before.boxId
          val swap = t.pool(box).swap(t.amountIn, t.xIn).get
          val out = swap.pool.successor(box, swap.after)
          out.contract.ergoTreeHex shouldBe t.after.ergoTree
          out.value shouldBe t.after.value
          tokensOf(out) shouldBe t.after.assets
          out.registers.map(_.toHex.toLowerCase) shouldBe registersOf(t.after)
          (out.value - box.value) shouldBe swap.poolErgDelta
        }
      }
    }
  }

  it should "sign as a leg against each pool's real contract, fee-less and with a miner fee" in {
    nodeContext.getClient.execute { ctx =>
      trades(ctx).foreach { t =>
        withClue(t.name) {
          val box = t.before.toInputUTXO(ctx)
          val swap = t.pool(box).swap(t.amountIn, t.xIn).get
          Seq(0L, 1000000L).foreach { fee =>
            val carry = carryFor(ctx, swap, spare = 5000000L)
            val signed = ArbLeg.build(ctx, wallet, swap, box, Seq(carry), wallet.contract, t.after.creationHeight, fee)
            val outputs = signed.getOutputsToSpend.asScala
            signed.getSignedInputs.asScala.map(_.getId.toString) shouldBe Seq(box.id.toString, carry.id.toString)
            outputs.head.getValue shouldBe t.after.value
            outputs.head.getTokens.asScala.map(tk => NodeAsset(tk.getId.toString, tk.getValue)) shouldBe t.after.assets
            outputs(1).getErgoTree.bytesHex shouldBe wallet.contract.ergoTreeHex
            val boughtErg = if (swap.assetOut.isErg) swap.amountOut else 0L
            outputs(1).getValue shouldBe 5000000L + boughtErg - fee
            outputs(1).getTokens.asScala.map(tk => NodeAsset(tk.getId.toString, tk.getValue)) shouldBe
              swap.assetOut.tokenId.map(id => NodeAsset(id, swap.amountOut)).toSeq
            outputs.size shouldBe (if (fee > 0) 3 else 2)
          }
        }
      }
    }
  }

  it should "be refused by every pool's contract when it takes one unit more than the quote" in {
    nodeContext.getClient.execute { ctx =>
      trades(ctx).foreach { t =>
        withClue(t.name) {
          val box = t.before.toInputUTXO(ctx)
          val pool = t.pool(box)
          val honest = pool.swap(t.amountIn, t.xIn).get
          val greedy = overpaid(pool, honest)
          val carry = carryFor(ctx, honest, spare = 5000000L)
          Try(ArbLeg.build(ctx, wallet, greedy, box, Seq(carry), wallet.contract, t.after.creationHeight))
            .isFailure shouldBe true
          Try(ArbLeg.build(ctx, wallet, honest, box, Seq(carry), wallet.contract, t.after.creationHeight))
            .isSuccess shouldBe true
        }
      }
    }
  }

  /** `swap` with one more unit paid out, and the pool state that payout leaves. */
  private def overpaid(pool: ArbPool, swap: ArbSwap): ArbSwap = pool match {
    case ErgoDexArbPool(state) =>
      swap.copy(amountOut = swap.amountOut + 1,
        after = ErgoDexArbPool(state.afterSwap(swap.amountIn, swap.amountOut + 1, swap.xIn)))
    case p @ LithosDexArbPool(state, _, _) =>
      val q = state.simSwap(swap.amountIn, swap.xIn)
      swap.copy(amountOut = q.amountOut + 1, after = p.copy(state = state.afterSwap(q.copy(amountOut = q.amountOut + 1))))
    case p @ LithosDexT2TArbPool(state, _, _) =>
      val q = state.simSwap(swap.amountIn, swap.xIn)
      swap.copy(amountOut = q.amountOut + 1, after = p.copy(state = state.afterSwap(q.copy(amountOut = q.amountOut + 1))))
  }

  it should "refuse a swap priced against a different pool box" in {
    nodeContext.getClient.execute { ctx =>
      val t = trades(ctx).head
      val box = t.before.toInputUTXO(ctx)
      val swap = t.pool(box).swap(t.amountIn, t.xIn).get
      val elsewhere = swap.copy(pool = swap.pool.withBox("00" * 32))
      the[IllegalArgumentException] thrownBy
        ArbLeg.build(ctx, wallet, elsewhere, box, Seq(carryFor(ctx, swap, 5000000L)), wallet.contract, 1) should
        have message s"requirement failed: swap priced against pool box ${"00" * 32}, not ${box.id}"
    }
  }

  "A leg's carry" should "keep the floor, refuse an input it does not hold, and add and drop tokens by amount" in {
    val sellTrade = nodeContext.getClient.execute(ctx => trades(ctx).head)
    val pool = sellTrade.parsed(sellTrade.before)
    val sell = pool.swap(1000000000L, xIn = true).get
    val token = sell.assetOut.tokenId.get
    // ERG in: the carry must keep MinCarryValue after the input and the fee
    ArbLeg.carried(ArbCarry(1000000000L + ArbLeg.MinCarryValue, Seq.empty), sell, 0L).map(_.value) shouldBe
      Some(ArbLeg.MinCarryValue)
    ArbLeg.carried(ArbCarry(1000000000L + ArbLeg.MinCarryValue - 1, Seq.empty), sell, 0L) shouldBe None
    ArbLeg.carried(ArbCarry(1000000000L + ArbLeg.MinCarryValue, Seq.empty), sell, 1L) shouldBe None
    // The bought token joins the carry
    ArbLeg.carried(ArbCarry(2000000000L, Seq.empty), sell, 0L).get.tokens shouldBe
      Seq(Token(ErgoId.create(token), sell.amountOut))
    // Token in: spent to nothing leaves the list; short of the input is refused
    val buy = pool.swap(sell.amountOut, xIn = false).get
    val held = ArbCarry(ArbLeg.MinCarryValue, Seq(Token(ErgoId.create(token), sell.amountOut)))
    ArbLeg.carried(held, buy, 0L) shouldBe Some(ArbCarry(ArbLeg.MinCarryValue + buy.amountOut, Seq.empty))
    ArbLeg.carried(held.copy(tokens = Seq(Token(ErgoId.create(token), sell.amountOut - 1))), buy, 0L) shouldBe None
  }

  "A pool that cannot fill a swap" should "quote zero and refuse to swap" in {
    nodeContext.getClient.execute { ctx =>
      trades(ctx).foreach { t =>
        withClue(t.name) {
          val pool = t.parsed(t.before)
          pool.quote(0L, t.xIn) shouldBe 0L
          pool.swap(0L, t.xIn) shouldBe None
          pool.quote(-5L, t.xIn) shouldBe 0L
        }
      }
    }
  }
}

object ArbLegSpec {
  /** A recorded trade: the pool box it spent and the successor it created. */
  final case class Trade(name: String, before: NodeBox, after: NodeBox, pool: InputUTXO => ArbPool,
                         parsed: NodeBox => ArbPool, xIn: Boolean) {
    /** What went into the pool, read off the two boxes on the side that rose. */
    def amountIn: Long = side(after, xIn) - side(before, xIn)
    def amountOut: Long = side(before, !xIn) - side(after, !xIn)
    private def side(box: NodeBox, x: Boolean): Long = {
      val probe = parsed(box)
      val asset = if (x) probe.x else probe.y
      asset.tokenId.fold(box.value)(id => box.assets.filter(_.tokenId == id).map(_.amount).sum)
    }
  }
}
