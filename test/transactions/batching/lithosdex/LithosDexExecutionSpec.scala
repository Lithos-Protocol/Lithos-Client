package transactions.batching.lithosdex

import lithosdex.contracts.{LDOrderContracts, LDOrderKind, LDOrderTerms}
import lithosdex.{LDHelpers, LDLiquidityPool}
import node.MutationConversions._
import node.model.{NodeAsset, NodeBox, NodeRegisters}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{BlockchainContext, Parameters, SignedTransaction}
import org.ergoplatform.sdk.ErgoId
import org.ergoplatform.{ErgoBox, ErgoBoxCandidate}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scorex.util.ModifierId
import sigma.ast.{ConcreteCollection, IntConstant, SInt}
import sigma.crypto.CryptoConstants
import sigma.data.{COR, ProveDlog}
import sigma.serialization.ValueSerializer
import sigma.{Colls, VersionContext}
import support.{FakeNodeContext, LDNodeFixtures}
import transactions.batching.Batcher
import transactions.candidate.BlockTxMessages.Supersede
import transactions.candidate.CapitalOrigin
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

import java.math.BigInteger
import scala.collection.JavaConverters._
import scala.concurrent.duration._

/**
 * LithosDex executions built by the client and signed by its wallet.
 *
 * Signing runs every input script, so each built transaction is also a check that the pool, the order,
 * the provision and the vault accept exactly what `LithosDexExecution` produced. What is asserted on top
 * is the client's own decisions: the positions it chose, what it charged, and what it refused to price.
 */
class LithosDexExecutionSpec extends AnyFlatSpec with Matchers {

  private lazy val fake = FakeNodeContext(numAddresses = 1)
  private def wallet = fake._3

  private def withCtx[A](f: BlockchainContext => A): A = fake._1.getClient.execute(ctx => f(ctx))

  private val erg = Parameters.OneErg
  private val Fee = 6000000L
  private val Height = 123414

  private def owner(ctx: BlockchainContext) =
    ctx.newProverBuilder().withDLogSecret(BigInteger.valueOf(7007L)).build().getAddress

  private def ownerTree(ctx: BlockchainContext): String = Contract.fromAddress(owner(ctx)).ergoTreeHex

  private def terms(ctx: BlockchainContext, fee: Long = Fee, maxMinerFee: Long = 2000000L): LDOrderTerms =
    LDOrderTerms(owner(ctx).getPublicKey, LDHelpers.getPoolNFT(ctx.getNetworkType), fee, maxMinerFee)

  private def lit(ctx: BlockchainContext): ErgoId = LDHelpers.getTokenY(ctx.getNetworkType)

  /** 10 ERG against 10,000 tokens, with fees already pending so a flush has something to move. */
  private def poolNode(ctx: BlockchainContext, pendingX: Long = 20000000L, pendingY: Long = 30000000L): NodeBox =
    LDNodeFixtures.poolBox(ctx, reservesX = 10L * erg, reservesY = 10000L * 1000000L,
      pendingX = pendingX, pendingY = pendingY, accX = BigInt(5), accY = BigInt(7))

  private def orderNode(ctx: BlockchainContext, contract: Contract, value: Long, index: Int, tokens: Token*): NodeBox =
    placedBy(ctx, "cd" * 32, contract, value, index, tokens: _*)

  private def placedBy(ctx: BlockchainContext, txId: String, contract: Contract, value: Long, index: Int,
                       tokens: Token*): NodeBox =
    LDNodeFixtures.nodeBox(ctx, UTXO(contract, value, tokens), index = index, txId = txId)

  private def sell(ctx: BlockchainContext, index: Int = 10, fee: Long = Fee, minQuote: Long = 1L): LithosDexOrder =
    LithosDexOrder.parse(orderNode(ctx, LDOrderContracts.swapSell(terms(ctx, fee), erg, minQuote),
      erg + fee + Parameters.MinFee, index)).get

  private def buy(ctx: BlockchainContext, index: Int = 11, fee: Long = Fee, amount: Long = 500L * 1000000L): LithosDexOrder =
    LithosDexOrder.parse(orderNode(ctx, LDOrderContracts.swapBuy(terms(ctx, fee), 1L), Parameters.MinFee, index,
      Token(lit(ctx), amount))).get

  private def deposit(ctx: BlockchainContext, index: Int = 12, fee: Long = Fee, tokens: Long = 1200L * 1000000L,
                      minShares: Long = 1L): LithosDexOrder =
    LithosDexOrder.parse(orderNode(ctx, LDOrderContracts.deposit(terms(ctx, fee), erg, minShares),
      erg + LDHelpers.PROVISION_MIN + Parameters.MinFee + fee, index, Token(lit(ctx), tokens))).get

  private val ownerNft = ErgoId.create("f0" * 32)

  private def redeem(ctx: BlockchainContext, index: Int = 13, fee: Long = Fee): LithosDexOrder =
    LithosDexOrder.parse(orderNode(ctx, LDOrderContracts.redeem(terms(ctx, fee)), Parameters.MinFee + fee, index,
      Token(ownerNft, 1L))).get

  private def provisions(ctx: BlockchainContext, shares: Long = 10000000000L): LithosDexExecution.Provisions = {
    val provision = LDBoxes.readProvision(LDNodeFixtures.provisionBox(ctx, shares, ownerNft).toInputUTXO(ctx))
    nft => if (nft == ownerNft.toString) Some(provision) else None
  }

  private def runOf(ctx: BlockchainContext, poolBox: InputUTXO, orders: Seq[LithosDexOrder], limit: Int = 10,
                    provs: LithosDexExecution.Provisions = LithosDexExecution.NoProvisions, ceiling: Long = 0L,
                    deadline: Deadline = 1.minute.fromNow): LithosDexRun =
    LithosDexExecution.run(ctx, wallet, poolBox, orders, limit, 0L, provs, Height, ceiling, useTrueProp = false, deadline)

  private def buildOne(ctx: BlockchainContext, order: LithosDexOrder, ceiling: Long = 0L,
                       provs: LithosDexExecution.Provisions = LithosDexExecution.NoProvisions): (LithosDexFill, LithosDexChain) = {
    val chain = runOf(ctx, poolNode(ctx).toInputUTXO(ctx), Seq(order), provs = provs, ceiling = ceiling)
      .chain.getOrElse(fail(s"the fixture ${order.kind} did not build"))
    chain.fills.head -> chain
  }

  private def outputs(tx: SignedTransaction) = tx.getOutputsToSpend.asScala.toVector

  private def tokenAmount(box: org.ergoplatform.appkit.InputBox, id: ErgoId): Long =
    box.getTokens.asScala.filter(_.getId == id).map(_.getValue).sum

  // ─── one execution of each kind ───────────────────────────────────────────

  "A sell" should "pay the owner the pool's output and the executor its fee" in withCtx { ctx =>
    val order = sell(ctx)
    val (fill, chain) = buildOne(ctx, order)
    val out = outputs(chain.transactions.head)
    out(1).getErgoTree.bytes.toSeq.map("%02x".format(_)).mkString shouldBe ownerTree(ctx)
    tokenAmount(out(1), lit(ctx)) shouldBe fill.rewardY
    fill.rewardY shouldBe LDLiquidityPool(poolNode(ctx).toInputUTXO(ctx)).simSwap(erg, ergIn = true).amountOut
    out(1).getValue shouldBe (order.box.value - erg - Fee)
    out(2).getValue shouldBe Fee
    out should have size 3
  }

  "A buy" should "pay the owner the pool's output less the fee" in withCtx { ctx =>
    val order = buy(ctx)
    val (fill, chain) = buildOne(ctx, order)
    val quote = LDLiquidityPool(poolNode(ctx).toInputUTXO(ctx)).simSwap(500L * 1000000L, ergIn = false).amountOut
    outputs(chain.transactions.head)(1).getValue shouldBe order.box.value + quote - Fee
    fill.rewardValue shouldBe order.box.value + quote - Fee
  }

  "A deposit" should "issue a provision named by the pool box it spends, and hand the owner its NFT" in withCtx { ctx =>
    val order = deposit(ctx)
    val poolBox = poolNode(ctx).toInputUTXO(ctx)
    val (fill, chain) = buildOne(ctx, order)
    val out = outputs(chain.transactions.head)
    out(1).getErgoTree.bytes.toSeq.map("%02x".format(_)).mkString shouldBe DexContracts(ctx).provisionGuard.ergoTreeHex
    out(1).getRegisters.get(2).getValue.asInstanceOf[sigma.Coll[Byte]].toArray.toSeq shouldBe poolBox.id.getBytes.toSeq
    out(1).getRegisters.get(3).getValue shouldBe fill.shares
    out(2).getTokens.get(0).getId shouldBe poolBox.id
    out(2).getErgoTree.bytes.toSeq.map("%02x".format(_)).mkString shouldBe ownerTree(ctx)
    // Takings sit after the provision, not at index 2
    out(3).getValue shouldBe Fee
    chain.takings.value shouldBe Fee
  }

  it should "return the ERG side a surplus of ERG leaves unmatched" in withCtx { ctx =>
    val (fill, chain) = buildOne(ctx, deposit(ctx, tokens = 500L * 1000000L))
    fill.rewardY shouldBe 0L
    outputs(chain.transactions.head)(2).getValue should be > Parameters.MinFee
  }

  "A redemption" should "close the provision its NFT owns and burn the NFT" in withCtx { ctx =>
    val order = redeem(ctx)
    val provs = provisions(ctx)
    val (fill, chain) = buildOne(ctx, order, provs = provs)
    val signed = chain.transactions.head
    outputs(signed).flatMap(_.getTokens.asScala).map(_.getId) should not contain ownerNft
    val q = LDLiquidityPool(poolNode(ctx).toInputUTXO(ctx)).simRedeem(10000000000L)
    outputs(signed)(1).getValue shouldBe order.box.value + LDHelpers.PROVISION_MIN + q.amountX - Fee
    tokenAmount(outputs(signed)(1), lit(ctx)) shouldBe q.amountY
    fill.provision.map(_.ownerNFT) shouldBe Some(ownerNft)
  }

  // ─── runs ─────────────────────────────────────────────────────────────────

  "A mixed run" should "chain every kind through the pool by fee, and carry one takings box" in withCtx { ctx =>
    val poolBox = poolNode(ctx).toInputUTXO(ctx)
    val orders = Seq(redeem(ctx, fee = 6000000L), sell(ctx, fee = 9000000L), deposit(ctx, fee = 7000000L), buy(ctx, fee = 8000000L))
    val chain = runOf(ctx, poolBox, orders, provs = provisions(ctx)).chain.get
    chain.fills.map(_.order.kind) shouldBe
      Vector(LDOrderKind.SwapSell, LDOrderKind.SwapBuy, LDOrderKind.Deposit, LDOrderKind.Redeem)

    chain.transactions should have size 4
    chain.transactions.sliding(2).foreach { case Seq(prev, next) =>
      val spent = next.getSignedInputs.asScala.map(_.getId)
      spent should contain(prev.getOutputsToSpend.get(0).getId)
    }
    chain.takings.value shouldBe (9000000L + 8000000L + 7000000L + 6000000L)
    chain.poolIds.head shouldBe poolBox.id.toString

    val bundle = chain.bundle(Set("ab" * 32))
    bundle.capital.map(c => c.origin -> c.value) shouldBe Seq(CapitalOrigin.ExecutorReward -> chain.takings.value)
    bundle.interactions shouldBe Seq(Supersede(Set("ab" * 32)))
    chain.transactions.zip(chain.fills).foreach { case (tx, fill) =>
      println(f"[lithosdex] ${fill.order.kind.scriptName}%-18s cost ${tx.getCost}%7d  bytes ${tx.toBytes.length}%5d")
    }
  }

  it should "close with a flush that moves the pending fees into the vault" in withCtx { ctx =>
    val (_, chain) = buildOne(ctx, sell(ctx))
    val vault = LDNodeFixtures.vaultBox(ctx, balanceX = LDHelpers.VAULT_MIN).toInputUTXO(ctx)
    val closed = LithosDexExecution.withFlush(ctx, wallet, chain, vault, Height)
    val flush = closed.flush.getOrElse(fail("no flush was built"))
    val poolIn = InputUTXO(chain.transactions.last.getOutputsToSpend.get(0))
    val before = LDLiquidityPool(poolIn)
    val after = LDLiquidityPool(InputUTXO(flush.getOutputsToSpend.get(0)))
    (after.pendingX, after.pendingY) shouldBe (0L -> 0L)
    flush.getOutputsToSpend.get(1).getValue shouldBe LDHelpers.VAULT_MIN + before.pendingX
    closed.members.map(_.kind) shouldBe Vector(LithosDexExecution.Kind, LithosDexExecution.FlushKind)
    println(f"[lithosdex] flush              cost ${flush.getCost}%7d  bytes ${flush.toBytes.length}%5d")
  }

  it should "offer no flush when nothing is pending" in withCtx { ctx =>
    val poolBox = poolNode(ctx, pendingX = 0L, pendingY = 0L).toInputUTXO(ctx)
    val chain = runOf(ctx, poolBox, Seq(deposit(ctx))).chain.get
    val vault = LDNodeFixtures.vaultBox(ctx, balanceX = LDHelpers.VAULT_MIN).toInputUTXO(ctx)
    LithosDexExecution.withFlush(ctx, wallet, chain, vault, Height).flush shouldBe None
  }

  it should "pay a broadcast's miner fee out of the executor fee, within the order's cap" in withCtx { ctx =>
    val (_, chain) = buildOne(ctx, sell(ctx), ceiling = 5000000L)
    val out = outputs(chain.transactions.head)
    val feeTree = Contract.FEE.ergoTreeHex
    // The order caps the miner fee at 2,000,000, under the 5,000,000 ceiling
    out.filter(_.getErgoTree.bytes.toSeq.map("%02x".format(_)).mkString == feeTree).map(_.getValue).sum shouldBe 2000000L
    chain.takings.value shouldBe Fee - 2000000L
  }

  private lazy val manyKeys = {
    val g = CryptoConstants.dlogGroup
    COR((1 to 80).map(i => ProveDlog(g.exponentiate(g.generator, BigInteger.valueOf(1000L + i)))))
  }

  /** A sell whose owner is an 80-key OR: its reward box is 2,802 bytes, so 1,000,000 nanoERG is under 360 per byte. */
  /** An order that prices but cannot be built, created by transaction `txId`. */
  private def undersizedSell(ctx: BlockchainContext, fee: Long, index: Int, txId: String = "cd" * 32): LithosDexOrder =
    LithosDexOrder.parse(placedBy(ctx, txId, LDOrderContracts.swapSell(terms(ctx, fee).copy(redeemer = manyKeys), erg, 1L),
      erg + fee + UTXO.MIN_CHANGE, index)).get

  it should "pass over an order whose reward box is under the node's minimum, and price the next against the same pool" in withCtx { ctx =>
    val poolBox = poolNode(ctx).toInputUTXO(ctx)
    val first = sell(ctx, fee = 9000000L)
    val undersized = undersizedSell(ctx, 8000000L, 30)
    val last = buy(ctx, fee = 7000000L)
    // Pricing sees nothing wrong: its floor is MIN_CHANGE, not the box's size
    LithosDexExecution.price(undersized, LDLiquidityPool(poolBox), 0L, LithosDexExecution.NoProvisions) should not be empty

    val run = runOf(ctx, poolBox, Seq(first, undersized, last))
    run.unbuildable shouldBe Vector(undersized.boxId)
    run.cutShort shouldBe false
    val chain = run.chain.get
    chain.fills.map(_.order.boxId) shouldBe Vector(first.boxId, last.boxId)
    chain.transactions(1).getSignedInputs.asScala.map(_.getId) should contain(chain.transactions(0).getOutputsToSpend.get(0).getId)
  }

  it should "name an order whose registers do not rebuild to its box id" in withCtx { ctx =>
    // Sigma accepts a list-typed register as a box's own encoding; the client re-encodes it as a constant
    val tree = LDOrderContracts.swapSell(terms(ctx), erg, 1L).ergoTree
    val register = ConcreteCollection(Seq(IntConstant(1), IntConstant(2)), SInt)
    val box = VersionContext.withVersions(VersionContext.V6SoftForkVersion, VersionContext.V6SoftForkVersion) {
      val made = new ErgoBoxCandidate(erg + Fee + UTXO.MIN_CHANGE, tree, 100, Colls.emptyColl, Map(ErgoBox.R4 -> register))
        .toBox(ModifierId @@ ("cd" * 32), 4.toShort)
      java.util.Arrays.equals(ErgoBox.sigmaSerializer.fromBytes(made.bytes).id, made.id) shouldBe true
      made
    }
    val node = NodeBox(Hex.toHexString(box.id), "cd" * 32, box.value, 4, 100, Hex.toHexString(tree.bytes),
      Seq.empty[NodeAsset], NodeRegisters(Map("R4" -> Hex.toHexString(ValueSerializer.serialize(register)))))
    val order = LithosDexOrder.parse(node).get
    runOf(ctx, poolNode(ctx).toInputUTXO(ctx), Seq(order)) shouldBe
      LithosDexRun(None, Vector.empty, Vector(order.boxId), cutShort = false)
  }

  it should "build nothing past its deadline, and say it stopped with orders left" in withCtx { ctx =>
    val run = runOf(ctx, poolNode(ctx).toInputUTXO(ctx), Seq(sell(ctx)), deadline = Deadline.now - 1.second)
    run.chain shouldBe None
    run.unbuildable shouldBe empty
    run.cutShort shouldBe true
  }

  it should "stop passing over orders at the unbuildable limit" in withCtx { ctx =>
    val limit = Batcher.UnbuildableLimits.Default.perRun
    // Each from its own transaction, so only the run's limit applies
    val bad = (1 to limit).map(i => undersizedSell(ctx, 8000000L + i, 40 + i, txId = f"$i%064x"))
    val run = runOf(ctx, poolNode(ctx).toInputUTXO(ctx), bad :+ sell(ctx, fee = 2000000L), limit = 20)
    run.unbuildable should have size limit
    run.chain shouldBe None
    run.cutShort shouldBe true
  }

  it should "try only maxUnbuildablePerTx of one transaction's orders, and build the rest of the run" in withCtx { ctx =>
    val perTx = Batcher.UnbuildableLimits.Default.perTx
    val spam = (1 to perTx + 3).map(i => undersizedSell(ctx, 8000000L + i, 40 + i, txId = "ee" * 32))
    val run = runOf(ctx, poolNode(ctx).toInputUTXO(ctx), spam :+ sell(ctx, fee = 2000000L), limit = 20)
    withClue("the transaction's later orders are passed over untried: ") {
      run.unbuildable should have size perTx
    }
    run.chain.map(_.fills.map(_.order.boxId)) shouldBe Some(Vector(sell(ctx, fee = 2000000L).boxId))
  }

  // ─── refusals ─────────────────────────────────────────────────────────────

  "Pricing" should "refuse a fee under the configured floor" in withCtx { ctx =>
    val pool = LDLiquidityPool(poolNode(ctx).toInputUTXO(ctx))
    LithosDexExecution.price(sell(ctx), pool, Fee, LithosDexExecution.NoProvisions) should not be empty
    LithosDexExecution.price(sell(ctx), pool, Fee + 1, LithosDexExecution.NoProvisions) shouldBe None
  }

  it should "refuse an order whose minimum the pool cannot meet" in withCtx { ctx =>
    val pool = LDLiquidityPool(poolNode(ctx).toInputUTXO(ctx))
    val out = pool.simSwap(erg, ergIn = true).amountOut
    LithosDexExecution.price(sell(ctx, minQuote = out), pool, 0L, LithosDexExecution.NoProvisions) should not be empty
    LithosDexExecution.price(sell(ctx, minQuote = out + 1), pool, 0L, LithosDexExecution.NoProvisions) shouldBe None
  }

  it should "refuse a fee that cannot fund the first takings box, or cannot cover a broadcast's miner fee" in withCtx { ctx =>
    val pool = LDLiquidityPool(poolNode(ctx).toInputUTXO(ctx))
    val small = sell(ctx, fee = UTXO.MIN_CHANGE - 1)
    LithosDexExecution.price(small, pool, 0L, LithosDexExecution.NoProvisions, fundsItsOwnBox = false) should not be empty
    LithosDexExecution.price(small, pool, 0L, LithosDexExecution.NoProvisions, fundsItsOwnBox = true) shouldBe None
    LithosDexExecution.price(sell(ctx, fee = 2000000L), pool, 0L, LithosDexExecution.NoProvisions,
      fundsItsOwnBox = false, minerFeeCeiling = 5000000L) shouldBe None
  }

  it should "refuse a redemption with no provision to close" in withCtx { ctx =>
    val pool = LDLiquidityPool(poolNode(ctx).toInputUTXO(ctx))
    LithosDexExecution.price(redeem(ctx), pool, 0L, provisions(ctx)) should not be empty
    LithosDexExecution.price(redeem(ctx), pool, 0L, LithosDexExecution.NoProvisions) shouldBe None
  }

  it should "refuse a buy or a deposit of some other token" in withCtx { ctx =>
    val pool = LDLiquidityPool(poolNode(ctx).toInputUTXO(ctx))
    val other = ErgoId.create("ee" * 32)
    val otherBuy = LithosDexOrder.parse(orderNode(ctx, LDOrderContracts.swapBuy(terms(ctx), 1L), Parameters.MinFee, 20,
      Token(other, 500L * 1000000L))).get
    val otherDeposit = LithosDexOrder.parse(orderNode(ctx, LDOrderContracts.deposit(terms(ctx), erg, 1L),
      2L * erg, 21, Token(other, 1200L * 1000000L))).get
    LithosDexExecution.price(otherBuy, pool, 0L, LithosDexExecution.NoProvisions) shouldBe None
    LithosDexExecution.price(otherDeposit, pool, 0L, LithosDexExecution.NoProvisions) shouldBe None
  }

  it should "refuse a deposit whose box cannot also fund the provision box" in withCtx { ctx =>
    val pool = LDLiquidityPool(poolNode(ctx).toInputUTXO(ctx))
    val short = LithosDexOrder.parse(orderNode(ctx, LDOrderContracts.deposit(terms(ctx), erg, 1L),
      erg + LDHelpers.PROVISION_MIN + Fee, 22, Token(lit(ctx), 1200L * 1000000L))).get
    LithosDexExecution.price(deposit(ctx), pool, 0L, LithosDexExecution.NoProvisions) should not be empty
    LithosDexExecution.price(short, pool, 0L, LithosDexExecution.NoProvisions) shouldBe None
  }

  it should "refuse a deposit whose funds buy fewer shares than the owner's minimum" in withCtx { ctx =>
    val pool = LDLiquidityPool(poolNode(ctx).toInputUTXO(ctx))
    val shares = LithosDexExecution.price(deposit(ctx), pool, 0L, LithosDexExecution.NoProvisions).get.shares
    LithosDexExecution.price(deposit(ctx, minShares = shares), pool, 0L, LithosDexExecution.NoProvisions) should not be empty
    LithosDexExecution.price(deposit(ctx, minShares = shares + 1), pool, 0L, LithosDexExecution.NoProvisions) shouldBe None
  }

  it should "refuse an order naming another pool" in withCtx { ctx =>
    val pool = LDLiquidityPool(poolNode(ctx).toInputUTXO(ctx))
    val elsewhere = LithosDexOrder.parse(orderNode(ctx, LDOrderContracts.swapSell(
      terms(ctx).copy(poolNFT = ErgoId.create("99" * 32)), erg, 1L), 2L * erg, 23)).get
    LithosDexExecution.price(elsewhere, pool, 0L, LithosDexExecution.NoProvisions) shouldBe None
  }
}
