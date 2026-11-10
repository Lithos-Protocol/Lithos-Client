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
    val quote = before.outputAmount(order.baseAmount, baseIsX = true)
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

  it should "count the tokens an executor kept, net of a carried takings box, naming only verified ones" in {
    val lit = lfsm.LFSMHelpers.getLitId(NetworkType.TESTNET).toString
    val stranger = "d9" * 32
    val pool = ErgoDexFixtures.Sell.poolBox
    val orderBox = ErgoDexFixtures.Sell.orderBox
    val order = ErgoDexOrder.parse(orderBox).get
    val before = ErgoDexPool.native(pool).get
    val quote = before.outputAmount(order.baseAmount, baseIsX = true)
    val after = pool.copy(boxId = SyncFixtures.id(1), value = ErgoDexFixtures.Sell.poolErgAfter,
      assets = pool.assets.updated(2, pool.assets(2).copy(amount = ErgoDexFixtures.Sell.poolTokensAfter)))
    val owner = orderBox.copy(boxId = SyncFixtures.id(2), ergoTree = Hex.toHexString(order.redeemerPropBytes),
      value = ErgoDexFixtures.Sell.rewardValue, assets = Seq(NodeAsset(before.yId, quote)))
    // The executor's rolling box already held 10 LIT; it leaves with 12 and one unit of a token nobody listed.
    val carry = owner.copy(boxId = SyncFixtures.id(3), ergoTree = Contract.SIGMA_TRUE.ergoTreeHex,
      value = 9000000000L, assets = Seq(NodeAsset(lit, 10L)))
    val takings = carry.copy(boxId = SyncFixtures.id(4), value = carry.value + ErgoDexFixtures.Sell.executorOutput,
      assets = Seq(NodeAsset(lit, 12L), NodeAsset(stranger, 1L)))
    val fee = carry.copy(boxId = SyncFixtures.id(5), ergoTree = Contract.FEE.ergoTreeHex, value = ErgoDexFixtures.Sell.minerFee,
      assets = Seq.empty)
    val tx = BlockTx("executed", Seq(pool, orderBox, carry).map(b => TxInput(b.boxId, None)), Seq.empty,
      Seq(after, owner, takings, fee).map(NodeSync.txOutput))
    val block = BlockInfo("block", 120, Seq(tx), resolvedInputs = Seq(pool, orderBox, carry).map(b => b.boxId -> NodeSync.txOutput(b)).toMap)
    val result = BatchingStatistics.read(block, tx, NetworkType.TESTNET, Contract.SIGMA_TRUE.ergoTreeHex)
    result.map(f => (f.tokens, f.otherTokens)) shouldBe Vector((Seq(TokenAmount(lit, "2")), 1))

    // The owner's reward is the order's, never the executor's, whatever it holds.
    val carriedOnly = tx.copy(outputs = tx.outputs.updated(2, NodeSync.txOutput(takings.copy(assets = carry.assets))))
    BatchingStatistics.read(block, carriedOnly, NetworkType.TESTNET, "00").map(f => (f.tokens, f.otherTokens)) shouldBe
      Vector((Seq.empty, 0))
  }

  it should "count a real ErgoDEX token pool execution, whose pool ERG never moves, at its executor fee" in {
    support.ErgoDexTokenFixtures.executions.foreach { e =>
      withClue(e.name) {
        val tx = BlockTx(e.tx.id, Seq(e.pool, e.order).map(b => TxInput(b.boxId, None)), Seq.empty,
          e.tx.outputs.map(o => NodeSync.txOutput(o.box)))
        val block = BlockInfo("block", e.tx.inclusionHeight, Seq(tx),
          resolvedInputs = Seq(e.pool, e.order).map(b => b.boxId -> NodeSync.txOutput(b)).toMap)
        val result = BatchingStatistics.read(block, tx, NetworkType.MAINNET, Contract.SIGMA_FALSE.ergoTreeHex)
        result should have size 1
        result.head.protocol shouldBe "ergodex"
        result.head.grossNanoErg shouldBe e.revenue.toString
        result.head.transactionFeeNanoErg shouldBe e.minerFee.toString
        result.head.local shouldBe false
      }
    }
  }

  it should "count a real Machina fill through an ErgoDEX pool at the gap its two legs left" in {
    support.MachinaFixtures.fills.foreach { m =>
      withClue(m.name) {
        val tx = BlockTx(m.tx.id, Seq(m.pool, m.order).map(b => TxInput(b.boxId, None)), Seq.empty,
          m.tx.outputs.map(o => NodeSync.txOutput(o.box)))
        val block = BlockInfo("block", m.tx.inclusionHeight, Seq(tx),
          resolvedInputs = Seq(m.pool, m.order).map(b => b.boxId -> NodeSync.txOutput(b)).toMap)
        val result = BatchingStatistics.read(block, tx, NetworkType.MAINNET, Contract.SIGMA_FALSE.ergoTreeHex)
        result should have size 1
        result.head.protocol shouldBe "ergodex"
        result.head.grossNanoErg shouldBe m.gap.toString
        result.head.transactionFeeNanoErg shouldBe m.minerFee.toString
      }
    }
  }

  it should "count a Machina fill the arb stage signed through a LithosDex ERG pool under ergodex, at its gap" in {
    import node.MutationConversions._
    val fake = FakeNodeContext()
    val wallet = fake._3
    fake._1.getClient.execute { ctx =>
      support.MachinaFixtures.fills.foreach { m =>
        withClue(m.name) {
          val live = transactions.batching.ergodex.MachinaOrder.parse(m.order).get
          val pool = support.MachinaFixtures.lithosDexFakuPoolAt(ctx,
            if (m.boxSells) live.ask.get * 1.5 else live.bid.get / 1.5)
          val order = live.routedThrough(pool.assets.head.tokenId)
          val state = transactions.batching.arb.ArbFixtures.entry(ctx, pool).pool
          val fill = transactions.batching.arb.ArbMachina.best(order, state).get
          val signed = transactions.batching.arb.ArbMachina.build(ctx, wallet, fill, pool.toInputUTXO(ctx),
            wallet.contract, 1913000)
          val body = node.rest.NodeCodecs.transaction(new com.google.gson.JsonParser()
            .parse(transactions.candidate.BlockTxMessages.CandidateTx.signed(signed, "arb").json).getAsJsonObject)
          val tx = NodeSync.blockTx(body)
          val block = BlockInfo("block", 1913000, Seq(tx),
            resolvedInputs = Seq(pool, m.order).map(b => b.boxId -> NodeSync.txOutput(b)).toMap)
          val result = BatchingStatistics.read(block, tx, NetworkType.MAINNET, wallet.contract.ergoTreeHex)
          result.map(f => (f.protocol, f.orderId, f.grossNanoErg, f.transactionFeeNanoErg, f.local)) shouldBe
            Vector(("ergodex", m.order.boxId, fill.gap.toString, "0", true))
          BatchingStatistics.read(block, tx, NetworkType.MAINNET, Contract.SIGMA_FALSE.ergoTreeHex).map(_.local) shouldBe
            Vector(false)
        }
      }
    }
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

  it should "count a token/token LithosDex swap at its executor fee, and not one on a pool its ids do not build" in {
    val fake = FakeNodeContext()
    fake._1.getClient.execute { ctx =>
      import lithosdex.contracts.{LDOrderContracts, LDOrderTerms, LDT2TContracts}
      import org.ergoplatform.sdk.ErgoId
      val t2t = LDT2TContracts(ErgoId.create("d1" * 32), ErgoId.create("d2" * 32), ErgoId.create("d3" * 32))
      val (x, y) = ("e1" * 32, "e2" * 32)
      val pool = NodeBox(SyncFixtures.id(31), "created", 6300000000L, 0, 100, t2t.liquidityPool.ergoTreeHex,
        Seq(NodeAsset(t2t.poolNFT.toString, 1L), NodeAsset(x, 1000000L), NodeAsset(y, 1000000L),
          NodeAsset(t2t.provToken.toString, 999000000000L)), NodeRegisters(Map.empty))
      val terms = LDOrderTerms(fake._3.contract.sigmaBoolean.get, t2t.poolNFT, 6000000, 2000000)
      val order = NodeBox(SyncFixtures.id(32), "placed", 7000000L, 0, 100,
        LDOrderContracts.t2tSwap(terms, 1L).ergoTreeHex, Seq(NodeAsset(x, 1000L)), NodeRegisters(Map.empty))
      val after = pool.copy(boxId = SyncFixtures.id(33),
        assets = pool.assets.updated(1, NodeAsset(x, 1001000L)).updated(2, NodeAsset(y, 999010L)))
      val reward = order.copy(boxId = SyncFixtures.id(34), ergoTree = fake._3.contract.ergoTreeHex, value = 1000000L,
        assets = Seq(NodeAsset(y, 990L)))
      def executed(poolIn: NodeBox, poolOut: NodeBox): (BlockInfo, BlockTx) = {
        val tx = BlockTx("ld-t2t", Seq(TxInput(poolIn.boxId, None), TxInput(order.boxId, None)), Seq.empty,
          Seq(poolOut, reward).map(NodeSync.txOutput))
        BlockInfo("block", 120, Seq(tx),
          resolvedInputs = Seq(poolIn, order).map(b => b.boxId -> NodeSync.txOutput(b)).toMap) -> tx
      }
      val (block, tx) = executed(pool, after)
      BatchingStatistics.read(block, tx, ctx.getNetworkType, "00").map(f => (f.protocol, f.grossNanoErg)) shouldBe
        Vector(("lithosdex", "6000000"))
      BatchingStatistics.poolProtocol(NodeSync.txOutput(pool), ctx.getNetworkType) shouldBe Some("lithosdex")

      val forgedTree = pool.ergoTree.replace(t2t.provisionGuard.hashedPropBytesHex, "ab" * 32)
      val (forgedBlock, forgedTx) = executed(pool.copy(ergoTree = forgedTree), after.copy(ergoTree = forgedTree))
      BatchingStatistics.read(forgedBlock, forgedTx, ctx.getNetworkType, "00") shouldBe empty
    }
  }
}
