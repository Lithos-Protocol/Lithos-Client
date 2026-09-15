package transactions.batching.lithosdex

import lithosdex.contracts.{LDOrderKind, LDOrderTerms, LDOrderContracts}
import node.model.NodeBox
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{BlockchainContext, Parameters}
import org.ergoplatform.sdk.ErgoId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scorex.crypto.hash.Blake2b256
import sigma.VersionContext
import sigma.ast.{BigIntConstant, ByteArrayConstant, Constant, ErgoTree, SType}
import sigma.serialization.ErgoTreeSerializer
import support.{FakeNodeContext, LDNodeFixtures}
import work.lithos.mutations.{Contract, Token, UTXO}

import java.math.BigInteger

/**
 * Reading LithosDex orders as the node reports them.
 *
 * Every box here is placed by a stranger, so the properties that matter are the refusals: a box the
 * parser accepts is one the batcher will spend, and one it accepts wrongly is a guaranteed rejection at
 * signing or, worse, an execution priced against terms the contract does not hold.
 */
class LithosDexOrderSpec extends AnyFlatSpec with Matchers {

  private lazy val fake = FakeNodeContext(numAddresses = 1)

  private def withCtx[A](f: BlockchainContext => A): A = fake._1.getClient.execute(ctx => f(ctx))

  private val poolNft = ErgoId.create("aa" * 32)
  private val lit = ErgoId.create("dd" * 32)
  private val ownerNft = ErgoId.create("f0" * 32)

  private def owner(ctx: BlockchainContext) =
    ctx.newProverBuilder().withDLogSecret(BigInteger.valueOf(7007L)).build().getAddress

  private def terms(ctx: BlockchainContext): LDOrderTerms =
    LDOrderTerms(owner(ctx).getPublicKey, poolNft, executorFee = 6000000L, maxMinerFee = 2000000L)

  private def expectedTerms(ctx: BlockchainContext): LithosDexTerms =
    LithosDexTerms(Contract.fromAddress(owner(ctx)).ergoTreeHex, Hex.toHexString(poolNft.getBytes),
      6000000L, 2000000L)

  private def boxOf(ctx: BlockchainContext, contract: Contract, value: Long, tokens: Token*): NodeBox =
    LDNodeFixtures.nodeBox(ctx, UTXO(contract, value, tokens))

  private def withTree(box: NodeBox, tree: ErgoTree): NodeBox = box.copy(ergoTree = Hex.toHexString(tree.bytes))

  private def v6[A](f: => A): A =
    VersionContext.withVersions(VersionContext.V6SoftForkVersion, VersionContext.V6SoftForkVersion)(f)

  /** A sell order's terms with one replaced, built through the template rather than the typed builder. */
  private def sellWith(ctx: BlockchainContext, name: String, value: Constant[SType]): Contract = {
    val t = terms(ctx)
    val base: Map[String, Constant[SType]] = Map(
      LDOrderContracts.REDEEMER -> sigma.ast.SigmaPropConstant(t.redeemer).asInstanceOf[Constant[SType]],
      LDOrderContracts.POOL_NFT -> ByteArrayConstant(poolNft.getBytes).asInstanceOf[Constant[SType]],
      LDOrderContracts.EXECUTOR_FEE -> LDOrderContracts.amount(t.executorFee),
      LDOrderContracts.MAX_MINER_FEE -> LDOrderContracts.amount(t.maxMinerFee),
      LDOrderContracts.BASE_AMOUNT -> LDOrderContracts.amount(Parameters.OneErg),
      LDOrderContracts.MIN_QUOTE -> LDOrderContracts.amount(1L))
    LDOrderContracts.template(LDOrderKind.SwapSell).withValues(base + (name -> value))
  }

  // ─── what it reads ────────────────────────────────────────────────────────

  "An order box" should "read back every term of a sell" in withCtx { ctx =>
    val box = boxOf(ctx, LDOrderContracts.swapSell(terms(ctx), 3L * Parameters.OneErg, 12345L), 4L * Parameters.OneErg)
    LithosDexOrder.parse(box) shouldBe Some(LithosDexOrder.SwapSell(box, expectedTerms(ctx), 3L * Parameters.OneErg, 12345L))
  }

  it should "read back every term of a buy, and the tokens it sells" in withCtx { ctx =>
    val box = boxOf(ctx, LDOrderContracts.swapBuy(terms(ctx), 777L), Parameters.MinFee, Token(lit, 5000L))
    val parsed = LithosDexOrder.parse(box)
    parsed shouldBe Some(LithosDexOrder.SwapBuy(box, expectedTerms(ctx), 777L))
    parsed.collect { case b: LithosDexOrder.SwapBuy => (b.tokenId, b.amount) } shouldBe Some(lit.toString -> 5000L)
  }

  it should "read back every term of a deposit" in withCtx { ctx =>
    val box = boxOf(ctx, LDOrderContracts.deposit(terms(ctx), 2L * Parameters.OneErg, 4321L), 3L * Parameters.OneErg,
      Token(lit, 5000L))
    LithosDexOrder.parse(box) shouldBe Some(LithosDexOrder.Deposit(box, expectedTerms(ctx), 2L * Parameters.OneErg, 4321L))
  }

  it should "read back every term of a redeem, and the NFT it holds" in withCtx { ctx =>
    val box = boxOf(ctx, LDOrderContracts.redeem(terms(ctx)), Parameters.OneErg, Token(ownerNft, 1L))
    val parsed = LithosDexOrder.parse(box)
    parsed shouldBe Some(LithosDexOrder.Redeem(box, expectedTerms(ctx)))
    parsed.collect { case r: LithosDexOrder.Redeem => r.ownerNft } shouldBe Some(ownerNft.toString)
  }

  // ─── what it refuses ──────────────────────────────────────────────────────

  it should "refuse a tree sharing an order's template but not its fixed constants" in withCtx { ctx =>
    val order = LDOrderContracts.swapSell(terms(ctx), Parameters.OneErg, 1L)
    val feeProp = order.ergoTree.constants.indexWhere(_ == ByteArrayConstant(Contract.FEE.propBytes))
    feeProp should be >= 0
    val altered = v6 {
      ErgoTree.fromBytes(ErgoTreeSerializer.DefaultSerializer.substituteConstants(order.propBytes, Array(feeProp),
        Array(ByteArrayConstant(Contract.fromAddress(owner(ctx)).propBytes).asInstanceOf[Constant[SType]]))._1)
    }
    // Same template hash, so this reaches the rebuild rather than failing the lookup
    Blake2b256.hash(altered.template).toSeq shouldBe Blake2b256.hash(order.ergoTree.template).toSeq

    val box = boxOf(ctx, order, 2L * Parameters.OneErg)
    LithosDexOrder.parse(box) should not be empty
    LithosDexOrder.parse(withTree(box, altered)) shouldBe None
  }

  it should "refuse an amount a Long cannot hold" in withCtx { ctx =>
    val huge = BigIntConstant(BigInteger.ONE.shiftLeft(63)).asInstanceOf[Constant[SType]]
    LithosDexOrder.parse(boxOf(ctx, sellWith(ctx, LDOrderContracts.BASE_AMOUNT, huge), 2L * Parameters.OneErg)) shouldBe None
  }

  it should "refuse a negative fee" in withCtx { ctx =>
    val negative = BigIntConstant(BigInteger.valueOf(-1L)).asInstanceOf[Constant[SType]]
    LithosDexOrder.parse(boxOf(ctx, sellWith(ctx, LDOrderContracts.EXECUTOR_FEE, negative), 2L * Parameters.OneErg)) shouldBe None
  }

  it should "refuse a pool NFT that is not 32 bytes" in withCtx { ctx =>
    val short = ByteArrayConstant(Array.fill(31)(0xaa.toByte)).asInstanceOf[Constant[SType]]
    LithosDexOrder.parse(boxOf(ctx, sellWith(ctx, LDOrderContracts.POOL_NFT, short), 2L * Parameters.OneErg)) shouldBe None
  }

  it should "refuse a sell of nothing and a deposit of no ERG" in withCtx { ctx =>
    LithosDexOrder.parse(boxOf(ctx, LDOrderContracts.swapSell(terms(ctx), 0L, 1L), Parameters.OneErg)) shouldBe None
    LithosDexOrder.parse(boxOf(ctx, LDOrderContracts.deposit(terms(ctx), 0L, 1L), Parameters.OneErg, Token(lit, 5L))) shouldBe None
  }

  it should "refuse a box shaped for some other kind" in withCtx { ctx =>
    val sell = LDOrderContracts.swapSell(terms(ctx), Parameters.OneErg, 1L)
    val buy = LDOrderContracts.swapBuy(terms(ctx), 1L)
    val deposit = LDOrderContracts.deposit(terms(ctx), Parameters.OneErg, 1L)
    val redeem = LDOrderContracts.redeem(terms(ctx))
    LithosDexOrder.parse(boxOf(ctx, sell, 2L * Parameters.OneErg, Token(lit, 5L))) shouldBe None
    LithosDexOrder.parse(boxOf(ctx, buy, Parameters.OneErg)) shouldBe None
    LithosDexOrder.parse(boxOf(ctx, buy, Parameters.OneErg, Token(lit, 5L), Token(ownerNft, 1L))) shouldBe None
    LithosDexOrder.parse(boxOf(ctx, deposit, 2L * Parameters.OneErg, Token(lit, 5L), Token(ownerNft, 1L))) shouldBe None
    LithosDexOrder.parse(boxOf(ctx, redeem, Parameters.OneErg, Token(ownerNft, 2L))) shouldBe None
  }

  it should "not recognise a wallet box, an ErgoDEX order or garbage" in withCtx { ctx =>
    LithosDexOrder.parse(boxOf(ctx, Contract.fromAddress(owner(ctx)), Parameters.OneErg)) shouldBe None
    LithosDexOrder.parse(support.ErgoDexFixtures.orderBox) shouldBe None
    LithosDexOrder.parse(support.ErgoDexFixtures.orderBox.copy(ergoTree = "zz")) shouldBe None
  }
}
