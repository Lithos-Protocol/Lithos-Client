package transactions.batching.lithosdex

import lithosdex.contracts.{LDOrderContracts, LDOrderTerms, LDT2TContracts}
import lithosdex.{LDHelpers, LDT2TLiquidityPool}
import node.MutationConversions._
import node.model.NodeBox
import org.ergoplatform.appkit.scalaapi.scalaLongType
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue, Parameters}
import org.ergoplatform.sdk.ErgoId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sigma.Colls
import support.{FakeNodeContext, LDNodeFixtures}
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

import java.math.BigInteger
import scala.collection.JavaConverters._
import scala.concurrent.duration._

/**
 * `LDT2TExecution` against the real token/token contracts: every kind priced, built and signed by this
 * client's wallet, chained through the pool, closed with a flush, and the refusals that must price as None.
 * Signing runs every script, so each accepted run is the contracts' own verdict on the builders.
 */
class LDT2TExecutionSpec extends AnyFlatSpec with Matchers {

  private lazy val fake = FakeNodeContext(numAddresses = 1)
  private def wallet = fake._3
  private def withCtx[A](f: BlockchainContext => A): A = fake._1.getClient.execute(ctx => f(ctx))

  private val Fee = 6000000L
  private val Height = 123414
  private val RewardErg = Parameters.MinFee

  private val poolNFT = ErgoId.create("c1" * 32)
  private val vaultNFT = ErgoId.create("c2" * 32)
  private val provToken = ErgoId.create("c3" * 32)
  private val tokenX = ErgoId.create("c4" * 32)
  private val tokenY = ErgoId.create("c5" * 32)
  private val ownerNFT = ErgoId.create("c6" * 32)
  private val deployment = LDT2TContracts(poolNFT, vaultNFT, provToken)

  private val reservesX = 50000000000L
  private val reservesY = 20000000000L
  private val supply = 30000000000L
  private val pendingX = 4000000L
  private val pendingY = 3000000L

  private def owner(ctx: BlockchainContext) =
    ctx.newProverBuilder().withDLogSecret(BigInteger.valueOf(7007L)).build().getAddress

  private def terms(ctx: BlockchainContext, nft: ErgoId = poolNFT): LDOrderTerms =
    LDOrderTerms(owner(ctx).getPublicKey, nft, Fee, 2000000L)

  private def longs(v: Array[Long]): ErgoValue[_] = ErgoValue.of(Colls.fromArray(v), scalaLongType)
  private def big(v: BigInt): ErgoValue[_] = ErgoValue.of(v.bigInteger)

  private def poolUTXO(accX: BigInt = BigInt(5), accY: BigInt = BigInt(7)): UTXO =
    UTXO(deployment.liquidityPool, LDHelpers.T2T_POOL_MIN_VALUE,
      Seq(Token(poolNFT, 1L), Token(tokenX, reservesX + pendingX), Token(tokenY, reservesY + pendingY),
        Token(provToken, LDHelpers.PROV_TOKEN_SUPPLY - 10L)),
      Seq(ErgoValue.of(LDHelpers.LOCKED_LP - supply), longs(Array(9970L, 15L, 25L)), longs(Array(pendingX, pendingY)),
        big(accX), big(accY)))

  private def poolIn(ctx: BlockchainContext): InputUTXO = LDNodeFixtures.nodeBox(ctx, poolUTXO(), index = 0).toInputUTXO(ctx)

  private def vaultIn(ctx: BlockchainContext): InputUTXO = LDNodeFixtures.nodeBox(ctx,
    UTXO(deployment.feeVault, LDHelpers.T2T_VAULT_MIN_VALUE,
      Seq(Token(vaultNFT, 1L), Token(tokenX, 1L), Token(tokenY, 1L)), Seq(big(BigInt(0)), big(BigInt(0)))),
    index = 1, txId = "ab" * 32).toInputUTXO(ctx)

  private def order(ctx: BlockchainContext, contract: Contract, value: Long, index: Int, tokens: Token*): NodeBox =
    LDNodeFixtures.nodeBox(ctx, UTXO(contract, value, tokens), index = index, txId = "cd" * 32)

  private def swap(ctx: BlockchainContext, index: Int, sold: ErgoId = tokenX, amount: Long = 1000000000L,
                   minQuote: Long = 1L, nft: ErgoId = poolNFT): Option[LDT2TOrder] =
    LDT2TOrder.parse(order(ctx, LDOrderContracts.t2tSwap(terms(ctx, nft), minQuote), RewardErg + Fee, index,
      Token(sold, amount)))

  private def deposit(ctx: BlockchainContext, index: Int, x: Token = Token(tokenX, 5000000000L),
                      y: Token = Token(tokenY, 2100000000L)): Option[LDT2TOrder] =
    LDT2TOrder.parse(order(ctx, LDOrderContracts.t2tDeposit(terms(ctx), 1L),
      LDHelpers.PROVISION_MIN + RewardErg + Fee, index, x, y))

  private def redeem(ctx: BlockchainContext, index: Int): Option[LDT2TOrder] =
    LDT2TOrder.parse(order(ctx, LDOrderContracts.t2tRedeem(terms(ctx)), RewardErg + Fee, index, Token(ownerNFT, 1L)))

  private def provision(ctx: BlockchainContext, shares: Long = 3000000000L): LDBoxes.Provision =
    LDBoxes.readProvision(LDNodeFixtures.nodeBox(ctx,
      UTXO(deployment.provisionGuard, LDHelpers.PROVISION_MIN, Seq(Token(provToken, 1L)),
        Seq(big(BigInt(5)), big(BigInt(7)), ErgoValue.of(Colls.fromArray(ownerNFT.getBytes),
          org.ergoplatform.appkit.scalaapi.scalaByteType), ErgoValue.of(shares))),
      index = 3, txId = "ef" * 32).toInputUTXO(ctx))

  private def run(ctx: BlockchainContext, orders: Seq[LDT2TOrder], provisions: LithosDexExecution.Provisions =
    LithosDexExecution.NoProvisions, minerFeeCeiling: Long = 0L): LDT2TRun =
    LDT2TExecution.run(ctx, wallet, deployment, poolIn(ctx), orders, limit = 10, minRevenue = 1000000L,
      provisions = provisions, blockHeight = Height, minerFeeCeiling = minerFeeCeiling, useTrueProp = false,
      deadline = 30.seconds.fromNow)

  "A token/token run" should "sign a swap each way, a deposit and a redemption, chained through the pool" in withCtx { ctx =>
    val prov = provision(ctx)
    val orders = Seq(swap(ctx, 10), swap(ctx, 11, sold = tokenY, amount = 400000000L), deposit(ctx, 12), redeem(ctx, 13))
    orders.foreach(_ shouldBe defined)
    val r = run(ctx, orders.flatten, provisions = owner => if (owner == ownerNFT.toString) Some(prov) else None)
    withClue(s"unbuildable ${r.unbuildable}: ") {
      r.unbuildable shouldBe empty
      r.chain.map(_.transactions.size) shouldBe Some(4)
    }
    val chain = r.chain.get
    // Each execution spends the pool box the one before it left
    chain.transactions.sliding(2).foreach { case Seq(a, b) =>
      b.getSignedInputs.asScala.head.getId shouldBe a.getOutputsToSpend.get(0).getId
    }
    chain.takings.value shouldBe 4L * Fee
    chain.fills.map(_.order.kind.scriptName) should contain allOf("LD_T2T_SwapOrder", "LD_T2T_DepositOrder", "LD_T2T_RedeemOrder")
    val last = LDT2TLiquidityPool(InputUTXO(chain.transactions.last.getOutputsToSpend.get(0)))
    last.value shouldBe LDHelpers.T2T_POOL_MIN_VALUE
  }

  it should "pay the capped miner fee out of the executor fee when broadcast" in withCtx { ctx =>
    val r = run(ctx, Seq(swap(ctx, 10).get), minerFeeCeiling = 1000000L)
    val tx = r.chain.get.transactions.head
    r.chain.get.takings.value shouldBe Fee - 1000000L
    tx.getOutputsToSpend.size shouldBe 4
  }

  it should "close with a flush that moves the pending fees into the vault" in withCtx { ctx =>
    val chain = run(ctx, Seq(swap(ctx, 10).get)).chain.get
    val closed = LDT2TExecution.withFlush(ctx, wallet, deployment, chain, vaultIn(ctx), Height)
    closed.flush shouldBe defined
    val pool = LDT2TLiquidityPool(InputUTXO(closed.flush.get.getOutputsToSpend.get(0)))
    pool.pendingX shouldBe 0L
    pool.pendingY shouldBe 0L
    val vault = InputUTXO(closed.flush.get.getOutputsToSpend.get(1))
    vault.tokens(1).amount should be > 1L
    vault.value shouldBe LDHelpers.T2T_VAULT_MIN_VALUE
  }

  "Pricing" should "refuse another pool's order, a token the pool does not hold, and an unmet minimum" in withCtx { ctx =>
    val pool = LDT2TLiquidityPool(poolIn(ctx))
    def priced(o: Option[LDT2TOrder]) = LDT2TExecution.price(o.get, pool, 1000000L, LithosDexExecution.NoProvisions)
    priced(swap(ctx, 10)) shouldBe defined
    priced(swap(ctx, 10, nft = ErgoId.create("d1" * 32))) shouldBe None
    priced(swap(ctx, 10, sold = ownerNFT)) shouldBe None
    priced(swap(ctx, 10, minQuote = 1000000000000L)) shouldBe None
    priced(deposit(ctx, 12, x = Token(tokenY, 2100000000L), y = Token(tokenX, 5000000000L))) shouldBe None
  }

  it should "refuse a redemption whose provision is not found" in withCtx { ctx =>
    LDT2TExecution.price(redeem(ctx, 13).get, LDT2TLiquidityPool(poolIn(ctx)), 1000000L,
      LithosDexExecution.NoProvisions) shouldBe None
  }

  "Parsing" should "refuse an ERG/token order and a box of the wrong shape" in withCtx { ctx =>
    LDT2TOrder.parse(order(ctx, LDOrderContracts.swapSell(terms(ctx), 1000000L, 1L), RewardErg + Fee, 20)) shouldBe None
    LDT2TOrder.parse(order(ctx, LDOrderContracts.t2tSwap(terms(ctx), 1L), RewardErg + Fee, 21)) shouldBe None
    LDT2TOrder.parse(order(ctx, LDOrderContracts.t2tDeposit(terms(ctx), 1L), RewardErg + Fee, 22,
      Token(tokenX, 10L))) shouldBe None
  }
}
