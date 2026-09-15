package transactions.batching.lithosdex

import lithosdex.{LDHelpers, LDLiquidityPool}
import node.MutationConversions._
import node.model._
import org.ergoplatform.appkit.{BlockchainContext, Parameters}
import org.ergoplatform.sdk.ErgoId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import state.synchronization.CompleteMempool
import support.{FakeNodeContext, LDNodeFixtures}
import transactions.batching.BatchingMempool
import work.lithos.mutations.{InputUTXO, UTXO}

import scala.collection.JavaConverters._

/**
 * The claim a redeem order is placed with, as the order API signs it and the mempool then reports it.
 * Every refusal below changes one thing about that transaction.
 */
class LDClaimPlacementSpec extends AnyFlatSpec with Matchers {

  private lazy val fake = FakeNodeContext(numAddresses = 1)
  private def wallet = fake._3

  private def withCtx[A](f: BlockchainContext => A): A = fake._1.getClient.execute(ctx => f(ctx))

  private val erg = Parameters.OneErg
  private val nft = ErgoId.create("f0" * 32)

  private def claims(ctx: BlockchainContext, poolNft: String = null): LDClaimPlacement =
    new LDClaimPlacement(Option(poolNft).getOrElse(LDHelpers.getPoolNFT(ctx.getNetworkType).toString),
      LDHelpers.getVaultNFT(ctx.getNetworkType).toString, DexContracts(ctx).feeVault.ergoTreeHex,
      DexContracts(ctx).provisionGuard.ergoTreeHex, LDHelpers.getProvToken(ctx.getNetworkType).toString)

  private def asNode(box: InputUTXO): NodeBox =
    NodeBox(box.id.toString, box.input.getTransactionId, box.value, box.input.getTransactionIndex,
      box.input.getCreationHeight, box.contract.ergoTreeHex, box.tokens.map(t => NodeAsset(t.id.toString, t.amount)),
      NodeRegisters(box.registers.zipWithIndex.map { case (v, i) => s"R${i + 4}" -> v.toHex }.toMap))

  /** The signed placement, with every box it spends. */
  private case class Placed(tx: CompleteMempool.MempoolTx, inputs: Map[String, NodeBox]) {
    def spenders(others: CompleteMempool.MempoolTx*): BatchingMempool.Spenders =
      BatchingMempool.spenders(CompleteMempool.Snapshot("cc" * 32, Set.empty, Set.empty, System.nanoTime(),
        transactions = (tx +: others).toVector))

    def outputs: Seq[NodeBox] = tx.body.outputs

    def withOutputs(outs: Seq[NodeBox]): Placed = copy(tx = tx.copy(body = tx.body.copy(outputs = outs)))

    def withInputs(ids: Seq[String]): Placed =
      copy(tx = tx.copy(body = tx.body.copy(inputs = ids.map(NodeInput(_, NodeSpendingProof.empty)))))
  }

  private def placed(ctx: BlockchainContext): Placed = {
    val step = LDHelpers.SCALE / 1000000
    val vault = LDNodeFixtures.vaultBox(ctx, 5L * erg, accX = step)
    val provision = LDNodeFixtures.provisionBox(ctx, 10000000000L, nft)
    val pool = LDLiquidityPool(LDNodeFixtures.poolBox(ctx, reservesX = 10L * erg, reservesY = 10000L * 1000000L,
      accX = step * 2).toInputUTXO(ctx))
    val plan = LDOrderTransactions.redeem(ctx, wallet, pool, LDBoxes.readProvision(provision.toInputUTXO(ctx)),
      Some(vault.toInputUTXO(ctx)), 3000000L, 1000000L)
    val funding = LDNodeFixtures.nodeBox(ctx, UTXO(wallet.contract, plan.value, plan.tokens), index = 20, txId = "ef" * 32)
    val signed = wallet.sign(plan.build(Seq(funding.toInputUTXO(ctx))).tx)
    val body = NodeTransaction(signed.getId,
      signed.getSignedInputs.asScala.map(input => NodeInput(input.getId.toString, NodeSpendingProof.empty)).toVector,
      Seq.empty, signed.getOutputsToSpend.asScala.map(box => asNode(InputUTXO(box))).toVector)
    Placed(CompleteMempool.MempoolTx(signed.getId, body, 100),
      Seq(vault, provision, funding).map(box => box.boxId -> box).toMap)
  }

  private def accepted(ctx: BlockchainContext, p: Placed, check: LDClaimPlacement = null): Boolean =
    Option(check).getOrElse(claims(ctx)).accepts(p.tx, p.inputs, p.spenders())

  "A redeem order placed with its claim" should "be chained from, naming the provision the claim leaves for the order" in withCtx { ctx =>
    val p = placed(ctx)
    accepted(ctx, p) shouldBe true
    val order = LithosDexOrder.parse(p.outputs(2)).collect { case r: LithosDexOrder.Redeem => r }.get
    claims(ctx).provisionFor(p.tx, order) shouldBe Some(p.outputs(1))
  }

  it should "not be chained from once another unconfirmed transaction spends the vault, or a box it spends is unknown" in withCtx { ctx =>
    val p = placed(ctx)
    val vaultId = p.tx.body.inputs.head.boxId
    val flush = CompleteMempool.MempoolTx("dd" * 32, NodeTransaction("dd" * 32,
      Seq(NodeInput(vaultId, NodeSpendingProof.empty)), Seq.empty, Seq.empty), 100)
    claims(ctx).accepts(p.tx, p.inputs, p.spenders(flush)) shouldBe false
    claims(ctx).accepts(p.tx, p.inputs - vaultId, p.spenders()) shouldBe false
  }

  it should "not be chained from with its vault and provision out of place, or an input no single key spends" in withCtx { ctx =>
    val p = placed(ctx)
    val ids = p.tx.body.inputs.map(_.boxId)
    withClue("provision ahead of the vault: ") {
      accepted(ctx, p.withInputs(ids(1) +: ids.head +: ids.drop(2))) shouldBe false
    }
    withClue("a funding box under the vault's script: ") {
      val funding = p.inputs(ids(2))
      accepted(ctx, p.copy(inputs = p.inputs + (funding.boxId -> funding.copy(ergoTree = p.inputs(ids.head).ergoTree)))) shouldBe false
    }
  }

  it should "not be chained from when the order is not a redeem on this pool holding that provision, or more follows it" in withCtx { ctx =>
    val p = placed(ctx)
    val outs = p.outputs
    withClue("an order on another pool: ") {
      accepted(ctx, p, claims(ctx, poolNft = "ee" * 32)) shouldBe false
    }
    withClue("a provision successor naming another owner: ") {
      val other = outs(1).copy(additionalRegisters = NodeRegisters(outs(1).additionalRegisters.values +
        ("R6" -> ("0e20" + "f1" * 32))))
      accepted(ctx, p.withOutputs(outs.updated(1, other))) shouldBe false
    }
    withClue("a second provision-shaped box after the order: ") {
      accepted(ctx, p.withOutputs(outs :+ outs(1).copy(boxId = "aa" * 32))) shouldBe false
    }
  }
}
