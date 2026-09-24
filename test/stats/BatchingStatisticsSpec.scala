package stats

import node.model.{NodeAsset, NodeBox, NodeRegisters}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.NetworkType
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import state.messages.{BlockInfo, BlockTx, NodeSync, TxInput}
import support.{ErgoDexFixtures, FakeNodeContext, LDNodeFixtures, SyncFixtures}
import transactions.batching.ergodex.{ErgoDexOrder, ErgoDexPool}
import transactions.batching.lithosdex.DexContracts
import work.lithos.mutations.Contract

class BatchingStatisticsSpec extends AnyFlatSpec with Matchers {
  "Confirmed batching fees" should "count each ErgoDEX execution once without counting carried takings again" in {
    val pool = ErgoDexFixtures.Sell.poolBox
    val orderBox = ErgoDexFixtures.Sell.orderBox
    val order = ErgoDexOrder.parse(orderBox).get
    val before = ErgoDexPool.native(pool).get
    val quote = before.outputAmount(order.baseAmount, baseIsErg = true)
    val after = pool.copy(boxId = SyncFixtures.id(1), value = ErgoDexFixtures.Sell.poolErgAfter,
      assets = pool.assets.updated(2, pool.assets(2).copy(amount = ErgoDexFixtures.Sell.poolTokensAfter)))
    val owner = orderBox.copy(boxId = SyncFixtures.id(2), ergoTree = Hex.toHexString(order.redeemerPropBytes),
      value = ErgoDexFixtures.Sell.rewardValue, assets = Seq(NodeAsset(before.yId, quote)))
    val carry = owner.copy(boxId = SyncFixtures.id(3), ergoTree = Contract.SIGMA_TRUE.ergoTreeHex,
      value = 9000000000L, assets = Seq.empty)
    val takings = carry.copy(boxId = SyncFixtures.id(4), value = carry.value + ErgoDexFixtures.Sell.executorOutput)
    val fee = carry.copy(boxId = SyncFixtures.id(5), ergoTree = Contract.FEE.ergoTreeHex, value = ErgoDexFixtures.Sell.minerFee)
    val tx = BlockTx("executed", Seq(pool, orderBox, carry).map(b => TxInput(b.boxId, None)), Seq.empty,
      Seq(after, owner, takings, fee).map(NodeSync.txOutput))
    val block = BlockInfo("block", 120, Seq(tx), resolvedInputs = Seq(pool, orderBox, carry).map(b => b.boxId -> NodeSync.txOutput(b)).toMap)
    val other = Contract.SIGMA_FALSE.ergoTreeHex
    val result = BatchingStatistics.read(block, tx, NetworkType.TESTNET, other)
    result should have size 1
    result.head.grossNanoErg shouldBe (ErgoDexFixtures.Sell.executorOutput + ErgoDexFixtures.Sell.minerFee).toString
    result.head.transactionFeeNanoErg shouldBe ErgoDexFixtures.Sell.minerFee.toString
    result.head.local shouldBe false
    // The takings box went to this client's script, so the execution was this client's.
    BatchingStatistics.read(block, tx, NetworkType.TESTNET, Contract.SIGMA_TRUE.ergoTreeHex).head.local shouldBe true
    val cancelled = tx.copy(outputs = tx.outputs.updated(0, NodeSync.txOutput(pool)))
    BatchingStatistics.read(block, cancelled, NetworkType.TESTNET, other) shouldBe empty
    val wrongOwner = tx.copy(outputs = tx.outputs.updated(1, tx.outputs(1).copy(ergoTree = Contract.SIGMA_FALSE.ergoTreeHex)))
    BatchingStatistics.read(block, wrongOwner, NetworkType.TESTNET, other) shouldBe empty
  }

  it should "measure the configured LithosDex executor fee separately from the pool's own fees" in {
    val fake = FakeNodeContext()
    fake._1.getClient.execute { ctx =>
      import lithosdex.LDHelpers
      import lithosdex.contracts.{LDOrderContracts, LDOrderTerms}
      val pool = LDNodeFixtures.poolBox(ctx, 10000000000L, 10000000000L)
      val terms = LDOrderTerms(fake._3.contract.sigmaBoolean.get, LDHelpers.getPoolNFT(ctx.getNetworkType), 6000000, 2000000)
      val script = LDOrderContracts.swapSell(terms, 1000000000L, 1L)
      val order = NodeBox(SyncFixtures.id(11), "placed", 1007000000L, 0, 100, script.ergoTreeHex,
        Seq.empty, NodeRegisters(Map.empty))
      val after = pool.copy(boxId = SyncFixtures.id(12), value = pool.value + 1000000000L)
      val reward = order.copy(boxId = SyncFixtures.id(13), ergoTree = fake._3.contract.ergoTreeHex, value = 1000000L)
      val tx = BlockTx("ld-executed", Seq(TxInput(pool.boxId, None), TxInput(order.boxId, None)), Seq.empty,
        Seq(after, reward).map(NodeSync.txOutput))
      val block = BlockInfo("block", 120, Seq(tx), resolvedInputs = Seq(pool, order).map(b => b.boxId -> NodeSync.txOutput(b)).toMap)
      // The order belongs to this client, so its reward pays this client — which is not executing it.
      val mine = fake._3.contract.ergoTreeHex
      val result = BatchingStatistics.read(block, tx, ctx.getNetworkType, mine)
      result.map(_.grossNanoErg) shouldBe Vector("6000000")
      result.head.transactionFeeNanoErg shouldBe "0"
      result.head.local shouldBe false
      val refunded = tx.copy(outputs = tx.outputs.updated(1, tx.outputs(1).copy(value = 7000000L)))
      BatchingStatistics.read(block, refunded, ctx.getNetworkType, mine) shouldBe empty
    }
  }

  it should "count an execution on a LithosDex pool other than ERG:LIT, and not on a pool with a forged guard" in {
    val fake = FakeNodeContext()
    fake._1.getClient.execute { ctx =>
      import lithosdex.contracts.{LDContracts, LDOrderContracts, LDOrderTerms}
      import org.ergoplatform.sdk.ErgoId
      val second = LDContracts(
        ErgoId.create("77ebb7ac1a9386d6cc5b64fffa52602bea574b0bb454fb856188ae54acb84894"),
        ErgoId.create("9cec4df2183f6047edf61ec9bb60649c22362e17c074fac7c41d75c5229129f0"),
        ErgoId.create("27bc91eaf7e72d2a17cf6e73de349262c87bea7894121963b4100a644a08da25"),
        ctx.getNetworkType)
      val canonical = LDNodeFixtures.poolBox(ctx, 10000000000L, 10000000000L)
      val pool = canonical.copy(ergoTree = second.liquidityPool.ergoTreeHex, assets = Seq(
        NodeAsset(second.poolNFT.toString, 1L), canonical.assets(1), NodeAsset(second.provToken.toString, 1000000L)))
      val terms = LDOrderTerms(fake._3.contract.sigmaBoolean.get, second.poolNFT, 6000000, 2000000)
      val order = NodeBox(SyncFixtures.id(21), "placed", 1007000000L, 0, 100,
        LDOrderContracts.swapSell(terms, 1000000000L, 1L).ergoTreeHex, Seq.empty, NodeRegisters(Map.empty))
      val after = pool.copy(boxId = SyncFixtures.id(22), value = pool.value + 1000000000L)
      val reward = order.copy(boxId = SyncFixtures.id(23), ergoTree = fake._3.contract.ergoTreeHex, value = 1000000L)
      def executed(poolIn: NodeBox, poolOut: NodeBox): (BlockInfo, BlockTx) = {
        val tx = BlockTx("ld-other-pool", Seq(TxInput(poolIn.boxId, None), TxInput(order.boxId, None)), Seq.empty,
          Seq(poolOut, reward).map(NodeSync.txOutput))
        BlockInfo("block", 120, Seq(tx),
          resolvedInputs = Seq(poolIn, order).map(b => b.boxId -> NodeSync.txOutput(b)).toMap) -> tx
      }

      val (block, tx) = executed(pool, after)
      BatchingStatistics.read(block, tx, ctx.getNetworkType, "00").map(_.grossNanoErg) shouldBe Vector("6000000")
      BatchingStatistics.poolProtocol(NodeSync.txOutput(pool), ctx.getNetworkType) shouldBe Some("lithosdex")

      // Same template, but provisions would be checked against ERG:LIT's guard: not a deployment's pool
      val forgedTree = pool.ergoTree.replace(second.provisionGuard.hashedPropBytesHex,
        DexContracts(ctx).provisionGuard.hashedPropBytesHex)
      val (forgedBlock, forgedTx) = executed(pool.copy(ergoTree = forgedTree), after.copy(ergoTree = forgedTree))
      BatchingStatistics.read(forgedBlock, forgedTx, ctx.getNetworkType, "00") shouldBe empty
    }
  }
}
