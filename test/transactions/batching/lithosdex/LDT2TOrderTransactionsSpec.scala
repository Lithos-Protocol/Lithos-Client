package transactions.batching.lithosdex

import lithosdex.contracts.LDT2TContracts
import lithosdex.{LDHelpers, LDT2TLiquidityPool}
import node.MutationConversions._
import node.model.{NodeAsset, NodeBox, NodeRegisters}
import org.ergoplatform.appkit.scalaapi.{scalaByteType, scalaLongType}
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue}
import org.ergoplatform.sdk.ErgoId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sigma.Colls
import support.{FakeNodeContext, LDNodeFixtures}
import work.lithos.mutations.{InputUTXO, Token, UTXO}

import scala.concurrent.duration._

/**
 * Placing token/token orders from this wallet: each order box is built, signed, read back as the order it
 * should be, and then filled by the batcher's own execution against a real pool box, so a placement no
 * executor could fill fails here. A redeem placement that claims first signs against the real vault and
 * provision contracts.
 */
class LDT2TOrderTransactionsSpec extends AnyFlatSpec with Matchers {

  private lazy val fake = FakeNodeContext(numAddresses = 1)
  private def wallet = fake._3
  private def withCtx[A](f: BlockchainContext => A): A = fake._1.getClient.execute(ctx => f(ctx))

  private val Fee = 3000000L
  private val Cap = 1000000L
  private val Height = 123414
  private val Headroom = LithosDexTransactions.FUNDING_HEADROOM
  private val Step: BigInt = LDHelpers.SCALE / 1000000
  private val Shares = 3000000000L

  private val tokenX = ErgoId.create("f4" * 32)
  private val tokenY = ErgoId.create("f5" * 32)
  private val ownerNFT = ErgoId.create("f6" * 32)
  private val deployment = LDT2TContracts(ErgoId.create("f1" * 32), ErgoId.create("f2" * 32), ErgoId.create("f3" * 32))

  private def longs(v: Array[Long]): ErgoValue[_] = ErgoValue.of(Colls.fromArray(v), scalaLongType)
  private def big(v: BigInt): ErgoValue[_] = ErgoValue.of(v.bigInteger)

  private def poolNode(ctx: BlockchainContext): NodeBox = LDNodeFixtures.nodeBox(ctx,
    UTXO(deployment.liquidityPool, LDHelpers.T2T_POOL_MIN_VALUE,
      Seq(Token(deployment.poolNFT, 1L), Token(tokenX, 50000000000L), Token(tokenY, 20000000000L),
        Token(deployment.provToken, LDHelpers.PROV_TOKEN_SUPPLY - 10L)),
      Seq(ErgoValue.of(LDHelpers.LOCKED_LP - 30000000000L), longs(Array(9970L, 15L, 25L)), longs(Array(0L, 0L)),
        big(BigInt(5) + 2 * Step), big(BigInt(7) + 2 * Step))), index = 0, txId = "ab" * 32)

  private def pool(ctx: BlockchainContext): LDT2TLiquidityPool = LDT2TLiquidityPool(poolNode(ctx).toInputUTXO(ctx))

  private def vaultIn(ctx: BlockchainContext): InputUTXO = LDNodeFixtures.nodeBox(ctx,
    UTXO(deployment.feeVault, LDHelpers.T2T_VAULT_MIN_VALUE,
      Seq(Token(deployment.vaultNFT, 1L), Token(tokenX, 1000000L), Token(tokenY, 1000000L)),
      Seq(big(BigInt(5) + Step), big(BigInt(7) + Step))), index = 1, txId = "ac" * 32).toInputUTXO(ctx)

  private def provision(ctx: BlockchainContext): LDBoxes.Provision = LDBoxes.readProvision(LDNodeFixtures.nodeBox(ctx,
    UTXO(deployment.provisionGuard, LDHelpers.PROVISION_MIN, Seq(Token(deployment.provToken, 1L)),
      Seq(big(BigInt(5)), big(BigInt(7)), ErgoValue.of(Colls.fromArray(ownerNFT.getBytes), scalaByteType),
        ErgoValue.of(Shares))), index = 2, txId = "ad" * 32).toInputUTXO(ctx))

  private def funding(ctx: BlockchainContext, plan: DexPlan[_], extra: Seq[Token] = Seq.empty): InputUTXO =
    LDNodeFixtures.nodeBox(ctx, UTXO(wallet.contract, plan.value, plan.tokens ++ extra), index = 20, txId = "ef" * 32)
      .toInputUTXO(ctx)

  private def signed(plan: DexPlan[LDOrderTx], inputs: Seq[InputUTXO]): LDOrderTx = {
    val unsigned = plan.build(inputs)
    unsigned.describe(wallet.sign(unsigned.tx))
  }

  private def asNode(box: InputUTXO): NodeBox =
    NodeBox(box.id.toString, box.input.getTransactionId, box.value, box.input.getTransactionIndex,
      box.input.getCreationHeight, box.contract.ergoTreeHex, box.tokens.map(t => NodeAsset(t.id.toString, t.amount)),
      NodeRegisters(box.registers.zipWithIndex.map { case (v, i) => s"R${i + 4}" -> v.toHex }.toMap))

  private def readBack(tx: LDOrderTx): LDT2TOrder =
    LDT2TOrder.parse(asNode(tx.order)).getOrElse(fail("the placed order box does not parse as a token/token order"))

  private def fill(ctx: BlockchainContext, order: LDT2TOrder,
                   provisions: LithosDexExecution.Provisions = LithosDexExecution.NoProvisions): LDT2TChain =
    LDT2TExecution.run(ctx, wallet, deployment, poolNode(ctx).toInputUTXO(ctx), Seq(order), 10, 0L, provisions, Height,
      0L, useTrueProp = false, 1.minute.fromNow).chain.getOrElse(fail(s"the placed ${order.kind} did not fill"))

  "A token/token swap order" should "lock the token sold, the fee and the return, and fill to this wallet" in withCtx { ctx =>
    val quote = pool(ctx).simSwap(1000000000L, xIn = true)
    val plan = LDOrderTransactions.t2tSwap(ctx, wallet, pool(ctx), tokenX, 1000000000L, quote.amountOut, Fee, Cap)
    plan.value shouldBe Fee + LDOrderTransactions.RETURNED + Headroom
    plan.tokens shouldBe Seq(Token(tokenX, 1000000000L))

    val placed = signed(plan, Seq(funding(ctx, plan)))
    placed.order.value shouldBe LDOrderTransactions.t2tOrderValue(Fee)
    val order = readBack(placed)
    order shouldBe a[LDT2TOrder.Swap]
    order.terms.redeemerTree shouldBe wallet.contract.ergoTreeHex
    order.terms.executorFee shouldBe Fee

    fill(ctx, order).fills.head.rewardTokens shouldBe Seq(Token(tokenY, quote.amountOut))
  }

  it should "refuse a token the pool does not hold" in withCtx { ctx =>
    an[IllegalArgumentException] should be thrownBy
      LDOrderTransactions.t2tSwap(ctx, wallet, pool(ctx), ownerNFT, 10L, 1L, Fee, Cap)
  }

  "A token/token deposit order" should "lock both tokens X first, the provision box, and fill for its shares" in withCtx { ctx =>
    val split = LDT2TExecution.depositSplit(pool(ctx), 5000000000L, 2100000000L)
    val plan = LDOrderTransactions.t2tDeposit(ctx, wallet, pool(ctx), 5000000000L, 2100000000L, split.shares.toLong, Fee, Cap)
    plan.value shouldBe LDHelpers.PROVISION_MIN + Fee + LDOrderTransactions.RETURNED + Headroom
    val order = readBack(signed(plan, Seq(funding(ctx, plan))))
    order shouldBe a[LDT2TOrder.Deposit]
    order.box.assets.map(_.tokenId) shouldBe Seq(tokenX.toString, tokenY.toString)
    fill(ctx, order).fills.head.shares shouldBe split.shares.toLong
  }

  "A token/token redeem order" should "claim what the vault owes first, then fill against the claimed provision" in withCtx { ctx =>
    val prov = provision(ctx)
    val plan = LDOrderTransactions.t2tRedeem(ctx, wallet, deployment, pool(ctx), prov, Some(vaultIn(ctx)), Fee, Cap)
    plan.tokens shouldBe Seq(Token(ownerNFT, 1L))
    val placed = signed(plan, Seq(funding(ctx, plan)))
    // Each owed Step per share across the provision's shares
    val owed = (Step * Shares / LDHelpers.SCALE).toLong
    placed.claimedX shouldBe owed
    placed.claimedY shouldBe owed
    placed.provisionSuccessor shouldBe defined

    val successor = LDBoxes.readProvision(InputUTXO(placed.tx.getOutputsToSpend.get(1)))
    successor.entryX shouldBe BigInt(5) + Step
    val order = readBack(placed)
    order shouldBe a[LDT2TOrder.Redeem]
    val filled = fill(ctx, order, nft => if (nft == ownerNFT.toString) Some(successor) else None)
    filled.fills.head.provision.map(_.boxId) shouldBe Some(successor.boxId)
  }

  "A token/token flush from this wallet" should "move pending fees and the pool's accumulators into the vault" in withCtx { ctx =>
    // The pool's accumulators stand one Step per share past the vault's, which across its supply is this much pending
    val pending = (Step * 30000000000L / LDHelpers.SCALE).toLong
    val poolIn = LDNodeFixtures.nodeBox(ctx, UTXO(deployment.liquidityPool, LDHelpers.T2T_POOL_MIN_VALUE,
      Seq(Token(deployment.poolNFT, 1L), Token(tokenX, 50000000000L + pending), Token(tokenY, 20000000000L + pending),
        Token(deployment.provToken, LDHelpers.PROV_TOKEN_SUPPLY - 10L)),
      Seq(ErgoValue.of(LDHelpers.LOCKED_LP - 30000000000L), longs(Array(9970L, 15L, 25L)), longs(Array(pending, pending)),
        big(BigInt(5) + 2 * Step), big(BigInt(7) + 2 * Step))), index = 0, txId = "ab" * 32).toInputUTXO(ctx)
    val plan = LithosDexTransactions.t2tFlush(ctx, wallet, deployment, poolIn, vaultIn(ctx))
    plan.tokens shouldBe empty
    val unsigned = plan.build(Seq(funding(ctx, plan)))
    val flushed = unsigned.describe(wallet.sign(unsigned.tx))
    (flushed.flushedX, flushed.flushedY) shouldBe ((pending, pending))
    val outputs = flushed.tx.getOutputsToSpend
    val poolOut = LDT2TLiquidityPool(InputUTXO(outputs.get(0)))
    (poolOut.pendingX, poolOut.pendingY, poolOut.reservesX) shouldBe ((0L, 0L, 50000000000L))
    val vaultOut = lithosdex.states.LDT2TFeeValue.fromBox(InputUTXO(outputs.get(1)), 0, Height)
    (vaultOut.balanceX, vaultOut.balanceY, vaultOut.accX) shouldBe ((1000000L + pending, 1000000L + pending, BigInt(5) + 2 * Step))
    outputs.get(1).getValue shouldBe LDHelpers.T2T_VAULT_MIN_VALUE
  }

  it should "be refused on a pool with nothing pending" in withCtx { ctx =>
    an[IllegalArgumentException] should be thrownBy
      LithosDexTransactions.t2tFlush(ctx, wallet, deployment, poolNode(ctx).toInputUTXO(ctx), vaultIn(ctx))
  }

  "A token/token claim from this wallet" should "pay what the vault owes and move the entry to the vault's" in withCtx { ctx =>
    val prov = provision(ctx)
    val plan = LithosDexTransactions.t2tClaim(ctx, wallet, deployment, vaultIn(ctx), prov)
    plan.tokens shouldBe Seq(Token(ownerNFT, 1L))
    val unsigned = plan.build(Seq(funding(ctx, plan)))
    val claimed = unsigned.describe(wallet.sign(unsigned.tx))
    val owed = (Step * Shares / LDHelpers.SCALE).toLong
    (claimed.claimedX, claimed.claimedY) shouldBe ((owed, owed))
    val successor = LDBoxes.readProvision(InputUTXO(claimed.tx.getOutputsToSpend.get(1)))
    (successor.entryX, successor.entryY, successor.shares) shouldBe ((BigInt(5) + Step, BigInt(7) + Step, Shares))
    val vaultOut = lithosdex.states.LDT2TFeeValue.fromBox(InputUTXO(claimed.tx.getOutputsToSpend.get(0)), 0, Height)
    (vaultOut.balanceX, vaultOut.balanceY) shouldBe ((1000000L - owed, 1000000L - owed))
  }

  "A token/token direct swap" should "spend the pool now, keep its ERG, and pay the bought token here" in withCtx { ctx =>
    val poolIn = poolNode(ctx).toInputUTXO(ctx)
    val q = pool(ctx).simSwap(1000000000L, xIn = true)
    val plan = LithosDexTransactions.t2tSwap(ctx, wallet, deployment, poolIn, 1000000000L, xIn = true, q.amountOut)
    (plan.value, plan.tokens) shouldBe ((Headroom, Seq(Token(tokenX, 1000000000L))))
    val unsigned = plan.build(Seq(funding(ctx, plan)))
    // Signing runs the pool contract, so a swap it refuses fails here
    val swapped = unsigned.describe(wallet.sign(unsigned.tx))
    val outputs = swapped.tx.getOutputsToSpend
    val poolOut = LDT2TLiquidityPool(InputUTXO(outputs.get(0)))
    (poolOut.reservesX, poolOut.reservesY) shouldBe ((pool(ctx).afterSwap(q).reservesX, pool(ctx).afterSwap(q).reservesY))
    outputs.get(0).getValue shouldBe LDHelpers.T2T_POOL_MIN_VALUE
    val bought = (1 until outputs.size).map(outputs.get).filter(o => InputUTXO(o).contract.ergoTreeHex == wallet.contract.ergoTreeHex)
      .flatMap(o => InputUTXO(o).tokens).filter(_.id == tokenY).map(_.amount).sum
    bought shouldBe q.amountOut
    an[IllegalArgumentException] should be thrownBy
      LithosDexTransactions.t2tSwap(ctx, wallet, deployment, poolIn, 1000000000L, xIn = true, q.amountOut + 1)
  }

  "A token/token direct deposit" should "open a provision at output 1, owned by the NFT the pool box mints" in withCtx { ctx =>
    val poolIn = poolNode(ctx).toInputUTXO(ctx)
    val q = pool(ctx).simDepositForShares(Shares)
    val plan = LithosDexTransactions.t2tDeposit(ctx, wallet, deployment, poolIn, Shares)
    plan.tokens shouldBe Seq(Token(tokenX, q.amountX), Token(tokenY, q.amountY))
    val unsigned = plan.build(Seq(funding(ctx, plan)))
    val deposited = unsigned.describe(wallet.sign(unsigned.tx))
    deposited.ownerNFT shouldBe poolIn.id
    val prov = LDBoxes.readProvision(InputUTXO(deposited.tx.getOutputsToSpend.get(1)))
    (prov.ownerNFT, prov.shares, prov.entryX, prov.entryY) shouldBe ((poolIn.id, Shares, pool(ctx).accX, pool(ctx).accY))
    InputUTXO(deposited.tx.getOutputsToSpend.get(2)).tokens shouldBe Seq(Token(poolIn.id, 1L))
  }

  "A token/token direct redemption" should "return both reserves' share and the box's ERG, and burn the NFT" in withCtx { ctx =>
    val prov = provision(ctx)
    val q = pool(ctx).simRedeem(Shares)
    val plan = LithosDexTransactions.t2tRedeem(ctx, wallet, deployment, poolNode(ctx).toInputUTXO(ctx), prov)
    plan.tokens shouldBe Seq(Token(ownerNFT, 1L))
    val unsigned = plan.build(Seq(funding(ctx, plan)))
    // Signing runs the pool and the token/token provision logic
    val redeemed = unsigned.describe(wallet.sign(unsigned.tx))
    val payout = InputUTXO(redeemed.tx.getOutputsToSpend.get(1))
    payout.value shouldBe LDHelpers.PROVISION_MIN
    payout.tokens.toSet shouldBe Set(Token(tokenX, q.amountX), Token(tokenY, q.amountY))
    (0 until redeemed.tx.getOutputsToSpend.size).map(i => InputUTXO(redeemed.tx.getOutputsToSpend.get(i)))
      .exists(_.tokens.exists(_.id == ownerNFT)) shouldBe false
  }

  it should "place without a claim when the vault owes nothing" in withCtx { ctx =>
    val plan = LDOrderTransactions.t2tRedeem(ctx, wallet, deployment, pool(ctx), provision(ctx), None, Fee, Cap)
    val placed = signed(plan, Seq(funding(ctx, plan)))
    placed.claimedX shouldBe 0L
    readBack(placed) shouldBe a[LDT2TOrder.Redeem]
  }
}
