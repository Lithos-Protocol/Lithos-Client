package stats

import node.model.{NodeAsset, NodeBox, NodeRegisters}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.NetworkType
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import state.messages.{BlockInfo, BlockTx, NodeSync, TxInput}
import support.{ErgoDexFixtures, FakeNodeContext, LDNodeFixtures, SyncFixtures}
import transactions.batching.ergodex.{ErgoDexOrder, ErgoDexPool}
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
}
