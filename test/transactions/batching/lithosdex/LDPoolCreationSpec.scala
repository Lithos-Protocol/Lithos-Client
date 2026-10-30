package transactions.batching.lithosdex

import lithosdex.contracts.{LDContracts, LDT2TContracts}
import lithosdex.{LDGenesisBuilder, LDHelpers, LDLiquidityPool, LDT2TLiquidityPool}
import node.MutationConversions._
import node.model.{NodeAsset, NodeBox, NodeRegisters}
import org.ergoplatform.appkit.{BlockchainContext, Parameters, SignedTransaction}
import org.ergoplatform.sdk.ErgoId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import support.{FakeNodeContext, LDNodeFixtures}
import transactions.batching.lithosdex.LDPoolCreation._
import work.lithos.mutations.{InputUTXO, Token, UTXO}

import scala.collection.JavaConverters._
import scala.concurrent.duration._

/**
 * Creating a pool: the four-transaction chain for both kinds, each signed on the outputs of the one before,
 * the genesis checked sound with the creator's provision in it, and that provision redeemed by the batcher's
 * own execution against the pool the chain creates, down to the supply no provision may take.
 */
class LDPoolCreationSpec extends AnyFlatSpec with Matchers {

  private lazy val fake = FakeNodeContext(numAddresses = 1)
  private def wallet = fake._3
  private def withCtx[A](f: BlockchainContext => A): A = fake._1.getClient.execute(ctx => f(ctx))

  private val Fee = 3000000L
  private val Cap = 1000000L
  private val Height = 123414
  private val tokenX = ErgoId.create("a4" * 32)
  private val tokenY = ErgoId.create("a5" * 32)
  private val unrelated = ErgoId.create("a6" * 32)
  private val Fees = LDHelpers.GENESIS_FEE_PARAMS.toSeq

  /** The wallet's funding: what the plan asks for, and a token the creation must carry through untouched. */
  private def funding(ctx: BlockchainContext, value: Long, tokens: Seq[Token], index: Int = 0): InputUTXO =
    LDNodeFixtures.nodeBox(ctx, UTXO(wallet.contract, value, tokens :+ Token(unrelated, 7L)), index = index,
      txId = "9a" * 32).toInputUTXO(ctx)

  private def asNode(box: InputUTXO): NodeBox =
    NodeBox(box.id.toString, box.input.getTransactionId, box.value, box.input.getTransactionIndex,
      box.input.getCreationHeight, box.contract.ergoTreeHex, box.tokens.map(t => NodeAsset(t.id.toString, t.amount)),
      NodeRegisters(box.registers.zipWithIndex.map { case (v, i) => s"R${i + 4}" -> v.toHex }.toMap))

  private def inputIds(tx: SignedTransaction): Set[String] = tx.getSignedInputs.asScala.map(_.getId.toString).toSet

  private def out(c: Chain, link: Int, index: Int): InputUTXO = InputUTXO(c.links(link).tx.getOutputsToSpend.get(index))

  /** Each link spends what it names, and those are outputs of earlier links. */
  private def chainedThrough(c: Chain): Unit = {
    c.links.map(_.step) shouldBe Seq(PoolNftStep, VaultNftStep, OwnerNftStep, GenesisStep)
    c.links.zipWithIndex.drop(1).foreach { case (link, i) =>
      val earlier = c.links.take(i).flatMap(_.tx.getOutputsToSpend.asScala.map(_.getId.toString)).toSet
      link.chained should not be empty
      link.chained.map(_.id.toString).toSet.subsetOf(inputIds(link.tx)) shouldBe true
      inputIds(link.tx).subsetOf(earlier) shouldBe true
    }
  }

  /** The creator's provision, its NFT in this wallet, and what is left of the funding. */
  private def ownedByCreator(c: Chain, guard: String): LDBoxes.Provision = {
    val prov = LDBoxes.readProvision(c.provision)
    prov.box.contract.ergoTreeHex shouldBe guard
    (prov.ownerNFT, prov.shares, prov.entryX, prov.entryY) shouldBe
      ((c.ownerNft, LDGenesisBuilder.GENESIS_PROVISION_SHARES, BigInt(0), BigInt(0)))
    val nft = out(c, 2, 0)
    (nft.contract.ergoTreeHex, nft.tokens) shouldBe ((wallet.contract.ergoTreeHex, Seq(Token(c.ownerNft, 1L))))
    new String(nft.registers.head.getValue.asInstanceOf[sigma.Coll[Byte]].toArray, "UTF-8") shouldBe
      LithosDexTransactions.provisionNftName(c.ownerNft)
    // The genesis change: the funding's headroom, carrying the token the chain was never asked for
    val change = c.links.last.tx.getOutputsToSpend.asScala.map(InputUTXO(_))
      .filter(_.contract.ergoTreeHex == wallet.contract.ergoTreeHex)
    change.map(b => (b.value, b.tokens)) shouldBe Seq((Parameters.MinFee, Seq(Token(unrelated, 7L))))
    prov
  }

  "An ERG/token creation" should "put all the liquidity in at genesis, and its provision redeem down to MIN_SUPPLY" in withCtx { ctx =>
    // Large enough that the 1e-6 locked share still leaves the pool over MIN_RENT once the creator leaves
    val p = Plan(None, tokenY, 20000L * Parameters.OneErg, 3000000000L, Fees)
    val c = chain(ctx, wallet, p, Seq(funding(ctx, p.fundingValue, p.tokens)))
    chainedThrough(c)
    c.provToken shouldBe c.links(3).chained.head.id

    val poolBox = out(c, 3, 0)
    val pool = LDLiquidityPool(poolBox)
    (pool.reservesX, pool.reservesY, pool.supply, pool.provTokensLeft) shouldBe
      ((p.amountX, p.amountY, LDHelpers.GENESIS_SUPPLY, LDHelpers.PROV_TOKEN_SUPPLY - 1L))
    out(c, 3, 1).value shouldBe LDHelpers.VAULT_MIN
    val contracts = LDContracts(c.poolNft, c.vaultNft, c.provToken, ctx.getNetworkType)
    val prov = ownedByCreator(c, contracts.provisionGuard.ergoTreeHex)

    val placement = LDOrderTransactions.redeem(ctx, wallet, contracts, pool, prov, None, Fee, Cap)
    val placed = placement.build(Seq(funding(ctx, placement.value, placement.tokens, index = 1)))
    val order = LithosDexOrder.parse(asNode(placed.describe(wallet.sign(placed.tx)).order)).get
    val run = LithosDexExecution.run(ctx, wallet, contracts, poolBox, Seq(order), 10, 0L,
      nft => if (nft == c.ownerNft.toString) Some(prov) else None, Height, 0L, useTrueProp = false, 1.minute.fromNow)
    run.chain.getOrElse(fail("the creator's redeem did not fill")).fills.head.poolAfter.supply shouldBe LDHelpers.MIN_SUPPLY
  }

  "A token/token creation" should "seed the vault with one of each token, and its provision redeem down to MIN_SUPPLY" in withCtx { ctx =>
    val p = Plan(Some(tokenX), tokenY, 5000L, 2000000000L, Fees)
    p.tokens shouldBe Seq(Token(tokenX, 5000L + LDHelpers.T2T_VAULT_SEED), Token(tokenY, 2000000000L + LDHelpers.T2T_VAULT_SEED))
    val c = chain(ctx, wallet, p, Seq(funding(ctx, p.fundingValue, p.tokens)))
    chainedThrough(c)

    val poolBox = out(c, 3, 0)
    val pool = LDT2TLiquidityPool(poolBox)
    (poolBox.value, pool.reservesX, pool.reservesY, pool.supply) shouldBe
      ((LDHelpers.T2T_POOL_MIN_VALUE, p.amountX, p.amountY, LDHelpers.GENESIS_SUPPLY))
    val vault = out(c, 3, 1)
    vault.value shouldBe LDHelpers.T2T_VAULT_MIN_VALUE
    vault.tokens.tail shouldBe Seq(Token(tokenX, LDHelpers.T2T_VAULT_SEED), Token(tokenY, LDHelpers.T2T_VAULT_SEED))
    val contracts = LDT2TContracts(c.poolNft, c.vaultNft, c.provToken)
    val prov = ownedByCreator(c, contracts.provisionGuard.ergoTreeHex)

    val placement = LDOrderTransactions.t2tRedeem(ctx, wallet, contracts, pool, prov, None, Fee, Cap)
    val placed = placement.build(Seq(funding(ctx, placement.value, placement.tokens, index = 1)))
    val order = LDT2TOrder.parse(asNode(placed.describe(wallet.sign(placed.tx)).order)).get
    val run = LDT2TExecution.run(ctx, wallet, contracts, poolBox, Seq(order), 10, 0L,
      nft => if (nft == c.ownerNft.toString) Some(prov) else None, Height, 0L, useTrueProp = false, 1.minute.fromNow)
    run.chain.getOrElse(fail("the creator's redeem did not fill")).fills.head.poolAfter.supply shouldBe LDHelpers.MIN_SUPPLY
  }

  "A creation's funding" should "be what leaves the wallet, the owner NFT's box and genesis's change" in {
    val n = Plan(None, tokenY, 100L * Parameters.OneErg, 1000000000L, Fees)
    n.ergRequired shouldBe 100L * Parameters.OneErg + LDHelpers.VAULT_MIN + LDHelpers.PROVISION_MIN + 4 * Parameters.MinFee
    n.lockedErg shouldBe LDHelpers.VAULT_MIN
    n.lockedTokens shouldBe empty
    val t = Plan(Some(tokenX), tokenY, 5000L, 2000000000L, Fees)
    t.ergRequired shouldBe LDHelpers.T2T_POOL_MIN_VALUE + LDHelpers.T2T_VAULT_MIN_VALUE + LDHelpers.PROVISION_MIN +
      4 * Parameters.MinFee
    t.fundingValue - t.ergRequired shouldBe LDGenesisBuilder.NFT_BOX_VALUE + Parameters.MinFee
    an[IllegalArgumentException] should be thrownBy Plan(None, tokenY, LDHelpers.MIN_RENT - 1L, 10L, Fees)
  }
}
