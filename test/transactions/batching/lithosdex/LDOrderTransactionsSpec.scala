package transactions.batching.lithosdex

import lithosdex.{LDHelpers, LDLiquidityPool}
import node.MutationConversions._
import node.model.{NodeAsset, NodeBox, NodeRegisters}
import org.ergoplatform.appkit.{BlockchainContext, Parameters, SignedTransaction}
import org.ergoplatform.sdk.ErgoId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import support.{FakeNodeContext, LDNodeFixtures}
import work.lithos.mutations.{InputUTXO, Token, UTXO}

import scala.collection.JavaConverters._
import scala.concurrent.duration._

/**
 * Orders this wallet places and cancels, built by `LDOrderTransactions` and signed by its wallet.
 *
 * A placement's own signature only runs the wallet's P2PK inputs, so each placed order is also read back
 * and filled through `LithosDexExecution`, whose signature runs the order contract. That is what shows an
 * order box the API places is one an executor can find and fill, and that its terms say what the request
 * asked for.
 */
class LDOrderTransactionsSpec extends AnyFlatSpec with Matchers {

  private lazy val fake = FakeNodeContext(numAddresses = 1)
  private def wallet = fake._3

  private def withCtx[A](f: BlockchainContext => A): A = fake._1.getClient.execute(ctx => f(ctx))

  private val erg = Parameters.OneErg
  private val Fee = 3000000L
  private val Cap = 1000000L
  private val Height = 123414
  private val Headroom = LithosDexTransactions.FUNDING_HEADROOM

  private def tokenY(ctx: BlockchainContext): ErgoId = LDHelpers.getTokenY(ctx.getNetworkType)

  private def poolNode(ctx: BlockchainContext, accX: BigInt = BigInt(5)): NodeBox =
    LDNodeFixtures.poolBox(ctx, reservesX = 10L * erg, reservesY = 10000L * 1000000L,
      pendingX = 20000000L, pendingY = 30000000L, accX = accX, accY = BigInt(7))

  private def pool(ctx: BlockchainContext): LDLiquidityPool = LDLiquidityPool(poolNode(ctx).toInputUTXO(ctx))

  /** A wallet box funding exactly what `plan` reserves. */
  private def funding(ctx: BlockchainContext, plan: DexPlan[_], index: Int = 20): InputUTXO =
    LDNodeFixtures.nodeBox(ctx, UTXO(wallet.contract, plan.value, plan.tokens), index = index, txId = "ef" * 32)
      .toInputUTXO(ctx)

  private def signed[A <: LDFundedTx](plan: DexPlan[A], inputs: Seq[InputUTXO]): A = {
    val unsigned = plan.build(inputs)
    unsigned.describe(wallet.sign(unsigned.tx))
  }

  /** The node box an output becomes, so it can be parsed the way a scan parses it. */
  private def asNode(box: InputUTXO): NodeBox =
    NodeBox(box.id.toString, box.input.getTransactionId, box.value, box.input.getTransactionIndex,
      box.input.getCreationHeight, box.contract.ergoTreeHex, box.tokens.map(t => NodeAsset(t.id.toString, t.amount)),
      NodeRegisters(box.registers.zipWithIndex.map { case (v, i) => s"R${i + 4}" -> v.toHex }.toMap))

  private def readBack(tx: LDOrderTx): LithosDexOrder =
    LithosDexOrder.parse(asNode(tx.order)).getOrElse(fail("the placed order box does not parse as an order"))

  private def fill(ctx: BlockchainContext, order: LithosDexOrder,
                   provisions: LithosDexExecution.Provisions = LithosDexExecution.NoProvisions,
                   poolBox: NodeBox = null): LithosDexChain =
    LithosDexExecution.run(ctx, wallet, Option(poolBox).getOrElse(poolNode(ctx)).toInputUTXO(ctx), Seq(order), 10, 0L,
      provisions, Height, 0L,
      useTrueProp = false, 1.minute.fromNow).chain.getOrElse(fail(s"the placed ${order.kind} did not fill"))

  private def outputs(tx: SignedTransaction) = tx.getOutputsToSpend.asScala.toVector

  // ─── placing ─────────────────────────────────────────────────────────────

  "A swap order selling ERG" should "lock the sale, the fee and the return, and fill to this wallet" in withCtx { ctx =>
    val quote = pool(ctx).simSwap(erg, ergIn = true)
    val plan = LDOrderTransactions.swap(ctx, wallet, pool(ctx), erg, ergIn = true, quote.amountOut, Fee, Cap)
    plan.value shouldBe erg + Fee + LDOrderTransactions.RETURNED + Headroom
    plan.tokens shouldBe empty

    val placed = signed(plan, Seq(funding(ctx, plan)))
    placed.order.value shouldBe LDOrderTransactions.swapValue(erg, ergIn = true, Fee)
    val order = readBack(placed)
    order shouldBe a[LithosDexOrder.SwapSell]
    order.terms.redeemerTree shouldBe wallet.contract.ergoTreeHex
    order.terms.executorFee shouldBe Fee
    order.terms.maxMinerFee shouldBe Cap

    val filled = fill(ctx, order).fills.head
    filled.rewardY shouldBe quote.amountOut
    filled.rewardValue shouldBe LDOrderTransactions.RETURNED
  }

  "A swap order selling the token" should "lock the tokens and a return, with the floor net of the fee" in withCtx { ctx =>
    val amount = 500L * 1000000L
    val quote = pool(ctx).simSwap(amount, ergIn = false)
    val plan = LDOrderTransactions.swap(ctx, wallet, pool(ctx), amount, ergIn = false, quote.amountOut - Fee, Fee, Cap)
    plan.value shouldBe LDOrderTransactions.RETURNED + Headroom
    plan.tokens shouldEqual Seq(Token(tokenY(ctx), amount))

    val order = readBack(signed(plan, Seq(funding(ctx, plan))))
    order shouldBe a[LithosDexOrder.SwapBuy]
    fill(ctx, order).fills.head.rewardValue shouldBe LDOrderTransactions.RETURNED + quote.amountOut - Fee
  }

  "A deposit order" should "fill for at least its minimum shares and return what the pool did not take" in withCtx { ctx =>
    val offeredY = 1200L * 1000000L
    val split = LithosDexExecution.depositSplit(pool(ctx), erg, offeredY)
    val plan = LDOrderTransactions.deposit(ctx, wallet, pool(ctx), erg, offeredY, split.shares.toLong, Fee, Cap)
    plan.value shouldBe erg + LDHelpers.PROVISION_MIN + Fee + LDOrderTransactions.RETURNED + Headroom

    val order = readBack(signed(plan, Seq(funding(ctx, plan))))
    order shouldBe a[LithosDexOrder.Deposit]
    val chain = fill(ctx, order)
    chain.fills.head.shares shouldBe split.shares.toLong
    chain.fills.head.rewardY shouldBe split.excessY.toLong

    // The largest reward box this wallet's owner produces, with the NFT, its EIP-4 registers and the
    // token change, still needs less than the return the order carries for it
    val reward = outputs(chain.transactions.head)(2)
    val perByte = ctx.getDataSource.getParameters.getMinValuePerByte.toLong
    reward.getBytes.length.toLong should be < 1000L
    reward.getBytes.length * perByte should be < LDOrderTransactions.RETURNED
  }

  "A redeem order with settled fees" should "claim them in the placement, then fill against the successor" in withCtx { ctx =>
    val nft = ErgoId.create("f0" * 32)
    val shares = 10000000000L
    // The vault has settled 1e-6 ERG per share since the provision entered at 0
    val step = LDHelpers.SCALE / 1000000
    val owed = (BigInt(shares) * step / LDHelpers.SCALE).toLong
    val vault = LDNodeFixtures.vaultBox(ctx, 5L * erg, accX = step).toInputUTXO(ctx)
    val provision = LDBoxes.readProvision(LDNodeFixtures.provisionBox(ctx, shares, nft).toInputUTXO(ctx))

    val plan = LDOrderTransactions.redeem(ctx, wallet, pool(ctx), provision, Some(vault), Fee, Cap)
    plan.value shouldBe LDOrderTransactions.RETURNED + Headroom
    plan.tokens shouldEqual Seq(Token(nft, 1L))

    // Signing runs the vault's and the provision's claim paths
    val placed = signed(plan, Seq(funding(ctx, plan)))
    placed.claimedX shouldBe owed
    val out = outputs(placed.tx)
    out(0).getValue shouldBe 5L * erg - owed
    placed.provisionSuccessor shouldBe Some(out(1).getId.toString)

    val order = readBack(placed)
    order shouldBe a[LithosDexOrder.Redeem]
    val successor = LDBoxes.readProvision(InputUTXO(out(1)))
    successor.entryX shouldBe step
    // A pool whose accumulator is past the vault's, as it always is on chain
    val chain = fill(ctx, order, nftId => if (nftId == nft.toString) Some(successor) else None,
      poolBox = poolNode(ctx, accX = step * 2))
    chain.fills.head.provision.map(_.boxId) shouldBe Some(successor.boxId)
  }

  it should "move the NFT alone into the order when there is nothing to claim" in withCtx { ctx =>
    val nft = ErgoId.create("f1" * 32)
    val provision = LDBoxes.readProvision(LDNodeFixtures.provisionBox(ctx, 10000000000L, nft).toInputUTXO(ctx))
    val plan = LDOrderTransactions.redeem(ctx, wallet, pool(ctx), provision, None, Fee, Cap)
    val placed = signed(plan, Seq(funding(ctx, plan)))
    placed.claimedX shouldBe 0L
    placed.provisionSuccessor shouldBe None
    placed.tx.getSignedInputs.size shouldBe 1
    readBack(placed) shouldBe a[LithosDexOrder.Redeem]
  }

  // ─── cancelling ──────────────────────────────────────────────────────────

  "Cancelling an order that holds enough ERG" should "pay the fee from the order and reserve nothing" in withCtx { ctx =>
    val quote = pool(ctx).simSwap(erg, ergIn = true)
    val place = LDOrderTransactions.swap(ctx, wallet, pool(ctx), erg, ergIn = true, quote.amountOut, Fee, Cap)
    val order = signed(place, Seq(funding(ctx, place))).order

    val plan = LDOrderTransactions.cancel(ctx, wallet, order)
    plan.value shouldBe 0L
    plan.tokens shouldBe empty
    // Signing runs the order's refund path, which only the owner key satisfies
    val cancel = signed(plan, Seq.empty)
    cancel.feeFromOrder shouldBe true
    val refund = outputs(cancel.tx).head
    refund.getValue shouldBe order.value - LithosDexTransactions.TX_FEE
    refund.getErgoTree.bytes.toSeq.map("%02x".format(_)).mkString shouldBe wallet.contract.ergoTreeHex
  }

  "Cancelling an order that holds only its return" should "fund the fee from the wallet and return the tokens" in withCtx { ctx =>
    val amount = 500L * 1000000L
    val place = LDOrderTransactions.swap(ctx, wallet, pool(ctx), amount, ergIn = false, 1L, Fee, Cap)
    val order = signed(place, Seq(funding(ctx, place))).order

    val plan = LDOrderTransactions.cancel(ctx, wallet, order)
    plan.value shouldBe Headroom
    val cancel = signed(plan, Seq(funding(ctx, plan, index = 21)))
    cancel.feeFromOrder shouldBe false
    val refund = outputs(cancel.tx).head
    refund.getValue shouldBe order.value
    refund.getTokens.asScala.map(t => t.getId -> t.getValue) shouldEqual Seq(tokenY(ctx) -> amount)
  }
}
